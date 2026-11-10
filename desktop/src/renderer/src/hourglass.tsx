type HourglassProps = {
  level: number
  tone?: 'brand' | 'light'
  className?: string
}

export function Hourglass({ level, tone = 'brand', className }: HourglassProps) {
  const clamped = Math.max(0, Math.min(1, level))
  const stroke = tone === 'light' ? 'var(--case-ink)' : 'currentColor'
  const top = topSand(1 - clamped)
  const bottom = bottomSand(clamped)
  const falling = clamped > 0.04 && clamped < 0.98
  return (
    <svg className={className} viewBox="0 0 120 160" aria-hidden="true">
      <rect x="18" y="8" width="84" height="10" rx="4" fill={stroke} />
      <rect x="18" y="142" width="84" height="10" rx="4" fill={stroke} />
      <path
        d="M32 30 H88 L60 78 L88 136 H32 L60 78 Z"
        fill="none"
        stroke={stroke}
        strokeWidth="4"
        strokeLinejoin="round"
      />
      {top ? <path d={top} fill="var(--sand)" /> : null}
      {bottom ? <path d={bottom} fill="var(--sand)" /> : null}
      {falling ? <rect x="57" y="78" width="6" height="16" rx="3" fill="var(--sand)" /> : null}
    </svg>
  )
}

function topSand(remaining: number): string | null {
  if (remaining <= 0.02) return null
  const y = 74 - remaining * 44
  const half = 28 * remaining
  return `M${60 - half} ${y} H${60 + half} L60 74 Z`
}

function bottomSand(level: number): string | null {
  if (level <= 0.02) return null
  const y = 136 - level * 50
  const half = (28 * (y - 86)) / 50
  return `M${60 - half} ${y} H${60 + half} L88 136 H32 Z`
}
