import { useCallback, useEffect, useState } from 'react'
import { CalendarClock, Flame, Hourglass, Moon, Palmtree, Power, Thermometer, Timer, Unplug, WifiOff, Zap } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { activityLabel, activityLine, ago } from '@/lib/activity'
import { api, type ActivityEvent } from '@/lib/api'
import { useHome } from '@/lib/home'
import { cn } from '@/lib/utils'

const PAGE = 50

function iconFor(e: ActivityEvent) {
  switch (e.kind) {
    case 'setpoint':
    case 'knob':
      return Thermometer
    case 'mode':
      return Power
    case 'boost':
      return Zap
    case 'holiday':
      return Palmtree
    case 'schedule':
      return CalendarClock
    case 'offline':
      return e.device ? Unplug : WifiOff
    default:
      return { heating: Flame, cycling: Timer, cycled: Timer, resting: Hourglass }[String(e.value)] ?? Moon
  }
}

/** The browser clock, ticking every 30 seconds so "5m ago" stays right while the tray is open. */
function useNow(): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    const timer = window.setInterval(() => setNow(Date.now()), 30_000)
    return () => window.clearInterval(timer)
  }, [])
  return now
}

/**
 * What changed in the heating, newest first: who set what, and what the boiler did and why. Loads when
 * it opens, adds events pushed on /ws as they happen, and "Load older" pages back. Lines are a sentence on
 * wide screens and a few words on phones; screen readers always get the sentence.
 */
export function ActivityTray() {
  const { latestActivity } = useHome()
  const now = useNow()
  const [events, setEvents] = useState<ActivityEvent[]>([])
  const [state, setState] = useState<'loading' | 'ready' | 'failed'>('loading')
  const [more, setMore] = useState(false)
  const [loadingOlder, setLoadingOlder] = useState(false)

  const load = useCallback(async () => {
    setState('loading')
    try {
      const page = (await api.activity(PAGE)).events
      setEvents((current) => mergeNewest(page, current))
      setMore(page.length === PAGE)
      setState('ready')
    } catch {
      setState('failed')
    }
  }, [])

  useEffect(() => {
    void load()
  }, [load])

  // Events pushed while the tray is open join the top of the list.
  useEffect(() => {
    if (latestActivity) setEvents((current) => mergeNewest([latestActivity], current))
  }, [latestActivity])

  const older = async () => {
    setLoadingOlder(true)
    try {
      const page = (await api.activity(PAGE, events[events.length - 1]?.id)).events
      setEvents((current) => [...current, ...page.filter((p) => !current.some((c) => c.id === p.id))])
      setMore(page.length === PAGE)
    } catch {
      setState('failed')
    } finally {
      setLoadingOlder(false)
    }
  }

  return (
    <div className="flex min-h-0 flex-1 flex-col gap-2" data-testid="activity-tray">
      <h3 className="text-xs font-semibold tracking-wide text-muted-foreground uppercase">Activity</h3>
      {state === 'failed' && (
        <div className="flex items-center justify-between gap-2 rounded-lg bg-muted p-3 text-sm">
          <span>Couldn't load activity</span>
          <Button size="sm" variant="outline" onClick={() => void load()}>
            Retry
          </Button>
        </div>
      )}
      {state === 'ready' && events.length === 0 && <p className="py-6 text-center text-sm text-muted-foreground">Nothing has changed yet</p>}
      {state === 'loading' && events.length === 0 && <p className="py-6 text-center text-sm text-muted-foreground">Loading…</p>}
      {events.length > 0 && (
        <ul className="-mx-1 flex min-h-0 flex-1 flex-col overflow-y-auto" data-testid="activity-list">
          {events.map((e) => {
            const Icon = iconFor(e)
            const line = activityLine(e)
            return (
              <li
                key={e.id}
                data-testid="activity-event"
                aria-label={`${activityLabel(e)}, ${ago(e.at, now)}`}
                title={e.device ?? e.room ?? undefined}
                className="flex items-center gap-2 rounded-md px-1 py-1.5 text-sm"
              >
                <Icon className={cn('size-4 shrink-0 text-muted-foreground', e.kind === 'boiler' && e.value === 'heating' && 'text-heat')} aria-hidden="true" />
                <span className="min-w-0 flex-1 truncate sm:whitespace-normal" aria-hidden="true">
                  <span className="hidden sm:inline" data-testid="activity-wide">{line.wide}</span>
                  <span className="sm:hidden" data-testid="activity-narrow">{line.narrow}</span>
                </span>
                <span className="shrink-0 text-xs text-muted-foreground tabular-nums" aria-hidden="true">
                  {ago(e.at, now)}
                </span>
              </li>
            )
          })}
          {more && (
            <li className="py-2 text-center">
              <Button size="sm" variant="ghost" disabled={loadingOlder} onClick={() => void older()}>
                Load older
              </Button>
            </li>
          )}
        </ul>
      )}
    </div>
  )
}

/** [fresh] events joined onto [current], newest first, without repeating one already in the list. */
function mergeNewest(fresh: ActivityEvent[], current: ActivityEvent[]): ActivityEvent[] {
  const known = new Set(current.map((e) => e.id))
  return [...fresh.filter((e) => !known.has(e.id)), ...current].sort((a, b) => b.id - a.id)
}
