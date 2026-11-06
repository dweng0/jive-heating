import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent } from 'react'
import { Bar, CartesianGrid, Cell, ComposedChart, Area, Line, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { Card, CardContent } from '@/components/ui/card'
import { Button } from '@/components/ui/button'
import { formatCost, formatKwh } from '@/lib/energy'
import { clampEnd, historyApi, pointLabel, RANGES, rangeOf, type HistoryRange, type HouseHistoryPoint } from '@/lib/energyDials'
import { formatWatts } from '@/lib/livePower'
import { cn } from '@/lib/utils'

/** While the chart shows the present it moves on with each five-minute sample. */
const FOLLOW_EVERY_MS = 5 * 60_000

interface Row {
  /** Where it is drawn: the start of a line point, the middle of a bar */
  x: number
  p: HouseHistoryPoint
  watts: number | null
  kwh: number | null
  target: number | null
}

/** What has been loaded for one range: points by their start, and the stretches of time asked for. */
interface Loaded {
  points: Map<number, HouseHistoryPoint>
  covered: [number, number][]
}

const empty = (): Loaded => ({ points: new Map(), covered: [] })

/** The latest time in [from, to] no loaded stretch covers, working back from [to]; null when it is all covered. */
function uncovered(covered: [number, number][], from: number, to: number): number | null {
  let end = to
  for (;;) {
    const holding = covered.find(([a, b]) => a < end && b >= end)
    if (!holding) return end
    end = holding[0]
    if (end <= from) return null
  }
}

function tickEvery(range: HistoryRange): (t: number) => number {
  return (t) => {
    const d = new Date(t)
    if (range === 'day') {
      d.setMinutes(0, 0, 0)
      d.setHours(Math.ceil(d.getHours() / 3) * 3)
    } else if (range === 'week') {
      d.setHours(0, 0, 0, 0)
      if (d.getTime() < t) d.setDate(d.getDate() + 1)
    } else if (range === 'month') {
      d.setHours(0, 0, 0, 0)
      d.setDate(d.getDate() + ((7 - d.getDay() + 1) % 7))
      if (d.getTime() < t) d.setDate(d.getDate() + 7)
    } else {
      d.setHours(0, 0, 0, 0)
      d.setDate(1)
      if (d.getTime() < t) d.setMonth(d.getMonth() + 1)
    }
    return d.getTime()
  }
}

function ticks(from: number, to: number, range: HistoryRange): number[] {
  const first = tickEvery(range)(from)
  const out: number[] = []
  for (let t = first; t <= to && out.length < 40; ) {
    out.push(t)
    const d = new Date(t)
    if (range === 'day') d.setHours(d.getHours() + 3)
    else if (range === 'week') d.setDate(d.getDate() + 1)
    else if (range === 'month') d.setDate(d.getDate() + 7)
    else d.setMonth(d.getMonth() + 1)
    t = d.getTime()
  }
  return out
}

function tickLabel(t: number, range: HistoryRange): string {
  const d = new Date(t)
  if (range === 'day') return d.toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })
  if (range === 'week') return d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric' })
  if (range === 'month') return d.toLocaleDateString('en-GB', { day: 'numeric', month: 'short' })
  return d.toLocaleDateString('en-GB', { month: 'short' })
}

/**
 * The house's use under the dials: a line of power over a day or week, bars of kWh over a month or year. Drag it to go
 * back in time; it loads more as it needs it, stops at the first sample and never goes past now.
 */
export function HouseHistoryChart() {
  const [range, setRange] = useState<HistoryRange>('day')
  const [end, setEnd] = useState(() => Date.now())
  const [follow, setFollow] = useState(true)
  const [loaded, setLoaded] = useState<Record<HistoryRange, Loaded>>({ day: empty(), week: empty(), month: empty(), year: empty() })
  const [earliest, setEarliest] = useState<number | null>(null)
  const [currency, setCurrency] = useState('GBP')
  const [failed, setFailed] = useState(false)
  const [width, setWidth] = useState(600)
  const asking = useRef(new Set<string>())
  const box = useRef<HTMLDivElement>(null)
  const drag = useRef<{ x: number; end: number; moved: boolean } | null>(null)

  const { span, step } = rangeOf(range)
  const bars = range === 'month' || range === 'year'
  const from = end - span

  const load = useCallback((r: HistoryRange, to: number | undefined) => {
    const key = `${r}:${to ?? 'now'}`
    if (asking.current.has(key)) return
    asking.current.add(key)
    historyApi.get(r, to).then(
      (h) => {
        asking.current.delete(key)
        setFailed(false)
        setCurrency(h.currency)
        setEarliest(h.earliest == null ? null : Date.parse(h.earliest))
        setLoaded((all) => {
          const was = all[r]
          const points = new Map(was.points)
          h.points.forEach((p) => points.set(Date.parse(p.at), p))
          // An empty stretch before the first sample still counts as asked for.
          const covered: [number, number][] = [...was.covered, [Date.parse(h.from), Date.parse(h.to)]]
          return { ...all, [r]: { points, covered } }
        })
      },
      () => {
        asking.current.delete(key)
        setFailed(true)
      },
    )
  }, [])

  // Whatever the window shows and has not been loaded yet.
  useEffect(() => {
    const now = Date.now()
    const lacking = uncovered(loaded[range].covered, Math.max(from, earliest ?? from) - step, end)
    if (lacking == null || (earliest != null && lacking <= earliest)) return
    load(range, lacking >= now - 1000 ? undefined : lacking)
  }, [range, end, from, step, earliest, loaded, load])

  // Following the present: the newest points again every five minutes.
  useEffect(() => {
    if (!follow) return
    const timer = window.setInterval(() => {
      setEnd(Date.now())
      load(range, undefined)
    }, FOLLOW_EVERY_MS)
    return () => window.clearInterval(timer)
  }, [follow, range, load])

  useEffect(() => {
    const el = box.current
    if (!el) return
    const watch = new ResizeObserver(([e]) => setWidth(e.contentRect.width))
    watch.observe(el)
    return () => watch.disconnect()
  }, [])

  const rows: Row[] = useMemo(() => {
    const out: Row[] = []
    loaded[range].points.forEach((p, at) => {
      if (at < from - step * 2 || at > end + step) return
      out.push({
        x: bars ? at + step / 2 : at,
        p,
        watts: p.watts,
        kwh: p.kwh,
        target: p.targetKwh,
      })
    })
    return out.sort((a, b) => a.x - b.x)
  }, [loaded, range, from, end, step, bars])

  function moveTo(next: number) {
    const now = Date.now()
    const clamped = clampEnd(next, span, now, earliest)
    setEnd(clamped)
    setFollow(clamped >= now - step)
  }

  function choose(r: HistoryRange) {
    setRange(r)
    // Keep the time at the right edge.
    const now = Date.now()
    setEnd(follow ? now : clampEnd(end, rangeOf(r).span, now, earliest))
  }

  function onPointerDown(e: PointerEvent<HTMLDivElement>) {
    drag.current = { x: e.clientX, end, moved: false }
  }
  function onPointerMove(e: PointerEvent<HTMLDivElement>) {
    const d = drag.current
    if (!d) return
    const dx = e.clientX - d.x
    if (!d.moved && Math.abs(dx) < 4) return
    if (!d.moved) e.currentTarget.setPointerCapture(e.pointerId)
    d.moved = true
    // Dragging right shows earlier times.
    moveTo(d.end - (dx / Math.max(1, width)) * span)
  }
  function onPointerUp() {
    drag.current = null
  }

  const atNow = follow
  const barSize = Math.max(2, ((width - 60) / (span / step)) * 0.7)
  const max = Math.max(0, ...rows.map((r) => (bars ? Math.max(r.kwh ?? 0, r.target ?? 0) : (r.watts ?? 0))))

  return (
    <Card>
      <CardContent className="flex flex-col gap-4 p-4 sm:p-6">
        <div className="flex flex-wrap items-end justify-between gap-3">
          <div>
            <h2 className="text-2xl font-bold tracking-tight">History</h2>
            <p className="text-sm text-muted-foreground">The whole house. Drag the chart to go back in time.</p>
          </div>
          <div className="flex items-center gap-2">
            {!atNow && (
              <Button data-testid="history-now" variant="outline" size="sm" onClick={() => moveTo(Date.now())}>
                Now
              </Button>
            )}
            <div role="group" aria-label="Chart range" className="flex rounded-xl bg-secondary p-1">
              {RANGES.map((r) => (
                <button
                  key={r.value}
                  type="button"
                  aria-pressed={range === r.value}
                  onClick={() => choose(r.value)}
                  className={cn(
                    'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors',
                    range === r.value ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
                  )}
                >
                  {r.label}
                </button>
              ))}
            </div>
          </div>
        </div>
        <div
          ref={box}
          data-testid="history-chart"
          data-range={range}
          data-end={String(end)}
          data-from={String(from)}
          data-points={rows.filter((r) => r.x >= from && r.x <= end).length}
          className="h-64 w-full cursor-grab touch-pan-y select-none active:cursor-grabbing sm:h-80"
          onPointerDown={onPointerDown}
          onPointerMove={onPointerMove}
          onPointerUp={onPointerUp}
          onPointerCancel={onPointerUp}
        >
          {failed && rows.length === 0 ? (
            <div className="flex h-full items-center justify-center text-sm text-muted-foreground">Couldn't load the history</div>
          ) : (
            <ResponsiveContainer>
              <ComposedChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: -8 }}>
                <defs>
                  <linearGradient id="history-fill" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="0%" stopColor="var(--power-neutral)" stopOpacity="0.35" />
                    <stop offset="100%" stopColor="var(--power-neutral)" stopOpacity="0" />
                  </linearGradient>
                </defs>
                <CartesianGrid vertical={false} stroke="var(--border)" />
                <XAxis
                  dataKey="x"
                  type="number"
                  scale="time"
                  domain={[from, end]}
                  allowDataOverflow
                  ticks={ticks(from, end, range)}
                  tickFormatter={(t: number) => tickLabel(t, range)}
                  tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }}
                  tickLine={false}
                  axisLine={{ stroke: 'var(--border)' }}
                />
                <YAxis
                  domain={[0, max > 0 ? 'auto' : 1]}
                  tickFormatter={(v: number) => (bars ? `${v}` : v >= 1000 ? `${(v / 1000).toFixed(1)}k` : `${v}`)}
                  tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }}
                  tickLine={false}
                  axisLine={false}
                  width={44}
                  label={{ value: bars ? 'kWh' : 'W', position: 'insideTopLeft', offset: 0, fill: 'var(--muted-foreground)', fontSize: 11, dy: -4, dx: 8 }}
                />
                {bars ? (
                  <Bar dataKey="kwh" name="Used" fill="var(--power-neutral)" barSize={barSize} radius={[3, 3, 0, 0]} isAnimationActive={false} className="history-bar">
                    {/* Readings missing: a paler bar. */}
                    {rows.map((r) => (
                      <Cell key={r.x} fillOpacity={r.p.incomplete ? 0.4 : 1} className={r.p.incomplete ? 'history-incomplete' : undefined} />
                    ))}
                  </Bar>
                ) : (
                  <Area
                    dataKey="watts"
                    name="Power"
                    type="monotone"
                    stroke="var(--power-neutral)"
                    strokeWidth={2}
                    fill="url(#history-fill)"
                    connectNulls={false}
                    dot={false}
                    activeDot={{ r: 4, strokeWidth: 2, stroke: 'var(--card)' }}
                    isAnimationActive={false}
                    className="history-line"
                  />
                )}
                {bars && (
                  <Line
                    dataKey="target"
                    name="Target"
                    type="stepAfter"
                    stroke="var(--muted-foreground)"
                    strokeWidth={1.5}
                    strokeDasharray="5 4"
                    dot={false}
                    activeDot={false}
                    isAnimationActive={false}
                    className="history-target"
                  />
                )}
                <Tooltip
                  cursor={bars ? { fill: 'var(--accent)', opacity: 0.4 } : { stroke: 'var(--muted-foreground)', strokeDasharray: '3 3' }}
                  content={({ active, payload }) => {
                    const row = payload?.[0]?.payload as Row | undefined
                    if (!active || !row) return null
                    const p = row.p
                    return (
                      <div data-testid="history-readout" className="min-w-40 rounded-lg border bg-popover px-3 py-2 text-xs text-popover-foreground shadow-md">
                        <div className="mb-1 font-semibold">{pointLabel(p.at, range)}</div>
                        {!bars && (
                          <div className="flex justify-between gap-4">
                            <span>Power</span>
                            <span className="tabular-nums">{formatWatts(p.watts)}</span>
                          </div>
                        )}
                        <div className="flex justify-between gap-4">
                          <span>Used</span>
                          <span className="tabular-nums">{p.kwh == null ? '–' : bars ? formatKwh(p.kwh) : `${p.kwh.toFixed(2)} kWh`}</span>
                        </div>
                        <div className="flex justify-between gap-4">
                          <span>Cost</span>
                          <span className="font-semibold tabular-nums">{formatCost(p.cost, currency)}</span>
                        </div>
                        {p.targetKwh != null && (
                          <div className="flex justify-between gap-4">
                            <span>Target</span>
                            <span className="tabular-nums">{formatKwh(p.targetKwh)}</span>
                          </div>
                        )}
                        {p.incomplete && <div className="mt-1 text-muted-foreground">Some readings are missing</div>}
                      </div>
                    )
                  }}
                />
              </ComposedChart>
            </ResponsiveContainer>
          )}
        </div>
      </CardContent>
    </Card>
  )
}
