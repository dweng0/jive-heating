import { useEffect, useRef, useState } from 'react'
import { formatCost, symbol } from '@/lib/energy'
import { dayOf, fiveMinuteAverage, gasDayLabel, gasDelta, ODOMETER_PERIODS, tripAt, versusYesterday, type EnergyTotals, type OdometerPeriod, type Trip } from '@/lib/energyDials'
import { cn } from '@/lib/utils'

const PERIOD_KEY = 'energy.odometer'
const SHOWS_KEY = 'energy.usageShows'
/** How often the usage counter moves on: often enough that its last wheel is seen to turn. */
const TICK_MS = 250

/**
 * A car's mechanical counter, square-cornered: each digit a wheel that rolls to its place. The last [decimals] wheels
 * are the fractions; [prefix], such as "£", stands before them.
 */
function Rolling({ value, digits, decimals, testId, prefix, roll = 700, fraction = 'bg-[var(--power-high)]/80' }: { value: number; digits: number; decimals: number; testId: string; prefix?: string; roll?: number; fraction?: string }) {
  const text = value.toFixed(decimals).padStart(digits + (decimals > 0 ? 1 : 0), '0')
  return (
    <div data-testid={testId} data-value={value.toFixed(decimals)} aria-label={`${prefix ?? ''}${value.toFixed(decimals)}`} className="inline-flex items-stretch overflow-hidden rounded-[3px] bg-foreground font-mono text-2xl leading-none font-bold text-background tabular-nums shadow-inner sm:text-[1.75rem]">
      {prefix && <span className="flex items-center px-1">{prefix}</span>}
      {[...text].map((ch, i) =>
        ch === '.' ? (
          <span key={i} className="flex items-end px-px pb-1 text-base">.</span>
        ) : (
          <span
            key={i}
            className={cn('relative h-[1.4em] w-[0.85em] overflow-hidden border-r border-background/15 last:border-r-0', i >= text.length - decimals && fraction)}
          >
            <span className="absolute inset-x-0 top-0 flex flex-col items-center transition-transform ease-out" style={{ transform: `translateY(-${Number(ch) * 1.4}em)`, transitionDuration: `${roll}ms` }}>
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
 * The dashboard's counters, like a car's: today's usage (or, tapped, its cost) rolling on at the last 5 minutes' average power, an
 * odometer for the day, week, month or year, the gas meter's latest whole day against the day before, and today against
 * yesterday at the same time.
 */
export function TripMeters({ totals, readings }: { totals: EnergyTotals | null; readings: { at: number; watts: number | null }[] }) {
  const [period, setPeriod] = useState<OdometerPeriod>(() => (localStorage.getItem(PERIOD_KEY) as OdometerPeriod | null) ?? 'week')
  const [shows, setShows] = useState<'kwh' | 'cost'>(() => (localStorage.getItem(SHOWS_KEY) === 'cost' ? 'cost' : 'kwh'))
  const [trip, setTrip] = useState<Trip | null>(null)
  const latest = useRef({ totals, readings })
  useEffect(() => {
    latest.current = { totals, readings }
  })

  // Every quarter second the usage counter rolls on at the last 5 minutes' average power; a new recorded figure is taken at once.
  useEffect(() => {
    const step = () => {
      const { totals: t, readings: r } = latest.current
      if (!t) return
      const now = Date.now()
      setTrip((was) => tripAt(was, { day: dayOf(now), kwh: t.today.kwh }, fiveMinuteAverage(r, now), now))
    }
    step()
    const timer = window.setInterval(step, TICK_MS)
    return () => window.clearInterval(timer)
  }, [totals])

  const todayKwh = trip?.kwh ?? totals?.today.kwh ?? 0
  // What the trip meter has rolled on since today's recorded figure, which the odometer has not counted yet.
  const rolled = totals ? Math.max(0, todayKwh - totals.today.kwh) : 0
  const odometer = totals ? totals[period].kwh + rolled : 0
  const price = totals?.pencePerKwh ?? 0
  const currency = totals?.currency ?? 'GBP'
  const vs = totals ? versusYesterday(todayKwh, totals.yesterdaySoFar) : null
  const gas = totals?.gas ?? null
  const gasVs = gas ? gasDelta(gas) : null

  function swap() {
    const next = shows === 'kwh' ? 'cost' : 'kwh'
    setShows(next)
    localStorage.setItem(SHOWS_KEY, next)
  }

  function choose(p: OdometerPeriod) {
    setPeriod(p)
    localStorage.setItem(PERIOD_KEY, p)
  }

  return (
    <div data-testid="trip-meters" className={cn('grid grid-cols-1 gap-4 border-t pt-4 sm:items-end', gas ? 'sm:grid-cols-2 lg:grid-cols-4' : 'sm:grid-cols-3')}>
      <button
        type="button"
        data-testid="usage-today"
        data-shows={shows}
        onClick={swap}
        aria-label={shows === 'kwh' ? 'Usage today, tap for cost' : 'Cost today, tap for usage'}
        className="flex flex-col items-center gap-1.5 rounded-lg focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
      >
        <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{shows === 'kwh' ? 'Usage today' : 'Cost today'}</div>
        {shows === 'kwh' ? (
          <Rolling testId="trip-kwh" value={todayKwh} digits={3} decimals={4} roll={TICK_MS * 2} />
        ) : (
          <Rolling testId="trip-pounds" value={(todayKwh * price) / 100} digits={2} decimals={4} prefix={symbol(currency)} roll={TICK_MS * 2} />
        )}
        <div className="text-sm tabular-nums">
          {shows === 'kwh' ? (
            <>
              <span className="text-muted-foreground">kWh · </span>
              <span data-testid="trip-cost" className="font-semibold">{formatCost(todayKwh * price, currency)}</span>
            </>
          ) : (
            <span data-testid="trip-used" className="text-muted-foreground">{todayKwh.toFixed(1)} kWh</span>
          )}
        </div>
      </button>
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
      {gas && (
        <div data-testid="gas-day" className="flex flex-col items-center gap-1.5">
          <div className="text-xs font-medium uppercase tracking-wide text-muted-foreground">{gasDayLabel(gas.date)}</div>
          <Rolling testId="gas-kwh" value={gas.kwh} digits={3} decimals={1} fraction="bg-heat/90" />
          <div className="flex flex-wrap justify-center gap-x-2 text-sm tabular-nums">
            <span>
              <span className="text-muted-foreground">kWh · </span>
              <span className="font-semibold">{formatCost(gas.cost, currency)}</span>
            </span>
            {gasVs && (
              <span data-testid="gas-delta" data-more={gasVs.more} className={cn('font-semibold', gasVs.more ? 'text-[var(--power-mid)]' : 'text-[var(--power-low)]')}>
                {gasVs.text}
              </span>
            )}
          </div>
        </div>
      )}
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
