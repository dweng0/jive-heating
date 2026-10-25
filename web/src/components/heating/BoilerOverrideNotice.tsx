import { useState } from 'react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { BoilerOverrideButton } from '@/components/heating/BoilerOverrideButton'
import { useHome } from '@/lib/home'

/**
 * Tells every signed-in person, while an admin has forced the boiler off, that nothing will heat. "Got it"
 * hides it for that override; a new one, or a reload, shows it again.
 */
export function BoilerOverrideNotice() {
  const { boilerOverride, me } = useHome()
  const [seen, setSeen] = useState<string | null>(null)
  const open = boilerOverride != null && seen !== boilerOverride.since

  return (
    <Dialog open={open} onOpenChange={(next) => !next && boilerOverride && setSeen(boilerOverride.since)}>
      <DialogContent data-testid="boiler-override-notice">
        <DialogHeader>
          <DialogTitle>The boiler is turned off</DialogTitle>
          <DialogDescription>
            {boilerOverride?.by === 'admin' ? 'An admin' : `${boilerOverride?.by} (an admin)`} has turned the boiler off. The system will not heat while the
            admin override is set. Changes you make are saved and take effect once the boiler is turned back on.
          </DialogDescription>
        </DialogHeader>
        <DialogFooter>
          {me.admin && <BoilerOverrideButton />}
          <Button variant="outline" data-testid="boiler-override-got-it" onClick={() => boilerOverride && setSeen(boilerOverride.since)}>
            Got it
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}
