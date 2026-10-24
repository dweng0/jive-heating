import { useState } from 'react'
import { Flame, House, Info } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/card'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { Skeleton } from '@/components/ui/skeleton'
import { HistoryChart, NoHistoryCard, RangePicker } from '@/components/heating/HistoryChart'
import { RANGES, useHistory } from '@/lib/history'
import type { History, HistoryRange } from '@/lib/api'
import { formatTemp, seriesColor } from '@/lib/format'
import { cn } from '@/lib/utils'

export function DashboardPage() {
  const [range, setRange] = useState<HistoryRange>('24h')
  const { history, failed } = useHistory(range)
  const [hidden, setHidden] = useState<Set<string>>(new Set())
  const rangeLabel = RANGES.find((r) => r.value === range)?.label ?? ''

  const empty = history != null && history.rooms.every((r) => r.points.length === 0)
  const toggle = (id: string) =>
    setHidden((h) => {
      const next = new Set(h)
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })

  return (
    <div className="flex flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">Dashboard</h1>
          <p className="text-muted-foreground">How the house heats, and when the boiler ran</p>
        </div>
        <RangePicker range={range} onChange={setRange} />
      </div>

      {failed && !history && <p className="text-muted-foreground">Couldn't load the history. It will try again in a few minutes.</p>}
      {!history && !failed && <Skeleton className="h-80 w-full rounded-2xl" />}

      {history && empty && <NoHistoryCard />}

      {history && !empty && (
        <>
          <div className="grid grid-cols-2 gap-3">
            <Stat
              icon={<Flame className="size-5" />}
              label={`Boiler on, ${rangeLabel.toLowerCase()}`}
              value={formatDuration(boilerOnMs(history))}
              info={
                <StatInfo label="About boiler on" title={`Boiler on over the last ${rangeLabel.toLowerCase()}`}>
                  How long the boiler fired from the left edge of the chart to now: the shaded “Boiler heating” and “Finishing its run”
                  stretches added together. Finishing its run is the boiler completing its minimum run after the rooms are warm enough.
                </StatInfo>
              }
            />
            <Stat
              icon={<House className="size-5" />}
              label={`Whole house, ${rangeLabel.toLowerCase()} average`}
              value={formatTemp(average(history))}
              info={
                <StatInfo label="About this average" title={`Average over the last ${rangeLabel.toLowerCase()}`}>
                  The black “Whole house” line, averaged across the whole chart: every room’s reading is taken every few minutes, the
                  middle (median) room temperature at each moment makes the line, and this is that line’s average from the left edge of
                  the chart to now. It is not the temperature right now — each room’s latest reading is beside its name below the chart.
                </StatInfo>
              }
            />
          </div>

          <Card>
            <CardContent className="flex flex-col gap-4 p-4 sm:p-6">
              <HistoryChart history={history} range={range} hidden={hidden} />
              <div className="flex flex-wrap gap-x-4 gap-y-2 text-sm">
                <span className="flex items-center gap-2 font-semibold">
                  <span className="h-[3px] w-5 rounded-full bg-foreground" /> Whole house
                </span>
                <span className="flex items-center gap-2 text-muted-foreground">
                  <span className="size-3 rounded-sm bg-heat/40" /> Boiler heating
                </span>
                <span className="flex items-center gap-2 text-muted-foreground">
                  <span className="size-3 rounded-sm bg-heat/15" /> Finishing its run
                </span>
              </div>
              {/* Every room with its latest reading; tap to hide or show its line. */}
              <div className="flex flex-wrap gap-2" aria-label="Rooms">
                {history.rooms.map((room, i) => {
                  const latest = room.points.findLast((p) => p.current != null)?.current
                  const off = hidden.has(room.id)
                  return (
                    <button
                      key={room.id}
                      type="button"
                      aria-pressed={!off}
                      onClick={() => toggle(room.id)}
                      className={cn(
                        'flex items-center gap-2 rounded-full border px-3 py-1 text-sm transition-opacity',
                        off && 'opacity-45',
                      )}
                    >
                      <span className="h-[2px] w-4 rounded-full" style={{ background: seriesColor(i) }} />
                      {room.name}
                      <span className="tabular-nums text-muted-foreground">{formatTemp(latest)}</span>
                    </button>
                  )
                })}
              </div>
            </CardContent>
          </Card>
        </>
      )}
    </div>
  )
}

function Stat({ icon, label, value, info }: { icon: React.ReactNode; label: string; value: string; info?: React.ReactNode }) {
  return (
    <Card>
      <CardContent className="flex items-center gap-3 p-3 sm:gap-4 sm:p-5">
        <div className="hidden size-10 shrink-0 items-center justify-center rounded-xl bg-heat-container text-heat-text sm:flex">{icon}</div>
        <div>
          <div className="flex items-center gap-1 text-xs text-muted-foreground sm:text-sm">
            {label}
            {info}
          </div>
          <div className="text-xl font-bold tabular-nums sm:text-2xl">{value}</div>
        </div>
      </CardContent>
    </Card>
  )
}

/** A small info button beside a stat's label; opens a note on what the number covers. */
function StatInfo({ label, title, children }: { label: string; title: string; children: React.ReactNode }) {
  return (
    <Popover>
      <PopoverTrigger
        aria-label={label}
        className="inline-flex size-6 shrink-0 items-center justify-center rounded-full transition-colors hover:bg-secondary hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
      >
        <Info className="size-4" />
      </PopoverTrigger>
      <PopoverContent className="flex flex-col gap-2 text-left">
        <p className="font-semibold">{title}</p>
        <p className="text-muted-foreground">{children}</p>
      </PopoverContent>
    </Popover>
  )
}

/** Time the boiler fired in the range: heating plus finishing its minimum run. */
function boilerOnMs(h: History): number {
  const from = Date.parse(h.from)
  const to = Date.parse(h.to)
  return h.boiler
    .filter((b) => b.state === 'heating' || b.state === 'cycling')
    .reduce((sum, b) => sum + Math.max(0, Math.min(to, b.to ? Date.parse(b.to) : to) - Math.max(from, Date.parse(b.from))), 0)
}

function average(h: History): number | null {
  const values = h.house.map((p) => p.current).filter((v): v is number => v != null)
  return values.length ? values.reduce((a, b) => a + b, 0) / values.length : null
}

function formatDuration(ms: number): string {
  const minutes = Math.round(ms / 60_000)
  if (minutes < 60) return `${minutes} min`
  const hours = Math.floor(minutes / 60)
  return `${hours} h ${minutes % 60} min`
}
