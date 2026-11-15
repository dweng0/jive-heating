import { useEffect, useLayoutEffect, useMemo, useReducer, useRef, useState, type ReactNode } from 'react'
import { toast } from 'sonner'
import { Card, CardContent } from '@/components/ui/card'
import { JobDialog, type Act } from '@/components/heating/MeterJobDialog'
import { energyApi, type EnergyArea, type Meter } from '@/lib/energy'
import { useHome } from '@/lib/home'
import { leagueRows, nextUnlock, NO_PLACING, placeRows, type LeagueRow, type LeagueView, type Placing } from '@/lib/league'
import { formatWatts, type LivePower } from '@/lib/livePower'
import { cn } from '@/lib/utils'

/** How long a row takes to slide to its new place, and a number to count to its new value. */
const MOVE_MS = 500
/** How long a share stays green or red after it changes. */
const FLASH_MS = 700
/** Where this device remembers whether the table shows Items or Rooms. */
const VIEW_KEY = 'heating.leagueView'

function rememberedView(): LeagueView {
  try {
    return localStorage.getItem(VIEW_KEY) === 'rooms' ? 'rooms' : 'items'
  } catch {
    return 'items'
  }
}

/** The rows in the order they are drawn: towards biggest first, but no row changes place more than once every 2 s. */
function usePlacedRows(rows: LeagueRow[]): LeagueRow[] {
  const [tick, wake] = useReducer((n: number) => n + 1, 0)
  const placing = useRef<Placing>(NO_PLACING)
  const desired = rows.map((r) => r.key)
  const signature = desired.join('\n')
  const order = useMemo(() => {
    placing.current = placeRows(placing.current, desired, Date.now())
    return placing.current.order
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [signature, tick])
  // A held row is let go when its 2 s are up, without waiting for the next reading.
  useEffect(() => {
    const now = Date.now()
    const at = nextUnlock(placing.current, desired, now)
    if (at == null) return
    // A millisecond past the 2 s, so a timer firing a touch early does not find the row still held.
    const t = setTimeout(wake, at - now + 1)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [order, signature, tick])
  const byKey = new Map(rows.map((r) => [r.key, r]))
  return order.map((k) => byKey.get(k)!).filter(Boolean)
}

/** [value], counting to each new value over half a second instead of jumping. */
function useCountTo(value: number | null): number | null {
  const [shown, setShown] = useState(value)
  const from = useRef(value)
  useEffect(() => {
    const start = from.current
    if (value == null || start == null || start === value) {
      from.current = value
      setShown(value)
      return
    }
    const began = performance.now()
    let frame = requestAnimationFrame(function step(t) {
      const p = Math.min(1, (t - began) / MOVE_MS)
      const eased = 1 - (1 - p) ** 3
      const v = start + (value - start) * eased
      from.current = v
      setShown(v)
      if (p < 1) frame = requestAnimationFrame(step)
    })
    return () => cancelAnimationFrame(frame)
  }, [value])
  return shown
}

/** "up" for a moment after [pct] rises, "down" after it falls. */
function useFlash(pct: number | null): 'up' | 'down' | null {
  const [flash, setFlash] = useState<'up' | 'down' | null>(null)
  const last = useRef(pct)
  useEffect(() => {
    const before = last.current
    last.current = pct
    if (before == null || pct == null || before === pct) return
    setFlash(pct > before ? 'up' : 'down')
    const t = setTimeout(() => setFlash(null), FLASH_MS)
    return () => clearTimeout(t)
  }, [pct])
  return flash
}

/** Opens a meter's settings by name: loads it and the energy areas, then shows the dialog to name it and give it a job. */
function useMeterSettings() {
  const { run } = useHome()
  const [meter, setMeter] = useState<Meter | null>(null)
  const [areas, setAreas] = useState<EnergyArea[]>([])
  const act: Act = async (action, failure, done) => {
    const result = await run(action, failure)
    if (result === undefined) return false
    if (done) toast.success(done)
    return true
  }
  async function open(name: string) {
    const loaded = await run(() => Promise.all([energyApi.meters(), energyApi.areas()]), "Couldn't load the meter")
    if (!loaded) return
    setAreas([...loaded[1]].sort((x, y) => x.name.localeCompare(y.name)))
    setMeter(loaded[0].find((m) => m.name === name) ?? null)
  }
  const dialog = <JobDialog meter={meter} areas={areas} onClose={() => setMeter(null)} act={act} withLabel />
  return { open, dialog }
}

/**
 * What is using power right now, biggest first: a row per meter, with a bar as long as its share of the house. A meter
 * with no job says so; an admin taps it to name it and give it a job.
 */
export function LeagueTable({ live, admin = false }: { live: LivePower | null; admin?: boolean }) {
  const settings = useMeterSettings()
  return <LeagueBoard live={live} onSetUp={admin ? settings.open : undefined} extra={settings.dialog} />
}

/**
 * The table itself, needing nothing from the app around it, so the Live view in the Android app can show it too.
 * [wide] lays it out like one side of an order book across the page: place, name with its room or area, watts, and a
 * bar of its share stacked after the rows above it, with the running total.
 */
export function LeagueBoard({
  live,
  onSetUp,
  extra,
  wide = false,
}: {
  live: LivePower | null
  onSetUp?: (meter: string) => void
  extra?: ReactNode
  wide?: boolean
}) {
  // While the house reading is stale the table holds the last good reading, greyed, rather than re-ranking on part of it.
  const stale = live != null && live.watts == null
  const lastGood = useRef<LivePower | null>(null)
  if (live != null && !stale) lastGood.current = live
  const shown = stale ? (lastGood.current ?? live) : live
  const [view, setView] = useState<LeagueView>(rememberedView)
  const rows = usePlacedRows(leagueRows(shown, view))
  function choose(next: LeagueView) {
    setView(next)
    try {
      localStorage.setItem(VIEW_KEY, next)
    } catch {
      /* not remembered */
    }
  }
  // Each row's bar starts where the rows above it end, like the depth on one side of an order book.
  const running: number[] = []
  rows.forEach((_, i) => running.push(i === 0 ? 0 : running[i - 1] + (rows[i - 1].share ?? 0)))
  const roomName = new Map((shown?.rooms ?? []).map((x) => [x.room, x.name]))
  const areaName = new Map((shown?.areas ?? []).map((x) => [x.area, x.name]))
  const whereOf = (r: LeagueRow) =>
    r.item?.room ? roomName.get(r.item.room) ?? null : r.item?.area ? areaName.get(r.item.area) ?? null : null
  const list = useRef<HTMLUListElement>(null)
  const tops = useRef(new Map<string, number>())
  const [sliding, setSliding] = useState<Set<string>>(new Set())

  // A row that changed place is drawn where it was, then eased to where it is now (FLIP).
  const orderKey = rows.map((r) => r.key).join('\n')
  useLayoutEffect(() => {
    const el = list.current
    if (!el) return
    const moved = new Set<string>()
    const now = new Map<string, number>()
    for (const li of el.querySelectorAll<HTMLLIElement>(':scope > li')) {
      const key = li.dataset.key!
      li.style.transition = ''
      li.style.transform = ''
      const top = li.offsetTop
      now.set(key, top)
      const was = tops.current.get(key)
      if (was != null && was !== top) {
        moved.add(key)
        li.style.transform = `translateY(${was - top}px)`
      }
    }
    tops.current = now
    if (moved.size === 0) return
    void el.offsetHeight
    for (const li of el.querySelectorAll<HTMLLIElement>(':scope > li')) {
      if (!moved.has(li.dataset.key!)) continue
      li.style.transition = `transform ${MOVE_MS}ms ease-out`
      li.style.transform = ''
    }
    setSliding(moved)
    const t = setTimeout(() => setSliding(new Set()), MOVE_MS)
    return () => clearTimeout(t)
  }, [orderKey])

  if (live == null) return null
  // Nothing but the house meter yet.
  const empty = live.items != null && live.items.length === 0
  if (rows.length === 0 && !empty) return null
  return (
    <Card data-testid="league-table">
      <CardContent className="flex flex-col gap-1 p-4">
        <div className="mb-1 flex items-center justify-between gap-3">
          <h2 className="text-lg font-semibold">Using power now</h2>
          <div role="group" aria-label="Rank by" className="flex rounded-lg bg-muted p-0.5 text-sm">
            {(['items', 'rooms'] as const).map((v) => (
              <button
                key={v}
                type="button"
                aria-pressed={view === v}
                onClick={() => choose(v)}
                className={cn(
                  'rounded-md px-3 py-1 font-medium transition-colors',
                  view === v ? 'bg-background text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
                )}
              >
                {v === 'items' ? 'Items' : 'Rooms'}
              </button>
            ))}
          </div>
        </div>
        {stale && <p className="text-sm text-muted-foreground">Readings stopped</p>}
        {empty ? (
          <p className="py-2 text-sm text-muted-foreground">Add a plug or clamp to see what is using power</p>
        ) : wide ? (
          <>
            <div className="hidden grid-cols-[2rem_minmax(0,1fr)_5.5rem_minmax(6rem,22rem)_3.5rem] items-center gap-3 border-b px-3 pb-2 text-[11px] font-semibold uppercase tracking-wider text-muted-foreground sm:grid">
              <span>#</span>
              <span>{view === 'items' ? 'Item' : 'Place'}</span>
              <span className="text-right">Now</span>
              <span>Share of the house</span>
              <span className="text-right">Share</span>
            </div>
            <ul ref={list} className="flex flex-col">
              {rows.map((r, i) => (
                <LeagueWideRow
                  key={r.key}
                  row={r}
                  place={i + 1}
                  before={running[i]}
                  where={whereOf(r)}
                  stale={stale}
                  sliding={sliding.has(r.key)}
                  onSetUp={onSetUp}
                />
              ))}
            </ul>
          </>
        ) : (
          <ul ref={list} className="flex flex-col gap-1">
            {rows.map((r) => (
              <LeagueRowView key={r.key} row={r} stale={stale} sliding={sliding.has(r.key)} onSetUp={onSetUp} />
            ))}
          </ul>
        )}
        {extra}
      </CardContent>
    </Card>
  )
}

function LeagueRowView({
  row: r,
  stale,
  sliding,
  onSetUp,
}: {
  row: LeagueRow
  /** The house reading has stopped: greyed, and its share shown as "—" */
  stale: boolean
  sliding: boolean
  onSetUp?: (meter: string) => void
}) {
  const noJob = r.item != null && r.item.job == null
  const pct = r.share == null ? null : r.share * 100
  const watts = useCountTo(r.watts)
  const shownPct = useCountTo(pct)
  const flash = useFlash(pct == null ? null : Math.round(pct))
  return (
    <li
      data-key={r.key}
      data-testid="league-row"
      data-state={r.state}
      data-sliding={sliding}
      data-stale={stale}
      className={cn('relative overflow-hidden rounded-md px-3 py-2', (r.state === 'no-reading' || stale) && 'opacity-50')}
    >
      <div
        data-testid="league-bar"
        aria-hidden
        className={cn('absolute inset-y-0 left-0 transition-[width] duration-500', r.state === 'else' ? 'bg-foreground/10' : 'bg-primary/35')}
        style={{ width: `${pct ?? 0}%` }}
      />
      {noJob && onSetUp && (
        // The whole row is the button, drawn over it so the figures stay where they are.
        <button
          type="button"
          aria-label={`Set up ${r.name}`}
          onClick={() => onSetUp(r.item!.meter)}
          className="absolute inset-0 z-10 rounded-md hover:bg-foreground/5 focus-visible:outline-2 focus-visible:outline-ring"
        />
      )}
      <div className="relative flex items-baseline justify-between gap-3">
        <span className="flex min-w-0 flex-col">
          <span data-testid="league-name" className={cn('truncate font-medium', r.state === 'else' && 'text-muted-foreground')}>
            {r.name}
          </span>
          {noJob && (
            <span data-testid="league-note" className="text-xs text-muted-foreground">
              No job yet
            </span>
          )}
        </span>
        <span className="flex shrink-0 items-baseline gap-3 tabular-nums">
          <span data-testid="league-watts" className={cn(r.state !== 'on' && r.state !== 'else' && 'text-muted-foreground')}>
            {r.state === 'off' ? 'Off' : r.state === 'no-reading' ? 'No reading' : formatWatts(watts)}
          </span>
          <span
            data-testid="league-share"
            data-flash={flash ?? undefined}
            className={cn(
              'w-10 rounded text-right text-sm text-muted-foreground transition-colors duration-300',
              flash === 'up' && 'bg-green-500/25 text-green-700 dark:text-green-400',
              flash === 'down' && 'bg-red-500/25 text-red-700 dark:text-red-400',
            )}
          >
            {stale ? '—' : shownPct == null ? '' : `${Math.round(shownPct)}%`}
          </span>
        </span>
      </div>
    </li>
  )
}

function LeagueWideRow({
  row: r,
  place,
  before,
  where,
  stale,
  sliding,
  onSetUp,
}: {
  row: LeagueRow
  place: number
  /** The share of the rows above this one (0..1), where its bar starts */
  before: number
  where: string | null
  stale: boolean
  sliding: boolean
  onSetUp?: (meter: string) => void
}) {
  const noJob = r.item != null && r.item.job == null
  const pct = r.share == null ? null : r.share * 100
  const watts = useCountTo(r.watts)
  const shownPct = useCountTo(pct)
  const flash = useFlash(pct == null ? null : Math.round(pct))
  const runningPct = pct == null ? null : Math.min(100, before * 100 + pct)
  return (
    <li
      data-key={r.key}
      data-testid="league-row"
      data-state={r.state}
      data-sliding={sliding}
      data-stale={stale}
      className={cn(
        'relative grid grid-cols-[2rem_minmax(0,1fr)_5.5rem] items-center gap-x-3 gap-y-1 border-b border-border/60 px-3 py-3 last:border-b-0 sm:grid-cols-[2rem_minmax(0,1fr)_5.5rem_minmax(6rem,22rem)_3.5rem]',
        (r.state === 'no-reading' || stale) && 'opacity-50',
      )}
    >
      {noJob && onSetUp && (
        <button
          type="button"
          aria-label={`Set up ${r.name}`}
          onClick={() => onSetUp(r.item!.meter)}
          className="absolute inset-0 z-10 hover:bg-foreground/5 focus-visible:outline-2 focus-visible:outline-ring"
        />
      )}
      <span data-testid="league-place" className="text-sm tabular-nums text-muted-foreground">
        {place}
      </span>
      <span className="flex min-w-0 flex-col">
        <span data-testid="league-name" className={cn('truncate font-semibold', r.state === 'else' && 'text-muted-foreground')}>
          {r.name}
        </span>
        {noJob ? (
          <span data-testid="league-note" className="text-xs text-muted-foreground">
            No job yet
          </span>
        ) : where ? (
          <span data-testid="league-where" className="truncate text-xs text-muted-foreground">
            {where}
          </span>
        ) : null}
      </span>
      <span
        data-testid="league-watts"
        className={cn('text-right font-semibold tabular-nums', r.state !== 'on' && r.state !== 'else' && 'font-normal text-muted-foreground')}
      >
        {r.state === 'off' ? 'Off' : r.state === 'no-reading' ? 'No reading' : formatWatts(watts)}
      </span>
      <span className="col-span-3 flex flex-col gap-1 sm:col-span-1">
        <span className="relative h-3 overflow-hidden rounded-sm bg-muted">
          <span
            data-testid="league-bar"
            aria-hidden
            className={cn('absolute inset-y-0 transition-[left,width] duration-500', r.state === 'else' ? 'bg-foreground/25' : 'bg-primary')}
            style={{ left: `${before * 100}%`, width: `${pct ?? 0}%` }}
          />
        </span>
        <span className="flex justify-between text-[11px] tabular-nums text-muted-foreground">
          <span>{pct == null || stale ? '' : `+${Math.round(shownPct ?? 0)}%`}</span>
          <span data-testid="league-running">{runningPct == null || stale ? '' : `${Math.round(runningPct)}%`}</span>
        </span>
      </span>
      <span
        data-testid="league-share"
        data-flash={flash ?? undefined}
        className={cn(
          'hidden rounded text-right text-sm tabular-nums text-muted-foreground transition-colors duration-300 sm:block',
          flash === 'up' && 'bg-green-500/25 text-green-700 dark:text-green-400',
          flash === 'down' && 'bg-red-500/25 text-red-700 dark:text-red-400',
        )}
      >
        {stale ? '—' : shownPct == null ? '' : `${Math.round(shownPct)}%`}
      </span>
    </li>
  )
}
