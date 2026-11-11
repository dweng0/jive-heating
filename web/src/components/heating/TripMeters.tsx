import { useEffect, useRef, useState } from 'react'
import { formatCost, symbol } from '@/lib/energy'
import { counterAt, fiveMinuteAverage, gasDayLabel, gasDelta, nextUsagePeriod, serviceSkew, USAGE_PERIODS, versusYesterday, type Counter, type ReceivedTotals, type UsagePeriod } from '@/lib/energyDials'
import { cn } from '@/lib/utils'

const PERIOD_KEY = 'energy.usagePeriod'
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

/** Whole-number wheels and fraction wheels for each period: enough to hold it, and the last turning while watched. */
const WHEELS: Record<UsagePeriod, { digits: number; decimals: number }> = {
  today: { digits: 3, decimals: 4 },
  week: { digits: 3, decimals: 4 },
  month: { digits: 4, decimals: 3 },
  year: { digits: 5, decimals: 3 },
}

function storedPeriod(): UsagePeriod {
  try {
    const p = localStorage.getItem(PERIOD_KEY)
    return USAGE_PERIODS.some((q) => q.value === p) ? (p as UsagePeriod) : 'today'
  } catch {
    return 'today'
  }
}

/**
 * The dashboard's counters, like a car's: the house's usage today, this week, this month or this year (a tap moves on
 * through them), in kWh or, with the "£" switch, its cost, rolling on at the last 5 minutes' average power; the gas
 * meter's latest whole day against the day before; and today against yesterday at the same time. The day is the
 * household's, from the service, and turns over at its midnight whatever the browser's clock says.
 */
export function TripMeters({ totals, readings }: { totals: ReceivedTotals | null; readings: { at: number; watts: number | null }[] }) {
  const [period, setPeriod] = useState<UsagePeriod>(storedPeriod)
  const [shows, setShows] = useState<'kwh' | 'cost'>(() => {
    try {
      return localStorage.getItem(SHOWS_KEY) === 'cost' ? 'cost' : 'kwh'
    } catch {
      return 'kwh'
    }
  })
  const [counter, setCounter] = useState<Counter | null>(null)
  const latest = useRef({ totals, readings })
  useEffect(() => {
    latest.current = { totals, readings }
  })

  // Every quarter second the counter rolls on at the last 5 minutes' average power; new totals are taken at once.
  useEffect(() => {
    const step = () => {
      const { totals: t, readings: r } = latest.current
      if (!t) return
      const now = Date.now() + serviceSkew(t)
      setCounter((was) => counterAt(was, t, fiveMinuteAverage(r, now), now))
    }
    step()
    const timer = window.setInterval(step, TICK_MS)
    return () => window.clearInterval(timer)
  }, [totals])

  const kwh = counter?.[period] ?? totals?.[period].kwh ?? 0
  const todayKwh = counter?.today ?? totals?.today.kwh ?? 0
  const label = USAGE_PERIODS.find((p) => p.value === period)!.label
  const wheels = WHEELS[period]
  const price = totals?.pencePerKwh ?? 0
  const currency = totals?.currency ?? 'GBP'
  const vs = totals ? versusYesterday(todayKwh, totals.yesterdaySoFar) : null
  const gas = totals?.gas ?? null
  const gasVs = gas ? gasDelta(gas) : null

  function swap() {
    const next = shows === 'kwh' ? 'cost' : 'kwh'
    setShows(next)
    try {
      localStorage.setItem(SHOWS_KEY, next)
    } catch {
      /* not remembered */
    }
  }

  function moveOn() {
    const next = nextUsagePeriod(period)
    setPeriod(next)
    try {
      localStorage.setItem(PERIOD_KEY, next)
    } catch {
      /* not remembered */
    }
  }

  return (
    <div data-testid="trip-meters" className={cn('grid grid-cols-1 gap-4 border-t pt-4 sm:items-end', gas ? 'sm:grid-cols-3' : 'sm:grid-cols-2')}>
      <div data-testid="usage-counter" data-period={period} data-shows={shows} className="flex flex-col items-center gap-1.5">
        <div className="flex items-center gap-2">
          <div data-testid="usage-label" className="text-xs font-medium uppercase tracking-wide text-muted-foreground">
            {shows === 'kwh' ? 'Usage' : 'Cost'} {label}
          </div>
          <button
            type="button"
            onClick={swap}
            aria-label={shows === 'kwh' ? 'Show cost' : 'Show usage'}
            className="rounded-md bg-secondary px-1.5 py-0.5 text-xs font-semibold text-foreground hover:bg-secondary/80 focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
          >
            {shows === 'kwh' ? symbol(currency) : 'kWh'}
          </button>
        </div>
        <button
          type="button"
          onClick={moveOn}
          aria-label={`${shows === 'kwh' ? 'Usage' : 'Cost'} ${label}, tap for ${USAGE_PERIODS.find((p) => p.value === nextUsagePeriod(period))!.label}`}
          className="flex flex-col items-center gap-1.5 rounded-lg focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
        >
          {shows === 'kwh' ? (
            <Rolling testId="trip-kwh" value={kwh} digits={wheels.digits} decimals={wheels.decimals} roll={TICK_MS * 2} />
          ) : (
            <Rolling testId="trip-pounds" value={(kwh * price) / 100} digits={wheels.digits - 1} decimals={wheels.decimals} prefix={symbol(currency)} roll={TICK_MS * 2} />
          )}
          <div className="text-sm tabular-nums">
            {shows === 'kwh' ? (
              <>
                <span className="text-muted-foreground">kWh · </span>
                <span data-testid="trip-cost" className="font-semibold">{formatCost(kwh * price, currency)}</span>
              </>
            ) : (
              <span data-testid="trip-used" className="text-muted-foreground">{kwh.toFixed(1)} kWh</span>
            )}
          </div>
        </button>
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
