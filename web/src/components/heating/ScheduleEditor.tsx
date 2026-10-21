import { useEffect, useState } from 'react'
import { CalendarDays, Copy, Loader2, Minus, Plus, Trash2 } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { DAYS, api, type Block, type Day, type WeekSchedule } from '@/lib/api'
import { formatTemp, stepTarget } from '@/lib/format'
import { useHome } from '@/lib/home'
import { cn } from '@/lib/utils'

const WEEKDAYS: Day[] = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday']
const WEEKEND: Day[] = ['saturday', 'sunday']
const short = (d: Day) => d.slice(0, 3)
// Service rules (openapi DaySchedule): 1-6 blocks, the first at 00:00, increasing start times.
const MAX_BLOCKS = 6
const today = (): Day => DAYS[(new Date().getDay() + 6) % 7]

/** A zone's week: pick a day, edit its blocks (start time + temperature), save, copy to other days. */
export function ScheduleEditor({ zoneId }: { zoneId: string }) {
  const { run } = useHome()
  const [week, setWeek] = useState<WeekSchedule | null>(null)
  const [day, setDay] = useState<Day>(today())
  const [blocks, setBlocks] = useState<Block[]>([])
  const [dirty, setDirty] = useState(false)
  const [saving, setSaving] = useState(false)

  useEffect(() => {
    api.schedule(zoneId).then(setWeek, (e: Error) => toast.error(e.message))
  }, [zoneId])

  useEffect(() => {
    if (week) {
      setBlocks(week[day].blocks)
      setDirty(false)
    }
  }, [week, day])

  const starts = blocks.map((b) => b.start)
  const problem = new Set(starts).size !== starts.length ? 'Two blocks start at the same time' : null

  const edit = (next: Block[]) => {
    setBlocks(next)
    setDirty(true)
  }

  async function save() {
    setSaving(true)
    const sorted = [...blocks].sort((a, b) => a.start.localeCompare(b.start))
    const saved = await run(() => api.saveDay(zoneId, day, sorted), "Couldn't save the schedule")
    if (saved) {
      setWeek(saved)
      toast.success(`${day[0].toUpperCase() + day.slice(1)} saved`)
    }
    setSaving(false)
  }

  async function copyTo(to: Day[], label: string) {
    const saved = await run(() => api.copyDay(zoneId, day, to.filter((d) => d !== day)), "Couldn't copy")
    if (saved) {
      setWeek(saved)
      toast.success(`Copied to ${label}`)
    }
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-4">
        <div>
          <CardTitle className="flex items-center gap-2">
            <CalendarDays className="size-5 text-muted-foreground" /> Schedule
          </CardTitle>
          <CardDescription>Each block holds its temperature until the next one starts.</CardDescription>
        </div>
        <DropdownMenu>
          <DropdownMenuTrigger asChild>
            <Button variant="outline" size="sm" disabled={!week || dirty}>
              <Copy /> Copy day
            </Button>
          </DropdownMenuTrigger>
          <DropdownMenuContent align="end">
            <DropdownMenuItem onSelect={() => copyTo(WEEKDAYS, 'weekdays')}>To weekdays</DropdownMenuItem>
            <DropdownMenuItem onSelect={() => copyTo(WEEKEND, 'the weekend')}>To the weekend</DropdownMenuItem>
            <DropdownMenuItem onSelect={() => copyTo(DAYS, 'every day')}>To every day</DropdownMenuItem>
          </DropdownMenuContent>
        </DropdownMenu>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <div className="grid grid-cols-7 gap-0.5 rounded-xl bg-secondary/60 p-1 sm:gap-1">
          {DAYS.map((d) => (
            <button
              key={d}
              onClick={() => setDay(d)}
              className={cn(
                'rounded-lg py-2 text-sm font-medium capitalize transition-colors',
                d === day ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {short(d)}
            </button>
          ))}
        </div>

        {!week ? (
          <div className="flex justify-center py-6">
            <Loader2 className="size-5 animate-spin text-muted-foreground" />
          </div>
        ) : (
          <>
            <Timeline blocks={blocks} />
            <div className="flex flex-col gap-2">
              {blocks.map((block, i) => (
                <div key={i} className="flex flex-wrap items-center gap-x-2 gap-y-1 rounded-xl border border-border/70 px-3 py-2">
                  <Input
                    type="time"
                    step={900}
                    className="w-36"
                    value={block.start}
                    disabled={i === 0}
                    onChange={(e) => edit(blocks.map((b, j) => (j === i ? { ...b, start: e.target.value } : b)))}
                    aria-label="Start time"
                  />
                  <div className="ml-auto flex items-center gap-1">
                    <Button variant="ghost" size="icon" onClick={() => edit(blocks.map((b, j) => (j === i ? { ...b, target: stepTarget(b.target, -1) } : b)))} aria-label="Cooler">
                      <Minus />
                    </Button>
                    <span className="w-14 text-center font-semibold tabular-nums">{formatTemp(block.target)}</span>
                    <Button variant="ghost" size="icon" onClick={() => edit(blocks.map((b, j) => (j === i ? { ...b, target: stepTarget(b.target, 1) } : b)))} aria-label="Warmer">
                      <Plus />
                    </Button>
                    <Button
                      variant="ghost"
                      size="icon"
                      disabled={i === 0}
                      onClick={() => edit(blocks.filter((_, j) => j !== i))}
                      aria-label="Remove block"
                    >
                      <Trash2 />
                    </Button>
                  </div>
                </div>
              ))}
            </div>
            <div className="flex items-center gap-2">
              <Button
                variant="outline"
                disabled={blocks.length >= MAX_BLOCKS}
                onClick={() => {
                  const last = blocks[blocks.length - 1]
                  const hour = last ? Math.min(23, Number(last.start.slice(0, 2)) + 2) : 7
                  edit([...blocks, { start: `${String(hour).padStart(2, '0')}:00`, target: last?.target ?? 20 }])
                }}
              >
                <Plus /> Add block
              </Button>
              {problem && <span className="text-sm text-destructive">{problem}</span>}
              <Button className="ml-auto" disabled={!dirty || saving || problem != null} onClick={save}>
                {saving && <Loader2 className="animate-spin" />} Save <span className="capitalize">{day}</span>
              </Button>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  )
}

/** The day as a bar: each block coloured warmer for higher targets. */
function Timeline({ blocks }: { blocks: Block[] }) {
  const sorted = [...blocks].sort((a, b) => a.start.localeCompare(b.start))
  const minutes = (s: string) => Number(s.slice(0, 2)) * 60 + Number(s.slice(3, 5))
  return (
    <div>
      <div className="flex h-8 overflow-hidden rounded-lg">
        {sorted.map((b, i) => {
          const start = i === 0 ? 0 : minutes(b.start)
          const end = i + 1 < sorted.length ? minutes(sorted[i + 1].start) : 1440
          const warmth = Math.min(1, Math.max(0, (b.target - 12) / 10))
          return (
            <div
              key={i}
              title={`${b.start} ${formatTemp(b.target)}`}
              className="flex items-center justify-center text-xs font-semibold text-primary-foreground/90"
              style={{
                width: `${((end - start) / 1440) * 100}%`,
                background: `color-mix(in oklch, var(--heat) ${Math.round(25 + warmth * 75)}%, var(--cool))`,
              }}
            >
              {end - start >= 120 ? formatTemp(b.target) : ''}
            </div>
          )
        })}
      </div>
      <div className="mt-1 flex justify-between text-xs text-muted-foreground tabular-nums">
        <span>00:00</span>
        <span>06:00</span>
        <span>12:00</span>
        <span>18:00</span>
        <span>24:00</span>
      </div>
    </div>
  )
}
