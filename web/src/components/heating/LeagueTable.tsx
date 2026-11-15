import { useEffect, useLayoutEffect, useMemo, useReducer, useRef, useState } from 'react'
import { Card, CardContent } from '@/components/ui/card'
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
    const t = setTimeout(wake, at - now)
    return () => clearTimeout(t)
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [order, signature])
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

/** What is using power right now, biggest first: a row per meter, with a bar as long as its share of the house. */
export function LeagueTable({ live }: { live: LivePower | null }) {
  const [view, setView] = useState<LeagueView>(rememberedView)
  const rows = usePlacedRows(leagueRows(live, view))
  function choose(next: LeagueView) {
    setView(next)
    try {
      localStorage.setItem(VIEW_KEY, next)
    } catch {
      /* not remembered */
    }
  }
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

  if (rows.length === 0) return null
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
        <ul ref={list} className="flex flex-col gap-1">
          {rows.map((r) => (
            <LeagueRowView key={r.key} row={r} sliding={sliding.has(r.key)} />
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}

function LeagueRowView({ row: r, sliding }: { row: LeagueRow; sliding: boolean }) {
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
      className={cn('relative overflow-hidden rounded-md px-3 py-2', r.state === 'no-reading' && 'opacity-50')}
    >
      <div
        data-testid="league-bar"
        aria-hidden
        className={cn('absolute inset-y-0 left-0 transition-[width] duration-500', r.state === 'else' ? 'bg-foreground/10' : 'bg-primary/35')}
        style={{ width: `${pct ?? 0}%` }}
      />
      <div className="relative flex items-baseline justify-between gap-3">
        <span data-testid="league-name" className={cn('truncate font-medium', r.state === 'else' && 'text-muted-foreground')}>
          {r.name}
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
            {shownPct == null ? '' : `${Math.round(shownPct)}%`}
          </span>
        </span>
      </div>
    </li>
  )
}
