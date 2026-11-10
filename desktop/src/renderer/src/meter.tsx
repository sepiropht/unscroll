export function Meter({
  ratio,
  tone = '',
  label,
  kind = 'progressbar',
  color
}: {
  ratio: number
  tone?: '' | 'warn' | 'over'
  label: string
  kind?: 'progressbar' | 'meter'
  color?: string
}) {
  const value = Math.max(0, Math.min(100, Math.round(ratio * 100)))
  return (
    <div
      className={tone ? `bar ${tone}` : 'bar'}
      role={kind}
      aria-valuemin={0}
      aria-valuemax={100}
      aria-valuenow={value}
      aria-label={label}
    >
      <span style={{ width: `${value}%`, background: color }} />
    </div>
  )
}
