import { useState } from 'react'
import { Minus, Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { api } from '@/lib/api'
import { dateTime, formatTemp, stepTarget } from '@/lib/format'
import { useHome } from '@/lib/home'

/** Return [days] from now at 15:00 local time, ISO-8601 with offset, as the service expects. */
function untilIso(days: number): string {
  const d = new Date()
  d.setDate(d.getDate() + days)
  d.setHours(15, 0, 0, 0)
  const offset = -d.getTimezoneOffset()
  const sign = offset >= 0 ? '+' : '-'
  const pad = (n: number) => String(Math.abs(n)).padStart(2, '0')
  const local = new Date(d.getTime() + offset * 60_000).toISOString().slice(0, 19)
  return `${local}${sign}${pad(Math.trunc(offset / 60))}:${pad(offset % 60)}`
}

export function HolidayDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const { status, setStatus, run } = useHome()
  const [days, setDays] = useState(7)
  const [target, setTarget] = useState(12)
  const holiday = status?.holiday

  async function start() {
    const s = await run(() => api.startHoliday(untilIso(days), target), "Couldn't start holiday")
    if (s) {
      setStatus(s)
      onOpenChange(false)
    }
  }

  async function end() {
    const s = await run(() => api.endHoliday(), "Couldn't end holiday")
    if (s) {
      setStatus(s)
      onOpenChange(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <DialogHeader>
          <DialogTitle>{holiday ? 'Holiday is on' : 'Going away?'}</DialogTitle>
          <DialogDescription>
            {holiday
              ? `Every room is held at ${formatTemp(holiday.target)} until ${dateTime(holiday.until)}. Rooms that are off stay off.`
              : 'Hold every room low while you are away. Boosts are cancelled; rooms that are off stay off.'}
          </DialogDescription>
        </DialogHeader>
        {!holiday && (
          <div className="flex flex-col gap-3 py-2">
            <Stepper label="Back in" value={`${days} day${days === 1 ? '' : 's'} (15:00)`} onLess={() => setDays(Math.max(1, days - 1))} onMore={() => setDays(Math.min(60, days + 1))} />
            <Stepper label="Keep rooms at" value={formatTemp(target)} onLess={() => setTarget(stepTarget(target, -1))} onMore={() => setTarget(stepTarget(target, 1))} />
          </div>
        )}
        <DialogFooter>
          <Button variant="ghost" onClick={() => onOpenChange(false)}>
            Cancel
          </Button>
          {holiday ? <Button onClick={end}>End holiday now</Button> : <Button onClick={start}>Start holiday</Button>}
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function Stepper({ label, value, onLess, onMore }: { label: string; value: string; onLess: () => void; onMore: () => void }) {
  return (
    <div className="flex items-center gap-3 rounded-xl bg-secondary/60 px-4 py-3">
      <div className="flex-1">
        <div className="text-sm text-muted-foreground">{label}</div>
        <div className="text-lg font-semibold">{value}</div>
      </div>
      <Button variant="outline" size="icon-lg" className="rounded-full" onClick={onLess} aria-label={`Less ${label}`}>
        <Minus />
      </Button>
      <Button variant="outline" size="icon-lg" className="rounded-full" onClick={onMore} aria-label={`More ${label}`}>
        <Plus />
      </Button>
    </div>
  )
}
