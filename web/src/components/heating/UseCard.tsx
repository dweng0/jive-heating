import { Link } from 'react-router-dom'
import { Card, CardContent } from '@/components/ui/card'
import { energyPlace, formatCost, formatKwh } from '@/lib/energy'
import { formatWatts } from '@/lib/livePower'
import { cn } from '@/lib/utils'

/** A live card on the Energy tab: power now, its share of the house, and today's use and cost. */
export function UseCard({
  to,
  testId,
  name,
  detail,
  watts,
  house,
  kwh,
  cost,
  currency,
  muted,
  noReading,
}: {
  /** Where picking the card goes: a room's or everything else's energy dashboard. */
  to?: string
  testId: string
  name: string
  detail?: string
  watts: number | null
  house: number | null
  kwh: number | undefined
  cost: number | undefined
  currency: string
  muted?: boolean
  /** Connected, but nothing it measures is reporting: "No reading", never a last figure */
  noReading?: boolean
}) {
  // Share of the house's load now: only while both are live.
  const share = watts != null && house != null && house > 0 ? Math.min(1, watts / house) : null
  const card = (
    <Card data-testid={testId} className={cn('h-full', to && 'transition-colors hover:bg-accent/40')}>
      <CardContent className="flex flex-col gap-2 p-4">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="truncate font-medium">{name}</div>
            {detail && <div className="truncate text-xs text-muted-foreground">{detail}</div>}
          </div>
          <div data-testid="live-watts" className={cn('shrink-0 text-xl font-bold tabular-nums', watts == null && 'text-muted-foreground')}>
            {noReading ? 'No reading' : formatWatts(watts)}
          </div>
        </div>
        <div className="h-1.5 overflow-hidden rounded-full bg-secondary" title={share == null ? undefined : `${Math.round(share * 100)}% of the house now`}>
          <div
            data-testid="live-share"
            data-share={share == null ? '' : Math.round(share * 100)}
            className={cn('h-full rounded-full transition-[width] duration-700', muted ? 'bg-muted-foreground/50' : 'bg-[var(--power-neutral)]')}
            style={{ width: `${(share ?? 0) * 100}%` }}
          />
        </div>
        <div className="flex justify-between text-xs text-muted-foreground tabular-nums">
          <span>{share == null ? '' : `${Math.round(share * 100)}% of the house`}</span>
          <span data-testid="live-day">
            Today {kwh == null ? '–' : formatKwh(kwh)} · <span className="font-semibold text-foreground">{formatCost(cost, currency)}</span>
          </span>
        </div>
      </CardContent>
    </Card>
  )
  if (!to) return card
  return (
    <Link to={to} aria-label={`${name} energy`} className="rounded-xl focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none" onClick={() => (energyPlace.scrollY = window.scrollY)}>
      {card}
    </Link>
  )
}
