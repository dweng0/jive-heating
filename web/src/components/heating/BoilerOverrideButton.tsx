import { useState } from 'react'
import { Power } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { api } from '@/lib/api'
import { useHome } from '@/lib/home'

/**
 * Admins: turn the boiler off (after an "Are you sure?") or, while it is forced off, back on.
 * Off holds heat demand false and every valve off until an admin turns it back on.
 */
export function BoilerOverrideButton() {
  const { boilerOverride, setBoilerOverride, run } = useHome()
  const [confirming, setConfirming] = useState(false)
  const [busy, setBusy] = useState(false)

  const act = async (off: boolean) => {
    setBusy(true)
    const result = await run(() => (off ? api.boilerOff() : api.boilerOn()))
    setBusy(false)
    setConfirming(false)
    if (result) setBoilerOverride(result.override)
  }

  if (boilerOverride) {
    return (
      <Button data-testid="boiler-on" onClick={() => void act(false)} disabled={busy}>
        <Power /> Turn boiler back on
      </Button>
    )
  }
  if (!confirming) {
    return (
      <Button variant="outline" data-testid="boiler-off" onClick={() => setConfirming(true)}>
        <Power /> Turn boiler off
      </Button>
    )
  }
  return (
    <div role="alertdialog" aria-label="Turn the boiler off?" className="flex flex-col gap-2 rounded-lg border border-destructive/40 p-3">
      <p className="text-sm">Are you sure? No room will heat, and every valve is sent off, until an admin turns the boiler back on. Boosts are cancelled.</p>
      <div className="flex gap-2">
        <Button variant="destructive" data-testid="boiler-off-confirm" onClick={() => void act(true)} disabled={busy}>
          Turn boiler off
        </Button>
        <Button variant="ghost" onClick={() => setConfirming(false)} disabled={busy}>
          Cancel
        </Button>
      </div>
    </div>
  )
}
