import { donutChart, legend, type DonutSlice } from '../shared/charts.ts';
import { sendMessage } from '../shared/chrome-call.ts';
import { normalizeDomainInput } from '../shared/domain.ts';
import { clear, download, el } from '../shared/dom.ts';
import { formatHM, localeTag } from '../shared/format.ts';
import { t, type MessageKey } from '../shared/i18n.ts';
import type { ClientResponse } from '../shared/messages.ts';
import { logicalDateKey, shiftDateKey } from '../shared/time.ts';
import { toCanvas } from 'qrcode';
import { encodePairingLink } from './pairing.ts';
import { readProData, type ProData } from './pro-data.ts';
import { buildAdvancedReport, reportToCsv, type AdvancedReport } from './reports.ts';
import { DEFAULT_SYNC_URL } from './sync-codec.ts';
import type { CategoryId, OptionsMount } from './types.ts';

const SVG = 'http://www.w3.org/2000/svg';
const CATEGORY_ORDER: CategoryId[] = ['productive', 'social', 'entertainment', 'other'];
const CATEGORY_COLOR: Record<CategoryId, string> = {
  productive: 'slice-0',
  social: 'slice-4',
  entertainment: 'slice-2',
  other: 'slice-6',
};
const WEEK_ORDER = [1, 2, 3, 4, 5, 6, 0] as const;

const CATEGORY_KEY: Record<CategoryId, MessageKey> = {
  productive: 'proProductive',
  social: 'proSocial',
  entertainment: 'proEntertainment',
  other: 'proOther',
};

const errors: Record<string, MessageKey> = {
  'sync-config': 'proErrConfig',
  'sync-disabled': 'proErrDisabled',
  'sync-empty': 'proErrEmpty',
  'sync-auth': 'proErrAuth',
  'sync-conflict': 'proErrConflict',
  'sync-network': 'proErrNetwork',
  'sync-http': 'proErrHttp',
  'bad-passphrase': 'proErrPass',
  'pair-invalid': 'proPairInvalid',
  'invalid-domain': 'proErrDomain',
  storage: 'storageError',
  failed: 'failed',
};

const weekdayKey: Record<number, MessageKey> = {
  1: 'dayMon',
  2: 'dayTue',
  3: 'dayWed',
  4: 'dayThu',
  5: 'dayFri',
  6: 'daySat',
  0: 'daySun',
};

let mounted: OptionsMount | null = null;
let proStorageTold = false;
let dayOffset = 0;
let latest: AdvancedReport | null = null;
let formReady = false;

const reportBody = el('div', { class: 'stack' });
const categoryList = el('ul', { class: 'domain-list' });
const statusLine = el('p', { class: 'help', id: 'pro-sync-status', role: 'status' });
const dayLabel = el('strong', {});
const tokenHint = el('span', { class: 'help' });
const passHint = el('span', { class: 'help' });
const urlHint = el('p', { class: 'help', id: 'pro-sync-url-hint' });
const enabledInput = el('input', { id: 'pro-sync-enabled', type: 'checkbox' }) as HTMLInputElement;
const urlInput = el('input', { id: 'pro-sync-url', type: 'url', inputmode: 'url', spellcheck: 'false', autocomplete: 'off' }) as HTMLInputElement;
const tokenInput = el('input', { id: 'pro-sync-token', type: 'password', autocomplete: 'new-password', spellcheck: 'false' }) as HTMLInputElement;
const passInput = el('input', { id: 'pro-sync-pass', type: 'password', autocomplete: 'new-password', spellcheck: 'false' }) as HTMLInputElement;
const clearTokenInput = el('input', { id: 'pro-sync-clear-token', type: 'checkbox' }) as HTMLInputElement;
const clearSecretInput = el('input', { id: 'pro-sync-clear-secret', type: 'checkbox' }) as HTMLInputElement;
const pairButton = el('button', { type: 'button', id: 'pro-pair', class: 'secondary', 'aria-expanded': 'false' }) as HTMLButtonElement;
const pairWarning = el('p', { class: 'pair-warning', 'data-pro-i18n': 'proPairWarning' });
const qrCanvas = el('canvas', { class: 'pair-qr', width: '280', height: '280' }) as HTMLCanvasElement;
const qrMissing = el('p', { class: 'help', 'data-pro-i18n': 'proQrTooBig' });
const linkInput = el('input', { id: 'pairing-link', class: 'pair-link', readonly: 'readonly', spellcheck: 'false', autocomplete: 'off' }) as HTMLInputElement;
const copyButton = el('button', { type: 'button', id: 'pro-copy-link', class: 'secondary', 'data-pro-i18n': 'proCopyLink' }) as HTMLButtonElement;
const pasteInput = el('input', { id: 'pro-paste-link', spellcheck: 'false', autocomplete: 'off', maxlength: '8192', placeholder: 'unscroll://pair?…' }) as HTMLInputElement;
const pairPanel = el(
  'div',
  { class: 'pair-panel' },
  pairWarning,
  qrCanvas,
  qrMissing,
  el('label', {}, el('span', { 'data-pro-i18n': 'proPairLink' }, ''), linkInput),
  copyButton,
);
let pairingLink: string | null = null;
const domainInput = el('input', { id: 'pro-category-domain' }) as HTMLInputElement;
const categorySelect = el('select', { id: 'pro-category' }) as HTMLSelectElement;

function text(key: MessageKey, vars?: Record<string, string | number>): string {
  return t(mounted?.lang() ?? 'fr', key, vars);
}

function applyLabels(): void {
  document.querySelectorAll<HTMLElement>('[data-pro-i18n]').forEach((node) => {
    const key = node.dataset.proI18n as MessageKey | undefined;
    if (key) node.textContent = text(key);
  });
  pairButton.textContent = pairingLink ? text('proHideQr') : text('proPair');
  qrCanvas.setAttribute('aria-label', text('proQrAlt'));
}

function svg<K extends keyof SVGElementTagNameMap>(tag: K, attrs: Record<string, string>): SVGElementTagNameMap[K] {
  const node = document.createElementNS(SVG, tag);
  for (const [key, value] of Object.entries(attrs)) node.setAttribute(key, value);
  return node;
}

function labeledBars(rows: { label: string; seconds: number }[], label: string): SVGSVGElement {
  const width = 640;
  const height = 200;
  const padL = 28;
  const padB = 28;
  const root = svg('svg', { viewBox: `0 0 ${width} ${height}`, role: 'img', 'aria-label': label, class: 'chart' });
  const max = Math.max(1, ...rows.map((row) => row.seconds));
  const innerW = width - padL - 8;
  const innerH = height - padB - 12;
  const gap = rows.length > 12 ? 2 : 6;
  const barW = rows.length > 0 ? Math.max(2, (innerW - gap * (rows.length - 1)) / rows.length) : innerW;
  rows.forEach((row, index) => {
    const h = (row.seconds / max) * innerH;
    const x = padL + index * (barW + gap);
    const y = 12 + innerH - h;
    const rect = svg('rect', {
      x: String(x),
      y: String(y),
      width: String(barW),
      height: String(Math.max(h, row.seconds > 0 ? 2 : 0)),
      rx: '3',
      class: 'bar',
    });
    const title = svg('title', {});
    title.textContent = `${row.label} · ${formatHM(row.seconds, mounted?.lang() ?? 'fr')}`;
    rect.append(title);
    root.append(rect);
    if (rows.length <= 12 || index % 3 === 0) {
      const caption = svg('text', {
        x: String(x + barW / 2),
        y: String(height - 8),
        'text-anchor': 'middle',
        class: 'chart-label',
      });
      caption.textContent = row.label;
      root.append(caption);
    }
  });
  return root;
}

function selectedDay(): string {
  const start = mounted ? logicalDateKey(new Date(), mounted.getState().settings.dayStartHour) : logicalDateKey(new Date(), 0);
  return shiftDateKey(start, dayOffset);
}

function errorText(code: string): string {
  const key = errors[code] ?? 'proErrUnknown';
  return text(key);
}

async function call(message: unknown): Promise<ClientResponse> {
  try {
    return await sendMessage<ClientResponse>(message);
  } catch {
    return { ok: false, error: 'sync-network' };
  }
}

function renderReport(data: ProData): void {
  if (!mounted) return;
  const lang = mounted.lang();
  const dayKey = selectedDay();
  dayLabel.textContent = dayKey;
  const report = buildAdvancedReport({
    stats: mounted.getState().stats,
    hourly: data.hourly,
    categories: data.categories,
    focusLog: data.focusLog,
    dayKey,
    dayStartHour: mounted.getState().settings.dayStartHour,
  });
  latest = report;
  clear(reportBody);
  const hours = report.hours.map((seconds, hour) => ({ label: String(hour), seconds }));
  const hourPanel = el('div', { class: 'panel' }, el('h2', { 'data-pro-i18n': 'proHourTitle' }, text('proHourTitle')));
  if (report.hours.every((seconds) => seconds === 0)) hourPanel.append(el('p', { class: 'empty', 'data-pro-i18n': 'proNoHour' }, text('proNoHour')));
  else {
    hourPanel.append(labeledBars(hours, text('proHourTitle')));
    if (report.busiestHour != null) {
      hourPanel.append(el('p', { class: 'help' }, text('proBusiest', { hour: `${report.busiestHour}:00` })));
    }
  }
  const slices: DonutSlice[] = report.categories
    .filter((row) => row.seconds > 0)
    .map((row) => ({ label: text(CATEGORY_KEY[row.id]), seconds: row.seconds, color: CATEGORY_COLOR[row.id] }));
  const categoryPanel = el('div', { class: 'panel' }, el('h2', { 'data-pro-i18n': 'proCategoryTitle' }, text('proCategoryTitle')));
  if (slices.length === 0) categoryPanel.append(el('p', { class: 'empty', 'data-pro-i18n': 'proNoCategory' }, text('proNoCategory')));
  else categoryPanel.append(donutChart(slices, formatHM(report.rangeSeconds, lang), text('proCategoryTitle')), legend(slices, lang));
  const weekdayRows = WEEK_ORDER.map((day) => {
    const row = report.weekdayAverages.find((item) => item.day === day);
    const key = weekdayKey[day] ?? 'dayMon';
    return { label: text(key), seconds: row?.seconds ?? 0 };
  });
  const weekdayPanel = el(
    'div',
    { class: 'panel' },
    el('h2', { 'data-pro-i18n': 'proWeekdayTitle' }, text('proWeekdayTitle')),
    labeledBars(weekdayRows, text('proWeekdayTitle')),
  );
  const focusPanel = el(
    'div',
    { class: 'tiles' },
    tile(text('proSessions'), String(report.focus.sessions)),
    tile(text('proFocusMinutes'), formatHM(report.focus.minutes * 60, lang)),
    tile(text('proStrictSessions'), String(report.focus.strict)),
  );
  reportBody.append(
    el('h2', { 'data-pro-i18n': 'proFocusTitle' }, text('proFocusTitle')),
    focusPanel,
    hourPanel,
    el('div', { class: 'split' }, categoryPanel, weekdayPanel),
  );
}

function tile(label: string, value: string): HTMLElement {
  return el('div', { class: 'tile' }, el('span', {}, label), el('strong', {}, value));
}

function renderCategories(data: ProData): void {
  clear(categoryList);
  const domains = Object.keys(data.categories).sort();
  if (domains.length === 0) {
    categoryList.append(el('li', {}, el('p', { class: 'empty', 'data-pro-i18n': 'proEmptyCategories' }, text('proEmptyCategories'))));
    return;
  }
  for (const domain of domains) {
    const category = data.categories[domain] ?? 'other';
    categoryList.append(
      el(
        'li',
        {},
        el('span', {}, `${domain} · ${text(CATEGORY_KEY[category])}`),
        el('button', { type: 'button', class: 'danger', 'data-pro-remove': domain, 'aria-label': text('deleteNamed', { name: domain }) }, text('remove')),
      ),
    );
  }
}

function renderStatus(data: ProData): void {
  const when = data.sync.lastSyncAt
    ? new Date(data.sync.lastSyncAt).toLocaleString(localeTag(mounted?.lang() ?? 'fr'))
    : text('proNever');
  const err = data.sync.lastError ? errorText(data.sync.lastError) : '';
  statusLine.textContent = `${data.sync.enabled ? text('proEnabled') : text('proDisabled')} · ${text('proLast')}: ${when}${err ? ` · ${err}` : ''}`;
  const navState = document.querySelector('#nav-sync-state');
  if (navState) navState.textContent = data.sync.enabled ? text('syncOn') : text('syncOff');
  tokenHint.textContent = data.sync.token ? text('proTokenSet') : text('proTokenMissing');
  const secretReady = data.sync.secret.length > 0;
  passHint.textContent = secretReady ? text('proPassSet') : text('proPassMissing');
  urlHint.textContent = secretReady ? text('proSecretReady') : text('proSyncHttps');
  if (!formReady) showStored(data);
}

function showStored(data: ProData): void {
  urlInput.value = data.sync.url || DEFAULT_SYNC_URL;
  enabledInput.checked = data.sync.enabled;
  tokenInput.value = '';
  passInput.value = '';
  clearTokenInput.checked = false;
  clearSecretInput.checked = false;
  formReady = true;
}

function hidePairing(): void {
  pairingLink = null;
  linkInput.value = '';
  const ctx = qrCanvas.getContext('2d');
  if (ctx) ctx.clearRect(0, 0, qrCanvas.width, qrCanvas.height);
  pairPanel.hidden = true;
  pairButton.textContent = text('proPair');
  pairButton.setAttribute('aria-expanded', 'false');
}

async function drawQr(link: string): Promise<boolean> {
  const color = { dark: '#142226', light: '#ffffff' };
  try {
    await toCanvas(qrCanvas, link, { errorCorrectionLevel: 'M', margin: 2, width: 280, color });
    return true;
  } catch {
    try {
      await toCanvas(qrCanvas, link, { errorCorrectionLevel: 'L', margin: 2, width: 280, color });
      return true;
    } catch {
      return false;
    }
  }
}

async function showPairing(): Promise<void> {
  let data: ProData;
  try {
    data = await readProData();
  } catch {
    if (mounted) mounted.toast(text('storageReadError'), 'err');
    return;
  }
  let link = '';
  try {
    link = encodePairingLink({
      baseUrl: data.sync.url,
      token: data.sync.token,
      mode: data.sync.secretKind === 'passphrase' ? 'pbkdf2' : 'raw',
      secret: data.sync.secret,
    });
  } catch {
    if (mounted) mounted.toast(text('proPairNeedSave'), 'err');
    return;
  }
  const drew = await drawQr(link);
  if (!mounted) return;
  pairingLink = link;
  linkInput.value = link;
  qrCanvas.hidden = !drew;
  qrMissing.hidden = drew;
  pairPanel.hidden = false;
  pairButton.setAttribute('aria-expanded', 'true');
  applyLabels();
}

async function copyPairingLink(): Promise<void> {
  if (!pairingLink || !mounted) return;
  linkInput.focus();
  linkInput.select();
  const write = navigator.clipboard?.writeText;
  if (!write) {
    mounted.toast(text('proCopyFailed'), 'err');
    return;
  }
  try {
    await write.call(navigator.clipboard, pairingLink);
    mounted.toast(text('proCopied'), 'ok');
  } catch {
    mounted.toast(text('proCopyFailed'), 'err');
  }
}

export async function refreshProOptions(): Promise<ProData | null> {
  if (!mounted) return null;
  try {
    const data = await readProData();
    proStorageTold = false;
    renderStatus(data);
    renderReport(data);
    renderCategories(data);
    applyLabels();
    return data;
  } catch {
    if (!proStorageTold && mounted) {
      proStorageTold = true;
      mounted.toast(text('storageReadError'), 'err');
    }
    return null;
  }
}

async function after(message: unknown, okText: string): Promise<void> {
  const response = await call(message);
  await refreshProOptions();
  if (!mounted) return;
  mounted.toast(response.ok ? okText : errorText(response.error), response.ok ? 'ok' : 'err');
}

function buildCategoryOptions(): void {
  clear(categorySelect);
  for (const category of CATEGORY_ORDER) {
    if (category === 'other') continue;
    const option = el('option', { value: category }) as HTMLOptionElement;
    option.dataset.proI18n = CATEGORY_KEY[category];
    option.textContent = text(CATEGORY_KEY[category]);
    categorySelect.append(option);
  }
}

export function mountProOptions(host: OptionsMount): void {
  mounted = host;
  buildCategoryOptions();
  const plusLink = host.nav.querySelector('a[href="#plus"]');
  const syncLink = el('a', { href: '#sync', id: 'nav-sync', class: 'nav-sync' }, el('span', { 'data-pro-i18n': 'proSync' }, text('proSync')), el('small', { id: 'nav-sync-state', class: 'sync-state' }, text('syncOff')));
  if (plusLink) host.nav.insertBefore(syncLink, plusLink);
  else host.nav.append(syncLink);

  const dayBar = el(
    'div',
    { class: 'inline' },
    el('button', { type: 'button', id: 'pro-prev-day', class: 'secondary', 'data-pro-i18n': 'proPrevDay' }, text('proPrevDay')),
    dayLabel,
    el('button', { type: 'button', id: 'pro-next-day', class: 'secondary', 'data-pro-i18n': 'proNextDay' }, text('proNextDay')),
    el('button', { type: 'button', id: 'pro-export-report', class: 'secondary', 'data-pro-i18n': 'proExportReport' }, text('proExportReport')),
  );
  const categoryForm = el(
    'form',
    { id: 'pro-category-form', class: 'form-grid panel' },
    el('h2', { 'data-pro-i18n': 'proCategories' }, text('proCategories')),
    el('p', { class: 'help', 'data-pro-i18n': 'proCategoriesHelp' }, text('proCategoriesHelp')),
    el('label', {}, el('span', { 'data-pro-i18n': 'domain' }, text('domain')), domainInput),
    el('label', {}, el('span', { 'data-pro-i18n': 'proCategories' }, text('proCategories')), categorySelect),
    el('button', { type: 'submit', 'data-pro-i18n': 'addDomain' }, text('addDomain')),
    categoryList,
  );
  const reports = el(
    'section',
    { 'data-view': 'reports' },
    el(
      'div',
      { class: 'stack' },
      el('h1', { 'data-pro-i18n': 'proReports' }, text('proReports')),
      el('p', { class: 'help', 'data-pro-i18n': 'proReportsHelp' }, text('proReportsHelp')),
      dayBar,
      reportBody,
      categoryForm,
    ),
  );
  urlInput.placeholder = DEFAULT_SYNC_URL;
  tokenInput.maxLength = 512;
  passInput.maxLength = 200;
  pairButton.textContent = text('proPair');
  pairPanel.hidden = true;
  qrMissing.hidden = true;
  const syncForm = el(
    'form',
    { id: 'pro-sync-form', class: 'form-grid panel' },
    el('p', { class: 'help', 'data-pro-i18n': 'proSyncHelp' }, text('proSyncHelp')),
    el('label', { class: 'check' }, enabledInput, el('span', { 'data-pro-i18n': 'proSyncEnable' }, text('proSyncEnable'))),
    el('label', {}, el('span', { 'data-pro-i18n': 'proSyncUrl' }, text('proSyncUrl')), urlInput),
    urlHint,
    el(
      'details',
      { class: 'advanced' },
      el('summary', { 'data-pro-i18n': 'proAdvanced' }, text('proAdvanced')),
      el(
        'div',
        { class: 'stack' },
        el('label', {}, el('span', { 'data-pro-i18n': 'proSyncToken' }, text('proSyncToken')), tokenInput, tokenHint),
        el('label', {}, el('span', { 'data-pro-i18n': 'proSyncPass' }, text('proSyncPass')), passInput, passHint),
        el('p', { class: 'help', 'data-pro-i18n': 'proAdvancedHelp' }, text('proAdvancedHelp')),
        el('label', { class: 'check' }, clearTokenInput, el('span', { 'data-pro-i18n': 'proClearToken' }, text('proClearToken'))),
        el('label', { class: 'check' }, clearSecretInput, el('span', { 'data-pro-i18n': 'proClearSecret' }, text('proClearSecret'))),
      ),
    ),
    el(
      'div',
      { class: 'inline' },
      el('button', { type: 'submit', 'data-pro-i18n': 'save' }, text('save')),
      el('button', { type: 'button', id: 'pro-sync-now', class: 'secondary', 'data-pro-i18n': 'proSyncNow' }, text('proSyncNow')),
    ),
    statusLine,
  );
  const pasteForm = el(
    'form',
    { id: 'pro-pair-form', class: 'form-grid' },
    el('label', {}, el('span', { 'data-pro-i18n': 'proPaste' }, text('proPaste')), pasteInput),
    el('p', { class: 'help', 'data-pro-i18n': 'proPasteHelp' }, text('proPasteHelp')),
    el('button', { type: 'submit', 'data-pro-i18n': 'proPasteAction' }, text('proPasteAction')),
  );
  const pairBlock = el('div', { class: 'panel pair-block' }, pairButton, pairPanel, pasteForm);
  const sync = el(
    'section',
    { 'data-view': 'sync', 'aria-labelledby': 'sync-title' },
    el(
      'div',
      { class: 'stack' },
      el('h1', { id: 'sync-title', 'data-pro-i18n': 'proSync' }, text('proSync')),
      syncForm,
      pairBlock,
    ),
  );
  const toast = host.main.querySelector('.toast');
  if (toast) {
    host.main.insertBefore(reports, toast);
    host.main.insertBefore(sync, toast);
  } else {
    host.main.append(reports, sync);
  }

  categoryForm.addEventListener('submit', (event) => {
    event.preventDefault();
    const domain = normalizeDomainInput(domainInput.value);
    if (!domain) {
      host.toast(text('proInvalidDomain'));
      return;
    }
    const category = categorySelect.value;
    void after({ type: 'pro-category', domain, category }, text('saved')).then(() => {
      domainInput.value = '';
    });
  });
  categoryList.addEventListener('click', (event) => {
    const target = event.target;
    if (!(target instanceof HTMLElement)) return;
    const button = target.closest('button');
    if (!(button instanceof HTMLButtonElement) || !button.dataset.proRemove) return;
    void after({ type: 'pro-category', domain: button.dataset.proRemove, category: null }, text('saved'));
  });
  syncForm.addEventListener('submit', (event) => {
    event.preventDefault();
    void (async () => {
      const response = await call({
        type: 'pro-sync',
        action: 'save',
        enabled: enabledInput.checked,
        url: urlInput.value,
        token: tokenInput.value,
        passphrase: passInput.value,
        clearToken: clearTokenInput.checked,
        clearPassphrase: clearSecretInput.checked,
      });
      const data = await refreshProOptions();
      if (!mounted) return;
      if (response.ok && data) {
        showStored(data);
        hidePairing();
      }
      if (!response.ok) {
        mounted.toast(errorText(response.error), 'err');
        return;
      }
      if (response.provisioned) {
        const failed = Boolean(data?.sync.lastError);
        mounted.toast(failed ? text('proAccountSaved') : text('proProvisioned'), failed ? 'err' : 'ok');
        return;
      }
      mounted.toast(text('saved'), 'ok');
    })();
  });
  syncForm.querySelector('#pro-sync-now')?.addEventListener('click', () => {
    void after({ type: 'pro-sync', action: 'now' }, text('proSynced'));
  });
  pairButton.addEventListener('click', () => {
    if (pairingLink) {
      hidePairing();
      applyLabels();
      return;
    }
    void showPairing();
  });
  copyButton.addEventListener('click', () => {
    void copyPairingLink();
  });
  linkInput.addEventListener('focus', () => linkInput.select());
  pasteForm.addEventListener('submit', (event) => {
    event.preventDefault();
    const link = pasteInput.value.trim();
    if (!link) return;
    void (async () => {
      const response = await call({ type: 'pro-sync', action: 'pair', link });
      const data = await refreshProOptions();
      if (!mounted) return;
      if (response.ok) {
        if (data) showStored(data);
        hidePairing();
        pasteInput.value = '';
        mounted.toast(text('proPaired'), 'ok');
        return;
      }
      if (data && response.error !== 'pair-invalid') showStored(data);
      mounted.toast(errorText(response.error), 'err');
    })();
  });
  dayBar.querySelector('#pro-prev-day')?.addEventListener('click', () => {
    dayOffset -= 1;
    void refreshProOptions();
  });
  dayBar.querySelector('#pro-next-day')?.addEventListener('click', () => {
    if (dayOffset < 0) dayOffset += 1;
    void refreshProOptions();
  });
  dayBar.querySelector('#pro-export-report')?.addEventListener('click', () => {
    if (!latest || !mounted) return;
    const labels = {
      productive: text('proProductive'),
      social: text('proSocial'),
      entertainment: text('proEntertainment'),
      other: text('proOther'),
    };
    download(`unscroll-report-${latest.dayKey}.csv`, 'text/csv;charset=utf-8', reportToCsv(latest, labels));
  });
  void refreshProOptions();
}
