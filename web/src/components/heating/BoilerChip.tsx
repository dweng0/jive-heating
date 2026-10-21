import { useEffect, useState } from 'react'
import { Flame, Hourglass, Moon, Timer } from 'lucide-react'
import { Dialog as DialogPrimitive } from 'radix-ui'
import { XIcon } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogClose, DialogDescription, DialogOverlay, DialogPortal, DialogTitle, DialogTrigger } from '@/components/ui/dialog'
import { ActivityTray } from '@/components/heating/ActivityTray'
import type { BoilerStateName } from '@/lib/api'
import { boilerExplanation, boilerLabel, boilerState } from '@/lib/format'
import { useHome } from '@/lib/home'
import { cn } from '@/lib/utils'

const ICONS: Record<BoilerStateName, typeof Flame> = { heating: Flame, cycling: Timer, resting: Hourglass, idle: Moon }

const TITLES: Record<BoilerStateName, string> = {
  heating: 'Boiler heating',
  cycling: 'Boiler finishing its run',
  resting: 'Boiler resting',
  idle: 'Boiler idle',
}

/** The browser clock, ticking every second while [ticking]. */
function useNow(ticking: boolean): number {
  const [now, setNow] = useState(() => Date.now())
  useEffect(() => {
    if (!ticking) return
    const tick = () => setNow(Date.now())
    const first = window.setTimeout(tick, 0) // catch up at once after a stretch without ticking
    const timer = window.setInterval(tick, 1000)
    return () => {
      window.clearTimeout(first)
      window.clearInterval(timer)
    }
  }, [ticking])
  return now
}

/**
 * The header's boiler chip: heating, finishing its minimum run, resting between runs, or idle, with a
 * live countdown for the timed states. Opens (click, tap, Enter or Space) the activity tray: the boiler's
 * state and what it means, then what changed in the heating lately.
 */
export function BoilerChip() {
  const { status, live } = useHome()
  const boiler = boilerState(status)
  const now = useNow(boiler.until != null)
  const Icon = ICONS[boiler.state]
  const label = boilerLabel(boiler, now)
  const short = label.includes(' · ') ? label.split(' · ')[1] : label

  return (
    <Dialog>
      <DialogTrigger
        data-testid="boiler-chip"
        data-state-name={boiler.state}
        className={cn(
          'flex items-center gap-2 rounded-full px-3 py-1.5 text-sm font-medium tabular-nums transition-colors focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none',
          boiler.state === 'heating' || boiler.state === 'cycling' ? 'bg-heat-container text-heat-text' : 'bg-off text-off-foreground',
        )}
      >
        <Icon className={cn('size-4', boiler.state === 'heating' && 'animate-pulse')} />
        {/* Phones get the short form (the countdown, or the state word); wider screens the full label. */}
        <span className="hidden sm:inline" data-testid="boiler-label">{label}</span>
        <span className="sm:hidden" aria-hidden="true">{short}</span>
        <span className={cn('size-1.5 rounded-full', live ? 'bg-ok-foreground' : 'bg-muted-foreground/50')} />
      </DialogTrigger>
      <DialogPortal>
        <DialogOverlay />
        {/* A tray down the right-hand side (the full width on a phone). */}
        <DialogPrimitive.Content
          data-testid="activity-panel"
          className="fixed inset-y-0 right-0 z-50 flex w-full flex-col gap-3 bg-popover p-4 text-left text-sm text-popover-foreground ring-1 ring-foreground/10 outline-none data-open:animate-in data-open:slide-in-from-right data-closed:animate-out data-closed:slide-out-to-right sm:max-w-md"
        >
          <div className="flex flex-col gap-2 pr-8">
            <DialogTitle className="text-base font-semibold">{TITLES[boiler.state]}</DialogTitle>
            <DialogDescription>{boilerExplanation[boiler.state]}</DialogDescription>
            <p className="text-xs text-muted-foreground">{live ? 'Live updates connected' : 'Reconnecting to live updates…'}</p>
          </div>
          <ActivityTray />
          <DialogClose asChild>
            <Button variant="ghost" size="icon-sm" className="absolute top-2 right-2">
              <XIcon />
              <span className="sr-only">Close</span>
            </Button>
          </DialogClose>
        </DialogPrimitive.Content>
      </DialogPortal>
    </Dialog>
  )
}
