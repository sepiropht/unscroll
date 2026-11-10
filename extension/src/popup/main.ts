import '../styles/tokens.css';
import './popup.css';
import { sendMessage } from '../shared/chrome-call.ts';
import { applyDocument, el } from '../shared/dom.ts';
import { formatCountdown, formatHM } from '../shared/format.ts';
import { t, type MessageKey } from '../shared/i18n.ts';
import { hourglassMark } from '../shared/mark.ts';
import { errorMessageKey, type ClientResponse, type Snapshot } from '../shared/messages.ts';
import type { Lang } from '../shared/state.ts';

const app = document.querySelector('#app');
if (!app) throw new Error('missing app');

let lang: Lang = 'fr';
let currentHost: string | null = null;

const totalEl = el('p', { class: 'big' }, t('fr', 'durationMin', { n: 0 }));
const vsEl = el('p', { class: 'metric-value' });
const weekEl = el('p', { class: 'metric-value' });
const siteName = el('strong', {}, '—');
const siteTime = el('span', { class: 'muted' });
const tops = el('div', { class: 'tops' });
const focusTime = el('p', { class: 'focus-time' }, '00:00');
const focusIdle = el('div', { class: 'stack' });
const focusRunning = el('div', { class: 'stack' });
const errorEl = el('p', { class: 'error', id: 'popup-error', role: 'alert' });
const storageEl = el('p', { class: 'banner', role: 'status' });
const syncStateEl = el('small', { class: 'sync-state' });
const startBtn = el('button', { id: 'focus-start', type: 'button' });
const stopBtn = el('button', { id: 'focus-stop', class: 'secondary', type: 'button' });

function text(key: MessageKey, vars?: Record<string, string | number>): string {
  return t(lang, key, vars);
}

function leaf(key: MessageKey, tag: keyof HTMLElementTagNameMap = 'span'): HTMLElement {
  return el(tag, { 'data-i18n': key }, text(key));
}

function applyStatic(): void {
  document.querySelectorAll<HTMLElement>('[data-i18n]').forEach((node) => {
    const key = node.dataset.i18n as MessageKey | undefined;
    if (key) node.textContent = text(key);
  });
  document.querySelectorAll<HTMLElement>('[data-i18n-aria]').forEach((node) => {
    const key = node.dataset.i18nAria as MessageKey | undefined;
    if (key) node.setAttribute('aria-label', text(key));
  });
  document.title = text('appName');
}

function showError(code: string, kind: 'form' | 'link' = 'form'): void {
  errorEl.dataset.kind = kind;
  const next = text(errorMessageKey(code));
  if (errorEl.textContent !== next) errorEl.textContent = next;
}

async function ask(message: unknown): Promise<ClientResponse> {
  try {
    return await sendMessage<ClientResponse>(message);
  } catch {
    return { ok: false, error: 'failed' };
  }
}

async function syncEnabled(): Promise<boolean> {
  try {
    const data = await chrome.storage.local.get('proData');
    const packed = data as { proData?: { sync?: { enabled?: boolean } } };
    return Boolean(packed.proData?.sync?.enabled);
  } catch {
    return false;
  }
}

function vsYesterday(total: number, previous: number): string {
  if (previous <= 0) return text('vsYesterdayNone');
  const pct = Math.round(((total - previous) / previous) * 100);
  if (pct < 0) return text('vsYesterdayDown', { n: Math.abs(pct) });
  if (pct > 0) return text('vsYesterdayUp', { n: pct });
  return text('vsYesterdaySame');
}

function paint(snap: Snapshot, enabled: boolean): void {
  lang = snap.language;
  applyDocument(snap.theme, lang);
  applyStatic();
  totalEl.textContent = formatHM(snap.totalSeconds, lang);
  vsEl.textContent = vsYesterday(snap.totalSeconds, snap.yesterdaySeconds);
  weekEl.textContent = formatHM(snap.weekAvgSeconds, lang);
  currentHost = snap.currentHost;
  if (!snap.currentHost) {
    siteName.textContent = text('noSite');
    siteTime.textContent = '';
  } else {
    siteName.textContent = snap.currentHost;
    const visitKey = snap.currentVisits === 1 ? 'visit' : 'visits';
    siteTime.textContent = `${formatHM(snap.currentSeconds, lang)} · ${text(visitKey, { n: snap.currentVisits })}`;
  }
  storageEl.hidden = snap.storageOk;
  if (!snap.storageOk) storageEl.textContent = text('storageError');
  tops.replaceChildren();
  const max = Math.max(1, ...snap.sites.map((site) => site.seconds), snap.totalSeconds);
  if (snap.sites.length === 0) {
    tops.append(el('p', { class: 'empty' }, text('emptyToday')));
  } else {
    for (const site of snap.sites) {
      const bar = el('div', { class: 'meter', 'aria-hidden': 'true' }, el('span'));
      (bar.firstElementChild as HTMLElement).style.width = `${(site.seconds / max) * 100}%`;
      const visitKey = site.visits === 1 ? 'visit' : 'visits';
      tops.append(
        el(
          'div',
          { class: 'top' },
          el('span', {}, site.domain),
          el('span', { class: 'muted' }, `${formatHM(site.seconds, lang)} · ${text(visitKey, { n: site.visits })}`),
          bar,
        ),
      );
    }
  }
  syncStateEl.textContent = enabled ? text('syncOn') : text('syncOff');
  if (snap.focus) {
    focusIdle.hidden = true;
    focusRunning.hidden = false;
    focusTime.textContent = formatCountdown(snap.focus.remainingSeconds);
    stopBtn.disabled = snap.focus.strict;
  } else {
    focusIdle.hidden = false;
    focusRunning.hidden = true;
  }
}

async function refresh(): Promise<void> {
  const [response, enabled] = await Promise.all([ask({ type: 'snapshot' }), syncEnabled()]);
  if (response.ok && response.snapshot) {
    if (errorEl.dataset.kind === 'link') {
      errorEl.textContent = '';
      delete errorEl.dataset.kind;
    }
    paint(response.snapshot, enabled);
  } else showError(response.ok ? 'failed' : response.error, 'link');
}

storageEl.hidden = true;
app.append(
  el(
    'main',
    { class: 'popup' },
    el(
      'header',
      {},
      el('div', { class: 'brand' }, hourglassMark(), leaf('appName', 'strong')),
      el('button', { id: 'open-dashboard', class: 'icon-btn', type: 'button', 'data-i18n': 'openOptions' }, text('openOptions')),
    ),
    storageEl,
    el(
      'section',
      { 'aria-labelledby': 'today-label' },
      el('p', { id: 'today-label', class: 'eyebrow', 'data-i18n': 'today' }, text('today')),
      totalEl,
      el('div', { class: 'metrics' }, el('div', { class: 'metric' }, vsEl), el('div', { class: 'metric' }, el('span', { 'data-i18n': 'weekAvg' }, text('weekAvg')), weekEl)),
    ),
    el('section', { class: 'card site-card' }, el('div', { class: 'site-line' }, siteName, siteTime)),
    el('section', {}, el('h2', { 'data-i18n': 'topSites' }, text('topSites')), tops),
    el('section', { class: 'card' }, el('h2', { 'data-i18n': 'focus' }, text('focus')), focusRunning, focusIdle, errorEl),
    el(
      'a',
      { id: 'open-sync', class: 'sync-link', href: '#sync' },
      el('span', { 'data-i18n': 'proSync' }, text('proSync')),
      syncStateEl,
    ),
  ),
);

focusRunning.append(el('p', { class: 'eyebrow', 'data-i18n': 'remaining' }, text('remaining')), focusTime, stopBtn);
focusIdle.append(startBtn);
startBtn.textContent = text('startQuickFocus');
startBtn.dataset.i18n = 'startQuickFocus';
stopBtn.textContent = text('stop');
stopBtn.dataset.i18n = 'stop';

document.querySelector('#open-dashboard')?.addEventListener('click', () => chrome.runtime.openOptionsPage());
document.querySelector('#open-sync')?.addEventListener('click', (event) => {
  event.preventDefault();
  chrome.tabs.create({ url: chrome.runtime.getURL('options.html#sync') });
});
startBtn.addEventListener('click', () => {
  errorEl.textContent = '';
  if (!currentHost) {
    errorEl.textContent = text('needSiteForFocus');
    return;
  }
  void ask({ type: 'start-focus', minutes: 25, domains: [currentHost], strict: false }).then((response) => {
    if (!response.ok) showError(response.error);
    else if (response.snapshot) void syncEnabled().then((enabled) => paint(response.snapshot!, enabled));
  });
});
stopBtn.addEventListener('click', () => {
  errorEl.textContent = '';
  void ask({ type: 'stop-focus' }).then((response) => {
    if (!response.ok) showError(response.error);
    else if (response.snapshot) void syncEnabled().then((enabled) => paint(response.snapshot!, enabled));
  });
});

void refresh();
setInterval(() => void refresh(), 1000);
