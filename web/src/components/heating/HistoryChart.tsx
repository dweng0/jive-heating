import { useMemo } from 'react'
import { ChartLine } from 'lucide-react'
import { Bar, BarChart, CartesianGrid, Cell, Line, LineChart, ReferenceArea, ResponsiveContainer, Tooltip, XAxis, YAxis, type TooltipContentProps } from 'recharts'
import type { NameType, ValueType } from 'recharts/types/component/DefaultTooltipContent'
import { Card, CardContent } from '@/components/ui/card'
import type { BoilerStateName, History, HistoryRange } from '@/lib/api'
import { RANGES } from '@/lib/history'
import { formatTemp, seriesColor } from '@/lib/format'
import type { RoomEnergyHistory } from '@/lib/roomPlugs'
import { cn } from '@/lib/utils'

/** Spacing of points for each range; a longer gap than three of these breaks the line. */
const STEP_MS: Record<HistoryRange, number> = { '24h': 5 * 60_000, '7d': 30 * 60_000, '30d': 2 * 3600_000 }

const BAND_OPACITY: Partial<Record<BoilerStateName, number>> = { heating: 0.22, cycling: 0.1 }

const BOILER_WORDS: Record<BoilerStateName, string> = {
  heating: 'heating',
  cycling: 'finishing its run',
  resting: 'resting',
  idle: 'idle',
}

export function RangePicker({ range, onChange }: { range: HistoryRange; onChange: (range: HistoryRange) => void }) {
  return (
    <div role="group" aria-label="Time range" className="flex rounded-xl bg-secondary p-1">
      {RANGES.map((r) => (
        <button
          key={r.value}
          type="button"
          aria-pressed={range === r.value}
          onClick={() => onChange(r.value)}
          className={cn(
            'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors',
            range === r.value ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
          )}
        >
          {r.label}
        </button>
      ))}
    </div>
  )
}

/** Stands in for an empty chart until the first reading is recorded. */
export function NoHistory({ className }: { className?: string }) {
  return (
    <div className={cn('flex flex-col items-center gap-3 py-12 text-center', className)}>
      <ChartLine className="size-8 text-muted-foreground" />
      <p className="font-medium">No history yet</p>
      <p className="max-w-sm text-sm text-muted-foreground">
        Room temperatures are recorded every five minutes. The chart appears here within five minutes.
      </p>
    </div>
  )
}

export function NoHistoryCard() {
  return (
    <Card>
      <CardContent>
        <NoHistory />
      </CardContent>
    </Card>
  )
}

/** One row per time: the house median under "house", each room under its id. */
type Row = { t: number } & Record<string, number | null>

/**
 * Every room's temperature as a thin line and the whole-house median as a bold one, with the boiler's
 * heating (and finishing-run) periods shaded behind. Hover or drag for the values at a time.
 */
export function HistoryChart({ history, range, hidden }: { history: History; range: HistoryRange; hidden: Set<string> }) {
  const from = Date.parse(history.from)
  const to = Date.parse(history.to)
  const step = STEP_MS[range]

  const rows = useMemo(() => toRows(history, step), [history, step])
  const shown = history.rooms.map((room, i) => ({ ...room, color: seriesColor(i) })).filter((r) => !hidden.has(r.id))
  const values = rows.flatMap((row) => [row.house, ...shown.map((r) => row[r.id])]).filter((v): v is number => v != null)
  const lo = Math.floor(Math.min(...values, 18) - 0.5)
  const hi = Math.ceil(Math.max(...values, 21) + 0.5)

  const bands = boilerBands(history, from, to)

  const boilerAt = (t: number) => history.boiler.find((b) => Date.parse(b.from) <= t && (b.to == null || Date.parse(b.to) > t))?.state

  return (
    <div
      className="h-60 w-full sm:h-80"
      role="img"
      aria-label={`Room temperatures over the last ${range === '24h' ? '24 hours' : range === '7d' ? '7 days' : '30 days'}, with boiler heating shaded`}
    >
      <ResponsiveContainer>
        <LineChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
          <CartesianGrid vertical={false} stroke="var(--border)" />
          <XAxis
            dataKey="t"
            type="number"
            scale="time"
            domain={[from, to]}
            ticks={timeTicks(from, to, range)}
            tickFormatter={(t: number) => tickLabel(t, range)}
            tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }}
            tickLine={false}
            axisLine={{ stroke: 'var(--border)' }}
          />
          <YAxis
            domain={[lo, hi]}
            ticks={tempTicks(lo, hi)}
            tickFormatter={(v: number) => `${v}°`}
            tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }}
            tickLine={false}
            axisLine={false}
          />
          {bands.map((b) => (
            <ReferenceArea
              key={`${b.state}-${b.x1}`}
              x1={b.x1}
              x2={b.x2}
              className={`boiler-${b.state}`}
              fill="var(--heat)"
              fillOpacity={BAND_OPACITY[b.state]}
              stroke="none"
              ifOverflow="hidden"
            />
          ))}
          {shown.map((room) => (
            <Line
              key={room.id}
              dataKey={room.id}
              name={room.name}
              className="room-line"
              stroke={room.color}
              strokeWidth={1.5}
              dot={false}
              activeDot={{ r: 4, strokeWidth: 2, stroke: 'var(--card)' }}
              isAnimationActive={false}
            />
          ))}
          <Line
            dataKey="house"
            name="Whole house"
            className="house-line"
            stroke="var(--foreground)"
            strokeWidth={3}
            dot={false}
            activeDot={{ r: 5, strokeWidth: 2, stroke: 'var(--card)' }}
            isAnimationActive={false}
          />
          <Tooltip
            cursor={{ stroke: 'var(--muted-foreground)', strokeDasharray: '3 3' }}
            content={({ active, payload, label }) => (
              <Readout active={active} payload={payload} label={label} range={range} boilerAt={boilerAt} />
            )}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}

/** One room's temperature (solid) and target (dashed steps), with the boiler's heating shaded behind. */
export function RoomHistoryChart({ history, roomId, range }: { history: History; roomId: string; range: HistoryRange }) {
  const from = Date.parse(history.from)
  const to = Date.parse(history.to)
  const step = STEP_MS[range]
  const rows = useMemo(() => {
    const points = history.rooms.find((r) => r.id === roomId)?.points ?? []
    const sorted = points.map((p) => ({ t: Date.parse(p.at), current: p.current ?? null, target: p.target ?? null })).sort((a, b) => a.t - b.t)
    const out: { t: number; current: number | null; target: number | null }[] = []
    sorted.forEach((row, i) => {
      if (i > 0 && row.t - sorted[i - 1].t > step * 3) out.push({ t: (row.t + sorted[i - 1].t) / 2, current: null, target: null })
      out.push(row)
    })
    return out
  }, [history, roomId, step])
  const values = rows.flatMap((r) => [r.current, r.target]).filter((v): v is number => v != null)
  const lo = Math.floor(Math.min(...values, 18) - 0.5)
  const hi = Math.ceil(Math.max(...values, 21) + 0.5)
  const bands = boilerBands(history, from, to)
  const boilerAt = (t: number) => history.boiler.find((b) => Date.parse(b.from) <= t && (b.to == null || Date.parse(b.to) > t))?.state

  return (
    <div className="h-60 w-full sm:h-72" role="img" aria-label={`Temperature and target over the last ${RANGES.find((r) => r.value === range)!.label}${history.boiler.length > 0 ? ', with boiler heating shaded' : ''}`}>
      <ResponsiveContainer>
        <LineChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: -16 }}>
          <CartesianGrid vertical={false} stroke="var(--border)" />
          <XAxis
            dataKey="t"
            type="number"
            scale="time"
            domain={[from, to]}
            ticks={timeTicks(from, to, range)}
            tickFormatter={(t: number) => tickLabel(t, range)}
            tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }}
            tickLine={false}
            axisLine={{ stroke: 'var(--border)' }}
          />
          <YAxis
            domain={[lo, hi]}
            ticks={tempTicks(lo, hi)}
            tickFormatter={(v: number) => `${v}°`}
            tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }}
            tickLine={false}
            axisLine={false}
          />
          {bands.map((b) => (
            <ReferenceArea
              key={`${b.state}-${b.x1}`}
              x1={b.x1}
              x2={b.x2}
              className={`boiler-${b.state}`}
              fill="var(--heat)"
              fillOpacity={BAND_OPACITY[b.state]}
              stroke="none"
              ifOverflow="hidden"
            />
          ))}
          <Line
            dataKey="target"
            name="Target"
            type="stepAfter"
            className="target-line"
            stroke="var(--muted-foreground)"
            strokeWidth={1.5}
            strokeDasharray="5 4"
            dot={false}
            activeDot={false}
            isAnimationActive={false}
          />
          <Line
            dataKey="current"
            name="Temperature"
            className="temperature-line"
            stroke={seriesColor(0)}
            strokeWidth={2.5}
            dot={false}
            activeDot={{ r: 4, strokeWidth: 2, stroke: 'var(--card)' }}
            isAnimationActive={false}
          />
          <Tooltip
            cursor={{ stroke: 'var(--muted-foreground)', strokeDasharray: '3 3' }}
            content={({ active, payload, label }) => {
              if (!active || !payload?.length || label == null) return null
              const t = Number(label)
              const state = boilerAt(t)
              return (
                <div className="min-w-36 rounded-lg border bg-popover px-3 py-2 text-xs text-popover-foreground shadow-md">
                  <div className="mb-1 font-semibold">{readoutTime(t, range)}</div>
                  {payload.filter((p) => p.value != null).map((p) => (
                    <div key={String(p.dataKey)} className="flex justify-between gap-4">
                      <span>{p.name}</span>
                      <span className="tabular-nums">{formatTemp(Number(p.value))}</span>
                    </div>
                  ))}
                  <div className="mt-1 text-muted-foreground">Boiler {state ? BOILER_WORDS[state] : 'not recorded'}</div>
                </div>
              )
            }}
          />
        </LineChart>
      </ResponsiveContainer>
    </div>
  )
}

/**
 * A room's electricity use as bars: one per hour over 24 hours, one per day over 7 or 30. An incomplete bar is faded; an
 * hour or day with no reading at all has no bar, never 0 kWh.
 */
export function RoomEnergyChart({ history, range }: { history: RoomEnergyHistory; range: HistoryRange }) {
  const rows = useMemo(() => {
    const byStart = new Map(history.points.map((p) => [Date.parse(p.at), p]))
    // Every hour or day in the range, so one with nothing recorded shows as a gap.
    const out: { t: number; kwh: number | null; incomplete: boolean }[] = []
    const d = new Date(Date.parse(history.from))
    if (range === '24h') d.setMinutes(0, 0, 0)
    else d.setHours(0, 0, 0, 0)
    for (const to = Date.parse(history.to); d.getTime() < to; range === '24h' ? d.setHours(d.getHours() + 1) : d.setDate(d.getDate() + 1)) {
      const p = byStart.get(d.getTime())
      out.push({ t: d.getTime(), kwh: p?.kwh ?? null, incomplete: p?.incomplete ?? false })
    }
    return out
  }, [history, range])
  const ticks = rows
    .map((r) => r.t)
    .filter((t, i) => (range === '24h' ? new Date(t).getHours() % 6 === 0 : range === '7d' || i % 7 === 0))

  return (
    <div className="h-60 w-full sm:h-72" role="img" aria-label={`Electricity used by the room’s plugs over the last ${RANGES.find((r) => r.value === range)!.label}, one bar per ${range === '24h' ? 'hour' : 'day'}`}>
      <ResponsiveContainer>
        <BarChart data={rows} margin={{ top: 8, right: 8, bottom: 0, left: -8 }}>
          <CartesianGrid vertical={false} stroke="var(--border)" />
          <XAxis
            dataKey="t"
            ticks={ticks}
            tickFormatter={(t: number) => tickLabel(t, range)}
            tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }}
            tickLine={false}
            axisLine={{ stroke: 'var(--border)' }}
          />
          <YAxis
            tickFormatter={(v: number) => `${v} kWh`}
            tick={{ fill: 'var(--muted-foreground)', fontSize: 11 }}
            tickLine={false}
            axisLine={false}
            width={64}
          />
          <Bar dataKey="kwh" name="Used" className="energy-bar" radius={[3, 3, 0, 0]} isAnimationActive={false}>
            {rows.map((r) => (
              <Cell key={r.t} fill={seriesColor(0)} fillOpacity={r.incomplete ? 0.4 : 1} />
            ))}
          </Bar>
          <Tooltip
            cursor={{ fill: 'var(--muted)', opacity: 0.4 }}
            content={({ active, payload, label }) => {
              if (!active || !payload?.length || label == null) return null
              const row = payload[0].payload as (typeof rows)[number]
              return (
                <div className="min-w-36 rounded-lg border bg-popover px-3 py-2 text-xs text-popover-foreground shadow-md">
                  <div className="mb-1 font-semibold">{barTime(Number(label), range)}</div>
                  <div className="tabular-nums">{row.kwh == null ? 'No reading' : barKwh(row.kwh)}</div>
                  {row.incomplete && row.kwh != null && <div className="mt-1 text-muted-foreground">Some readings missing</div>}
                </div>
              )
            }}
          />
        </BarChart>
      </ResponsiveContainer>
    </div>
  )
}

/** "0.09 kWh" for an hour's small use, "1.6 kWh" for a day's. */
const barKwh = (kwh: number) => `${kwh < 1 ? kwh.toFixed(2) : kwh.toFixed(1)} kWh`

/** "14:00–15:00", or "Mon 12 Oct" for a day's bar. */
function barTime(t: number, range: HistoryRange): string {
  const d = new Date(t)
  if (range !== '24h') return d.toLocaleDateString([], { weekday: 'short', day: 'numeric', month: 'short' })
  const hour = (h: Date) => h.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
  return `${hour(d)}–${hour(new Date(t + 3600_000))}`
}

function boilerBands(history: History, from: number, to: number) {
  return history.boiler
    .filter((b) => BAND_OPACITY[b.state])
    .map((b) => ({ state: b.state, x1: Math.max(from, Date.parse(b.from)), x2: Math.min(to, b.to ? Date.parse(b.to) : to) }))
    .filter((b) => b.x2 > b.x1)
}

function Readout({
  active,
  payload,
  label,
  range,
  boilerAt,
}: Pick<TooltipContentProps<ValueType, NameType>, 'active' | 'payload' | 'label'> & {
  range: HistoryRange
  boilerAt: (t: number) => BoilerStateName | undefined
}) {
  if (!active || !payload?.length || label == null) return null
  const t = Number(label)
  const house = payload.find((p) => p.dataKey === 'house')
  const rooms = payload.filter((p) => p.dataKey !== 'house' && p.value != null)
  const state = boilerAt(t)
  return (
    <div className="min-w-40 rounded-lg border bg-popover px-3 py-2 text-xs text-popover-foreground shadow-md">
      <div className="mb-1 font-semibold">{readoutTime(t, range)}</div>
      {house?.value != null && (
        <div className="flex justify-between gap-4 font-semibold">
          <span>Whole house</span>
          <span className="tabular-nums">{formatTemp(Number(house.value))}</span>
        </div>
      )}
      {rooms.map((p) => (
        <div key={String(p.dataKey)} className="flex items-center justify-between gap-4">
          <span className="flex items-center gap-1.5">
            <span className="size-2 rounded-full" style={{ background: p.color }} />
            {p.name}
          </span>
          <span className="tabular-nums">{formatTemp(Number(p.value))}</span>
        </div>
      ))}
      <div className="mt-1 text-muted-foreground">Boiler {state ? BOILER_WORDS[state] : 'not recorded'}</div>
    </div>
  )
}

/** Merges the house and room series into rows by time, with an empty row in any gap so lines break there. */
function toRows(history: History, step: number): Row[] {
  const byTime = new Map<number, Row>()
  const put = (at: string, key: string, v: number | null | undefined) => {
    if (v == null) return
    const t = Date.parse(at)
    const row = byTime.get(t) ?? ({ t } as Row)
    row[key] = v
    byTime.set(t, row)
  }
  history.house.forEach((p) => put(p.at, 'house', p.current))
  history.rooms.forEach((r) => r.points.forEach((p) => put(p.at, r.id, p.current)))
  const sorted = [...byTime.values()].sort((a, b) => a.t - b.t)
  const rows: Row[] = []
  sorted.forEach((row, i) => {
    if (i > 0 && row.t - sorted[i - 1].t > step * 3) rows.push({ t: (row.t + sorted[i - 1].t) / 2 } as Row)
    rows.push(row)
  })
  return rows
}

function tempTicks(lo: number, hi: number): number[] {
  const every = hi - lo > 12 ? 4 : hi - lo > 6 ? 2 : 1
  const out: number[] = []
  for (let v = Math.ceil(lo / every) * every; v <= hi; v += every) out.push(v)
  return out
}

function timeTicks(from: number, to: number, range: HistoryRange): number[] {
  const out: number[] = []
  const d = new Date(from)
  if (range === '24h') {
    d.setMinutes(0, 0, 0)
    d.setHours(Math.ceil((d.getHours() + 1) / 6) * 6)
    for (; d.getTime() <= to; d.setHours(d.getHours() + 6)) out.push(d.getTime())
  } else {
    d.setHours(0, 0, 0, 0)
    d.setDate(d.getDate() + 1)
    for (; d.getTime() <= to; d.setDate(d.getDate() + (range === '7d' ? 1 : 7))) out.push(d.getTime())
  }
  return out
}

function tickLabel(t: number, range: HistoryRange): string {
  const d = new Date(t)
  if (range === '24h') return d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
  if (range === '7d') return d.toLocaleDateString([], { weekday: 'short' })
  return d.toLocaleDateString([], { day: 'numeric', month: 'short' })
}

function readoutTime(t: number, range: HistoryRange): string {
  const d = new Date(t)
  return range === '24h'
    ? d.toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
    : d.toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
}
