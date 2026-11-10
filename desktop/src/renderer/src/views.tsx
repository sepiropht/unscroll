import { categoryLabel } from '@shared/categories'
import { formatDuration, formatLongDay, REFERENCE_MS, splitDuration } from '@shared/format'
import { t, type MessageKey } from '@shared/i18n'
import { limitBarTone, limitCapMs, limitRatio } from '@shared/limits'
import { isLocale, LOCALES, type HistoryDay, type LimitRecord, type Locale, type Settings, type Snapshot } from '@shared/types'
import { useEffect, useState, type FormEvent } from 'react'
import { UsageCharts } from './charts'
import { EmptyState } from './empty'
import { Hourglass } from './hourglass'
import { Meter } from './meter'

const LANGUAGE_KEY: Record<Locale, MessageKey> = {
  fr: 'settings.fr',
  en: 'settings.en',
  es: 'settings.es',
  de: 'settings.de',
  pt: 'settings.pt'
}

function limitMeter(limit: LimitRecord): { cap: number; ratio: number; tone: '' | 'warn' | 'over' } {
  const cap = limitCapMs(limit.dailyMinutes, limit.extraMinutes)
  return { cap, ratio: limitRatio(limit.usedMs, cap), tone: limitBarTone(limit.usedMs, cap) }
}


function yesterdayMs(snap: Snapshot): number {
  const today = snap.week.findIndex((row) => row.day === snap.day)
  if (today > 0) return snap.week[today - 1]?.durationMs ?? 0
  if (snap.week.length >= 2) return snap.week[snap.week.length - 2]?.durationMs ?? 0
  return 0
}

function weekAverageMs(snap: Snapshot): number {
  if (snap.week.length === 0) return 0
  const total = snap.week.reduce((sum, row) => sum + row.durationMs, 0)
  return Math.round(total / snap.week.length)
}

function vsYesterday(locale: Locale, todayMs: number, previousMs: number): string {
  if (previousMs <= 0) return t(locale, 'today.vsNone')
  const pct = Math.round(((todayMs - previousMs) / previousMs) * 100)
  if (pct < 0) return t(locale, 'today.vsDown', { n: Math.abs(pct) })
  if (pct > 0) return t(locale, 'today.vsUp', { n: pct })
  return t(locale, 'today.vsSame')
}

function statusText(snap: Snapshot): string {
  const locale = snap.settings.locale
  const current = snap.current
  if (current.paused) return t(locale, 'today.paused')
  if (current.locked) return t(locale, 'today.locked')
  if (current.suspended) return t(locale, 'today.suspended')
  if (current.idle) return t(locale, 'today.idle')
  if (!current.appName) return t(locale, 'today.none')
  return current.appName
}

export function TodayView({ snap }: { snap: Snapshot }) {
  const locale = snap.settings.locale
  const parts = splitDuration(snap.todayTotalMs)
  const level = Math.min(1, snap.todayTotalMs / REFERENCE_MS)
  const top = snap.topApps[0]?.durationMs ?? 0
  const previous = yesterdayMs(snap)
  const average = weekAverageMs(snap)
  return (
    <>
      <section className="hero">
        <div>
          <p className="kicker">{formatLongDay(snap.day, locale)}</p>
          <p className="clock">
            <span className="sr-only">{formatDuration(snap.todayTotalMs, locale)}</span>
            <span aria-hidden="true">
              {parts.hours}
              <small>h</small> {String(parts.minutes).padStart(2, '0')}
              <small>min</small>
            </span>
          </p>
          <div className="metrics" role="group">
            <p className="metric">
              <strong>{vsYesterday(locale, snap.todayTotalMs, previous)}</strong>
            </p>
            <p className="metric">
              <span>{t(locale, 'today.weekAvg')}</span>
              <strong>{formatDuration(average, locale)}</strong>
            </p>
          </div>
          <div className="status-line">
            <span>{t(locale, 'today.now')}</span>
            <strong role="status">{statusText(snap)}</strong>
            <button
              className="ghost"
              type="button"
              aria-pressed={snap.settings.trackingPaused}
              onClick={() => void window.unscroll.setPaused(!snap.settings.trackingPaused)}
            >
              {t(locale, snap.settings.trackingPaused ? 'tracking.resume' : 'tracking.pause')}
            </button>
          </div>
        </div>
        <figure className="glass">
          <Hourglass level={level} />
          <figcaption>{t(locale, 'today.reference')}</figcaption>
        </figure>
      </section>
      <section className="card">
        <h2>{t(locale, 'today.top')}</h2>
        {snap.topApps.length === 0 ? (
          <EmptyState title={t(locale, 'today.empty')} />
        ) : (
          <ul className="app-list">
            {snap.topApps.map((appRow) => (
              <li key={appRow.appName}>
                <div className="app-meta">
                  <span>{appRow.appName}</span>
                  <strong>{formatDuration(appRow.durationMs, locale)}</strong>
                </div>
                <Meter
                  kind="meter"
                  ratio={top > 0 ? appRow.durationMs / top : 0}
                  color={appRow.categoryColor}
                  label={`${appRow.appName} · ${formatDuration(appRow.durationMs, locale)}`}
                />
                <span className="pill">
                  <i aria-hidden="true" style={{ background: appRow.categoryColor }} />
                  {appRow.categoryName}
                  {appRow.automatic ? ` · ${t(locale, 'categories.auto')}` : ''}
                </span>
              </li>
            ))}
          </ul>
        )}
      </section>
      <div className="stack" style={{ marginTop: 16 }}>
        <UsageCharts
          snap={snap}
          title={t(locale, 'today.hourly')}
          weekTitle={t(locale, 'today.week')}
          splitTitle={t(locale, 'today.distribution')}
          emptySplit={t(locale, 'chart.empty')}
        />
      </div>
    </>
  )
}

export function HistoryView({ snap }: { snap: Snapshot }) {
  const locale = snap.settings.locale
  const [days, setDays] = useState<HistoryDay[]>([])
  useEffect(() => {
    let alive = true
    void window.unscroll.getHistory().then((next) => {
      if (alive) setDays(next)
    })
    return () => {
      alive = false
    }
  }, [snap.todayTotalMs, snap.day])

  return (
    <>
      <header className="page-head">
        <h2>{t(locale, 'history.title')}</h2>
      </header>
      {days.length === 0 ? (
        <EmptyState title={t(locale, 'history.empty')} detail={t(locale, 'history.emptyHelp')} />
      ) : (
        <div className="stack">
          {days.map((day) => (
            <details key={day.day} className="history-day" open={day.day === snap.day}>
              <summary>
                <span>{formatLongDay(day.day, locale)}</span>
                <strong>{formatDuration(day.totalMs, locale)}</strong>
              </summary>
              <ul>
                {day.apps.map((appRow) => (
                  <li key={appRow.appName}>
                    <span>{appRow.appName}</span>
                    <span>{formatDuration(appRow.durationMs, locale)}</span>
                  </li>
                ))}
              </ul>
            </details>
          ))}
        </div>
      )}
    </>
  )
}

export function LimitsView({ snap }: { snap: Snapshot }) {
  const locale = snap.settings.locale
  const [appName, setAppName] = useState('')
  const [minutes, setMinutes] = useState('60')
  const [strict, setStrict] = useState(false)
  const [error, setError] = useState('')
  const names = Array.from(new Set([...snap.knownApps.map((item) => item.appName), ...snap.limits.map((item) => item.appName)]))

  function onSubmit(event: FormEvent) {
    event.preventDefault()
    void window.unscroll
      .saveLimit({ appName, dailyMinutes: Number(minutes), strict })
      .then((result) => {
        if (!result.ok) setError(t(locale, 'limits.invalid'))
        else {
          setError('')
          setAppName('')
        }
      })
  }

  return (
    <>
      <header className="page-head">
        <h2>{t(locale, 'limits.title')}</h2>
      </header>
      <p className="help">{t(locale, 'limits.help')}</p>
      <form className="card form" onSubmit={onSubmit}>
        <div className="inline">
          <label className="field">
            {t(locale, 'limits.app')}
            <input
              list="known-apps"
              value={appName}
              onChange={(event) => setAppName(event.target.value)}
              autoComplete="off"
              aria-invalid={error ? true : undefined}
              required
            />
            <datalist id="known-apps">
              {names.map((name) => (
                <option key={name} value={name} />
              ))}
            </datalist>
          </label>
          <label className="field">
            {t(locale, 'limits.minutes')}
            <input
              type="number"
              min={1}
              max={1440}
              value={minutes}
              onChange={(event) => setMinutes(event.target.value)}
              required
            />
          </label>
          <label className="checks">
            <input type="checkbox" checked={strict} onChange={(event) => setStrict(event.target.checked)} />
            {t(locale, 'limits.strict')}
          </label>
          <button className="primary" type="submit">
            {t(locale, 'limits.add')}
          </button>
        </div>
        <p className="muted">{t(locale, 'limits.strictHint')}</p>
        {error ? (
          <p className="error" role="alert">
            {error}
          </p>
        ) : null}
      </form>
      <div className="stack" style={{ marginTop: 16 }}>
        {snap.limits.length === 0 ? <EmptyState title={t(locale, 'limits.empty')} /> : null}
        {snap.limits.map((limit) => {
          const meter = limitMeter(limit)
          const used = t(locale, 'limits.used', {
            used: formatDuration(limit.usedMs, locale),
            limit: formatDuration(meter.cap, locale)
          })
          return (
            <article key={limit.appName} className="card limit-row">
              <div className="limit-head">
                <strong>{limit.appName}</strong>
                <span>
                  {limit.dailyMinutes} min{limit.strict ? ` · ${t(locale, 'limits.strict')}` : ''}
                  {limit.extraMinutes > 0 ? ` +${limit.extraMinutes}` : ''}
                </span>
                <button
                  className="danger"
                  type="button"
                  aria-label={t(locale, 'a11y.removeLimit', { app: limit.appName })}
                  onClick={() => void window.unscroll.deleteLimit(limit.appName)}
                >
                  {t(locale, 'limits.delete')}
                </button>
              </div>
              <Meter ratio={meter.ratio} tone={meter.tone} label={`${limit.appName} · ${used}`} />
              <span className="muted">
                {used}
                {meter.tone === 'over' ? ` · ${t(locale, 'limits.over')}` : ''}
                {meter.tone === 'warn' ? ` · ${t(locale, 'limits.near')}` : ''}
              </span>
            </article>
          )
        })}
      </div>
    </>
  )
}

export function CategoriesView({ snap }: { snap: Snapshot }) {
  const locale = snap.settings.locale
  const [name, setName] = useState('')
  const [color, setColor] = useState('#0F766E')
  const [error, setError] = useState('')

  function add(event: FormEvent) {
    event.preventDefault()
    void window.unscroll.saveCategory({ name, color }).then((result) => {
      if (!result.ok) setError(t(locale, 'categories.invalid'))
      else {
        setError('')
        setName('')
      }
    })
  }

  return (
    <>
      <header className="page-head">
        <h2>{t(locale, 'categories.title')}</h2>
      </header>
      <p className="help">{t(locale, 'categories.help')}</p>
      <div className="stack">
        {snap.categories.map((category) => (
          <CategoryEditor key={category.id} category={category} locale={locale} />
        ))}
      </div>
      <form className="card inline" style={{ marginTop: 16 }} onSubmit={add}>
        <label className="field">
          {t(locale, 'categories.name')}
          <input value={name} onChange={(event) => setName(event.target.value)} maxLength={40} required />
        </label>
        <label className="field">
          {t(locale, 'categories.color')}
          <input type="color" value={color} onChange={(event) => setColor(event.target.value)} />
        </label>
        <button className="primary" type="submit">
          {t(locale, 'categories.add')}
        </button>
        {error ? (
          <p className="error" role="alert">
            {error}
          </p>
        ) : null}
      </form>
      <section className="card" style={{ marginTop: 16 }}>
        <h2>{t(locale, 'categories.apps')}</h2>
        {snap.knownApps.length === 0 ? (
          <EmptyState title={t(locale, 'categories.empty')} />
        ) : (
          <ul className="app-list">
            {snap.knownApps.map((appRow) => (
              <li key={appRow.appName} className="assign-row">
                <span>{appRow.appName}</span>
                <select
                  aria-label={`${appRow.appName} · ${t(locale, 'categories.apps')}`}
                  value={appRow.categoryId ?? ''}
                  onChange={(event) => {
                    const value = event.target.value
                    void window.unscroll.setAppCategory(appRow.appName, value ? Number(value) : null)
                  }}
                >
                  <option value="">{t(locale, 'categories.auto')}</option>
                  {snap.categories.map((category) => (
                    <option key={category.id} value={category.id}>
                      {categoryLabel(category.builtinKey, category.name, locale)}
                    </option>
                  ))}
                </select>
              </li>
            ))}
          </ul>
        )}
      </section>
    </>
  )
}

function CategoryEditor({
  category,
  locale
}: {
  category: Snapshot['categories'][number]
  locale: Snapshot['settings']['locale']
}) {
  const [name, setName] = useState(category.name)
  const [color, setColor] = useState(category.color)
  const [error, setError] = useState('')
  useEffect(() => {
    setName(category.name)
    setColor(category.color)
  }, [category.name, category.color])

  return (
    <form
      className="card category-row"
      onSubmit={(event) => {
        event.preventDefault()
        void window.unscroll.saveCategory({ id: category.id, name, color }).then((result) => {
          setError(result.ok ? '' : t(locale, 'categories.invalid'))
        })
      }}
    >
      <div className="inline">
        <label className="field">
          {t(locale, 'categories.name')}
          <input
            value={name}
            aria-label={`${t(locale, 'categories.name')} · ${categoryLabel(category.builtinKey, category.name, locale)}`}
            onChange={(event) => setName(event.target.value)}
            maxLength={40}
            required
          />
        </label>
        <label className="field">
          {t(locale, 'categories.color')}
          <input type="color" value={color} onChange={(event) => setColor(event.target.value)} />
        </label>
        <button className="primary" type="submit">
          {t(locale, 'categories.save')}
        </button>
        <button
          className="danger"
          type="button"
          disabled={category.builtinKey === 'autre'}
          aria-label={t(locale, 'a11y.removeCategory', {
            name: categoryLabel(category.builtinKey, category.name, locale)
          })}
          onClick={() => {
            void window.unscroll.deleteCategory(category.id).then((result) => {
              setError(result.ok ? '' : t(locale, 'categories.keep'))
            })
          }}
        >
          {t(locale, 'categories.delete')}
        </button>
      </div>
      <span className="pill">
        <i aria-hidden="true" style={{ background: category.color }} />
        {categoryLabel(category.builtinKey, category.name, locale)} · {formatDuration(category.durationMs, locale)}
      </span>
      {category.builtinKey === 'autre' ? <span className="sr-only">{t(locale, 'categories.keep')}</span> : null}
      {error ? (
        <p className="error" role="alert">
          {error}
        </p>
      ) : null}
    </form>
  )
}

export function SettingsView({ snap }: { snap: Snapshot }) {
  const settings = snap.settings
  const locale = settings.locale
  const [saved, setSaved] = useState('')
  const [exportNote, setExportNote] = useState('')

  function save(patch: Partial<Settings>) {
    void window.unscroll.updateSettings(patch).then(() => {
      setSaved(t(locale, 'settings.saved'))
      window.setTimeout(() => setSaved(''), 1600)
    })
  }

  return (
    <>
      <header className="page-head">
        <h2>{t(locale, 'settings.title')}</h2>
        <span className="note" role="status">
          {saved}
        </span>
      </header>
      <div className="card settings">
        <label className="field">
          {t(locale, 'settings.language')}
          <select
            value={settings.locale}
            onChange={(event) => {
              const next = event.target.value
              if (isLocale(next)) save({ locale: next })
            }}
          >
            {LOCALES.map((code) => (
              <option key={code} value={code}>
                {t(locale, LANGUAGE_KEY[code])}
              </option>
            ))}
          </select>
        </label>
        <label className="field">
          {t(locale, 'settings.theme')}
          <select
            value={settings.theme}
            onChange={(event) => {
              const theme = event.target.value
              if (theme === 'light' || theme === 'dark' || theme === 'system') save({ theme })
            }}
          >
            <option value="system">{t(locale, 'settings.themeSystem')}</option>
            <option value="light">{t(locale, 'settings.themeLight')}</option>
            <option value="dark">{t(locale, 'settings.themeDark')}</option>
          </select>
        </label>
        <NumberField
          label={t(locale, 'settings.idle')}
          value={settings.idleThresholdSec}
          min={10}
          max={3600}
          onCommit={(value) => save({ idleThresholdSec: value })}
        />
        <NumberField
          label={t(locale, 'settings.break')}
          value={settings.breakIntervalMin}
          min={0}
          max={240}
          onCommit={(value) => save({ breakIntervalMin: value })}
        />
        <NumberField
          label={t(locale, 'settings.dayStart')}
          value={settings.dayStartHour}
          min={0}
          max={23}
          onCommit={(value) => save({ dayStartHour: value })}
        />
        <label className="checks">
          <input
            type="checkbox"
            checked={settings.autostart}
            onChange={(event) => save({ autostart: event.target.checked })}
          />
          {t(locale, 'settings.autostart')}
        </label>
        <label className="checks">
          <input
            type="checkbox"
            checked={settings.dailySummaryEnabled}
            onChange={(event) => save({ dailySummaryEnabled: event.target.checked })}
          />
          {t(locale, 'settings.summary')}
        </label>
        <NumberField
          label={t(locale, 'settings.summaryHour')}
          value={settings.dailySummaryHour}
          min={0}
          max={23}
          onCommit={(value) => save({ dailySummaryHour: value })}
        />
        <NumberField
          label={t(locale, 'settings.grantMinutes')}
          value={settings.grantMinutes}
          min={1}
          max={60}
          onCommit={(value) => save({ grantMinutes: value })}
        />
        <NumberField
          label={t(locale, 'settings.maxGrants')}
          value={settings.maxGrantsPerDay}
          min={0}
          max={50}
          onCommit={(value) => save({ maxGrantsPerDay: value })}
        />
        <p className="muted">{t(locale, 'settings.localNote')}</p>
        <p className="muted">{t(locale, 'settings.noUpdate')}</p>
        <p className="muted">{t(locale, 'settings.license')}</p>
        <div className="inline">
          <button
            className="ghost"
            type="button"
            onClick={() => {
              void window.unscroll.exportData('csv').then((result) => {
                setExportNote(result.ok ? `${t(locale, 'export.done')} · ${result.path}` : result.canceled ? '' : t(locale, 'export.failed'))
              })
            }}
          >
            {t(locale, 'export.csv')}
          </button>
          <button
            className="ghost"
            type="button"
            onClick={() => {
              void window.unscroll.exportData('json').then((result) => {
                setExportNote(result.ok ? `${t(locale, 'export.done')} · ${result.path}` : result.canceled ? '' : t(locale, 'export.failed'))
              })
            }}
          >
            {t(locale, 'export.json')}
          </button>
        </div>
        {exportNote ? (
          <p className="note" role="status">
            {exportNote}
          </p>
        ) : null}
      </div>
    </>
  )
}

function NumberField({
  label,
  value,
  min,
  max,
  onCommit
}: {
  label: string
  value: number
  min: number
  max: number
  onCommit: (value: number) => void
}) {
  const [text, setText] = useState(String(value))
  useEffect(() => setText(String(value)), [value])
  return (
    <label className="field">
      {label}
      <input
        type="number"
        min={min}
        max={max}
        value={text}
        onChange={(event) => setText(event.target.value)}
        onBlur={() => {
          const next = Number(text)
          if (!Number.isFinite(next)) {
            setText(String(value))
            return
          }
          onCommit(Math.min(max, Math.max(min, Math.round(next))))
        }}
      />
    </label>
  )
}

export function PlusView({
  locale,
  onOpen
}: {
  locale: Locale
  onOpen: (page: 'history' | 'categories' | 'settings' | 'report') => void
}) {
  return (
    <>
      <header className="page-head">
        <h2>{t(locale, 'plus.title')}</h2>
      </header>
      <ul className="plus-list">
        <li>
          <button className="plus-link" type="button" onClick={() => onOpen('history')}>
            {t(locale, 'nav.history')}
          </button>
        </li>
        <li>
          <button className="plus-link" type="button" onClick={() => onOpen('categories')}>
            {t(locale, 'nav.categories')}
          </button>
        </li>
        <li>
          <button className="plus-link" type="button" onClick={() => onOpen('report')}>
            {t(locale, 'plus.report')}
          </button>
        </li>
        <li>
          <button className="plus-link" type="button" onClick={() => onOpen('settings')}>
            {t(locale, 'nav.settings')}
          </button>
        </li>
      </ul>
    </>
  )
}
