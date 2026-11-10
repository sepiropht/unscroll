import { StrictMode, useEffect, useRef, useState } from 'react'
import { createRoot } from 'react-dom/client'
import { formatDuration } from '@shared/format'
import { t } from '@shared/i18n'
import type { OverlayPayload } from '@shared/types'
import { EmptyState } from './empty'
import { Hourglass } from './hourglass'
import './styles.css'

function OverlayApp() {
  const [payload, setPayload] = useState<OverlayPayload | null>(null)
  const [note, setNote] = useState('')
  const dialogRef = useRef<HTMLElement>(null)

  useEffect(() => {
    void window.unscroll.getOverlayState().then(setPayload)
    return window.unscroll.onOverlay((next) => {
      setPayload(next)
      if (next) setNote('')
    })
  }, [])

  useEffect(() => {
    if (!payload) return
    document.documentElement.lang = payload.locale
    document.title = t(payload.locale, 'overlay.title')
  }, [payload])

  const locale = payload?.locale
  const remaining = payload ? Math.max(0, payload.maxGrants - payload.grantsUsed) : 0

  useEffect(() => {
    const root = dialogRef.current
    if (!root || !payload) return
    const focusable = () =>
      [...root.querySelectorAll<HTMLElement>('button:not(:disabled), [href], input, select, textarea')]
    focusable()[0]?.focus()
    const onKey = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        if (!payload.strict) void window.unscroll.ignoreOverlay()
        return
      }
      if (event.key !== 'Tab') return
      const list = focusable()
      if (list.length === 0) return
      const first = list[0]
      const last = list[list.length - 1]
      if (event.shiftKey && document.activeElement === first) {
        event.preventDefault()
        last.focus()
      } else if (!event.shiftKey && document.activeElement === last) {
        event.preventDefault()
        first.focus()
      }
    }
    root.addEventListener('keydown', onKey)
    return () => root.removeEventListener('keydown', onKey)
  }, [payload])

  if (!payload || !locale) {
    return (
      <main className="overlay-root">
        <EmptyState title={t('fr', 'overlay.waiting')} />
      </main>
    )
  }

  const level = Math.max(0, Math.min(1, payload.usedMs / Math.max(payload.limitMs, 1)))

  return (
    <main className="overlay-root">
      <section
        className="overlay-card"
        role="alertdialog"
        aria-modal="true"
        aria-labelledby="overlay-title"
        aria-describedby="overlay-body"
        ref={dialogRef}
        tabIndex={-1}
      >
        <Hourglass className="glass" level={level} tone="light" />
        <h1 id="overlay-title">{t(locale, 'overlay.title')}</h1>
        <p className="app-name">{payload.appName}</p>
        <p id="overlay-body">{t(locale, 'overlay.body', { app: payload.appName })}</p>
        <p>
          {t(locale, 'overlay.used', {
            used: formatDuration(payload.usedMs, locale),
            limit: formatDuration(payload.limitMs, locale)
          })}
        </p>
        {payload.strict ? <p className="muted">{t(locale, 'overlay.strictNote')}</p> : null}
        <p className="muted">{remaining > 0 ? t(locale, 'overlay.remaining', { n: remaining }) : t(locale, 'overlay.noGrants')}</p>
        {note ? (
          <p className="note" role="status">
            {note}
          </p>
        ) : null}
        <div className="overlay-actions">
          <button
            className="primary"
            type="button"
            disabled={remaining <= 0}
            onClick={() => {
              void window.unscroll.grantOverlay().then((result) => {
                if (!result.ok && result.reason === 'still-over') setNote(t(locale, 'overlay.stillOver'))
                if (!result.ok && result.reason === 'max') setNote(t(locale, 'overlay.noGrants'))
              })
            }}
          >
            {t(locale, 'overlay.grant', { minutes: payload.grantMinutes })}
          </button>
          {payload.strict ? null : (
            <button className="ghost" type="button" onClick={() => void window.unscroll.ignoreOverlay()}>
              {t(locale, 'overlay.ignore')}
            </button>
          )}
        </div>
        <button className="text-btn" type="button" onClick={() => void window.unscroll.quitFromOverlay()}>
          {t(locale, 'overlay.quit')}
        </button>
      </section>
    </main>
  )
}

createRoot(document.getElementById('root') as HTMLElement).render(
  <StrictMode>
    <OverlayApp />
  </StrictMode>
)
