import { categoryLabel } from '@shared/categories'
import { formatDayLabel, formatDuration } from '@shared/format'
import type { Locale, Snapshot } from '@shared/types'
import { useEffect, useState, type ReactNode } from 'react'
import { Bar, BarChart, CartesianGrid, Cell, Pie, PieChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { EmptyState } from './empty'

function useDark(theme: Snapshot['settings']['theme']): boolean {
  const [systemDark, setSystemDark] = useState(() => window.matchMedia('(prefers-color-scheme: dark)').matches)
  useEffect(() => {
    const media = window.matchMedia('(prefers-color-scheme: dark)')
    const onChange = () => setSystemDark(media.matches)
    media.addEventListener('change', onChange)
    return () => media.removeEventListener('change', onChange)
  }, [])
  if (theme === 'dark') return true
  if (theme === 'light') return false
  return systemDark
}

function axisHours(ms: number): string {
  if (ms <= 0) return '0'
  if (ms < 3600000) return `${Math.round(ms / 60000)}m`
  const hours = ms / 3600000
  return Number.isInteger(hours) ? `${hours}h` : `${hours.toFixed(1)}h`
}

function Tip({
  active,
  payload,
  label,
  locale
}: {
  active?: boolean
  payload?: { value?: number }[]
  label?: string
  locale: Locale
}) {
  if (!active || !payload?.length) return null
  return (
    <div className="tip">
      <span>{label}</span>
      <strong>{formatDuration(Number(payload[0].value ?? 0), locale)}</strong>
    </div>
  )
}

function AccessibleChart({
  title,
  rows,
  children
}: {
  title: string
  rows: { key: string; label: string; value: string }[]
  children: ReactNode
}) {
  return (
    <section className="card chart-card">
      <h2>{title}</h2>
      <div className="chart-frame" aria-hidden="true">
        {children}
      </div>
      <ul className="sr-only">
        {rows.map((row) => (
          <li key={row.key}>
            {row.label} · {row.value}
          </li>
        ))}
      </ul>
    </section>
  )
}

export function UsageCharts({
  snap,
  title,
  weekTitle,
  splitTitle,
  emptySplit
}: {
  snap: Snapshot
  title: string
  weekTitle: string
  splitTitle: string
  emptySplit: string
}) {
  const locale = snap.settings.locale
  const dark = useDark(snap.settings.theme)
  const ink = dark ? '#E7EEF2' : '#0F172A'
  const grid = dark ? '#3D6270' : '#C5D9D6'
  const hourFill = dark ? '#2DD4BF' : '#0F766E'
  const weekFill = dark ? '#5EEAD4' : '#0F766E'
  const reduce = window.matchMedia('(prefers-reduced-motion: reduce)').matches
  const hourly = snap.hourly.map((item) => ({ label: String(item.hour), durationMs: item.durationMs }))
  const week = snap.week.map((item) => ({
    label: formatDayLabel(item.day, locale),
    durationMs: item.durationMs
  }))
  const slices = snap.categories
    .filter((item) => item.durationMs > 0)
    .map((item) => ({
      name: categoryLabel(item.builtinKey, item.name, locale),
      durationMs: item.durationMs,
      color: item.color
    }))

  const hourlyRows = hourly
    .filter((item) => item.durationMs > 0)
    .map((item) => ({ key: item.label, label: `${item.label} h`, value: formatDuration(item.durationMs, locale) }))
  const weekRows = week
    .filter((item) => item.durationMs > 0)
    .map((item) => ({ key: item.label, label: item.label, value: formatDuration(item.durationMs, locale) }))

  return (
    <>
      {hourlyRows.length === 0 ? (
        <section className="card">
          <h2>{title}</h2>
          <EmptyState title={emptySplit} />
        </section>
      ) : (
        <AccessibleChart title={title} rows={hourlyRows}>
          <ResponsiveContainer width="100%" height={220}>
            <BarChart data={hourly} barCategoryGap={4}>
              <CartesianGrid vertical={false} stroke={grid} />
              <XAxis dataKey="label" tick={{ fill: ink, fontSize: 12 }} axisLine={false} tickLine={false} interval={2} />
              <YAxis tickFormatter={axisHours} tick={{ fill: ink, fontSize: 12 }} axisLine={false} tickLine={false} width={42} />
              <Tooltip content={<Tip locale={locale} />} cursor={{ fill: grid }} />
              <Bar dataKey="durationMs" fill={hourFill} radius={[6, 6, 0, 0]} isAnimationActive={!reduce} />
            </BarChart>
          </ResponsiveContainer>
        </AccessibleChart>
      )}
      <div className="grid-2">
        {weekRows.length === 0 ? (
          <section className="card">
            <h2>{weekTitle}</h2>
            <EmptyState title={emptySplit} />
          </section>
        ) : (
          <AccessibleChart title={weekTitle} rows={weekRows}>
            <ResponsiveContainer width="100%" height={220}>
              <BarChart data={week}>
                <CartesianGrid vertical={false} stroke={grid} />
                <XAxis dataKey="label" tick={{ fill: ink, fontSize: 12 }} axisLine={false} tickLine={false} />
                <YAxis tickFormatter={axisHours} tick={{ fill: ink, fontSize: 12 }} axisLine={false} tickLine={false} width={42} />
                <Tooltip content={<Tip locale={locale} />} cursor={{ fill: grid }} />
                <Bar dataKey="durationMs" fill={weekFill} radius={[6, 6, 0, 0]} isAnimationActive={!reduce} />
              </BarChart>
            </ResponsiveContainer>
          </AccessibleChart>
        )}
        <section className="card">
          <h2>{splitTitle}</h2>
          {slices.length === 0 ? (
            <EmptyState title={emptySplit} />
          ) : (
            <div className="split">
              <div className="chart-frame pie-frame" aria-hidden="true">
                <ResponsiveContainer width="100%" height={200}>
                  <PieChart>
                    <Pie
                      data={slices}
                      dataKey="durationMs"
                      nameKey="name"
                      innerRadius={54}
                      outerRadius={80}
                      paddingAngle={2}
                      stroke="none"
                      isAnimationActive={!reduce}
                    >
                      {slices.map((slice) => (
                        <Cell key={slice.name} fill={slice.color} />
                      ))}
                    </Pie>
                    <Tooltip content={<Tip locale={locale} />} />
                  </PieChart>
                </ResponsiveContainer>
              </div>
              <ul className="legend">
                {slices.map((slice) => (
                  <li key={slice.name}>
                    <i aria-hidden="true" style={{ background: slice.color }} />
                    <span>{slice.name}</span>
                    <strong>{formatDuration(slice.durationMs, locale)}</strong>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>
      </div>
    </>
  )
}
