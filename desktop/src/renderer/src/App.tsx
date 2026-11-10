import { EditionPage, type EditionPageId } from '@edition-ui'
import { formatDuration } from '@shared/format'
import { t } from '@shared/i18n'
import type { Settings, Snapshot, SyncPublicState } from '@shared/types'
import { useEffect, useState } from 'react'
import { EmptyState } from './empty'
import { Hourglass } from './hourglass'
import { CategoriesView, HistoryView, LimitsView, PlusView, SettingsView, TodayView } from './views'

type Page = 'today' | 'history' | 'limits' | 'categories' | 'settings' | 'plus' | EditionPageId

function useSnapshot(): Snapshot | null {
  const [snap, setSnap] = useState<Snapshot | null>(null)
  useEffect(() => {
    let alive = true
    void window.unscroll.getSnapshot().then((next) => {
      if (alive) setSnap(next)
    })
    const off = window.unscroll.onTick((next) => setSnap(next))
    return () => {
      alive = false
      off()
    }
  }, [])
  return snap
}

function useTheme(settings: Settings | undefined): void {
  useEffect(() => {
    if (!settings) return
    const root = document.documentElement
    root.lang = settings.locale
    if (settings.theme === 'system') delete root.dataset.theme
    else root.dataset.theme = settings.theme
    document.title = t(settings.locale, 'app.name')
  }, [settings])
}

function useSyncState(page: Page): SyncPublicState | null {
  const [state, setState] = useState<SyncPublicState | null>(null)
  useEffect(() => {
    let alive = true
    const load = () => {
      void window.unscroll.getSyncState().then((next) => {
        if (alive) setState(next)
      })
    }
    load()
    const id = window.setInterval(load, 4000)
    return () => {
      alive = false
      window.clearInterval(id)
    }
  }, [page])
  return state
}

export function App() {
  if (typeof window.unscroll === 'undefined') {
    return (
      <main className="boot">
        <div className="empty-state" role="alert">
          <p>{t('fr', 'common.missing')}</p>
          <p className="muted">{t('fr', 'common.missingHint')}</p>
        </div>
      </main>
    )
  }
  return <Dashboard />
}

function Dashboard() {
  const snap = useSnapshot()
  const [page, setPage] = useState<Page>('today')
  const sync = useSyncState(page)
  useTheme(snap?.settings)
  if (!snap) {
    return (
      <main className="boot" aria-busy="true">
        <EmptyState title={t('fr', 'common.loading')} />
      </main>
    )
  }
  const locale = snap.settings.locale
  const syncOn = Boolean(sync?.enabled)
  const plusOpen = page === 'plus' || page === 'history' || page === 'categories' || page === 'settings' || page === 'report'
  return (
    <div className="shell">
      <a className="skip" href="#contenu">
        {t(locale, 'a11y.skip')}
      </a>
      <aside className="case">
        <div className="brand">
          <Hourglass level={Math.min(1, snap.todayTotalMs / (8 * 60 * 60 * 1000))} tone="light" />
          <div>
            <h1>{t(locale, 'app.name')}</h1>
            <span>{formatDuration(snap.todayTotalMs, locale)}</span>
          </div>
        </div>
        <nav className="nav" aria-label={t(locale, 'a11y.nav')}>
          <button
            className="nav-btn"
            type="button"
            aria-current={page === 'today' ? 'page' : undefined}
            onClick={() => setPage('today')}
          >
            {t(locale, 'nav.home')}
          </button>
          <button
            className="nav-btn"
            type="button"
            aria-current={page === 'limits' ? 'page' : undefined}
            onClick={() => setPage('limits')}
          >
            {t(locale, 'nav.limits')}
          </button>
          <button
            className="nav-btn nav-btn-sync"
            type="button"
            aria-current={page === 'sync' ? 'page' : undefined}
            onClick={() => setPage('sync')}
          >
            <span>{t(locale, 'nav.sync')}</span>
            <small>{t(locale, syncOn ? 'nav.syncOn' : 'nav.syncOff')}</small>
          </button>
          <button
            className="nav-btn"
            type="button"
            aria-current={plusOpen ? 'page' : undefined}
            onClick={() => setPage('plus')}
          >
            {t(locale, 'nav.more')}
          </button>
        </nav>
        <p className="case-foot">
          {t(locale, 'nav.local')}
          <br />
          {snap.version}
        </p>
      </aside>
      <main className="main" id="contenu" tabIndex={-1}>
        {snap.current.provider.supported ? null : (
          <p className="compositor-note" role="status">
            {t(locale, 'today.unsupported')}
          </p>
        )}
        {page === 'today' ? <TodayView snap={snap} /> : null}
        {page === 'history' ? <HistoryView snap={snap} /> : null}
        {page === 'limits' ? <LimitsView snap={snap} /> : null}
        {page === 'categories' ? <CategoriesView snap={snap} /> : null}
        {page === 'settings' ? <SettingsView snap={snap} /> : null}
        {page === 'plus' ? <PlusView locale={locale} onOpen={setPage} /> : null}
        <EditionPage page={page} snap={snap} />
      </main>
    </div>
  )
}
