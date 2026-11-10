import {
  lastFocusedWindow,
  notify,
  queryIdle,
  queryTabs,
  storageGet,
  storageSet,
  updateTab,
} from '../shared/chrome-call.ts';
import {
  domainMatches,
  hostFromUrl,
  normalizeDomainInput,
  parseTabUrl,
  safeReturnUrl,
} from '../shared/domain.ts';
import { parseImport } from '../shared/exchange.ts';
import { badgeText, formatHM } from '../shared/format.ts';
import { t } from '../shared/i18n.ts';
import type { ClientMessage, ClientResponse, SiteStatus, Snapshot } from '../shared/messages.ts';
import { isMessage } from '../shared/messages.ts';
import {
  describeLimit,
  evaluate,
  focusRunning,
  isExcluded,
  nearestWarning,
  type Decision,
} from '../shared/rules.ts';
import { hashPassword, sameHex } from '../shared/secret.ts';
import { hostUsage, rankSites, recordUsage, sumDay } from '../shared/stats.ts';
import {
  defaultAppState,
  defaultRuntime,
  defaultSettings,
  normalizeState,
  type AppState,
  type Checkpoint,
  type FocusSession,
} from '../shared/state.ts';
import { clampIdle, clipSegment, logicalDateKey, logicalDayEndMs, shiftDateKey } from '../shared/time.ts';
import { bindBackground, onBoot, onFocusEnded, onMessage as onEditionMessage, onReset, onUsage } from '@edition';

interface Live {
  countHost: string | null;
  displayHost: string | null;
  lastVisitHost: string | null;
  segmentStart: number | null;
  incognito: boolean;
  focused: boolean;
  idle: chrome.idle.IdleState;
  tabId: number | null;
  counting: boolean;
}

let state: AppState = defaultAppState();
let live: Live = blankLive();
let chain: Promise<unknown> = Promise.resolve();
let ticks = 0;
let lastCheckpointSig = '';
let storageOk = true;

async function writeLocal(items: Record<string, unknown>): Promise<boolean> {
  try {
    await storageSet(items);
    storageOk = true;
    return true;
  } catch {
    storageOk = false;
    return false;
  }
}

function blankLive(): Live {
  return {
    countHost: null,
    displayHost: null,
    lastVisitHost: null,
    segmentStart: null,
    incognito: false,
    focused: false,
    idle: 'active',
    tabId: null,
    counting: false,
  };
}

function enqueue<T>(task: () => Promise<T>): Promise<T> {
  const run = chain.then(task, task);
  chain = run.then(
    () => undefined,
    () => undefined,
  );
  return run;
}

function checkpointFromLive(): Checkpoint {
  return {
    countHost: live.countHost,
    displayHost: live.displayHost,
    lastVisitHost: live.lastVisitHost,
    segmentStart: live.segmentStart,
    incognito: live.incognito,
    focused: live.focused,
    idle: live.idle,
    counting: live.counting,
  };
}

function liveFromCheckpoint(checkpoint: Checkpoint): Live {
  return {
    countHost: checkpoint.countHost,
    displayHost: checkpoint.displayHost,
    lastVisitHost: checkpoint.lastVisitHost,
    segmentStart: checkpoint.segmentStart,
    incognito: checkpoint.incognito,
    focused: checkpoint.focused,
    idle: checkpoint.idle,
    tabId: null,
    counting: checkpoint.counting && !!checkpoint.segmentStart && !!checkpoint.countHost,
  };
}

async function ensureDefaults(): Promise<void> {
  let raw: Record<string, unknown>;
  try {
    raw = await storageGet(null);
  } catch {
    storageOk = false;
    return;
  }
  const patch: Record<string, unknown> = {};
  if (raw.settings == null) patch.settings = defaultSettings();
  if (raw.limits == null) patch.limits = [];
  if (raw.blocklist == null) patch.blocklist = [];
  if (raw.whitelist == null) patch.whitelist = [];
  if (raw.schedules == null) patch.schedules = [];
  if (!Object.prototype.hasOwnProperty.call(raw, 'focus')) patch.focus = null;
  if (raw.runtime == null) patch.runtime = defaultRuntime();
  if (raw.stats == null) patch.stats = {};
  if (Object.keys(patch).length > 0) {
    await writeLocal(patch);
    return;
  }
  storageOk = true;
}

async function readState(): Promise<AppState> {
  try {
    return normalizeState(await storageGet(null));
  } catch {
    storageOk = false;
    return state;
  }
}

function applyIdleInterval(): void {
  try {
    chrome.idle.setDetectionInterval(clampIdle(state.settings.idleThresholdSeconds));
  } catch {
    // Keep the previous interval if the browser rejects the value.
  }
}

function paintBadge(): void {
  const today = logicalDateKey(new Date(), state.settings.dayStartHour);
  const total = sumDay(state.stats[today]);
  const text = state.settings.showBadge ? badgeText(total) : '';
  const title = `${t(state.settings.language, 'appName')} — ${formatHM(total, state.settings.language)}`;
  chrome.action.setBadgeText({ text }, () => {
    void chrome.runtime.lastError;
  });
  chrome.action.setBadgeBackgroundColor({ color: '#0F766E' }, () => {
    void chrome.runtime.lastError;
  });
  chrome.action.setTitle({ title }, () => {
    void chrome.runtime.lastError;
  });
  const action = chrome.action as typeof chrome.action & {
    setBadgeTextColor?: (details: { color: string }, callback?: () => void) => void;
  };
  try {
    action.setBadgeTextColor?.({ color: '#FFFFFF' }, () => {
      void chrome.runtime.lastError;
    });
  } catch {
    // Optional on Firefox.
  }
}

function addUsage(host: string, whenMs: number, seconds: number, visits: number): void {
  state = {
    ...state,
    stats: recordUsage(state.stats, host, whenMs, seconds, visits, state.settings.dayStartHour),
  };
}

/** Count at most three minutes of a gap so a sleep does not become a full day of screen time. */
function flush(now: number): { changed: boolean; usage: { host: string; endMs: number; seconds: number } | null } {
  if (!live.counting || live.segmentStart == null || !live.countHost) return { changed: false, usage: null };
  const taken = clipSegment(live.segmentStart, now);
  if (!taken) return { changed: false, usage: null };
  const host = live.countHost;
  addUsage(host, taken.endMs, taken.seconds, 0);
  live.segmentStart = taken.nextSegmentStart;
  return { changed: true, usage: { host, endMs: taken.endMs, seconds: taken.seconds } };
}

async function noteUsage(usage: { host: string; endMs: number; seconds: number } | null): Promise<void> {
  if (!usage) return;
  try {
    await onUsage(usage.host, usage.endMs, usage.seconds, state.settings.dayStartHour);
  } catch {
    // Hourly report data is optional and stays on this device.
  }
}

async function persist(statsChanged: boolean): Promise<void> {
  const checkpoint = checkpointFromLive();
  const sig = JSON.stringify(checkpoint);
  if (!statsChanged && sig === lastCheckpointSig) return;
  lastCheckpointSig = sig;
  state = { ...state, runtime: { ...state.runtime, checkpoint } };
  const payload: Record<string, unknown> = { runtime: state.runtime };
  if (statsChanged) payload.stats = state.stats;
  await writeLocal(payload);
}

function countingNow(host: string | null, incognito: boolean): boolean {
  if (!host || !live.focused || live.idle !== 'active') return false;
  if (incognito && !state.settings.trackIncognito) return false;
  if (isExcluded(host, state.settings)) return false;
  return true;
}

async function sample(): Promise<void> {
  const now = Date.now();
  const idle = await queryIdle(clampIdle(state.settings.idleThresholdSeconds));
  const win = await lastFocusedWindow();
  let tab: chrome.tabs.Tab | null = null;
  if (win?.id != null) {
    try {
      const tabs = await queryTabs({ active: true, windowId: win.id });
      tab = tabs[0] ?? null;
    } catch {
      tab = null;
    }
  }
  const parsed = tab?.url ? parseTabUrl(tab.url, chrome.runtime.getURL('block.html')) : { countHost: null, displayHost: null };
  const incognito = !!tab?.incognito;
  const previousHost = live.countHost;
  const flushed = flush(now);
  live.idle = idle;
  live.focused = !!win?.focused;
  live.incognito = incognito;
  live.tabId = tab?.id ?? null;
  const hidePrivate = incognito && !state.settings.trackIncognito;
  live.countHost = hidePrivate ? null : parsed.countHost;
  live.displayHost = hidePrivate ? null : parsed.displayHost;
  const counting = countingNow(live.countHost, incognito);
  let visitsChanged = false;
  if (counting && live.countHost && live.countHost !== previousHost) {
    addUsage(live.countHost, now, 0, 1);
    live.lastVisitHost = live.countHost;
    visitsChanged = true;
  }
  live.counting = counting;
  if (counting) {
    if (live.segmentStart == null) live.segmentStart = now;
  } else {
    live.segmentStart = null;
  }
  pruneRuntime(now);
  await noteUsage(flushed.usage);
  await persist(flushed.changed || visitsChanged);
  if (tab?.id != null && tab.url) await enforceTab(tab.id, tab.url);
  paintBadge();
  await warnIfNeeded();
}

function pruneRuntime(now: number): void {
  const snoozes = { ...state.runtime.snoozes };
  const unlocks = { ...state.runtime.unlocks };
  let changed = false;
  for (const [key, until] of Object.entries(snoozes)) {
    if (until <= now) {
      delete snoozes[key];
      changed = true;
    }
  }
  for (const [key, until] of Object.entries(unlocks)) {
    if (until <= now) {
      delete unlocks[key];
      changed = true;
    }
  }
  const today = logicalDateKey(new Date(now), state.settings.dayStartHour);
  const warned: Record<string, string[]> = {};
  if (state.runtime.warned[today]) warned[today] = state.runtime.warned[today];
  if (Object.keys(state.runtime.warned).length !== Object.keys(warned).length) changed = true;
  if (!changed) return;
  state = { ...state, runtime: { ...state.runtime, snoozes, unlocks, warned } };
}

async function warnIfNeeded(): Promise<void> {
  if (!live.countHost) return;
  const now = new Date();
  const key = logicalDateKey(now, state.settings.dayStartHour);
  const hit = nearestWarning(live.countHost, state.limits, state.stats[key], now, state.settings.dayStartHour);
  if (!hit) return;
  const already = state.runtime.warned[key] ?? [];
  if (already.includes(hit.rule)) return;
  const warned = { ...state.runtime.warned, [key]: [...already, hit.rule] };
  state = { ...state, runtime: { ...state.runtime, warned } };
  await writeLocal({ runtime: state.runtime });
  const lang = state.settings.language;
  await notify(
    `warn-${key}-${hit.rule}`.replaceAll('.', '-'),
    t(lang, 'warnTitle'),
    t(lang, 'warnBody', { domain: hit.rule }),
  );
}

function decisionFor(host: string | null, now = new Date()): Decision {
  return evaluate({
    host,
    now,
    settings: state.settings,
    limits: state.limits,
    blocklist: state.blocklist,
    whitelist: state.whitelist,
    schedules: state.schedules,
    focus: state.focus,
    stats: state.stats,
    runtime: state.runtime,
  });
}

function blockTarget(decision: Decision, returnUrl: string): string {
  const url = new URL(chrome.runtime.getURL('block.html'));
  url.searchParams.set('host', decision.host ?? '');
  url.searchParams.set('reason', decision.reason ?? 'limit');
  url.searchParams.set('rule', decision.ruleDomain ?? '');
  if (decision.detail) url.searchParams.set('detail', decision.detail);
  url.searchParams.set('return', returnUrl);
  return url.href;
}

function sameBlock(currentUrl: string, target: string): boolean {
  try {
    const current = new URL(currentUrl);
    const next = new URL(target);
    return (
      current.origin + current.pathname === next.origin + next.pathname &&
      current.searchParams.get('host') === next.searchParams.get('host') &&
      current.searchParams.get('reason') === next.searchParams.get('reason') &&
      current.searchParams.get('rule') === next.searchParams.get('rule')
    );
  } catch {
    return false;
  }
}

async function enforceTab(tabId: number, url: string): Promise<void> {
  if (!url.startsWith('http://') && !url.startsWith('https://')) return;
  const decision = decisionFor(hostFromUrl(url));
  if (!decision.block || !decision.host) return;
  const target = blockTarget(decision, url);
  if (sameBlock(url, target)) return;
  try {
    await updateTab(tabId, target);
  } catch {
    // The tab can close between the check and the update.
  }
}

async function enforceAll(): Promise<void> {
  let tabs: chrome.tabs.Tab[] = [];
  try {
    tabs = await queryTabs({});
  } catch {
    return;
  }
  for (const tab of tabs) {
    if (tab.id == null || !tab.url) continue;
    await enforceTab(tab.id, tab.url);
  }
}

async function releaseBlockedTabs(ruleDomain: string): Promise<void> {
  const base = chrome.runtime.getURL('block.html');
  let tabs: chrome.tabs.Tab[] = [];
  try {
    tabs = await queryTabs({});
  } catch {
    return;
  }
  for (const tab of tabs) {
    if (tab.id == null || !tab.url || !tab.url.startsWith(base)) continue;
    let host = '';
    let rule = '';
    let ret = '';
    try {
      const url = new URL(tab.url);
      host = url.searchParams.get('host') ?? '';
      rule = url.searchParams.get('rule') ?? host;
      ret = url.searchParams.get('return') ?? '';
    } catch {
      continue;
    }
    if (rule !== ruleDomain && !domainMatches(host, ruleDomain)) continue;
    const dest = safeReturnUrl(ret, host) ?? (host ? `https://${host}/` : null);
    if (!dest) continue;
    try {
      await updateTab(tab.id, dest);
    } catch {
      // Ignore a closed tab.
    }
  }
}

async function expireFocus(): Promise<void> {
  const focus: FocusSession | null = state.focus;
  if (!focus || focus.endsAt > Date.now()) return;
  await onFocusEnded({
    startedAt: focus.startedAt,
    endsAt: focus.endsAt,
    minutes: focus.durationMinutes,
    strict: focus.strict,
    completed: true,
  });
  state = { ...state, focus: null };
  await writeLocal({ focus: null });
  const lang = state.settings.language;
  await notify(
    'focus-done',
    t(lang, 'focusDoneTitle'),
    t(lang, 'focusDoneBody', { minutes: focus.durationMinutes }),
  );
  await enforceAll();
}

function snapshot(): Snapshot {
  const now = new Date();
  const todayKey = logicalDateKey(now, state.settings.dayStartHour);
  const day = state.stats[todayKey];
  const host = live.displayHost;
  const usage = host ? hostUsage(day, host) : { seconds: 0, visits: 0 };
  const tight = host ? describeLimit(host, state.limits, day, now, state.settings.dayStartHour) : null;
  const running = focusRunning(state.focus, now.getTime());
  const uses = state.runtime.snoozeUses[todayKey] ?? 0;
  const yesterdaySeconds = sumDay(state.stats[shiftDateKey(todayKey, -1)]);
  const weekKeys = [0, 1, 2, 3, 4, 5, 6].map((offset) => shiftDateKey(todayKey, offset - 6));
  const weekAvgSeconds = Math.round(weekKeys.reduce((total, key) => total + sumDay(state.stats[key]), 0) / weekKeys.length);
  return {
    language: state.settings.language,
    theme: state.settings.theme,
    todayKey,
    totalSeconds: sumDay(day),
    yesterdaySeconds,
    weekAvgSeconds,
    sites: rankSites(day).slice(0, 5),
    currentHost: host,
    currentSeconds: usage.seconds,
    currentVisits: usage.visits,
    limitMinutes: tight ? tight.minutes : null,
    limitUsedSeconds: tight ? tight.usedSeconds : usage.seconds,
    limitDomain: tight ? tight.limit.domain : null,
    focus: running
      ? {
          remainingSeconds: Math.max(0, Math.ceil((state.focus!.endsAt - now.getTime()) / 1000)),
          strict: state.focus!.strict,
          domains: state.focus!.domains,
          endsAt: state.focus!.endsAt,
          durationMinutes: state.focus!.durationMinutes,
        }
      : null,
    snoozeUsesLeft: Math.max(0, state.settings.snoozeLimitPerDay - uses),
    snoozeLimitPerDay: state.settings.snoozeLimitPerDay,
    hasPassword: state.settings.password != null,
    whitelistMode: state.settings.whitelistMode,
    storageOk,
  };
}

function siteStatus(host: string): SiteStatus {
  const now = new Date();
  const decision = decisionFor(host, now);
  const todayKey = logicalDateKey(now, state.settings.dayStartHour);
  const uses = state.runtime.snoozeUses[todayKey] ?? 0;
  return {
    blocked: decision.block,
    reason: decision.reason,
    ruleDomain: decision.ruleDomain,
    host,
    usedSeconds: decision.usedSeconds,
    limitMinutes: decision.limitMinutes,
    detail: decision.detail,
    strict: decision.strict,
    snoozeUsesLeft: Math.max(0, state.settings.snoozeLimitPerDay - uses),
    hasPassword: state.settings.password != null,
    language: state.settings.language,
    theme: state.settings.theme,
    todayKey,
    storageOk,
  };
}

function uniqueDomains(values: readonly string[]): string[] {
  const out: string[] = [];
  for (const value of values) {
    const domain = normalizeDomainInput(value);
    if (domain && !out.includes(domain)) out.push(domain);
    if (out.length >= 80) break;
  }
  return out;
}

async function onMessage(message: ClientMessage): Promise<ClientResponse> {
  if (message.type === 'snapshot') {
    const flushed = flush(Date.now());
    await noteUsage(flushed.usage);
    await persist(true);
    return { ok: true, snapshot: snapshot() };
  }
  if (message.type === 'site-status') {
    const host = normalizeDomainInput(message.host);
    if (!host) return { ok: false, error: 'invalid-domain' };
    return { ok: true, status: siteStatus(host) };
  }
  if (message.type === 'quick-limit') {
    const domain = normalizeDomainInput(message.domain);
    if (!domain) return { ok: false, error: 'invalid-domain' };
    const previous = state;
    if (message.minutes == null) {
      state = { ...state, limits: state.limits.filter((limit) => limit.domain !== domain) };
    } else if (!Number.isFinite(message.minutes) || message.minutes < 0 || message.minutes > 1440) {
      return { ok: false, error: 'invalid-minutes' };
    } else {
      const minutes = Math.round(message.minutes);
      const existing = state.limits.find((limit) => limit.domain === domain);
      const next = existing
        ? { ...existing, minutesPerDay: minutes, enabled: true }
        : { id: `limit:${domain}`, domain, minutesPerDay: minutes, weekdayMinutes: null, enabled: true };
      const limits = state.limits.filter((limit) => limit.domain !== domain);
      limits.push(next);
      state = { ...state, limits };
    }
    if (!(await writeLocal({ limits: state.limits }))) {
      state = previous;
      return { ok: false, error: 'storage' };
    }
    await enforceAll();
    return { ok: true, snapshot: snapshot() };
  }
  if (message.type === 'start-focus') {
    if (!Number.isFinite(message.minutes) || message.minutes < 1 || message.minutes > 240) {
      return { ok: false, error: 'invalid-minutes' };
    }
    const domains = uniqueDomains(message.domains);
    if (domains.length === 0) return { ok: false, error: 'focus-empty' };
    if (state.focus && state.focus.strict && state.focus.endsAt > Date.now()) {
      return { ok: false, error: 'focus-strict' };
    }
    if (state.focus && state.focus.endsAt > Date.now()) {
      await onFocusEnded({
        startedAt: state.focus.startedAt,
        endsAt: Date.now(),
        minutes: state.focus.durationMinutes,
        strict: state.focus.strict,
        completed: false,
      });
    }
    const minutes = Math.round(message.minutes);
    const startedAt = Date.now();
    const focus: FocusSession = {
      startedAt,
      endsAt: startedAt + minutes * 60_000,
      durationMinutes: minutes,
      domains,
      strict: message.strict === true,
    };
    const previous = state;
    state = { ...state, focus };
    if (!(await writeLocal({ focus }))) {
      state = previous;
      return { ok: false, error: 'storage' };
    }
    await enforceAll();
    return { ok: true, snapshot: snapshot() };
  }
  if (message.type === 'stop-focus') {
    if (state.focus && state.focus.strict && state.focus.endsAt > Date.now()) {
      return { ok: false, error: 'focus-strict' };
    }
    if (state.focus) {
      await onFocusEnded({
        startedAt: state.focus.startedAt,
        endsAt: Date.now(),
        minutes: state.focus.durationMinutes,
        strict: state.focus.strict,
        completed: false,
      });
    }
    const previous = state;
    state = { ...state, focus: null };
    if (!(await writeLocal({ focus: null }))) {
      state = previous;
      return { ok: false, error: 'storage' };
    }
    await enforceAll();
    return { ok: true, snapshot: snapshot() };
  }
  if (message.type === 'snooze' || message.type === 'unlock') {
    const host = normalizeDomainInput(message.host);
    if (!host) return { ok: false, error: 'invalid-domain' };
    const decision = decisionFor(host);
    if (!decision.block) return { ok: false, error: 'not-blocked' };
    if (decision.strict) return { ok: false, error: 'focus-strict' };
    const rule = decision.ruleDomain ?? host;
    const previous = state;
    if (message.type === 'snooze') {
      const key = logicalDateKey(new Date(), state.settings.dayStartHour);
      const used = state.runtime.snoozeUses[key] ?? 0;
      if (used >= state.settings.snoozeLimitPerDay) return { ok: false, error: 'snooze-exhausted' };
      state = {
        ...state,
        runtime: {
          ...state.runtime,
          snoozes: { ...state.runtime.snoozes, [rule]: Date.now() + 5 * 60_000 },
          snoozeUses: { ...state.runtime.snoozeUses, [key]: used + 1 },
        },
      };
    } else {
      const record = state.settings.password;
      if (!record) return { ok: false, error: 'no-password' };
      const hash = await hashPassword(message.password, record.salt);
      if (!sameHex(hash, record.hash)) return { ok: false, error: 'bad-password' };
      state = {
        ...state,
        runtime: {
          ...state.runtime,
          unlocks: {
            ...state.runtime.unlocks,
            [rule]: logicalDayEndMs(new Date(), state.settings.dayStartHour),
          },
        },
      };
    }
    if (!(await writeLocal({ runtime: state.runtime }))) {
      state = previous;
      return { ok: false, error: 'storage' };
    }
    await releaseBlockedTabs(rule);
    return { ok: true, status: siteStatus(host) };
  }
  if (message.type === 'apply-import') {
    const parsed = parseImport(message.data);
    if (!parsed.ok) return { ok: false, error: parsed.error };
    const previous = state;
    const segmentStart = live.segmentStart;
    const counting = live.counting;
    const patch: Record<string, unknown> = {};
    if (parsed.patch.settings) {
      state = { ...state, settings: parsed.patch.settings };
      patch.settings = parsed.patch.settings;
    }
    if (parsed.patch.limits) {
      state = { ...state, limits: parsed.patch.limits };
      patch.limits = parsed.patch.limits;
    }
    if (parsed.patch.blocklist) {
      state = { ...state, blocklist: parsed.patch.blocklist };
      patch.blocklist = parsed.patch.blocklist;
    }
    if (parsed.patch.whitelist) {
      state = { ...state, whitelist: parsed.patch.whitelist };
      patch.whitelist = parsed.patch.whitelist;
    }
    if (parsed.patch.schedules) {
      state = { ...state, schedules: parsed.patch.schedules };
      patch.schedules = parsed.patch.schedules;
    }
    if ('focus' in parsed.patch) {
      state = { ...state, focus: parsed.patch.focus ?? null };
      patch.focus = parsed.patch.focus ?? null;
    }
    if (parsed.patch.stats) {
      state = { ...state, stats: parsed.patch.stats };
      patch.stats = parsed.patch.stats;
      live.segmentStart = null;
      live.counting = false;
    }
    if (!(await writeLocal(patch))) {
      state = previous;
      live.segmentStart = segmentStart;
      live.counting = counting;
      return { ok: false, error: 'storage' };
    }
    applyIdleInterval();
    await enforceAll();
    paintBadge();
    return { ok: true };
  }
  if (message.type === 'reset') {
    const previous = state;
    const previousLive = live;
    const previousSig = lastCheckpointSig;
    state = defaultAppState();
    live = blankLive();
    lastCheckpointSig = '';
    const wrote = await writeLocal({
      settings: state.settings,
      limits: state.limits,
      blocklist: state.blocklist,
      whitelist: state.whitelist,
      schedules: state.schedules,
      focus: state.focus,
      runtime: state.runtime,
      stats: state.stats,
    });
    if (!wrote) {
      state = previous;
      live = previousLive;
      lastCheckpointSig = previousSig;
      return { ok: false, error: 'storage' };
    }
    await onReset();
    applyIdleInterval();
    paintBadge();
    await enforceAll();
    return { ok: true };
  }
  if (message.type === 'recheck') {
    state = await readState();
    if (!storageOk) return { ok: false, error: 'storage' };
    applyIdleInterval();
    await enforceAll();
    paintBadge();
    return { ok: true };
  }
  return { ok: false, error: 'unknown' };
}

async function boot(): Promise<void> {
  await ensureDefaults();
  state = await readState();
  applyIdleInterval();
  chrome.alarms.create('heartbeat', { periodInMinutes: 1 });
  if (state.runtime.checkpoint) {
    live = liveFromCheckpoint(state.runtime.checkpoint);
    const flushed = flush(Date.now());
    await noteUsage(flushed.usage);
    if (flushed.changed) await persist(true);
  }
  await sample();
  await expireFocus();
  await enforceAll();
  await onBoot();
}

async function tick(): Promise<void> {
  await sample();
  await expireFocus();
  ticks += 1;
  if (ticks % 10 === 0) await enforceAll();
}

function start(): void {
  bindBackground({
    getState: () => state,
    enqueue,
    replaceUserData: async (patch) => {
      const previous = state;
      const segmentStart = live.segmentStart;
      const counting = live.counting;
      state = {
        ...state,
        settings: patch.settings,
        limits: patch.limits,
        blocklist: patch.blocklist,
        whitelist: patch.whitelist,
        schedules: patch.schedules,
        focus: patch.focus,
        stats: patch.stats,
      };
      live.segmentStart = null;
      live.counting = false;
      const wrote = await writeLocal({
        settings: state.settings,
        limits: state.limits,
        blocklist: state.blocklist,
        whitelist: state.whitelist,
        schedules: state.schedules,
        focus: state.focus,
        stats: state.stats,
      });
      if (!wrote) {
        state = previous;
        live.segmentStart = segmentStart;
        live.counting = counting;
        throw new Error('storage');
      }
      applyIdleInterval();
      await enforceAll();
      paintBadge();
    },
  });
  chrome.runtime.onInstalled.addListener(() => {
    void enqueue(() => boot());
  });
  chrome.runtime.onStartup.addListener(() => {
    void enqueue(() => boot());
  });
  chrome.alarms.onAlarm.addListener((alarm) => {
    if (alarm.name !== 'heartbeat') return;
    void enqueue(async () => {
      await sample();
      await expireFocus();
      await enforceAll();
    });
  });
  chrome.tabs.onActivated.addListener(() => {
    void enqueue(() => sample());
  });
  chrome.tabs.onUpdated.addListener((_tabId, info) => {
    if (!info.url && info.status !== 'complete') return;
    void enqueue(() => sample());
  });
  chrome.windows.onFocusChanged.addListener(() => {
    void enqueue(() => sample());
  });
  chrome.idle.onStateChanged.addListener(() => {
    void enqueue(() => sample());
  });
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== 'local') return;
    const keys = Object.keys(changes);
    if (keys.every((key) => key === 'stats' || key === 'runtime' || key === 'proData')) return;
    void enqueue(async () => {
      state = await readState();
      applyIdleInterval();
      await enforceAll();
      paintBadge();
    });
  });
  chrome.runtime.onMessage.addListener((message: unknown, _sender, sendResponse) => {
    void enqueue(async () => {
      const extra = await onEditionMessage(message);
      if (extra) return extra;
      if (!isMessage(message)) return { ok: false, error: 'unknown' } satisfies ClientResponse;
      return onMessage(message);
    })
      .then((response) => sendResponse(response))
      .catch(() => sendResponse({ ok: false, error: 'failed' } satisfies ClientResponse));
    return true;
  });
  setInterval(() => {
    void enqueue(() => tick());
  }, 1000);
  void enqueue(() => boot());
}

start();
