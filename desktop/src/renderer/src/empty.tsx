import { Hourglass } from './hourglass'

export function EmptyState({ title, detail }: { title: string; detail?: string }) {
  return (
    <div className="empty-state" role="status">
      <Hourglass level={0.12} />
      <strong>{title}</strong>
      {detail ? <p className="muted">{detail}</p> : null}
    </div>
  )
}
