import { useEffect, useRef, useState } from 'react'
import { formatCost } from '@/lib/energy'
import { dayOf, fiveMinuteAverage, ODOMETER_PERIODS, tripAt, versusYesterday, type EnergyTotals, type OdometerPeriod, type Trip } from '@/lib/energyDials'
import { cn } from '@/lib/utils'

const PERIOD_KEY = 'energy.odometer'

/** A car's mechanical counter: each digit a wheel that rolls to its place. The last [decimals] wheels are tenths and hundredths. */
function Rolling({ value, digits, decimals, testId }: { value: number; digits: number; decimals: number; testId: string }) {
  const text = value.toFixed(decimals).padStart(digits + (decimals > 0 ? 1 : 0), '0')
  return (
    <div data-testid={testId} data-value={value.toFixed(decimals)} aria-label={value.toFixed(decimals)} className="inline-flex items-stretch overflow-hidden rounded-md bg-foreground font-mono text-xl leading-none text-background tabular-nums shadow-inner sm:text-2xl">
      {[...text].map((ch, i) =>
        ch === '.' ? (
          <span key={i} className="flex items-end px-px pb-1 text-base">.</span>
        ) : (
          <span
            key={i}
            className={cn('relative h-[1.4em] w-[0.8em] overflow-hidden border-r border-background/15 last:border-r-0', i >= text.length - decimals && 'bg-[var(--power-high)]/80')}
          >
            <span className="absolute inset-x-0 top-0 flex flex-col items-center transition-transform duration-700 ease-out" style={{ transform: `translateY(-${Number(ch) * 1.4}em)` }}>
              {Array.from({ length: 10 }, (_, d) => (
                <span key={d} className="flex h-[1.4em] items-center">{d}</span>
              ))}
            </span>
          </span>
        ),
      )}
    </div>
  )
}

/**
 * The dashboard's counters, like a car's: today's trip meter rolling on at the last 5 minutes' average power, an
 * odometer for the day, week, month or year, and today against yesterday at the same time.
 */
export function TripMeters({ totals, readings }: { totals: EnergyTotals | null; readings: { at: number; watts: number | null }[] }) {
  const [period, setPeriod] = useState<OdometerPeriod>(() => (localStorage.getItem(PERIOD_KEY) as OdometerPeriod | null) ?? 'week')
  const [trip, setTrip] = useState<Trip | null>(null)
  const latest = useRef({ totals, readings })
  useEffect(() => {
    latest.current = { totals, readings }
  })

  // Every second the trip meter rolls on at the last 5 minutes' average power; a new recorded figure is taken at once.
  useEffect(() => {
    const step = () => {
      const { totals: t, readings: r } = latest.current
      if (!t) return
      const now = Date.now()
      setTrip((was) => tripAt(was, { day: dayOf(now), kwh: t.today.kwh }, fiveMinuteAverage(r, now), now))
    }
    step()
    const timer = window.setInterval(step, 1000)
    return () => window.clearInterval(timer)
  }, [totals])

  const todayKwh = trip?.kwh ?? totals?.today.kwh ?? 0
  // What the trip meter has rolled on since today's recorded figure, which the odometer has not counted yet.
  const rolled = totals ? Math.max(0, todayKwh - totals.today.kwh) : 0
  const odometer = totals ? totals[period].kwh + rolled : 0
  const price = totals?.pencePerKwh ?? 0
  const currency = totals?.currency ?? 'GBP'
  const vs = totals ? versusYesterday(todayKwh, totals.yesterdaySoFar) : null

  function choose(p: OdometerPeriod) {
    setPeriod(p)
    localStorage.setItem(PERIOD_KEY, p)
  }

  return (
    <div data-testid="trip-meters" className="grid grid-cols-1 gap-4 border-t pt-4 sm:grid-cols-3 sm:items-end">
      <div className="flex flex-col items-center gap-1.5">
        <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Trip · today</div>
        <Rolling testId="trip-kwh" value={todayKwh} digits={3} decimals={2} />
        <div className="text-sm tabular-nums">
          <span className="text-muted-foreground">kWh · </span>
          <span data-testid="trip-cost" className="font-semibold">{formatCost(todayKwh * price, currency)}</span>
        </div>
      </div>
      <div className="flex flex-col items-center gap-1.5">
        <div role="group" aria-label="Odometer period" className="flex rounded-lg bg-secondary p-0.5">
          {ODOMETER_PERIODS.map((p) => (
            <button
              key={p.value}
              type="button"
              aria-pressed={period === p.value}
              onClick={() => choose(p.value)}
              className={cn('rounded-md px-2 py-0.5 text-xs font-medium transition-colors', period === p.value ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground')}
            >
              {p.label}
            </button>
          ))}
        </div>
        <Rolling testId="odometer-kwh" value={odometer} digits={5} decimals={1} />
        <div className="text-sm tabular-nums">
          <span className="text-muted-foreground">kWh · </span>
          <span data-testid="odometer-cost" className="font-semibold">{formatCost(odometer * price, currency)}</span>
        </div>
      </div>
      <div className="flex flex-col items-center gap-1.5 text-center">
        <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">Today vs yesterday</div>
        {vs ? (
          <div data-testid="vs-yesterday" data-more={vs.more} className={cn('text-lg font-semibold tabular-nums', vs.more ? 'text-[var(--power-mid)]' : 'text-[var(--power-low)]')}>
            {vs.text}
          </div>
        ) : (
          <div className="text-sm text-muted-foreground">Nothing recorded yesterday</div>
        )}
        {totals?.yesterdaySoFar && (
          <div className="text-xs text-muted-foreground tabular-nums">
            Yesterday by now {totals.yesterdaySoFar.kwh.toFixed(1)} kWh · {formatCost(totals.yesterdaySoFar.cost, currency)}
          </div>
        )}
      </div>
    </div>
  )
}
