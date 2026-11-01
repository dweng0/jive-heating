import { useCallback, useEffect, useMemo, useRef, useState, type PointerEvent as ReactPointerEvent } from 'react'
import { ChevronLeft, ChevronRight } from 'lucide-react'
import { CartesianGrid, Line, LineChart, ReferenceDot, ReferenceLine, ResponsiveContainer, XAxis, YAxis } from 'recharts'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import {
  METRICS,
  WINDOWS,
  clampEnd,
  metricValue,
  pointCost,
  pointKwh,
  rowAt,
  usageRows,
  withPage,
  type LoadedUsage,
  type UsageMetric,
  type UsagePage,
  type UsageWindow,
} from '@/lib/energyUsage'
import { formatPower } from '@/lib/energy'
import { cn } from '@/lib/utils'

/** Recorded every five minutes; there is no point asking more often. */
const REFRESH_MS = 5 * 60_000
const HOLD_MS = 400
/** A press that moves this far is a drag, not a hold. */
const DRAG_PX = 8
const SWIPE_PX = 40
const AXIS_WIDTH = 64
const RIGHT_MARGIN = 12

export interface UsageSlide {
  key: string
  name: string
}

/** The usage read so far and the time being looked at, shared by every chart. A null end follows the latest readings. */
function useUsage(load: (window: UsageWindow, to?: number) => Promise<UsagePage>) {
  const [window_, setWindow] = useState<UsageWindow>('24h')
  const [end, setEnd] = useState<number | null>(null)
  const [loaded, setLoaded] = useState<LoadedUsage | null>(null)
  const [failed, setFailed] = useState(false)
  const reading = useRef(false)
  /** Bumped with each range, so a page read for the last one is not added to this one's. */
  const generation = useRef(0)
  const { length, step } = WINDOWS.find((w) => w.value === window_)!

  useEffect(() => {
    let cancelled = false
    generation.current += 1
    reading.current = false
    setLoaded(null)
    setFailed(false)
    const latest = () =>
      load(window_).then(
        (page) => !cancelled && setLoaded((l) => withPage(l, page, step)),
        () => !cancelled && setFailed(true),
      )
    latest()
    const timer = window.setInterval(latest, REFRESH_MS)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [load, window_, step])

  const now = Date.now()
  const shownEnd = end ?? now
  const earliest = loaded?.earliest ?? null

  // Reads a window ahead of what is shown, so scrolling back does not run into nothing.
  const wanted = shownEnd - 2 * length
  const loadedFrom = loaded?.loadedFrom
  useEffect(() => {
    if (loadedFrom == null || earliest == null || reading.current) return
    if (wanted >= loadedFrom || earliest >= loadedFrom) return
    reading.current = true
    const asked = generation.current
    load(window_, loadedFrom)
      .then((page) => asked === generation.current && setLoaded((l) => (l ? withPage(l, page, step) : l)), () => {})
      .finally(() => {
        if (asked === generation.current) reading.current = false
      })
  }, [load, window_, step, wanted, loadedFrom, earliest])

  const moveTo = useCallback(
    (t: number) => {
      const clamped = clampEnd(t, Date.now(), earliest, length)
      setEnd(clamped >= Date.now() - 1000 ? null : clamped)
    },
    [earliest, length],
  )

  const chooseWindow = (next: UsageWindow) => {
    // The end stays where it is, so the time being looked at stays in view.
    const nextLength = WINDOWS.find((w) => w.value === next)!.length
    if (end != null) setEnd(clampEnd(end, Date.now(), earliest, nextLength))
    setWindow(next)
  }

  return { window: window_, chooseWindow, end, shownEnd, moveTo, backToNow: () => setEnd(null), loaded, failed, length, step }
}

function Choice<T extends string>({ label, options, value, onChange }: { label: string; options: { value: T; label: string }[]; value: T; onChange: (v: T) => void }) {
  return (
    <div role="group" aria-label={label} className="flex rounded-xl bg-secondary p-1">
      {options.map((o) => (
        <button
          key={o.value}
          type="button"
          aria-pressed={value === o.value}
          onClick={() => onChange(o.value)}
          className={cn('rounded-lg px-3 py-1.5 text-sm font-medium transition-colors', value === o.value ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground')}
        >
          {o.label}
        </button>
      ))}
    </div>
  )
}

/**
 * Line charts of [slides] over time, one at a time, with arrows, dots and a swipe on the heading to move between them.
 * The time and range are shared, so every chart shows the same time. [slide] says which is showing.
 */
export function UsageCarousel({
  load,
  slides,
  slide,
  onSlide,
}: {
  load: (window: UsageWindow, to?: number) => Promise<UsagePage>
  slides: UsageSlide[]
  slide: number
  onSlide: (slide: number) => void
}) {
  const usage = useUsage(load)
  const [metric, setMetric] = useState<UsageMetric>('watts')
  const swipe = useRef<number | null>(null)
  const current = slides[Math.min(slide, slides.length - 1)]
  const many = slides.length > 1
  const index = slides.indexOf(current)

  const onSwipeEnd = (e: ReactPointerEvent) => {
    if (swipe.current == null) return
    const dx = e.clientX - swipe.current
    swipe.current = null
    if (dx <= -SWIPE_PX && index < slides.length - 1) onSlide(index + 1)
    if (dx >= SWIPE_PX && index > 0) onSlide(index - 1)
  }

  return (
    <Card data-testid="usage-carousel">
      <CardContent className="flex flex-col gap-3 p-4 sm:p-6">
        <div className="flex flex-wrap items-center justify-between gap-2">
          <Choice label="What to chart" options={METRICS} value={metric} onChange={setMetric} />
          <div className="flex items-center gap-2">
            {usage.end != null && (
              <Button data-testid="chart-now" type="button" variant="secondary" size="sm" onClick={usage.backToNow}>
                Now
              </Button>
            )}
            <Choice label="Time range" options={WINDOWS} value={usage.window} onChange={usage.chooseWindow} />
          </div>
        </div>

        <div
          data-testid="chart-heading"
          className="flex touch-pan-y items-center justify-between gap-2 select-none"
          onPointerDown={(e) => {
            swipe.current = e.clientX
          }}
          onPointerUp={onSwipeEnd}
          onPointerCancel={() => {
            swipe.current = null
          }}
        >
          {many && (
            <Button type="button" variant="ghost" size="icon" aria-label="Previous chart" disabled={index === 0} onClick={() => onSlide(index - 1)}>
              <ChevronLeft />
            </Button>
          )}
          <div className="min-w-0 flex-1 text-center">
            <div data-testid="chart-title" className="truncate font-semibold">{current.name}</div>
            {many && (
              <div className="mt-1 flex justify-center gap-1.5" data-testid="chart-dots">
                {slides.map((s, i) => (
                  <button
                    key={s.key}
                    type="button"
                    aria-label={`Show ${s.name}`}
                    aria-current={i === index}
                    onClick={() => onSlide(i)}
                    className={cn('size-2 rounded-full', i === index ? 'bg-foreground' : 'bg-muted-foreground/30')}
                  />
                ))}
              </div>
            )}
          </div>
          {many && (
            <Button type="button" variant="ghost" size="icon" aria-label="Next chart" disabled={index === slides.length - 1} onClick={() => onSlide(index + 1)}>
              <ChevronRight />
            </Button>
          )}
        </div>

        <UsageChart usage={usage} seriesKey={current.key} name={current.name} metric={metric} />
      </CardContent>
    </Card>
  )
}

function UsageChart({ usage, seriesKey, name, metric }: { usage: ReturnType<typeof useUsage>; seriesKey: string; name: string; metric: UsageMetric }) {
  const { length, step, shownEnd, loaded, moveTo } = usage
  const from = shownEnd - length
  const currency = loaded?.currency ?? 'GBP'
  const box = useRef<HTMLDivElement>(null)
  const [cursor, setCursor] = useState<number | null>(null)
  const press = useRef<{ x: number; end: number; dragging: boolean; touch: boolean; timer?: number; holding: boolean } | null>(null)

  const points = loaded?.series[seriesKey]
  const rows = useMemo(() => usageRows(points ?? [], step, from, shownEnd), [points, step, from, shownEnd])
  const drawn = rows.filter((r) => r.t >= from && r.t <= shownEnd && r[metric] != null)

  const plotWidth = () => (box.current?.clientWidth ?? 1) - AXIS_WIDTH - RIGHT_MARGIN
  const timeAt = (clientX: number) => {
    const left = box.current?.getBoundingClientRect().left ?? 0
    const fraction = (clientX - left - AXIS_WIDTH) / plotWidth()
    return from + Math.min(1, Math.max(0, fraction)) * length
  }

  const onPointerDown = (e: ReactPointerEvent) => {
    box.current?.setPointerCapture?.(e.pointerId)
    const touch = e.pointerType !== 'mouse'
    const clientX = e.clientX
    const state = { x: clientX, end: shownEnd, dragging: false, touch, holding: false, timer: undefined as number | undefined }
    // A finger held still asks what the chart shows there; a mouse only has to hover.
    if (touch) {
      state.timer = window.setTimeout(() => {
        state.holding = true
        setCursor(timeAt(clientX))
      }, HOLD_MS)
    }
    press.current = state
  }

  const onPointerMove = (e: ReactPointerEvent) => {
    const p = press.current
    if (!p) {
      if (e.pointerType === 'mouse') setCursor(timeAt(e.clientX))
      return
    }
    if (p.holding) {
      setCursor(timeAt(e.clientX))
      return
    }
    const dx = e.clientX - p.x
    if (!p.dragging && Math.abs(dx) < DRAG_PX) return
    p.dragging = true
    window.clearTimeout(p.timer)
    setCursor(null)
    moveTo(p.end - (dx / plotWidth()) * length)
  }

  const onPointerEnd = (e: ReactPointerEvent) => {
    const p = press.current
    press.current = null
    if (p) window.clearTimeout(p.timer)
    if (p?.touch || e.type === 'pointercancel') setCursor(null)
  }

  const row = cursor == null ? null : rowAt(rows, cursor, step)
  const value = row?.[metric] ?? null

  return (
    <div
      ref={box}
      data-testid="usage-chart"
      data-from={from}
      data-to={shownEnd}
      data-window={usage.window}
      data-points={drawn.length}
      role="img"
      tabIndex={0}
      aria-label={`${name}, ${METRICS.find((m) => m.value === metric)!.label.toLowerCase()} over time. Left and right arrows move through time.`}
      className="relative h-64 cursor-grab touch-pan-y select-none rounded-lg focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none active:cursor-grabbing"
      onPointerDown={onPointerDown}
      onPointerMove={onPointerMove}
      onPointerUp={onPointerEnd}
      onPointerCancel={onPointerEnd}
      onPointerLeave={(e) => {
        if (!press.current && e.pointerType === 'mouse') setCursor(null)
      }}
      onKeyDown={(e) => {
        if (e.key === 'ArrowLeft') moveTo(shownEnd - length / 4)
        if (e.key === 'ArrowRight') moveTo(shownEnd + length / 4)
      }}
    >
      <ResponsiveContainer width="100%" height="100%">
        <LineChart data={rows} margin={{ top: 8, right: RIGHT_MARGIN, bottom: 0, left: 0 }}>
          <CartesianGrid vertical={false} stroke="var(--border)" />
          <XAxis
            dataKey="t"
            type="number"
            domain={[from, shownEnd]}
            allowDataOverflow
            tickFormatter={(t: number) => new Date(t).toLocaleString([], usage.window === '24h' ? { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' } : { day: 'numeric', month: 'short' })}
            tick={{ fontSize: 12, fill: 'var(--muted-foreground)' }}
            tickLine={false}
            minTickGap={48}
          />
          <YAxis width={AXIS_WIDTH} domain={[0, 'auto']} tickFormatter={(v: number) => metricValue(metric, v, currency)} tick={{ fontSize: 12, fill: 'var(--muted-foreground)' }} tickLine={false} axisLine={false} />
          <Line
            dataKey={metric}
            type="linear"
            stroke="var(--power-neutral)"
            strokeWidth={2}
            connectNulls={false}
            isAnimationActive={false}
            // A reading alone between gaps has no line to be part of.
            dot={(d: { index: number; cx?: number; cy?: number }) => {
              const alone = rows[d.index]?.[metric] != null && rows[d.index - 1]?.[metric] == null && rows[d.index + 1]?.[metric] == null
              return alone && d.cx != null && d.cy != null ? <circle key={d.index} cx={d.cx} cy={d.cy} r={3} fill="var(--power-neutral)" /> : <g key={d.index} />
            }}
          />
          {cursor != null && <ReferenceLine x={row?.t ?? cursor} stroke="var(--muted-foreground)" strokeDasharray="3 3" />}
          {row && value != null && <ReferenceDot x={row.t} y={value} r={4} fill="var(--power-neutral)" stroke="var(--card)" />}
        </LineChart>
      </ResponsiveContainer>

      {drawn.length === 0 && loaded && (
        <div data-testid="chart-nothing" className="pointer-events-none absolute inset-0 flex items-center justify-center text-sm text-muted-foreground">
          Nothing recorded for this time
        </div>
      )}
      {usage.failed && !loaded && <div className="pointer-events-none absolute inset-0 flex items-center justify-center text-sm text-muted-foreground">Couldn't load this</div>}

      {cursor != null && (
        <div data-testid="chart-point" className="pointer-events-none absolute top-1 right-3 rounded-lg border bg-popover px-3 py-2 text-xs tabular-nums shadow-md">
          <div className="font-medium">{new Date(row?.t ?? cursor).toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })}</div>
          {!row?.point || (row.point.watts == null && row.point.kwh == null) ? (
            <div>No reading</div>
          ) : (
            <>
              <div>
                {formatPower(row.point.watts)} · {pointKwh(row.point.kwh)} · {pointCost(row.point.cost, currency)}
              </div>
              {row.point.incomplete && <div className="text-muted-foreground">Some readings missing</div>}
            </>
          )}
        </div>
      )}
    </div>
  )
}
