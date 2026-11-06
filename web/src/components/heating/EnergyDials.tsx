import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router-dom'
import { Fuel as FuelIcon } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/card'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { formatCost, formatKwh, SET_TARGET, type EnergyDashboard } from '@/lib/energy'
import { clockTime, fuel, NO_PLACES, NO_TARGET, paceLine, type Place, type TodayPeak } from '@/lib/energyDials'
import { SWEEP_MS, dialFraction, dialMax, dialTicks, formatWatts, powerZone, sweep, type PowerBands, type PowerZone } from '@/lib/livePower'
import { cn } from '@/lib/utils'

const START = 150 // degrees, bottom-left
const SWEEP = 240

const ZONE_COLOR: Record<PowerZone, string> = {
  low: 'var(--power-low)',
  mid: 'var(--power-mid)',
  high: 'var(--power-high)',
  neutral: 'var(--power-neutral)',
}

function point(angle: number, r: number, cx = 100, cy = 100) {
  const rad = (angle * Math.PI) / 180
  return { x: cx + r * Math.cos(rad), y: cy + r * Math.sin(rad) }
}

function arc(from: number, to: number, r: number, cx = 100, cy = 100) {
  const a = point(from, r, cx, cy)
  const b = point(to, r, cx, cy)
  const large = to - from > 180 ? 1 : 0
  return `M ${a.x} ${a.y} A ${r} ${r} 0 ${large} 1 ${b.x} ${b.y}`
}

/** [target], eased towards over [SWEEP_MS] each time it changes, from wherever the last sweep had got to. */
function useSwept(target: number): number {
  const [shown, setShown] = useState(target)
  const shownRef = useRef(target)
  useEffect(() => {
    const from = shownRef.current
    if (from === target) return
    const started = performance.now()
    let frame = 0
    const step = (now: number) => {
      const value = sweep(from, target, now - started)
      shownRef.current = value
      setShown(value)
      if (now - started < SWEEP_MS) frame = requestAnimationFrame(step)
    }
    frame = requestAnimationFrame(step)
    return () => cancelAnimationFrame(frame)
  }, [target])
  return shown
}

/**
 * The Energy tab's dashboard: today's peak, the whole house, the busiest room or area and the daily target as a fuel
 * gauge, like a car's. The two dials share one scale, which only grows while the page is open.
 */
export function EnergyDials({
  watts,
  connected,
  bands,
  busiest,
  peak,
  today,
  admin,
}: {
  watts: number | null
  connected: boolean
  bands: PowerBands | null
  /** The room or area the second dial is on; null when none is reporting */
  busiest: Place | null
  peak: TodayPeak | null
  today: EnergyDashboard | null
  admin: boolean
}) {
  const wanted = dialMax(Math.max(watts ?? 0, busiest?.watts ?? 0, peak?.watts ?? 0))
  const [max, setMax] = useState(wanted)
  if (wanted > max) setMax(wanted)

  return (
    <Card data-testid="energy-dials">
      <CardContent className="grid grid-cols-2 items-end gap-x-4 gap-y-6 p-4 sm:p-6 lg:grid-cols-[1fr_1.5fr_1.5fr_1fr] lg:gap-x-8">
        <div className="order-3 lg:order-1">
          <PeakGauge peak={peak} max={max} />
        </div>
        <div className="order-1 lg:order-2">
          <SpeedDial
            testId="power-dial"
            nowTestId="power-now"
            label="Whole house"
            watts={watts}
            bands={bands}
            max={max}
            empty={connected ? 'No reading' : 'Connecting…'}
          />
        </div>
        <div className="order-2 lg:order-3">
          <SpeedDial
            testId="busiest-dial"
            nowTestId="busiest-now"
            label={busiest?.name ?? 'Busiest room'}
            labelTestId="busiest-name"
            watts={busiest?.watts ?? null}
            bands={null}
            max={max}
            empty={connected ? NO_PLACES : 'Connecting…'}
          />
        </div>
        <div className="order-4">
          <FuelGauge today={today} admin={admin} />
        </div>
      </CardContent>
    </Card>
  )
}

/** A big dial: a coloured arc up to the needle, colour bands as a thin outer ring, the watts in the middle and a label under it. */
function SpeedDial(props: {
  testId: string
  nowTestId: string
  label: string
  labelTestId?: string
  watts: number | null
  bands: PowerBands | null
  max: number
  empty: string
}) {
  const { testId, nowTestId, label, labelTestId, watts, bands, max, empty } = props
  const shown = useSwept(watts ?? 0)
  const angle = (w: number) => START + SWEEP * dialFraction(w, max)
  const zone = watts == null ? null : powerZone(shown, bands)
  const color = zone ? ZONE_COLOR[zone] : 'var(--muted-foreground)'
  const tip = point(angle(shown), 64)
  const reading = watts == null ? empty : formatWatts(watts)

  return (
    <div className="flex flex-col items-center gap-1">
      <div className="relative mx-auto aspect-square w-full max-w-80">
        <svg
          viewBox="0 0 200 200"
          className="size-full"
          role="img"
          aria-label={`${label}: ${reading}`}
          data-testid={testId}
          data-shown={shown.toFixed(1)}
          data-zone={zone ?? 'none'}
          data-max={max}
          data-fraction={dialFraction(shown, max).toFixed(3)}
        >
          <path d={arc(START, START + SWEEP, 80)} fill="none" stroke="var(--secondary)" strokeWidth="14" strokeLinecap="round" />
          {bands && (
            <g data-testid="power-bands" strokeWidth="3" fill="none">
              <path d={arc(START, angle(bands.green), 93)} stroke={ZONE_COLOR.low} />
              <path d={arc(angle(bands.green), angle(bands.red), 93)} stroke={ZONE_COLOR.mid} />
              <path d={arc(angle(bands.red), START + SWEEP, 93)} stroke={ZONE_COLOR.high} />
            </g>
          )}
          {watts != null && shown > 0 && (
            <path d={arc(START, Math.max(START + 0.01, angle(shown)), 80)} fill="none" stroke={color} strokeWidth="14" strokeLinecap="round" className="transition-[stroke] duration-700" />
          )}
          {dialTicks(max).map((t) => {
            const a = point(angle(t), 66)
            return (
              <text key={t} data-testid="dial-tick" x={a.x} y={a.y} textAnchor="middle" dominantBaseline="middle" className="fill-muted-foreground text-[7px] tabular-nums">
                {t / 1000}
              </text>
            )
          })}
          <line x1="100" y1="100" x2={tip.x} y2={tip.y} stroke="var(--foreground)" strokeWidth="2.5" strokeLinecap="round" opacity={watts == null ? 0.3 : 1} />
          <circle cx="100" cy="100" r="5" fill="var(--card)" stroke="var(--foreground)" strokeWidth="2.5" />
        </svg>
        <div className="pointer-events-none absolute inset-x-0 bottom-[14%] flex flex-col items-center">
          <div data-testid={nowTestId} className={cn('text-3xl font-bold tracking-tighter tabular-nums sm:text-4xl xl:text-5xl', watts == null && 'text-base text-muted-foreground sm:text-xl xl:text-2xl')}>
            {watts == null ? empty : formatWatts(shown)}
          </div>
        </div>
      </div>
      <div data-testid={labelTestId} className="max-w-full truncate text-sm font-medium text-muted-foreground transition-opacity">
        {label}
      </div>
    </div>
  )
}

/** Today's peak on the house dial's scale, like a car's small gauges; hovering or tapping it shows what was using the power. */
function PeakGauge({ peak, max }: { peak: TodayPeak | null; max: number }) {
  const [open, setOpen] = useState(false)
  const angle = (w: number) => START + SWEEP * dialFraction(w, max)
  const tip = point(angle(peak?.watts ?? 0), 50)
  const gauge = (
    <button
      type="button"
      data-testid="peak-gauge"
      data-watts={peak?.watts ?? ''}
      className="flex w-full flex-col items-center gap-1 rounded-xl p-1 outline-none focus-visible:ring-3 focus-visible:ring-ring/50"
      onMouseEnter={() => peak && setOpen(true)}
      onMouseLeave={() => setOpen(false)}
      onClick={() => peak && setOpen((o) => !o)}
      aria-label={peak ? `Today's peak ${formatWatts(peak.watts)} at ${clockTime(peak.at)}` : "Today's peak: none yet"}
    >
      <svg viewBox="0 0 200 170" className="w-full max-w-48">
        <path d={arc(START, START + SWEEP, 64)} fill="none" stroke="var(--secondary)" strokeWidth="10" strokeLinecap="round" />
        {peak && <path d={arc(START, Math.max(START + 0.01, angle(peak.watts)), 64)} fill="none" stroke="var(--power-high)" strokeWidth="10" strokeLinecap="round" opacity="0.85" />}
        <line x1="100" y1="100" x2={tip.x} y2={tip.y} stroke="var(--foreground)" strokeWidth="2.5" strokeLinecap="round" opacity={peak ? 1 : 0.3} />
        <circle cx="100" cy="100" r="4" fill="var(--card)" stroke="var(--foreground)" strokeWidth="2.5" />
        <text x="100" y="150" textAnchor="middle" className="fill-foreground text-[22px] font-bold tabular-nums" data-testid="peak-now">
          {peak ? formatWatts(peak.watts) : '–'}
        </text>
      </svg>
      <div className="text-sm font-medium text-muted-foreground">Today's peak</div>
      <div data-testid="peak-at" className="text-xs text-muted-foreground tabular-nums">
        {peak ? `at ${clockTime(peak.at)}` : 'None yet today'}
      </div>
    </button>
  )
  if (!peak) return gauge
  return (
    <Popover open={open} onOpenChange={setOpen}>
      <PopoverTrigger asChild>{gauge}</PopoverTrigger>
      <PopoverContent data-testid="peak-parts" className="w-72" onMouseEnter={() => setOpen(true)} onMouseLeave={() => setOpen(false)}>
        <div className="mb-2 text-sm font-semibold">
          {formatWatts(peak.watts)} at {clockTime(peak.at)}
        </div>
        <ul className="flex flex-col gap-1.5">
          {peak.parts.map((p) => {
            const share = peak.watts > 0 ? p.watts / peak.watts : 0
            return (
              <li key={`${p.kind}:${p.id ?? p.name}`} data-testid="peak-part" className="flex flex-col gap-0.5 text-sm">
                <div className="flex justify-between gap-3">
                  <span className={cn('truncate', p.kind === 'else' && 'text-muted-foreground')}>{p.name}</span>
                  <span className="shrink-0 tabular-nums">
                    {formatWatts(p.watts)} <span className="text-muted-foreground">· {Math.round(share * 100)}%</span>
                  </span>
                </div>
                <div className="h-1 overflow-hidden rounded-full bg-secondary">
                  <div className={cn('h-full rounded-full', p.kind === 'else' ? 'bg-muted-foreground/50' : 'bg-[var(--power-neutral)]')} style={{ width: `${share * 100}%` }} />
                </div>
              </li>
            )
          })}
        </ul>
      </PopoverContent>
    </Popover>
  )
}

/** The daily target as a fuel gauge: full (F) at midnight, draining to E as the house uses it, with a tick where it should be by now. */
function FuelGauge({ today, admin }: { today: EnergyDashboard | null; admin: boolean }) {
  const target = today?.target ?? null
  const f = target ? fuel(target) : null
  const shown = useSwept(f?.left ?? 0)
  const angle = (left: number) => 180 + 180 * left
  const tip = point(angle(shown), 54, 100, 96)
  const currency = today?.currency ?? 'GBP'

  return (
    <div data-testid="fuel-gauge" data-left={f ? f.left.toFixed(3) : ''} data-pace={f ? f.pace.toFixed(3) : ''} data-low={f?.low ? 'true' : 'false'} className="flex flex-col items-center gap-1">
      <svg viewBox="0 0 200 130" className="w-full max-w-48" aria-hidden>
        <path d={arc(180, 360, 66, 100, 96)} fill="none" stroke="var(--secondary)" strokeWidth="10" strokeLinecap="round" />
        {/* The fuel left: from E to the needle, green when plenty, amber under half, red when low. */}
        {f && shown > 0.005 && (
          <path
            data-testid="fuel-level"
            d={arc(180, angle(shown), 66, 100, 96)}
            fill="none"
            stroke={f.low ? 'var(--power-high)' : f.left < 0.5 ? 'var(--power-mid)' : 'var(--power-low)'}
            strokeWidth="10"
            strokeLinecap="round"
            className="transition-[stroke] duration-700"
          />
        )}
        {/* Where the needle should be by now: a small notch outside the arc. */}
        {f && (() => {
          const a = point(angle(f.pace), 74, 100, 96)
          const b = point(angle(f.pace), 84, 100, 96)
          return <line data-testid="fuel-pace" x1={a.x} y1={a.y} x2={b.x} y2={b.y} stroke="var(--muted-foreground)" strokeWidth="2.5" strokeLinecap="round" />
        })()}
        <text x="22" y="122" textAnchor="middle" className="fill-muted-foreground text-[14px] font-semibold">E</text>
        <text x="178" y="122" textAnchor="middle" className="fill-muted-foreground text-[14px] font-semibold">F</text>
        <line x1="100" y1="96" x2={tip.x} y2={tip.y} stroke="var(--foreground)" strokeWidth="2.5" strokeLinecap="round" opacity={f ? 1 : 0.3} />
        <circle cx="100" cy="96" r="4" fill="var(--card)" stroke="var(--foreground)" strokeWidth="2.5" />
      </svg>
      <div className="flex items-center gap-1.5 text-sm font-medium text-muted-foreground">
        <FuelIcon data-testid="fuel-low" data-on={f?.low ? 'true' : 'false'} className={cn('size-4 transition-colors', f?.low ? 'text-[var(--power-mid)]' : 'text-muted-foreground/40')} />
        Daily target
      </div>
      {target && f ? (
        <div className="flex flex-col items-center text-center text-xs tabular-nums">
          <span data-testid="fuel-left" className="text-sm font-semibold">
            {f.overKwh > 0 ? `${f.overKwh.toFixed(1)} kWh over` : `${f.leftKwh.toFixed(1)} kWh left`}
          </span>
          <span data-testid="fuel-used" className="text-muted-foreground">
            {target.usedKwh.toFixed(1)} of {formatKwh(target.kwh)} · <span className="font-semibold text-foreground">{formatCost(today?.electricityCost, currency)}</span>
          </span>
          <span data-testid="fuel-pace-line" className={f.paceKwh <= 0 ? 'text-[var(--power-low)]' : 'text-[var(--power-mid)]'}>
            {paceLine(f.paceKwh)}
          </span>
        </div>
      ) : (
        <div className="flex flex-col items-center text-center text-xs">
          <span data-testid="fuel-left" className="text-muted-foreground">{NO_TARGET}</span>
          {today && (
            <span data-testid="fuel-used" className="text-muted-foreground tabular-nums">
              Today {formatKwh(today.electricityKwh)} · <span className="font-semibold text-foreground">{formatCost(today.electricityCost, currency)}</span>
            </span>
          )}
          {admin && today?.electricityMeter != null && (
            <Link to="/energy/manage" className="font-medium text-primary underline-offset-4 hover:underline">
              {SET_TARGET}
            </Link>
          )}
        </div>
      )}
    </div>
  )
}
