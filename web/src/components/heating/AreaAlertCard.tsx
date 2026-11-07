import { useEffect, useState, type FormEvent } from 'react'
import { Bell, BellOff } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { alertLine, areaAlertApi, type AlertAudience, type AreaAlert } from '@/lib/areaAlerts'
import { useHome } from '@/lib/home'

/**
 * An area's notification when it is still using power after some hours: what it is set to, "Set up notification" (or
 * "Edit notification") and "Turn off".
 */
export function AreaAlertControls({ area, name }: { area: string; name: string }) {
  const { me, run } = useHome()
  const [alert, setAlert] = useState<AreaAlert | null>(null)
  const [editing, setEditing] = useState(false)

  useEffect(() => {
    areaAlertApi.get(area).then(setAlert, () => setAlert(null))
  }, [area])

  const line = alertLine(alert, me.username)

  async function save(hours: number, notify: AlertAudience) {
    const saved = await run(() => areaAlertApi.set(area, hours, notify), "Couldn't set up the notification")
    if (saved) setAlert(saved)
    return saved != null
  }

  async function turnOff() {
    if (await run(() => areaAlertApi.clear(area), "Couldn't turn the notification off")) setAlert({ hours: null, notify: null, by: null })
  }

  return (
    <div data-testid="area-alert" className="flex flex-wrap items-center gap-2">
      {line && <span data-testid="area-alert-line" className="text-sm text-muted-foreground">{line}</span>}
      <Button variant="outline" onClick={() => setEditing(true)}>
        <Bell /> {line ? 'Edit notification' : 'Set up notification'}
      </Button>
      {line && (
        <Button variant="outline" onClick={turnOff}>
          <BellOff /> Turn off
        </Button>
      )}
      {editing && <AreaAlertDialog name={name} initial={alert} onSave={save} onClose={() => setEditing(false)} />}
    </div>
  )
}

function AreaAlertDialog(props: { name: string; initial: AreaAlert | null; onSave: (hours: number, notify: AlertAudience) => Promise<boolean>; onClose: () => void }) {
  const { name, initial, onSave, onClose } = props
  const [hours, setHours] = useState(String(initial?.hours ?? 1))
  const [notify, setNotify] = useState<AlertAudience>(initial?.notify ?? 'me')
  const value = Number(hours)
  const valid = Number.isFinite(value) && value >= 0.5 && value <= 24 && (value * 2) % 1 === 0

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (valid && (await onSave(value, notify))) onClose()
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-sm">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>Notification for {name}</DialogTitle>
            <DialogDescription>Tell someone when {name} has been using power (over 5 W) without a break.</DialogDescription>
          </DialogHeader>
          <div className="flex items-center gap-2">
            <Label htmlFor="alert-hours" className="shrink-0">Notify after</Label>
            <Input id="alert-hours" type="number" inputMode="decimal" min={0.5} max={24} step={0.5} value={hours} onChange={(e) => setHours(e.target.value)} className="w-24" />
            <span className="text-sm">h if {name} is still using power</span>
          </div>
          {!valid && <p className="text-xs text-destructive">From 0.5 to 24 hours, in half hours.</p>}
          <fieldset className="flex flex-col gap-2">
            <legend className="mb-1 text-sm font-medium">Who to notify</legend>
            {(
              [
                ['me', 'Just me'],
                ['everyone', 'Everyone with energy access'],
              ] as const
            ).map(([v, label]) => (
              <label key={v} className="flex items-center gap-2 text-sm">
                <input type="radio" name="alert-notify" value={v} checked={notify === v} onChange={() => setNotify(v)} className="size-4 accent-[var(--primary)]" />
                {label}
              </label>
            ))}
          </fieldset>
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={!valid}>
              Save
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
