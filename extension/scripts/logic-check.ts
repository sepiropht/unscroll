import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import {
  bestMatchingDomain,
  domainMatches,
  normalizeDomainInput,
  safeReturnUrl,
} from '../src/shared/domain.ts';
import { parseImport } from '../src/shared/exchange.ts';
import { badgeText, statsToCsv, trendPercent } from '../src/shared/format.ts';
import { evaluate, limitMinutesFor, scheduleIsActive } from '../src/shared/rules.ts';
import { secondsMatching } from '../src/shared/stats.ts';
import { isMessage } from '../src/shared/messages.ts';
import { defaultAppState, normalizeState, type Schedule, type SiteLimit } from '../src/shared/state.ts';
import { allocateSeconds, logicalDateKey, logicalDayEndMs } from '../src/shared/time.ts';
import { addHourly, buildAdvancedReport, categoryFor } from '../src/edition/reports.ts';
import { blobUrl, canonical, normalizeSyncUrl, open, planSync, seal } from '../src/edition/sync-codec.ts';
import { sanitizeProData } from '../src/edition/pro-data.ts';

assert.equal(normalizeDomainInput('https://www.YouTube.com/watch?v=1'), 'youtube.com');
assert.equal(normalizeDomainInput('WWW.Example.com.'), 'example.com');
assert.equal(normalizeDomainInput('localhost:3000'), 'localhost');
assert.equal(normalizeDomainInput('not a domain'), null);
assert.equal(domainMatches('a.b.com', 'b.com'), true);
assert.equal(domainMatches('b.com', 'b.com'), true);
assert.equal(domainMatches('notb.com', 'b.com'), false);
assert.equal(domainMatches('b.com.evil.com', 'b.com'), false);
assert.equal(bestMatchingDomain('mail.google.com', ['google.com', 'mail.google.com']), 'mail.google.com');

assert.equal(safeReturnUrl('https://www.youtube.com/feed', 'youtube.com'), 'https://www.youtube.com/feed');
assert.equal(safeReturnUrl('https://evil.test/', 'youtube.com'), null);
assert.equal(safeReturnUrl('javascript:alert(1)', 'youtube.com'), null);

const morning = new Date(2026, 0, 2, 3, 30, 0, 0);
assert.equal(logicalDateKey(morning, 4), '2026-01-01');
assert.equal(logicalDateKey(morning, 0), '2026-01-02');
assert.equal(logicalDayEndMs(morning, 4), new Date(2026, 0, 2, 4, 0, 0, 0).getTime());
assert.equal(logicalDayEndMs(new Date(2026, 0, 2, 15, 0, 0, 0), 4), new Date(2026, 0, 3, 4, 0, 0, 0).getTime());

const end = new Date(2026, 0, 2, 0, 0, 3).getTime();
const parts = allocateSeconds(end, 5, 0);
assert.deepEqual(parts, [
  { key: '2026-01-01', seconds: 2 },
  { key: '2026-01-02', seconds: 3 },
]);

const overnight: Schedule = {
  id: 's',
  name: 'Soir',
  domains: ['example.com'],
  days: [5],
  startMinutes: 22 * 60,
  endMinutes: 2 * 60,
  enabled: true,
};
assert.equal(scheduleIsActive(overnight, new Date(2026, 0, 2, 22, 30)), true);
assert.equal(scheduleIsActive(overnight, new Date(2026, 0, 2, 21, 0)), false);
assert.equal(scheduleIsActive(overnight, new Date(2026, 0, 3, 1, 0)), true);
assert.equal(scheduleIsActive(overnight, new Date(2026, 0, 3, 3, 0)), false);
assert.equal(scheduleIsActive(overnight, new Date(2026, 0, 2, 1, 0)), false);

const daytime: Schedule = { ...overnight, days: [1, 2, 3, 4, 5], startMinutes: 9 * 60, endMinutes: 17 * 60 };
assert.equal(scheduleIsActive(daytime, new Date(2026, 0, 2, 10, 0)), true);
assert.equal(scheduleIsActive(daytime, new Date(2026, 0, 2, 17, 0)), false);
assert.equal(scheduleIsActive({ ...daytime, startMinutes: 0, endMinutes: 1440 }, new Date(2026, 0, 2, 23, 0)), true);

const limit: SiteLimit = {
  id: 'limit:youtube.com',
  domain: 'youtube.com',
  minutesPerDay: 30,
  weekdayMinutes: [5, 10, 10, 10, 10, 10, 10],
  enabled: true,
};
assert.equal(limitMinutesFor({ ...limit, weekdayMinutes: null }, new Date(2026, 0, 2, 12), 0), 30);
assert.equal(limitMinutesFor(limit, new Date(2026, 0, 4, 12), 0), 5);

const state = defaultAppState();
state.stats['2026-01-02'] = {
  'youtube.com': { seconds: 20, visits: 1 },
  'm.youtube.com': { seconds: 50, visits: 1 },
};
assert.equal(secondsMatching(state.stats['2026-01-02'], 'youtube.com'), 70);
state.limits = [{ ...limit, weekdayMinutes: null, minutesPerDay: 1 }];
const now = new Date(2026, 0, 2, 12, 0, 0, 0);
const blocked = evaluate({
  host: 'm.youtube.com',
  now,
  settings: state.settings,
  limits: state.limits,
  blocklist: [],
  whitelist: [],
  schedules: [],
  focus: null,
  stats: state.stats,
  runtime: state.runtime,
});
assert.equal(blocked.block, true);
assert.equal(blocked.reason, 'limit');
assert.equal(blocked.ruleDomain, 'youtube.com');

state.runtime.snoozes['youtube.com'] = now.getTime() + 60_000;
const snoozed = evaluate({ ...blockedInput(state, now), host: 'm.youtube.com' });
assert.equal(snoozed.block, false);

state.runtime.snoozes = {};
state.limits = [];
state.focus = {
  startedAt: now.getTime(),
  endsAt: now.getTime() + 60_000,
  durationMinutes: 25,
  domains: ['youtube.com'],
  strict: true,
};
state.runtime.snoozes['youtube.com'] = now.getTime() + 60_000;
const strict = evaluate({ ...blockedInput(state, now), host: 'music.youtube.com' });
assert.equal(strict.block, true);
assert.equal(strict.reason, 'focus');
assert.equal(strict.strict, true);

state.focus = null;
state.runtime.snoozes = {};
state.settings.excludedDomains = ['mail.youtube.com'];
const excluded = evaluate({ ...blockedInput(state, now), host: 'mail.youtube.com' });
assert.equal(excluded.block, false);

state.settings.excludedDomains = [];
state.settings.whitelistMode = true;
state.whitelist = ['example.com'];
state.blocklist = ['bad.example.com'];
assert.equal(evaluate({ ...blockedInput(state, now), host: 'other.test' }).reason, 'whitelist');
assert.equal(evaluate({ ...blockedInput(state, now), host: 'www.example.com' }).block, false);
assert.equal(evaluate({ ...blockedInput(state, now), host: 'bad.example.com' }).reason, 'blocklist');

const csv = statsToCsv({ '2026-01-02': { '=evil.com': { seconds: 1, visits: 2 } } });
assert.ok(csv.charCodeAt(0) === 0xfeff);
assert.ok(csv.includes("'=evil.com"));
assert.equal(badgeText(59 * 60), '59m');
assert.equal(badgeText(60 * 60), '1h');
assert.equal(badgeText(100 * 3600), '99h+');
assert.equal(trendPercent(0, 0).kind, 'flat');
assert.equal(trendPercent(10, 0).kind, 'new');
assert.equal(trendPercent(15, 10).percent, 50);

const imported = parseImport({
  version: 1,
  limits: [{ domain: 'https://www.Example.com/a', minutesPerDay: 12 }],
  stats: { '2026-01-02': { 'WWW.News.example.com': { seconds: 3, visits: 1 } } },
});
assert.equal(imported.ok, true);
if (imported.ok) {
  assert.equal(imported.patch.limits?.[0]?.domain, 'example.com');
  assert.equal(imported.patch.stats?.['2026-01-02']?.['news.example.com']?.seconds, 3);
}
assert.equal(parseImport({ version: 2 }).ok, false);
assert.equal(parseImport({ version: 1 }).ok, false);

const healed = normalizeState({ settings: { language: 'nope', idleThresholdSeconds: 1 }, limits: [{ domain: 'not a domain' }] });
assert.equal(healed.settings.language, defaultAppState().settings.language);
assert.equal(healed.settings.idleThresholdSeconds, 15);
assert.equal(healed.limits.length, 0);

function blockedInput(source: ReturnType<typeof defaultAppState>, when: Date) {
  return {
    host: 'example.com',
    now: when,
    settings: source.settings,
    limits: source.limits,
    blocklist: source.blocklist,
    whitelist: source.whitelist,
    schedules: source.schedules,
    focus: source.focus,
    stats: source.stats,
    runtime: source.runtime,
  };
}

const manifest = JSON.parse(readFileSync(new URL('../manifest.json', import.meta.url), 'utf8')) as {
  permissions: string[];
  host_permissions?: unknown;
  content_security_policy: { extension_pages: string };
};
assert.deepEqual(manifest.permissions, ['storage', 'tabs', 'idle', 'alarms', 'notifications']);
assert.equal(manifest.host_permissions, undefined);
assert.equal(manifest.permissions.includes('webNavigation'), false);
assert.ok(manifest.content_security_policy.extension_pages.includes("default-src 'none'"));
assert.ok(manifest.content_security_policy.extension_pages.includes('connect-src http: https:'));
assert.ok(manifest.content_security_policy.extension_pages.includes("script-src 'self'"));
assert.ok(!manifest.content_security_policy.extension_pages.includes('unsafe-eval'));
assert.ok(manifest.content_security_policy.extension_pages.includes("object-src 'none'"));

assert.equal(isMessage({ type: 'snapshot' }), true);
assert.equal(isMessage({ type: 'start-focus', minutes: 25, domains: ['youtube.com'], strict: false }), true);
assert.equal(isMessage({ type: 'start-focus', minutes: 25, domains: 'youtube.com', strict: false }), false);
assert.equal(isMessage({ type: 'start-focus', minutes: Number.NaN, domains: [], strict: false }), false);
assert.equal(isMessage({ type: 'quick-limit', domain: 'example.com', minutes: null }), true);
assert.equal(isMessage({ type: 'quick-limit', domain: 'example.com', minutes: Number.POSITIVE_INFINITY }), false);
assert.equal(isMessage({ type: 'unlock', host: 'example.com', password: 'secret', returnUrl: 'https://example.com/' }), true);
assert.equal(isMessage({ type: 'unlock', host: 'example.com', password: 1, returnUrl: 'https://example.com/' }), false);
assert.equal(isMessage({ type: 'apply-import', data: { version: 1 } }), true);
assert.equal(isMessage({ type: 'apply-import' }), false);
assert.equal(isMessage({ type: 'eval' }), false);
assert.equal(isMessage(null), false);
const manyDomains = normalizeState({ blocklist: Array.from({ length: 250 }, (_, index) => `n${index}.example`) });
assert.equal(manyDomains.blocklist.length, 200);

assert.equal(normalizeSyncUrl('https://sync.example.com/temps/'), 'https://sync.example.com/temps');
assert.equal(normalizeSyncUrl('http://127.0.0.1:8787'), 'http://127.0.0.1:8787');
assert.equal(normalizeSyncUrl('https://user:pw@sync.example.com/'), null);
assert.equal(normalizeSyncUrl('javascript:alert(1)'), null);
assert.equal(normalizeSyncUrl('https://sync.example.com/v1/snapshot?token=1'), null);
assert.equal(blobUrl('https://sync.example.com/temps', 'settings'), 'https://sync.example.com/temps/v1/blobs/settings');
assert.equal(blobUrl('https://sync.example.com/v1/blobs', 'device.browser.abcdef12'), 'https://sync.example.com/v1/blobs/device.browser.abcdef12');

assert.equal(planSync({ direction: 'auto', localDirty: false, localRevision: 2, remoteRevision: 2 }).toString(), 'idle');
assert.equal(planSync({ direction: 'auto', localDirty: true, localRevision: 2, remoteRevision: 4 }), 'conflict');
assert.equal(planSync({ direction: 'auto', localDirty: false, localRevision: 2, remoteRevision: 4 }), 'pull');
assert.equal(planSync({ direction: 'auto', localDirty: true, localRevision: 4, remoteRevision: 2 }), 'push');
assert.equal(planSync({ direction: 'pull', localDirty: true, localRevision: 1, remoteRevision: null }), 'idle');
assert.equal(planSync({ direction: 'push', localDirty: false, localRevision: 1, remoteRevision: 9 }), 'push');
assert.equal(planSync({ direction: 'auto', localDirty: true, localRevision: 2, remoteRevision: 2, remoteBodyChanged: true }), 'conflict');
assert.equal(planSync({ direction: 'auto', localDirty: false, localRevision: 2, remoteRevision: 2, remoteBodyChanged: true }), 'pull');

const hourEnd = new Date(2026, 0, 2, 10, 0, 5).getTime();
const hours = addHourly({}, hourEnd, 10, 0);
assert.equal(hours['2026-01-02']?.[9], 5);
assert.equal(hours['2026-01-02']?.[10], 5);
const boundary = new Date(2026, 0, 2, 4, 0, 3).getTime();
const split = addHourly({}, boundary, 5, 4);
assert.equal(split['2026-01-01']?.[3], 2);
assert.equal(split['2026-01-02']?.[4], 3);

assert.equal(categoryFor('music.youtube.com', { 'youtube.com': 'entertainment', 'music.youtube.com': 'productive' }), 'productive');
assert.equal(categoryFor('news.example.com', {}), 'other');
const report = buildAdvancedReport({
  stats: { '2026-01-02': { 'youtube.com': { seconds: 30, visits: 1 }, 'mail.example.com': { seconds: 10, visits: 1 } } },
  hourly: { '2026-01-02': Array.from({ length: 24 }, (_, hour) => (hour === 9 ? 30 : 0)) },
  categories: { 'youtube.com': 'entertainment', 'example.com': 'productive' },
  focusLog: [{ startedAt: new Date(2026, 0, 2, 9, 0, 0).getTime(), endsAt: new Date(2026, 0, 2, 9, 25, 0).getTime(), minutes: 25, strict: true, completed: true }],
  dayKey: '2026-01-02',
  dayStartHour: 0,
});
assert.equal(report.busiestHour, 9);
assert.equal(report.categories.find((row) => row.id === 'entertainment')?.seconds, 30);
assert.equal(report.categories.find((row) => row.id === 'productive')?.seconds, 10);
assert.equal(report.focus.sessions, 1);
assert.equal(report.focus.strict, 1);

const saved = sanitizeProData({
  sync: { enabled: true, url: 'not a url', token: 'short', passphrase: 'secret-passphrase' },
});
assert.equal(saved.sync.enabled, false);
const armed = sanitizeProData({
  sync: { enabled: true, url: 'https://sync.example.com/box/', token: 'local-token', passphrase: 'secret-passphrase' },
});
assert.equal(armed.sync.enabled, true);
assert.equal(armed.sync.url, 'https://sync.example.com/box');

const usage = {
  version: 1 as const,
  kind: 'usage' as const,
  revision: 3,
  updatedAt: '2026-01-02T12:00:00.000Z',
  platform: 'browser' as const,
  deviceId: 'abcdef1234567890',
  snapshot: {
    settings: state.settings,
    limits: [],
    blocklist: [],
    whitelist: [],
    schedules: [],
    focus: null,
    stats: {},
    hourly: {},
    categories: {},
    focusLog: [],
  },
};
assert.ok(canonical({ b: 1, a: 2 }).indexOf('"a"') < canonical({ b: 1, a: 2 }).indexOf('"b"'));
const envelope = await seal(usage, 'secret-passphrase', 1_000);
assert.equal(envelope.kdf, 'PBKDF2-SHA-256');
assert.equal(envelope.alg, 'AES-GCM');
const opened = await open(envelope, 'secret-passphrase');
assert.equal((opened as { revision: number }).revision, 3);
assert.equal((opened as { snapshot: { focus: null } }).snapshot.focus, null);
await assert.rejects(open(envelope, 'wrong-passphrase-here'));

console.log('logic-check ok');
