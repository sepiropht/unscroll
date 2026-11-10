import '../styles/tokens.css';
import './block.css';
import { sendMessage } from '../shared/chrome-call.ts';
import { safeReturnUrl } from '../shared/domain.ts';
import { applyDocument, el } from '../shared/dom.ts';
import { formatHM } from '../shared/format.ts';
import { t, type MessageKey } from '../shared/i18n.ts';
import { hourglassMark } from '../shared/mark.ts';
import type { BlockReason } from '../shared/rules.ts';
import { errorMessageKey, type ClientResponse, type SiteStatus } from '../shared/messages.ts';
import { quoteFor } from '../shared/quotes.ts';
import type { Lang } from '../shared/state.ts';

const params = new URLSearchParams(location.search);
const host = params.get('host') ?? '';
const fallbackReason = params.get('reason');
const returnUrl = params.get('return') ?? '';
const app = document.querySelector('#app');
if (!app) throw new Error('missing app');

const eyebrowEl = el('p', { class: 'eyebrow' });
const titleEl = el('h1');
const quoteLabelEl = el('p', { class: 'quote-label' });
const reasonEl = el('p', { class: 'reason' });
const usedEl = el('p', { class: 'used' });
const quoteEl = el('blockquote');
const noteEl = el('p', { class: 'note', role: 'status' });
const errorEl = el('p', { class: 'error', id: 'block-error', role: 'alert' });
const storageEl = el('p', { class: 'banner', role: 'status' });
const passwordLabel = el('label', { for: 'block-password' });
const snoozeBtn = el('button', { class: 'amber', type: 'button', 'data-i18n': 'snooze' }, t('fr', 'snooze'));
const continueBtn = el('button', { type: 'button', class: 'hidden' });
const passwordInput = el('input', {
  id: 'block-password',
  type: 'password',
  autocomplete: 'current-password',
  maxlength: '200',
  'aria-describedby': 'block-error',
}) as HTMLInputElement;
const unlockBtn = el('button', { type: 'submit', class: 'secondary' });
const form = el('form', {}, passwordLabel, passwordInput, unlockBtn);
const actions = el('div', { class: 'actions' }, snoozeBtn, form, continueBtn);
let lang: Lang = 'fr';

function text(key: MessageKey, vars?: Record<string, string | number>): string {
  return t(lang, key, vars);
}

function reasonCopy(reason: BlockReason | null, site: string, detail: string | null): string {
  if (reason === 'limit') return text('reasonLimit', { host: site });
  if (reason === 'blocklist') return text('reasonBlocklist', { host: site });
  if (reason === 'whitelist') return text('reasonWhitelist', { host: site });
  if (reason === 'schedule') return text('reasonSchedule', { host: site, detail: detail ?? '' });
  if (reason === 'focus') return text('reasonFocus', { host: site });
  return text('blockIdle');
}

function paint(status: SiteStatus | null): void {
  lang = status?.language ?? 'fr';
  applyDocument(status?.theme ?? 'auto', lang);
  document.title = text('appName');
  const site = status?.host || host || '—';
  const reason = status?.reason ?? (fallbackReason as BlockReason | null);
  const idle = !host;
  eyebrowEl.textContent = idle ? text('blockIdleTitle') : text('blocked');
  titleEl.textContent = idle ? text('appName') : host;
  quoteLabelEl.hidden = idle;
  quoteEl.hidden = idle;
  actions.hidden = idle;
  storageEl.hidden = status?.storageOk !== false;
  if (!storageEl.hidden) storageEl.textContent = text('storageError');
  quoteLabelEl.textContent = text('quoteLabel');
  reasonEl.textContent = idle ? text('blockIdle') : reasonCopy(reason, site, status?.detail ?? params.get('detail'));
  passwordLabel.textContent = text('passwordField');
  if (status) {
    const bits = [text('usedToday', { time: formatHM(status.usedSeconds, lang) })];
    if (status.limitMinutes != null) bits.push(text('limitValue', { n: status.limitMinutes }));
    usedEl.textContent = bits.join(' · ');
    quoteEl.textContent = quoteFor(lang, site, status.todayKey);
  } else if (host) {
    quoteEl.textContent = quoteFor(lang, host, new Date().toISOString().slice(0, 10));
  }
  snoozeBtn.textContent = text('snooze');
  continueBtn.textContent = text('continue');
  unlockBtn.textContent = text('unlock');
  passwordInput.placeholder = text('password');
  const strict = status?.strict === true;
  const paused = status ? !status.blocked : false;
  snoozeBtn.classList.toggle('hidden', idle || strict || paused || (status?.snoozeUsesLeft ?? 1) <= 0);
  form.classList.toggle('hidden', strict || paused || !status?.hasPassword);
  continueBtn.classList.toggle('hidden', !paused);
  if (strict) noteEl.textContent = text('strictNoBypass');
  else if (status && status.snoozeUsesLeft <= 0 && status.blocked) noteEl.textContent = text('noBypassLeft');
  else if (status) noteEl.textContent = text(status.snoozeUsesLeft === 1 ? 'usesLeft' : 'usesLeftPlural', { n: status.snoozeUsesLeft });
  else noteEl.textContent = '';
}

async function refresh(): Promise<void> {
  if (!host) {
    paint(null);
    return;
  }
  try {
    const response = await sendMessage<ClientResponse>({ type: 'site-status', host });
    if (response.ok && response.status) paint(response.status);
    else {
      paint(null);
      errorEl.textContent = text(errorMessageKey(response.ok ? 'failed' : response.error));
    }
  } catch {
    paint(null);
    errorEl.textContent = text('failed');
  }
}

function destination(statusHost: string): string {
  return safeReturnUrl(returnUrl, statusHost) ?? `https://${statusHost}/`;
}

snoozeBtn.addEventListener('click', () => {
  errorEl.textContent = '';
  void sendMessage<ClientResponse>({ type: 'snooze', host, returnUrl: destination(host) })
    .then((response) => {
      if (!response.ok) {
        errorEl.textContent = text(errorMessageKey(response.error));
        return;
      }
      if (response.status) paint(response.status);
    })
    .catch(() => {
      errorEl.textContent = text('failed');
    });
});

form.addEventListener('submit', (event) => {
  event.preventDefault();
  errorEl.textContent = '';
  void sendMessage<ClientResponse>({
    type: 'unlock',
    host,
    password: passwordInput.value,
    returnUrl: destination(host),
  }).then((response) => {
    passwordInput.setAttribute('aria-invalid', response.ok ? 'false' : 'true');
    if (!response.ok) {
      errorEl.textContent = text(errorMessageKey(response.error));
      return;
    }
    if (response.status) paint(response.status);
  });
});

continueBtn.addEventListener('click', () => {
  location.href = destination(host);
});

storageEl.hidden = true;
app.append(
  el(
    'main',
    { class: 'gate' },
    el(
      'section',
      { class: 'panel' },
      hourglassMark('mark'),
      eyebrowEl,
      titleEl,
      reasonEl,
      usedEl,
      quoteLabelEl,
      quoteEl,
      storageEl,
      actions,
      noteEl,
      errorEl,
    ),
  ),
);

void refresh();
if (host) setInterval(() => void refresh(), 1000);
