import { useEffect, useState } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { formatCost, formatKwh } from '@/lib/energy'
import { busiest, gasApi, gasDayTitle, halfHourTime, type GasDay } from '@/lib/gas'
import { useHome } from '@/lib/home'

/**
 * A day of the smart gas meter as a bar per half-hour, with the day's kWh, cost and busiest half-hour, and buttons for
 * the day before and after. Admins also see the boiler's burn rate as the meter measured it, and can use it for the
 * estimate. Hidden while the meter has no readings.
 */
export function GasCard() {
  const { me } = useHome()
  const [date, setDate] = useState<string | undefined>(undefined)
  const [day, setDay] = useState<GasDay | null>(null)

  useEffect(() => {
    let cancelled = false
    gasApi.day(date).then(
      (d) => !cancelled && setDay(d),
      () => !cancelled && date == null && setDay(null),
    )
    return () => {
      cancelled = true
    }
  }, [date])

  if (!day) return null
  const max = Math.max(0.5, ...day.halfHours.map((h) => h.kwh))
  const top = busiest(day)


  return (
    <Card data-testid="gas-card">
      <CardContent className="flex flex-col gap-4 p-4 sm:p-5">
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="flex flex-col gap-0.5">
            <div className="text-xs font-semibold uppercase tracking-wide text-muted-foreground">Gas</div>
            <div data-testid="gas-card-date" className="text-xl font-bold">{gasDayTitle(day.date)}</div>
            <div data-testid="gas-card-figures" className="text-sm text-muted-foreground tabular-nums">
              {formatKwh(day.kwh)} · {formatCost(day.cost, day.currency)}
              {top && ` · busiest ${halfHourTime(top.at)}, ${top.kwh.toFixed(1)} kWh`}
            </div>
          </div>
          <div className="flex gap-2">
            <Button variant="outline" size="icon" aria-label="Day before" disabled={!day.previous} onClick={() => day.previous && setDate(day.previous)}>
              <ChevronLeft />
            </Button>
            <Button variant="outline" size="icon" aria-label="Day after" disabled={!day.next} onClick={() => day.next && setDate(day.next)}>
              <ChevronRight />
            </Button>
          </div>
        </div>
        <div className="flex h-36 items-end gap-px border-b" aria-label={`Gas each half-hour on ${gasDayTitle(day.date)}`} role="img">
          {day.halfHours.map((h) => (
            <div
              key={h.at}
              data-testid="gas-half-hour"
              title={`${halfHourTime(h.at)} · ${h.kwh.toFixed(2)} kWh`}
              className="flex-1 rounded-t-[2px] bg-heat"
              style={{ height: `${(h.kwh / max) * 100}%`, minHeight: h.kwh > 0 ? 2 : 0 }}
            />
          ))}
        </div>
        <div className="flex justify-between text-xs text-muted-foreground">
          <span>00:00</span>
          <span>06:00</span>
          <span>12:00</span>
          <span>18:00</span>
          <span>24:00</span>
        </div>
        {me.admin && day.measuredBoilerKw != null && (
          <div data-testid="boiler-measured" className="rounded-xl bg-secondary px-4 py-3 text-sm">
            <span className="font-semibold">Boiler average from the meter: {day.measuredBoilerKw.toFixed(1)} kW, used for estimates.</span>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
