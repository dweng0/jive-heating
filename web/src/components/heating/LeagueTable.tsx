import { Card, CardContent } from '@/components/ui/card'
import { leagueRows } from '@/lib/league'
import { formatWatts, type LivePower } from '@/lib/livePower'
import { cn } from '@/lib/utils'

/** What is using power right now, biggest first: a row per meter, with a bar as long as its share of the house. */
export function LeagueTable({ live }: { live: LivePower | null }) {
  const rows = leagueRows(live)
  if (rows.length === 0) return null
  return (
    <Card data-testid="league-table">
      <CardContent className="flex flex-col gap-1 p-4">
        <h2 className="mb-1 text-lg font-semibold">Using power now</h2>
        <ul className="flex flex-col gap-1">
          {rows.map((r) => {
            const pct = r.share == null ? null : r.share * 100
            return (
              <li
                key={r.key}
                data-testid="league-row"
                data-state={r.state}
                className={cn('relative overflow-hidden rounded-md px-3 py-2', r.state === 'no-reading' && 'opacity-50')}
              >
                <div
                  data-testid="league-bar"
                  aria-hidden
                  className={cn('absolute inset-y-0 left-0', r.state === 'else' ? 'bg-foreground/10' : 'bg-primary/35')}
                  style={{ width: `${pct ?? 0}%` }}
                />
                <div className="relative flex items-baseline justify-between gap-3">
                  <span data-testid="league-name" className={cn('truncate font-medium', r.state === 'else' && 'text-muted-foreground')}>
                    {r.name}
                  </span>
                  <span className="flex shrink-0 items-baseline gap-3 tabular-nums">
                    <span data-testid="league-watts" className={cn(r.state !== 'on' && r.state !== 'else' && 'text-muted-foreground')}>
                      {r.state === 'off' ? 'Off' : r.state === 'no-reading' ? 'No reading' : formatWatts(r.watts)}
                    </span>
                    <span data-testid="league-share" className="w-10 text-right text-sm text-muted-foreground">
                      {pct == null ? '' : `${Math.round(pct)}%`}
                    </span>
                  </span>
                </div>
              </li>
            )
          })}
        </ul>
      </CardContent>
    </Card>
  )
}
