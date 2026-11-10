import '../styles/tokens.css';
import './options.css';
import { sendMessage, storageGet, storageSet } from '../shared/chrome-call.ts';
import { barChart, donutChart, donutSlices, legend } from '../shared/charts.ts';
import { linesToDomains, normalizeDomainInput } from '../shared/domain.ts';
import { applyDocument, clear, download, el } from '../shared/dom.ts';
import { buildExport } from '../shared/exchange.ts';
import { formatCountdown, formatHM, longDayLabel, statsToCsv, trendPercent } from '../shared/format.ts';
import { t, type MessageKey } from '../shared/i18n.ts';
import { hourglassMark } from '../shared/mark.ts';
import { errorMessageKey, type ClientResponse } from '../shared/messages.ts';
import { hashPassword, randomSalt } from '../shared/secret.ts';
import { mergeDays, rankSites, sumDay, sumKeys, sumVisits } from '../shared/stats.ts';
import {
  defaultAppState,
  isLang,
  normalizeState,
  type AppState,
  type Lang,
  type Schedule,
  type Settings,
  type SiteLimit,
  type Theme,
} from '../shared/state.ts';
import { clampHour, clampIdle, dateRange, formatClock, logicalDateKey, parseClock, shiftDateKey } from '../shared/time.ts';
import { mountOptions, refreshOptions } from '@edition';

const VIEWS = ['overview', 'history', 'limits', 'blocking', 'schedules', 'focus', 'settings', 'data', 'plus'] as const;
type View = (typeof VIEWS)[number];
const NAV_VIEWS: View[] = ['overview', 'limits', 'focus', 'plus'];
const WEEK = [1, 2, 3, 4, 5, 6, 0] as const;
const WEEK_KEYS: MessageKey[] = ['dayMon', 'dayTue', 'dayWed', 'dayThu', 'dayFri', 'daySat', 'daySun'];

const root = document.querySelector('#app');
if (!root) throw new Error('missing app');
const app: Element = root;

let model: AppState = defaultAppState();
let range: 'day' | 'week' | 'month' = 'week';
let editingLimit: string | null = null;
let editingSchedule: string | null = null;
let selectedDay = '';

const toastEl = el('p', { class: 'toast', 'aria-live': 'polite' });
const overviewCharts = el('div', { class: 'split' });
const historyBody = el('tbody');
const historyDetail = el('div');
const limitRows = el('div', { class: 'stack' });
const blockRows = el('ul', { class: 'domain-list' });
const allowRows = el('ul', { class: 'domain-list' });
const scheduleRows = el('div', { class: 'stack' });
const focusStatus = el('div', { class: 'panel' });

const langSelect = el('select', { id: 'language' }) as HTMLSelectElement;
const themeSelect = el('select', { id: 'theme' }) as HTMLSelectElement;
const idleInput = el('input', { id: 'idle', type: 'number', min: '15', max: '3600' }) as HTMLInputElement;
const dayInput = el('input', { id: 'day-start', type: 'number', min: '0', max: '23' }) as HTMLInputElement;
const excludedInput = el('textarea', { id: 'excluded' }) as HTMLTextAreaElement;
const badgeInput = el('input', { id: 'badge', type: 'checkbox' }) as HTMLInputElement;
const privateInput = el('input', { id: 'private', type: 'checkbox' }) as HTMLInputElement;
const snoozeInput = el('input', { id: 'snooze-count', type: 'number', min: '0', max: '30' }) as HTMLInputElement;
const passwordStatus = el('p', { class: 'help' });
const pw1 = el('input', { id: 'pw1', type: 'password', autocomplete: 'new-password', maxlength: '200' }) as HTMLInputElement;
const pw2 = el('input', { id: 'pw2', type: 'password', autocomplete: 'new-password', maxlength: '200' }) as HTMLInputElement;
const whiteMode = el('input', { id: 'white-mode', type: 'checkbox' }) as HTMLInputElement;

const limitDomain = el('input', { id: 'limit-domain', maxlength: '2048', spellcheck: 'false' }) as HTMLInputElement;
const limitMinutes = el('input', { id: 'limit-minutes', type: 'number', min: '0', max: '1440', value: '30' }) as HTMLInputElement;
const limitEnabled = el('input', { id: 'limit-enabled', type: 'checkbox' }) as HTMLInputElement;
const limitByDay = el('input', { id: 'limit-by-day', type: 'checkbox' }) as HTMLInputElement;
const limitWeek = el('div', { class: 'weekdays hidden' });
const limitSubmit = el('button', { type: 'submit', 'data-i18n': 'addLimit' });

const schedName = el('input', { id: 'sched-name', maxlength: '80' }) as HTMLInputElement;
const schedDomains = el('textarea', { id: 'sched-domains' }) as HTMLTextAreaElement;
const schedFrom = el('input', { id: 'sched-from', type: 'time', value: '09:00' }) as HTMLInputElement;
const schedTo = el('input', { id: 'sched-to', type: 'time', value: '17:00' }) as HTMLInputElement;
const schedAll = el('input', { id: 'sched-all', type: 'checkbox' }) as HTMLInputElement;
const schedEnabled = el('input', { id: 'sched-enabled', type: 'checkbox' }) as HTMLInputElement;
const schedDays = el('div', { class: 'days' });
const schedSubmit = el('button', { type: 'submit', 'data-i18n': 'addSchedule' });

const focusMinutes = el('input', { id: 'focus-minutes', type: 'number', min: '1', max: '240', value: '25' }) as HTMLInputElement;
const focusDomains = el('textarea', { id: 'focus-domains' }) as HTMLTextAreaElement;
const focusStrict = el('input', { id: 'focus-strict', type: 'checkbox' }) as HTMLInputElement;

const blockInput = el('input', { id: 'block-input', maxlength: '2048', spellcheck: 'false' }) as HTMLInputElement;
const allowInput = el('input', { id: 'allow-input', maxlength: '2048', spellcheck: 'false' }) as HTMLInputElement;

function lang(): Lang {
  return model.settings.language;
}

function text(key: MessageKey, vars?: Record<string, string | number>): string {
  return t(lang(), key, vars);
}

function applyStatic(): void {
  document.title = text('appName');
  applyDocument(model.settings.theme, lang());
  document.querySelectorAll<HTMLElement>('[data-i18n]').forEach((node) => {
    const key = node.dataset.i18n as MessageKey | undefined;
    if (key) node.textContent = text(key);
  });
  document.querySelectorAll<HTMLInputElement>('[data-i18n-placeholder]').forEach((node) => {
    const key = node.getAttribute('data-i18n-placeholder') as MessageKey | null;
    if (key) node.placeholder = text(key);
  });
  document.querySelectorAll<HTMLElement>('[data-i18n-aria]').forEach((node) => {
    const key = node.dataset.i18nAria as MessageKey | undefined;
    if (key) node.setAttribute('aria-label', text(key));
  });
}

function toast(message: string, tone: 'ok' | 'err' = 'ok'): void {
  toastEl.classList.toggle('is-error', tone === 'err');
  toastEl.setAttribute('role', tone === 'err' ? 'alert' : 'status');
  toastEl.textContent = message;
}

async function call(message: unknown): Promise<ClientResponse> {
  try {
    return await sendMessage<ClientResponse>(message);
  } catch {
    return { ok: false, error: 'failed' };
  }
}

async function reload(): Promise<boolean> {
  try {
    model = normalizeState(await storageGet(null));
    return true;
  } catch {
    return false;
  }
}

async function persist(partial: Record<string, unknown>, okMessage?: string): Promise<boolean> {
  try {
    await storageSet(partial);
  } catch {
    toast(text('storageError'), 'err');
    return false;
  }
  await call({ type: 'recheck' });
  const loaded = await reload();
  renderDynamic();
  applyStatic();
  if (!loaded) {
    toast(text('storageReadError'), 'err');
    return false;
  }
  if (okMessage) toast(okMessage);
  return true;
}

function settingsFromForm(): Settings {
  return {
    ...model.settings,
    language: isLang(langSelect.value) ? langSelect.value : 'fr',
    theme: (['auto', 'light', 'dark'].includes(themeSelect.value) ? themeSelect.value : 'auto') as Theme,
    idleThresholdSeconds: clampIdle(Number(idleInput.value)),
    dayStartHour: clampHour(Number(dayInput.value)),
    excludedDomains: linesToDomains(excludedInput.value),
    showBadge: badgeInput.checked,
    trackIncognito: privateInput.checked,
    snoozeLimitPerDay: Math.min(30, Math.max(0, Math.round(Number(snoozeInput.value) || 0))),
    password: model.settings.password,
    whitelistMode: whiteMode.checked,
  };
}

function fillSettings(): void {
  const settings = model.settings;
  langSelect.value = settings.language;
  themeSelect.value = settings.theme;
  idleInput.value = String(settings.idleThresholdSeconds);
  dayInput.value = String(settings.dayStartHour);
  if (document.activeElement !== excludedInput) excludedInput.value = settings.excludedDomains.join('\n');
  badgeInput.checked = settings.showBadge;
  privateInput.checked = settings.trackIncognito;
  snoozeInput.value = String(settings.snoozeLimitPerDay);
  whiteMode.checked = settings.whitelistMode;
  passwordStatus.textContent = settings.password ? text('passwordOn') : text('passwordOff');
  limitEnabled.checked = limitEnabled.checked || !editingLimit;
  schedEnabled.checked = schedEnabled.checked || !editingSchedule;
}

function currentView(): string {
  const name = location.hash.slice(1);
  if (name === 'reports' || name === 'sync') return name;
  return (VIEWS as readonly string[]).includes(name) ? name : 'overview';
}

function showView(): void {
  const view = currentView();
  document.querySelectorAll<HTMLElement>('[data-view]').forEach((section) => {
    section.hidden = section.dataset.view !== view;
  });
  document.querySelectorAll<HTMLAnchorElement>('.nav a').forEach((link) => {
    const on = link.getAttribute('href') === `#${view}`;
    if (on) link.setAttribute('aria-current', 'page');
    else link.removeAttribute('aria-current');
  });
}

function option(value: string, key: MessageKey): HTMLOptionElement {
  const node = el('option', { value }) as HTMLOptionElement;
  node.dataset.i18n = key;
  node.textContent = text(key);
  return node;
}

function viewKey(view: string): MessageKey {
  if (view === 'overview') return 'overview';
  if (view === 'history') return 'history';
  if (view === 'limits') return 'limits';
  if (view === 'blocking') return 'blocking';
  if (view === 'schedules') return 'schedules';
  if (view === 'focus') return 'focus';
  if (view === 'settings') return 'settings';
  if (view === 'plus') return 'more';
  if (view === 'sync') return 'proSync';
  if (view === 'reports') return 'plusReports';
  return 'data';
}

function build(): void {
  const languages: { value: Lang; key: MessageKey }[] = [
    { value: 'fr', key: 'langFr' },
    { value: 'en', key: 'langEn' },
    { value: 'es', key: 'langEs' },
    { value: 'de', key: 'langDe' },
    { value: 'pt', key: 'langPt' },
  ];
  for (const item of languages) langSelect.append(option(item.value, item.key));
  themeSelect.append(option('auto', 'themeAuto'), option('light', 'themeLight'), option('dark', 'themeDark'));

  WEEK.forEach((day, index) => {
    const key = WEEK_KEYS[index] ?? 'dayMon';
    const box = el('input', { type: 'checkbox', value: String(day) }) as HTMLInputElement;
    if (day >= 1 && day <= 5) box.checked = true;
    const label = el('label', {}, box, el('span', { 'data-i18n': key }, text(key)));
    schedDays.append(label);
    const field = el('input', { id: `wd-${day}`, type: 'number', min: '0', max: '1440', value: '30' }) as HTMLInputElement;
    limitWeek.append(el('label', {}, el('span', { 'data-i18n': key }, text(key)), field));
  });

  const nav = el(
    'nav',
    { 'aria-label': text('navLabel'), 'data-i18n-aria': 'navLabel' },
    ...NAV_VIEWS.map((view) => {
      const key = viewKey(view);
      return el('a', { href: `#${view}`, 'data-i18n': key }, text(key));
    }),
  );

  const main = el(
    'main',
    { class: 'main', id: 'content' },
    section('overview', overviewView()),
    section('history', historyView()),
    section('limits', limitsView()),
    section('blocking', blockingView()),
    section('schedules', schedulesView()),
    section('focus', focusView()),
    section('settings', settingsView()),
    section('data', dataView()),
    section('plus', plusView()),
    toastEl,
  );

  app.append(
    el('a', { class: 'skip', href: '#content', 'data-i18n': 'skipToContent' }, text('skipToContent')),
    el(
      'div',
      { class: 'shell' },
      el('aside', { class: 'nav' }, el('div', { class: 'brand' }, hourglassMark(), el('div', {}, el('strong', { 'data-i18n': 'appName' }, text('appName')), el('span', { 'data-i18n': 'tagline' }, text('tagline')))), nav),
      main,
    ),
  );

  bind();
  mountOptions({
    nav,
    main,
    lang,
    toast,
    getState: () => model,
  });
  if (!location.hash) location.hash = '#overview';
  showView();
}

function section(view: View, child: Node): HTMLElement {
  const node = el('section', { 'data-view': view, 'data-i18n-aria': viewKey(view) }, child);
  node.setAttribute('aria-label', text(viewKey(view)));
  return node;
}

function overviewView(): HTMLElement {
  return el(
    'div',
    { class: 'stack' },
    el('h1', { 'data-i18n': 'overview' }, text('overview')),
    el('div', { class: 'tiles', id: 'tiles' }),
    el('p', { class: 'trend', id: 'trend' }),
    el(
      'div',
      { class: 'range' },
      rangeButton('day', 'rangeDay'),
      rangeButton('week', 'rangeWeek'),
      rangeButton('month', 'rangeMonth'),
    ),
    overviewCharts,
  );
}

function rangeButton(value: 'day' | 'week' | 'month', key: MessageKey): HTMLButtonElement {
  const button = el('button', { type: 'button', 'data-range': value, 'data-i18n': key }, text(key)) as HTMLButtonElement;
  button.addEventListener('click', () => {
    range = value;
    renderOverview();
  });
  return button;
}

function historyView(): HTMLElement {
  return el(
    'div',
    { class: 'stack' },
    el('h1', { 'data-i18n': 'history' }, text('history')),
    el(
      'div',
      { class: 'panel' },
      el(
        'table',
        {},
        el('caption', { class: 'sr-only', 'data-i18n': 'historyCaption' }, text('historyCaption')),
        el(
          'thead',
          {},
          el(
            'tr',
            {},
            el('th', { scope: 'col', 'data-i18n': 'colDate' }, text('colDate')),
            el('th', { scope: 'col', class: 'num', 'data-i18n': 'colTime' }, text('colTime')),
            el('th', { scope: 'col', class: 'num', 'data-i18n': 'colVisits' }, text('colVisits')),
            el('th', { scope: 'col', 'data-i18n': 'colTop' }, text('colTop')),
          ),
        ),
        historyBody,
      ),
    ),
    historyDetail,
  );
}

function limitsView(): HTMLElement {
  const form = el(
    'form',
    { id: 'limit-form', class: 'form-grid panel' },
    el('h2', { 'data-i18n': 'limits' }, text('limits')),
    el('p', { class: 'help', 'data-i18n': 'limitsHelp' }, text('limitsHelp')),
    el('label', {}, el('span', { 'data-i18n': 'domain' }, text('domain')), limitDomain),
    el('label', {}, el('span', { 'data-i18n': 'perDay' }, text('perDay')), limitMinutes),
    el('label', { class: 'check' }, limitByDay, el('span', { 'data-i18n': 'byWeekday' }, text('byWeekday'))),
    limitWeek,
    el('label', { class: 'check' }, limitEnabled, el('span', { 'data-i18n': 'enabled' }, text('enabled'))),
    limitSubmit,
  );
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    void saveLimit();
  });
  limitByDay.addEventListener('change', () => {
    limitWeek.classList.toggle('hidden', !limitByDay.checked);
  });
  return el('div', { class: 'stack' }, form, limitRows);
}

function blockingView(): HTMLElement {
  return el(
    'div',
    { class: 'stack' },
    el('h1', { 'data-i18n': 'blocking' }, text('blocking')),
    el('p', { class: 'help', 'data-i18n': 'blockHelp' }, text('blockHelp')),
    el(
      'div',
      { class: 'panel' },
      el('h2', { 'data-i18n': 'blocklist' }, text('blocklist')),
      el('form', { id: 'block-form', class: 'inline' }, el('label', {}, el('span', { class: 'sr-only', 'data-i18n': 'domain' }, text('domain')), blockInput), el('button', { type: 'submit', 'data-i18n': 'addDomain' }, text('addDomain'))),
      blockRows,
    ),
    el(
      'div',
      { class: 'panel' },
      el('h2', { 'data-i18n': 'whitelist' }, text('whitelist')),
      el('label', { class: 'check' }, whiteMode, el('span', { 'data-i18n': 'whitelistMode' }, text('whitelistMode'))),
      el('p', { class: 'help', 'data-i18n': 'whitelistHelp' }, text('whitelistHelp')),
      el('form', { id: 'allow-form', class: 'inline' }, el('label', {}, el('span', { class: 'sr-only', 'data-i18n': 'domain' }, text('domain')), allowInput), el('button', { type: 'submit', 'data-i18n': 'addDomain' }, text('addDomain'))),
      allowRows,
    ),
  );
}

function schedulesView(): HTMLElement {
  const form = el(
    'form',
    { id: 'sched-form', class: 'form-grid panel' },
    el('h2', { 'data-i18n': 'schedules' }, text('schedules')),
    el('p', { class: 'help', 'data-i18n': 'schedulesHelp' }, text('schedulesHelp')),
    el('label', {}, el('span', { 'data-i18n': 'scheduleName' }, text('scheduleName')), schedName),
    el('label', {}, el('span', { 'data-i18n': 'domains' }, text('domains')), schedDomains),
    el('div', { class: 'inline' },
      el('label', {}, el('span', { 'data-i18n': 'from' }, text('from')), schedFrom),
      el('label', {}, el('span', { 'data-i18n': 'to' }, text('to')), schedTo),
    ),
    el('label', { class: 'check' }, schedAll, el('span', { 'data-i18n': 'allDay' }, text('allDay'))),
    el('div', {}, el('span', { class: 'help', 'data-i18n': 'days' }, text('days')), schedDays),
    el('label', { class: 'check' }, schedEnabled, el('span', { 'data-i18n': 'enabled' }, text('enabled'))),
    schedSubmit,
  );
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    void saveSchedule();
  });
  schedAll.addEventListener('change', () => {
    schedFrom.disabled = schedAll.checked;
    schedTo.disabled = schedAll.checked;
  });
  return el('div', { class: 'stack' }, form, scheduleRows);
}

function focusView(): HTMLElement {
  const form = el(
    'form',
    { id: 'focus-form', class: 'form-grid panel' },
    el('h2', { 'data-i18n': 'focus' }, text('focus')),
    el('p', { class: 'help', 'data-i18n': 'focusPageHelp' }, text('focusPageHelp')),
    el('div', { class: 'inline' },
      el('button', { type: 'button', id: 'set25', 'data-i18n': 'preset25' }, text('preset25')),
      el('button', { type: 'button', id: 'set50', 'data-i18n': 'preset50' }, text('preset50')),
    ),
    el('label', {}, el('span', { 'data-i18n': 'duration' }, text('duration')), focusMinutes),
    el('label', {}, el('span', { 'data-i18n': 'domains' }, text('domains')), focusDomains),
    el('label', { class: 'check' }, focusStrict, el('span', { 'data-i18n': 'strict' }, text('strict'))),
    el('p', { class: 'help', 'data-i18n': 'strictHelp' }, text('strictHelp')),
    el('button', { type: 'submit', 'data-i18n': 'start' }, text('start')),
  );
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    void call({
      type: 'start-focus',
      minutes: Number(focusMinutes.value),
      domains: linesToDomains(focusDomains.value).slice(0, 80),
      strict: focusStrict.checked,
    }).then((response) => {
      if (!response.ok) toast(text(errorMessageKey(response.error)), 'err');
      else toast(text('saved'));
    });
  });
  return el('div', { class: 'stack' }, focusStatus, form);
}


function plusView(): HTMLElement {
  const items: { href: string; key: MessageKey }[] = [
    { href: '#history', key: 'history' },
    { href: '#blocking', key: 'blocking' },
    { href: '#schedules', key: 'schedules' },
    { href: '#reports', key: 'plusReports' },
    { href: '#settings', key: 'settings' },
    { href: '#data', key: 'data' },
  ];
  return el(
    'div',
    { class: 'stack' },
    el('h1', { 'data-i18n': 'more' }, text('more')),
    el(
      'ul',
      { class: 'plus-list' },
      ...items.map((item) =>
        el('li', {}, el('a', { href: item.href, class: 'plus-link', 'data-i18n': item.key }, text(item.key))),
      ),
    ),
  );
}

function settingsView(): HTMLElement {
  const form = el(
    'form',
    { id: 'settings-form', class: 'form-grid panel' },
    el('h2', { 'data-i18n': 'settings' }, text('settings')),
    el('p', { class: 'help', 'data-i18n': 'settingsHelp' }, text('settingsHelp')),
    el('label', {}, el('span', { 'data-i18n': 'language' }, text('language')), langSelect),
    el('label', {}, el('span', { 'data-i18n': 'theme' }, text('theme')), themeSelect),
    el('label', {}, el('span', { 'data-i18n': 'idle' }, text('idle')), idleInput, el('span', { class: 'help', 'data-i18n': 'idleHelp' }, text('idleHelp'))),
    el('label', {}, el('span', { 'data-i18n': 'dayStart' }, text('dayStart')), dayInput, el('span', { class: 'help', 'data-i18n': 'dayStartHelp' }, text('dayStartHelp'))),
    el('label', {}, el('span', { 'data-i18n': 'excluded' }, text('excluded')), excludedInput, el('span', { class: 'help', 'data-i18n': 'excludedHelp' }, text('excludedHelp'))),
    el('label', { class: 'check' }, badgeInput, el('span', { 'data-i18n': 'badge' }, text('badge'))),
    el('label', { class: 'check' }, privateInput, el('span', { 'data-i18n': 'incognito' }, text('incognito'))),
    el('p', { class: 'help', 'data-i18n': 'incognitoHelp' }, text('incognitoHelp')),
    el('label', {}, el('span', { 'data-i18n': 'snoozeCount' }, text('snoozeCount')), snoozeInput, el('span', { class: 'help', 'data-i18n': 'snoozeCountHelp' }, text('snoozeCountHelp'))),
    el('button', { type: 'submit', 'data-i18n': 'save' }, text('save')),
  );
  form.addEventListener('submit', (event) => {
    event.preventDefault();
    void persist({ settings: settingsFromForm() }, text('saved'));
  });
  const password = el(
    'form',
    { id: 'password-form', class: 'form-grid panel' },
    el('h2', { 'data-i18n': 'password' }, text('password')),
    el('p', { class: 'help', 'data-i18n': 'passwordHelp' }, text('passwordHelp')),
    passwordStatus,
    el('label', {}, el('span', { 'data-i18n': 'passwordNew' }, text('passwordNew')), pw1),
    el('label', {}, el('span', { 'data-i18n': 'passwordAgain' }, text('passwordAgain')), pw2),
    el('div', { class: 'inline' },
      el('button', { type: 'submit', 'data-i18n': 'passwordSet' }, text('passwordSet')),
      el('button', { type: 'button', id: 'pw-clear', class: 'secondary', 'data-i18n': 'passwordClear' }, text('passwordClear')),
    ),
  );
  password.addEventListener('submit', (event) => {
    event.preventDefault();
    void setPassword();
  });
  return el('div', { class: 'stack' }, form, password);
}

function dataView(): HTMLElement {
  const file = el('input', { id: 'import-file', type: 'file', accept: 'application/json,.json' }) as HTMLInputElement;
  const wrap = el(
    'div',
    { class: 'stack' },
    el('h1', { 'data-i18n': 'data' }, text('data')),
    el('p', { class: 'help', 'data-i18n': 'privacy' }, text('privacy')),
    el('p', { class: 'help', 'data-i18n': 'importHint' }, text('importHint')),
    el(
      'div',
      { class: 'inline' },
      el('button', { type: 'button', id: 'export-csv', 'data-i18n': 'exportCsv' }, text('exportCsv')),
      el('button', { type: 'button', id: 'export-json', class: 'secondary', 'data-i18n': 'exportJson' }, text('exportJson')),
    ),
    el('label', {}, el('span', { 'data-i18n': 'importJson' }, text('importJson')), file),
    el('div', { class: 'panel' },
      el('h2', { 'data-i18n': 'reset' }, text('reset')),
      el('p', { class: 'help', 'data-i18n': 'resetHelp' }, text('resetHelp')),
      el('button', { type: 'button', id: 'reset-data', class: 'danger', 'data-i18n': 'reset' }, text('reset')),
    ),
  );
  file.addEventListener('change', () => {
    const picked = file.files?.[0];
    if (!picked) return;
    void picked.text().then(async (raw) => {
      try {
        if (raw.length > 5_000_000) {
          toast(text('importFail'), 'err');
          return;
        }
        const response = await call({ type: 'apply-import', data: JSON.parse(raw) as unknown });
        toast(text(response.ok ? 'importOk' : 'importFail'), response.ok ? 'ok' : 'err');
      } catch {
        toast(text('importFail'), 'err');
      } finally {
        file.value = '';
      }
    });
  });
  return wrap;
}

function bind(): void {
  document.querySelector('#block-form')?.addEventListener('submit', (event) => {
    event.preventDefault();
    void addDomain('blocklist', blockInput);
  });
  document.querySelector('#allow-form')?.addEventListener('submit', (event) => {
    event.preventDefault();
    void addDomain('whitelist', allowInput);
  });
  whiteMode.addEventListener('change', () => {
    void persist({ settings: { ...model.settings, whitelistMode: whiteMode.checked } }, text('saved'));
  });
  langSelect.addEventListener('change', () => {
    void persist({ settings: settingsFromForm() });
  });
  themeSelect.addEventListener('change', () => {
    const nextLang = isLang(langSelect.value) ? langSelect.value : lang();
    const nextTheme = (['auto', 'light', 'dark'].includes(themeSelect.value) ? themeSelect.value : 'auto') as Theme;
    applyDocument(nextTheme, nextLang);
    void persist({ settings: settingsFromForm() });
  });
  document.querySelector('#pw-clear')?.addEventListener('click', () => {
    void persist({ settings: { ...settingsFromForm(), password: null } }, text('saved'));
  });
  document.querySelector('#set25')?.addEventListener('click', () => {
    focusMinutes.value = '25';
  });
  document.querySelector('#set50')?.addEventListener('click', () => {
    focusMinutes.value = '50';
  });
  document.querySelector('#export-csv')?.addEventListener('click', () => {
    download(`unscroll-${logicalDateKey(new Date(), model.settings.dayStartHour)}.csv`, 'text/csv;charset=utf-8', statsToCsv(model.stats));
  });
  document.querySelector('#export-json')?.addEventListener('click', () => {
    const file = buildExport(model);
    download(
      `unscroll-${logicalDateKey(new Date(), model.settings.dayStartHour)}.json`,
      'application/json',
      JSON.stringify(file, null, 2),
    );
  });
  document.querySelector('#reset-data')?.addEventListener('click', () => {
    if (!confirm(text('resetConfirm'))) return;
    void call({ type: 'reset' }).then((response) => {
      if (response.ok) toast(text('resetDone'));
      else toast(text(errorMessageKey(response.error)), 'err');
    });
  });
  app.addEventListener('click', (event) => {
    const target = event.target;
    if (!(target instanceof HTMLElement)) return;
    const button = target.closest('button');
    if (!(button instanceof HTMLButtonElement)) return;
    const action = button.dataset.action;
    const domain = button.dataset.domain ?? '';
    const id = button.dataset.id ?? '';
    if (action === 'delete-limit') void persist({ limits: model.limits.filter((limit) => limit.domain !== domain) }, text('saved'));
    if (action === 'edit-limit') fillLimit(model.limits.find((limit) => limit.domain === domain) ?? null);
    if (action === 'delete-block') void persist({ blocklist: model.blocklist.filter((item) => item !== domain) }, text('saved'));
    if (action === 'delete-allow') void persist({ whitelist: model.whitelist.filter((item) => item !== domain) }, text('saved'));
    if (action === 'delete-schedule') void persist({ schedules: model.schedules.filter((item) => item.id !== id) }, text('saved'));
    if (action === 'edit-schedule') fillSchedule(model.schedules.find((item) => item.id === id) ?? null);
    if (action === 'toggle-limit') {
      const limits = model.limits.map((limit) => (limit.domain === domain ? { ...limit, enabled: !limit.enabled } : limit));
      void persist({ limits });
    }
    if (action === 'toggle-schedule') {
      const schedules = model.schedules.map((item) => (item.id === id ? { ...item, enabled: !item.enabled } : item));
      void persist({ schedules });
    }
    if (action === 'stop-focus') {
      void call({ type: 'stop-focus' }).then((response) => {
        if (!response.ok) toast(text(errorMessageKey(response.error)), 'err');
      });
    }
    if (action === 'show-day' && button.dataset.day) {
      selectedDay = button.dataset.day;
      renderHistory();
    }
  });
  window.addEventListener('hashchange', showView);
}

async function setPassword(): Promise<void> {
  if (pw1.value.length < 4) {
    toast(text('passwordShort'), 'err');
    return;
  }
  if (pw1.value.length > 200) {
    toast(text('passwordLong'), 'err');
    return;
  }
  if (pw1.value !== pw2.value) {
    toast(text('passwordMismatch'), 'err');
    return;
  }
  const salt = randomSalt();
  const hash = await hashPassword(pw1.value, salt);
  pw1.value = '';
  pw2.value = '';
  await persist({ settings: { ...settingsFromForm(), password: { salt, hash } } }, text('saved'));
}

async function saveLimit(): Promise<void> {
  const domain = normalizeDomainInput(limitDomain.value);
  limitDomain.setAttribute('aria-invalid', domain ? 'false' : 'true');
  if (!domain) {
    toast(text('invalidDomain'), 'err');
    return;
  }
  const minutes = Math.round(Number(limitMinutes.value));
  if (!Number.isFinite(minutes) || minutes < 0 || minutes > 1440) {
    limitMinutes.setAttribute('aria-invalid', 'true');
    toast(text('invalidMinutes'), 'err');
    return;
  }
  limitMinutes.setAttribute('aria-invalid', 'false');
  let weekdayMinutes: number[] | null = null;
  if (limitByDay.checked) {
    weekdayMinutes = [0, 0, 0, 0, 0, 0, 0];
    for (const day of WEEK) {
      const field = document.querySelector(`#wd-${day}`) as HTMLInputElement | null;
      const value = Math.round(Number(field?.value));
      weekdayMinutes[day] = Number.isFinite(value) ? Math.min(1440, Math.max(0, value)) : minutes;
    }
  }
  const limit: SiteLimit = {
    id: `limit:${domain}`,
    domain,
    minutesPerDay: minutes,
    weekdayMinutes,
    enabled: limitEnabled.checked,
  };
  const limits = model.limits.filter((item) => item.domain !== domain && item.domain !== editingLimit);
  limits.push(limit);
  editingLimit = null;
  limitDomain.value = '';
  limitSubmit.dataset.i18n = 'addLimit';
  await persist({ limits }, text('saved'));
}

function fillLimit(limit: SiteLimit | null): void {
  editingLimit = limit?.domain ?? null;
  limitDomain.value = limit?.domain ?? '';
  limitMinutes.value = String(limit?.minutesPerDay ?? 30);
  limitEnabled.checked = limit?.enabled ?? true;
  limitByDay.checked = !!limit?.weekdayMinutes;
  limitWeek.classList.toggle('hidden', !limitByDay.checked);
  for (const day of WEEK) {
    const field = document.querySelector(`#wd-${day}`) as HTMLInputElement | null;
    if (!field) continue;
    field.value = String(limit?.weekdayMinutes?.[day] ?? limit?.minutesPerDay ?? 30);
  }
  limitSubmit.dataset.i18n = limit ? 'updateLimit' : 'addLimit';
  limitSubmit.textContent = text(limit ? 'updateLimit' : 'addLimit');
}

async function saveSchedule(): Promise<void> {
  const name = schedName.value.trim();
  const domains = linesToDomains(schedDomains.value).slice(0, 200);
  if (!name || domains.length === 0) {
    schedName.setAttribute('aria-invalid', name ? 'false' : 'true');
    schedDomains.setAttribute('aria-invalid', domains.length === 0 ? 'true' : 'false');
    toast(text(domains.length === 0 ? 'needDomain' : 'needName'), 'err');
    return;
  }
  schedName.setAttribute('aria-invalid', 'false');
  schedDomains.setAttribute('aria-invalid', 'false');
  const days = [...schedDays.querySelectorAll('input:checked')].map((input) => Number((input as HTMLInputElement).value));
  if (days.length === 0) {
    toast(text('needDays'), 'err');
    return;
  }
  const startMinutes = schedAll.checked ? 0 : parseClock(schedFrom.value);
  const endMinutes = schedAll.checked ? 1440 : parseClock(schedTo.value);
  if (startMinutes == null || endMinutes == null) {
    toast(text('invalidMinutes'), 'err');
    return;
  }
  const schedule: Schedule = {
    id: editingSchedule ?? `sched-${Date.now().toString(36)}`,
    name: name.slice(0, 80),
    domains,
    days,
    startMinutes,
    endMinutes,
    enabled: schedEnabled.checked,
  };
  const schedules = model.schedules.filter((item) => item.id !== schedule.id);
  schedules.push(schedule);
  editingSchedule = null;
  schedName.value = '';
  schedDomains.value = '';
  schedSubmit.dataset.i18n = 'addSchedule';
  await persist({ schedules }, text('saved'));
}

function fillSchedule(schedule: Schedule | null): void {
  editingSchedule = schedule?.id ?? null;
  schedName.value = schedule?.name ?? '';
  schedDomains.value = schedule?.domains.join('\n') ?? '';
  const allDay = !!schedule && schedule.startMinutes === 0 && schedule.endMinutes >= 1440;
  schedAll.checked = allDay;
  schedFrom.disabled = allDay;
  schedTo.disabled = allDay;
  schedFrom.value = formatClock(schedule?.startMinutes ?? 9 * 60);
  schedTo.value = schedule && schedule.endMinutes >= 1440 ? '23:59' : formatClock(schedule?.endMinutes ?? 17 * 60);
  schedEnabled.checked = schedule?.enabled ?? true;
  schedDays.querySelectorAll('input').forEach((input) => {
    const box = input as HTMLInputElement;
    box.checked = schedule ? schedule.days.includes(Number(box.value)) : Number(box.value) >= 1 && Number(box.value) <= 5;
  });
  schedSubmit.dataset.i18n = schedule ? 'updateSchedule' : 'addSchedule';
  schedSubmit.textContent = text(schedule ? 'updateSchedule' : 'addSchedule');
}

async function addDomain(key: 'blocklist' | 'whitelist', input: HTMLInputElement): Promise<void> {
  const domain = normalizeDomainInput(input.value);
  input.setAttribute('aria-invalid', domain ? 'false' : 'true');
  if (!domain) {
    toast(text('invalidDomain'), 'err');
    return;
  }
  const list = model[key].includes(domain) ? model[key] : [...model[key], domain];
  input.value = '';
  await persist({ [key]: list }, text('saved'));
}

function renderDynamic(): void {
  renderOverview();
  renderHistory();
  renderLimits();
  renderDomains();
  renderSchedules();
  renderFocus();
  passwordStatus.textContent = model.settings.password ? text('passwordOn') : text('passwordOff');
  document.querySelectorAll<HTMLButtonElement>('[data-range]').forEach((button) => {
    button.setAttribute('aria-pressed', button.dataset.range === range ? 'true' : 'false');
  });
  void refreshOptions();
}

function renderOverview(): void {
  const todayKey = logicalDateKey(new Date(), model.settings.dayStartHour);
  const weekKeys = dateRange(todayKey, 7);
  const prevKeys = dateRange(shiftDateKey(todayKey, -7), 7);
  const trend = trendPercent(sumKeys(model.stats, weekKeys), sumKeys(model.stats, prevKeys));
  const tiles = document.querySelector('#tiles');
  const trendEl = document.querySelector('#trend');
  if (tiles) {
    clear(tiles);
    const todaySeconds = sumDay(model.stats[todayKey]);
    const yesterdaySeconds = sumDay(model.stats[shiftDateKey(todayKey, -1)]);
    const weekAvg = Math.round(sumKeys(model.stats, weekKeys) / Math.max(1, weekKeys.length));
    let vs = text('vsYesterdayNone');
    if (yesterdaySeconds > 0) {
      const pct = Math.round(((todaySeconds - yesterdaySeconds) / yesterdaySeconds) * 100);
      vs = pct < 0 ? text('vsYesterdayDown', { n: Math.abs(pct) }) : pct > 0 ? text('vsYesterdayUp', { n: pct }) : text('vsYesterdaySame');
    }
    tiles.append(
      tile(text('today'), formatHM(todaySeconds, lang())),
      tile(text('vsYesterdayNone'), vs),
      tile(text('weekAvg'), formatHM(weekAvg, lang())),
    );
  }
  if (trendEl) {
    trendEl.textContent = trend.kind === 'new' ? '' : text(trend.kind === 'up' ? 'trendUp' : trend.kind === 'down' ? 'trendDown' : 'trendFlat', { n: Math.abs(trend.percent) });
  }
  const count = range === 'month' ? 30 : range === 'day' ? 2 : 7;
  const end = range === 'day' ? todayKey : todayKey;
  const keys = dateRange(end, count);
  const rows = keys.map((key) => ({ key, seconds: sumDay(model.stats[key]) }));
  const merged = mergeDays(model.stats, range === 'day' ? [todayKey] : keys);
  const ranked = rankSites(merged);
  clear(overviewCharts);
  const bars = el('div', { class: 'panel' }, el('h2', {}, text('byDay')));
  const sites = el('div', { class: 'panel' }, el('h2', {}, text('bySite')));
  if (rows.every((row) => row.seconds === 0)) bars.append(el('p', { class: 'empty' }, text('noData')));
  else bars.append(barChart(rows, lang(), text('chartBars')));
  const slices = donutSlices(ranked, text('other'));
  if (slices.length === 0) sites.append(el('p', { class: 'empty' }, text('noData')));
  else {
    sites.append(donutChart(slices, formatHM(sumDay(merged), lang()), text('chartDonut')), legend(slices, lang()));
  }
  overviewCharts.append(bars, sites);
}

function tile(label: string, value: string): HTMLElement {
  return el('div', { class: 'tile' }, el('span', {}, label), el('strong', {}, value));
}

function renderHistory(): void {
  const days = Object.keys(model.stats).sort().reverse();
  clear(historyBody);
  if (!selectedDay || !days.includes(selectedDay)) selectedDay = days[0] ?? '';
  if (days.length === 0) {
    historyBody.append(el('tr', {}, el('td', { colspan: '4' }, el('p', { class: 'empty' }, text('emptyHistory')))));
  }
  for (const day of days) {
    const ranked = rankSites(model.stats[day]);
    const label = longDayLabel(day, lang());
    const button = el('button', {
      type: 'button',
      class: 'secondary',
      'data-action': 'show-day',
      'data-day': day,
      'aria-pressed': day === selectedDay ? 'true' : 'false',
      'aria-label': text('showDay', { name: label }),
    }, label);
    historyBody.append(
      el(
        'tr',
        {},
        el('td', {}, button),
        el('td', { class: 'num' }, formatHM(sumDay(model.stats[day]), lang())),
        el('td', { class: 'num' }, String(sumVisits(model.stats[day]))),
        el('td', {}, ranked[0]?.domain ?? '—'),
      ),
    );
  }
  clear(historyDetail);
  if (!selectedDay) return;
  const panel = el('div', { class: 'panel' }, el('h2', {}, longDayLabel(selectedDay, lang())));
  const table = el('table', {}, el('caption', { class: 'sr-only' }, text('detailCaption')), el('tbody'));
  const body = table.querySelector('tbody');
  for (const row of rankSites(model.stats[selectedDay])) {
    body?.append(
      el(
        'tr',
        {},
        el('td', {}, row.domain),
        el('td', { class: 'num' }, formatHM(row.seconds, lang())),
        el('td', { class: 'num' }, String(row.visits)),
      ),
    );
  }
  panel.append(table);
  historyDetail.append(panel);
}

function renderLimits(): void {
  clear(limitRows);
  if (model.limits.length === 0) {
    limitRows.append(el('p', { class: 'empty' }, text('emptyLimits')));
    return;
  }
  for (const limit of model.limits) {
    limitRows.append(
      el(
        'div',
        { class: 'panel' },
        el('strong', {}, limit.domain),
        el('span', { class: 'help' }, `${limit.minutesPerDay} min · ${limit.enabled ? text('on') : text('off')}`),
        el(
          'div',
          { class: 'row-actions' },
          el('button', { type: 'button', class: 'secondary', 'data-action': 'edit-limit', 'data-domain': limit.domain, 'aria-label': text('editNamed', { name: limit.domain }) }, text('edit')),
          el('button', { type: 'button', class: 'secondary', 'data-action': 'toggle-limit', 'data-domain': limit.domain, 'aria-label': text('toggleNamed', { name: limit.domain }) }, limit.enabled ? text('turnOff') : text('turnOn')),
          el('button', { type: 'button', class: 'danger', 'data-action': 'delete-limit', 'data-domain': limit.domain, 'aria-label': text('deleteNamed', { name: limit.domain }) }, text('delete')),
        ),
      ),
    );
  }
}

function renderDomains(): void {
  fillDomainList(blockRows, model.blocklist, 'delete-block', 'emptyBlock');
  fillDomainList(allowRows, model.whitelist, 'delete-allow', 'emptyAllow');
}

function fillDomainList(list: HTMLElement, domains: string[], action: string, emptyKey: MessageKey): void {
  clear(list);
  if (domains.length === 0) {
    list.append(el('li', {}, el('p', { class: 'empty' }, text(emptyKey))));
    return;
  }
  for (const domain of domains) {
    list.append(
      el(
        'li',
        {},
        el('span', {}, domain),
        el('button', { type: 'button', class: 'danger', 'data-action': action, 'data-domain': domain, 'aria-label': text('deleteNamed', { name: domain }) }, text('delete')),
      ),
    );
  }
}

function renderSchedules(): void {
  clear(scheduleRows);
  if (model.schedules.length === 0) {
    scheduleRows.append(el('p', { class: 'empty' }, text('emptySchedules')));
    return;
  }
  for (const schedule of model.schedules) {
    const span = schedule.endMinutes >= 1440 && schedule.startMinutes === 0
      ? text('allDay')
      : `${formatClock(schedule.startMinutes)}–${formatClock(Math.min(schedule.endMinutes, 23 * 60 + 59))}`;
    scheduleRows.append(
      el(
        'div',
        { class: 'panel' },
        el('strong', {}, schedule.name),
        el('span', { class: 'help' }, `${span} · ${schedule.domains.join(', ')} · ${schedule.enabled ? text('on') : text('off')}`),
        el(
          'div',
          { class: 'row-actions' },
          el('button', { type: 'button', class: 'secondary', 'data-action': 'edit-schedule', 'data-id': schedule.id, 'aria-label': text('editNamed', { name: schedule.name }) }, text('edit')),
          el('button', { type: 'button', class: 'secondary', 'data-action': 'toggle-schedule', 'data-id': schedule.id, 'aria-label': text('toggleNamed', { name: schedule.name }) }, schedule.enabled ? text('turnOff') : text('turnOn')),
          el('button', { type: 'button', class: 'danger', 'data-action': 'delete-schedule', 'data-id': schedule.id, 'aria-label': text('deleteNamed', { name: schedule.name }) }, text('delete')),
        ),
      ),
    );
  }
}

function renderFocus(): void {
  clear(focusStatus);
  const focus = model.focus;
  const now = Date.now();
  if (!focus || focus.endsAt <= now) {
    focusStatus.append(el('h2', {}, text('focus')), el('p', { class: 'empty' }, text('noFocus')));
    return;
  }
  const left = Math.max(0, Math.ceil((focus.endsAt - now) / 1000));
  const stop = el('button', { type: 'button', class: 'secondary', 'data-action': 'stop-focus', 'data-i18n': 'stop' }, text('stop'));
  stop.disabled = focus.strict && focus.endsAt > now;
  focusStatus.append(
    el('h2', {}, text('activeFocus')),
    el('p', { class: 'focus-time' }, formatCountdown(left)),
    el('p', { class: 'help' }, focus.domains.join(', ')),
    focus.strict ? el('p', { class: 'help' }, text('strictHelp')) : '',
    stop,
  );
}

let storageWarned = false;
void reload().then((ok) => {
  build();
  fillSettings();
  limitEnabled.checked = true;
  schedEnabled.checked = true;
  renderDynamic();
  applyStatic();
  showView();
  if (!ok) toast(text('storageReadError'), 'err');
  chrome.storage.onChanged.addListener((changes, area) => {
    if (area !== 'local') return;
    const keys = Object.keys(changes);
    void reload().then((loaded) => {
      if (!loaded) {
        if (!storageWarned) {
          storageWarned = true;
          toast(text('storageReadError'), 'err');
        }
        return;
      }
      storageWarned = false;
      if (keys.every((key) => key === 'stats' || key === 'runtime' || key === 'proData')) {
        renderOverview();
        renderFocus();
        void refreshOptions();
        return;
      }
      renderDynamic();
      applyStatic();
      const active = document.activeElement;
      if (!active || active === document.body) fillSettings();
      else whiteMode.checked = model.settings.whitelistMode;
    });
  });
  setInterval(() => {
    if (model.focus && model.focus.endsAt > Date.now()) renderFocus();
  }, 1000);
});
