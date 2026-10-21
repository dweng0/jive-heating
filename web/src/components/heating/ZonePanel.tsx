import { useEffect, useRef, useState, type ReactNode } from 'react'
import { Flame, Minus, Plus, X, Zap } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Dial } from '@/components/heating/Dial'
import { api, type Mode, type Zone } from '@/lib/api'
import { formatTemp, stepTarget, time, zoneSummary } from '@/lib/format'
import { useHome } from '@/lib/home'
import { cn } from '@/lib/utils'

const BOOSTS = [30, 60, 120] as const

/** One zone: dial with +/- (sent after a short pause, as manual), mode, and boost. [currentInfo] goes beside its current temperature. */
export function ZonePanel({ zone, title, currentInfo }: { zone: Zone; title?: string; currentInfo?: ReactNode }) {
  const { run, putRoom, boostTarget } = useHome()
  const [pending, setPending] = useState<number | null>(null)
  const timer = useRef<number | undefined>(undefined)
  const shown = pending ?? zone.target ?? zone.manualTarget
  const off = zone.mode === 'off' && !zone.boost

  useEffect(() => () => window.clearTimeout(timer.current), [])

  function nudge(direction: 1 | -1) {
    const next = stepTarget(shown, direction)
    setPending(next)
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(async () => {
      const room = await run(() => api.setMode(zone.id, 'manual', next), "Couldn't set the temperature")
      if (room) putRoom(room)
      setPending(null)
    }, 700)
  }

  async function setMode(mode: Mode) {
    const room = await run(() => api.setMode(zone.id, mode, mode === 'manual' ? zone.manualTarget : undefined), "Couldn't change mode")
    if (room) putRoom(room)
  }

  async function boost(minutes: number) {
    const room = await run(() => api.boost(zone.id, minutes, Math.max(boostTarget, shown)), "Couldn't boost")
    if (room) putRoom(room)
  }

  async function cancelBoost() {
    const room = await run(() => api.cancelBoost(zone.id), "Couldn't cancel boost")
    if (room) putRoom(room)
  }

  return (
    <Card className="gap-0 overflow-hidden">
      <CardContent className="flex flex-col gap-6 p-6">
        {title && <h3 className="text-sm font-semibold text-muted-foreground">{title}</h3>}

        <div className="flex items-center justify-center gap-2 sm:gap-6">
          <Button variant="outline" size="icon-lg" className="size-12 rounded-full" disabled={off} onClick={() => nudge(-1)} aria-label="Cooler">
            <Minus className="size-5" />
          </Button>
          <div className="w-full max-w-64">
            <Dial target={shown} current={zone.current} heating={zone.heating} off={off} label={title ?? 'Room'} currentInfo={currentInfo} />
          </div>
          <Button variant="outline" size="icon-lg" className="size-12 rounded-full" disabled={off} onClick={() => nudge(1)} aria-label="Warmer">
            <Plus className="size-5" />
          </Button>
        </div>

        <p className="-mt-2 text-center text-sm text-muted-foreground">
          {zone.callingForHeat && !off && <Flame className="mr-1 inline size-4 text-heat" />}
          {pending != null ? `Setting ${formatTemp(pending)}…` : zoneSummary(zone)}
        </p>

        <Tabs value={zone.mode} onValueChange={(m) => setMode(m as Mode)}>
          <TabsList className="grid h-11 w-full grid-cols-3">
            <TabsTrigger value="schedule">Schedule</TabsTrigger>
            <TabsTrigger value="manual">Manual</TabsTrigger>
            <TabsTrigger value="off">Off</TabsTrigger>
          </TabsList>
        </Tabs>

        {zone.boost ? (
          <div className="flex items-center gap-3 rounded-xl bg-heat-container px-4 py-3 text-heat-text">
            <Zap className="size-5 shrink-0" />
            <div className="flex-1 text-sm">
              <div className="font-semibold">Boosting to {formatTemp(zone.boost.target)}</div>
              <div>until {time(zone.boost.endsAt)}</div>
            </div>
            <Button variant="ghost" size="sm" className="text-heat-text hover:bg-heat/10" onClick={cancelBoost}>
              <X /> Cancel
            </Button>
          </div>
        ) : (
          <div className="flex flex-col gap-2">
            <div className="flex items-center gap-1.5 text-sm font-medium text-muted-foreground">
              <Zap className="size-4" /> Boost to {formatTemp(Math.max(boostTarget, shown))}
            </div>
            <div className="grid grid-cols-3 gap-2">
              {BOOSTS.map((m) => (
                <Button key={m} variant="secondary" className={cn('h-10')} onClick={() => boost(m)}>
                  {m < 60 ? `${m} min` : `${m / 60} h`}
                </Button>
              ))}
            </div>
          </div>
        )}
      </CardContent>
    </Card>
  )
}
