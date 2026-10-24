import { useEffect, useRef, useState } from 'react'
import { Card, CardContent } from '@/components/ui/card'
import { energyApi, formatCost, formatKwh, type EnergyDashboard } from '@/lib/energy'
import {
  SWEEP_MS,
  dialMax,
  followPower,
  formatWatts,
  powerApi,
  powerZone,
  sweep,
  type LivePower,
  type PowerBands,
  type PowerPoint,
  type PowerZone,
} from '@/lib/livePower'
import { cn } from '@/lib/utils'

const START = 150 // degrees, bottom-left
const SWEEP = 240
const SPARKLINE_MS = 30 * 60_000
/** Today's kWh and cost are recorded every five minutes; there is no point asking more often. */
const TODAY_EVERY_MS = 5 * 60_000

const ZONE_COLOR: Record<PowerZone, string> = {
  low: 'var(--power-low)',
  mid: 'var(--power-mid)',
  high: 'var(--power-high)',
  neutral: 'var(--power-neutral)',
}

function point(angle: number, r: number) {
  const rad = (angle * Math.PI) / 180
  return { x: 100 + r * Math.cos(rad), y: 100 + r * Math.sin(rad) }
}

function arc(from: number, to: number, r: number) {
  const a = point(from, r)
  const b = point(to, r)
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

/** The house's electricity now, on a dial coloured by the household's own use, with its rooms as cards. Admin only. */
export function LivePowerSection() {
  const [live, setLive] = useState<LivePower | null>(null)
  const [bands, setBands] = useState<PowerBands | null>(null)
  const [recent, setRecent] = useState<PowerPoint[]>([])
  const [today, setToday] = useState<EnergyDashboard | null>(null)

  useEffect(() => {
    let cancelled = false
    powerApi.summary().then(
      (s) => {
        if (cancelled) return
        setBands(s.bands)
        // Readings that came in while this was loading stay.
        setRecent((r) => [...s.recent, ...r.filter((p) => p.at > (s.recent.at(-1)?.at ?? ''))])
      },
      () => {},
    )
    const loadToday = () => energyApi.dashboard('today').then((d) => !cancelled && setToday(d), () => {})
    loadToday()
    const timer = window.setInterval(loadToday, TODAY_EVERY_MS)
    const stop = followPower(
      (p) => {
        setLive(p)
        if (p.at == null) return
        setRecent((r) => {
          const from = new Date(Date.now() - SPARKLINE_MS).toISOString()
          const kept = r.filter((q) => q.at >= from)
          return kept.at(-1)?.at === p.at ? kept : [...kept, { at: p.at!, watts: p.watts }]
        })
      },
      () => {},
    )
    return () => {
      cancelled = true
      window.clearInterval(timer)
      stop()
    }
  }, [])

  // No house meter chosen: nothing to show live.
  if (today && today.electricityMeter == null) return null

  const watts = live?.watts ?? null
  const rooms = live?.rooms ?? []
  const devices = live?.devices ?? []
  const currency = today?.currency ?? 'GBP'

  return (
    <section data-testid="live-power" className="flex flex-col gap-4">
      <Card>
        <CardContent className="flex flex-col items-center gap-4 p-4 sm:p-6">
          <div className="self-start">
            <h2 className="text-2xl font-bold tracking-tight">Electricity now</h2>
            <p className="text-sm text-muted-foreground">The whole house, live from the meter</p>
          </div>
          <PowerDial watts={watts} bands={bands} connected={live != null} />
          <div className="flex w-full max-w-md flex-col gap-2">
            <div data-testid="live-today" className="flex items-baseline justify-between gap-3 text-sm">
              <span className="text-muted-foreground">Today</span>
              <span className="tabular-nums">
                <span className="text-muted-foreground">{today?.electricityKwh == null ? '–' : formatKwh(today.electricityKwh)}</span>
                <span className="ml-3 font-semibold">{formatCost(today?.electricityCost, currency)}</span>
              </span>
            </div>
            <Sparkline points={recent} bands={bands} />
          </div>
        </CardContent>
      </Card>

      {(rooms.length > 0 || devices.length > 0) && (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
          {rooms.map((r) => {
            const day = today?.rooms?.find((d) => d.room === r.room)
            return <UseCard key={r.room} testId={`live-room-${r.room}`} name={r.name} watts={r.watts} house={watts} kwh={day?.kwh} cost={day?.cost} currency={currency} />
          })}
          {devices.map((d) => {
            const day = today?.devices?.find((e) => e.meter === d.meter)
            return <UseCard key={d.meter} testId={`live-device-${d.meter}`} name={d.meter} detail="Not in a room" watts={d.watts} house={watts} kwh={day?.kwh} cost={day?.cost} currency={currency} />
          })}
          {/* The rest of the house: only while the house reading is live, as it is the house less the rest. */}
          {live?.elseWatts != null && (
            <UseCard
              testId="live-rest"
              name="Everything else"
              detail="What no plug accounts for"
              watts={live.elseWatts}
              house={watts}
              kwh={today?.everythingElse?.kwh}
              cost={today?.everythingElse?.cost}
              currency={currency}
              muted
            />
          )}
        </div>
      )}
    </section>
  )
}

/** The gauge: a coloured arc up to the needle, the household's bands as a thin outer ring, big watts in the middle. */
function PowerDial({ watts, bands, connected }: { watts: number | null; bands: PowerBands | null; connected: boolean }) {
  // The scale only grows while the page is open, so a big reading does not shrink everything after it.
  const [max, setMax] = useState(() => dialMax(bands, watts))
  const wanted = dialMax(bands, watts)
  if (wanted > max) setMax(wanted)

  const shown = useSwept(watts ?? 0)
  const angle = (w: number) => START + (SWEEP * Math.min(max, Math.max(0, w))) / max
  const zone = watts == null ? null : powerZone(shown, bands)
  const color = zone ? ZONE_COLOR[zone] : 'var(--muted-foreground)'
  const tip = point(angle(shown), 64)
  // A label per kW, or per 2 kW on a big scale.
  const tickStep = max > 6000 ? 2000 : 1000
  const ticks = Array.from({ length: max / tickStep + 1 }, (_, i) => i * tickStep)
  const label = watts == null ? (connected ? 'No reading' : 'Connecting…') : formatWatts(watts)

  return (
    <div className="relative mx-auto aspect-square w-full max-w-80">
      <svg
        viewBox="0 0 200 200"
        className="size-full"
        role="img"
        aria-label={`Electricity now: ${label}`}
        data-testid="power-dial"
        data-shown={shown.toFixed(1)}
        data-zone={zone ?? 'none'}
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
        {ticks.map((t) => {
          const a = point(angle(t), 66)
          return (
            <text key={t} x={a.x} y={a.y} textAnchor="middle" dominantBaseline="middle" className="fill-muted-foreground text-[7px] tabular-nums">
              {t / 1000}
            </text>
          )
        })}
        <line x1="100" y1="100" x2={tip.x} y2={tip.y} stroke="var(--foreground)" strokeWidth="2.5" strokeLinecap="round" opacity={watts == null ? 0.3 : 1} />
        <circle cx="100" cy="100" r="5" fill="var(--card)" stroke="var(--foreground)" strokeWidth="2.5" />
      </svg>
      <div className="pointer-events-none absolute inset-x-0 bottom-[14%] flex flex-col items-center">
        <div data-testid="power-now" className={cn('text-4xl font-bold tracking-tighter tabular-nums sm:text-5xl', watts == null && 'text-2xl text-muted-foreground sm:text-3xl')}>
          {watts == null ? label : formatWatts(shown)}
        </div>
      </div>
    </div>
  )
}

/** The last 30 minutes of house watts; gaps where there was no reading. */
function Sparkline({ points, bands }: { points: PowerPoint[]; bands: PowerBands | null }) {
  const known = points.filter((p) => p.watts != null)
  if (known.length < 2) return <div data-testid="power-sparkline" data-points={known.length} className="h-12" />
  // The right edge is the newest reading.
  const now = Date.parse(points[points.length - 1].at)
  const top = Math.max(...known.map((p) => p.watts!), bands?.red ?? 0, 1)
  const x = (at: string) => 100 - ((now - Date.parse(at)) / SPARKLINE_MS) * 100
  const y = (w: number) => 30 - (w / top) * 28
  // A null reading breaks the line.
  const runs: PowerPoint[][] = []
  points.forEach((p) => {
    if (p.watts == null) runs.push([])
    else if (runs.length === 0) runs.push([p])
    else runs[runs.length - 1].push(p)
  })
  const line = (run: PowerPoint[]) => run.map((p) => `${x(p.at).toFixed(2)},${y(p.watts!).toFixed(2)}`).join(' ')
  return (
    <div className="flex flex-col gap-1">
      <svg data-testid="power-sparkline" data-points={known.length} viewBox="0 0 100 32" preserveAspectRatio="none" className="h-14 w-full" aria-label="Last 30 minutes">
        <defs>
          <linearGradient id="power-fill" x1="0" y1="0" x2="0" y2="1">
            <stop offset="0%" stopColor="var(--power-neutral)" stopOpacity="0.3" />
            <stop offset="100%" stopColor="var(--power-neutral)" stopOpacity="0" />
          </linearGradient>
        </defs>
        {runs.filter((r) => r.length > 1).map((run, i) => (
          <g key={i}>
            <polygon points={`${x(run[0].at).toFixed(2)},32 ${line(run)} ${x(run[run.length - 1].at).toFixed(2)},32`} fill="url(#power-fill)" />
            <polyline points={line(run)} fill="none" stroke="var(--power-neutral)" strokeWidth="1.5" strokeLinejoin="round" vectorEffect="non-scaling-stroke" />
          </g>
        ))}
      </svg>
      <div className="flex justify-between text-xs text-muted-foreground">
        <span>30 min ago</span>
        <span>Now</span>
      </div>
    </div>
  )
}

function UseCard({
  testId,
  name,
  detail,
  watts,
  house,
  kwh,
  cost,
  currency,
  muted,
}: {
  testId: string
  name: string
  detail?: string
  watts: number | null
  house: number | null
  kwh: number | undefined
  cost: number | undefined
  currency: string
  muted?: boolean
}) {
  // Share of the house's load now: only while both are live.
  const share = watts != null && house != null && house > 0 ? Math.min(1, watts / house) : null
  return (
    <Card data-testid={testId}>
      <CardContent className="flex flex-col gap-2 p-4">
        <div className="flex items-start justify-between gap-3">
          <div className="min-w-0">
            <div className="truncate font-medium">{name}</div>
            {detail && <div className="truncate text-xs text-muted-foreground">{detail}</div>}
          </div>
          <div data-testid="live-watts" className={cn('shrink-0 text-xl font-bold tabular-nums', watts == null && 'text-muted-foreground')}>
            {formatWatts(watts)}
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
}
