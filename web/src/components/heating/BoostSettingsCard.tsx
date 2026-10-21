import { useState } from 'react'
import { Minus, Plus, Zap } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { api } from '@/lib/api'
import { stepTarget } from '@/lib/format'
import { useHome } from '@/lib/home'

/** The household's boost temperature: where every boost starts. Admins only: the page leaves it out for members. */
export function BoostSettingsCard() {
  const { run, boostTarget, setBoostTarget } = useHome()
  const [draft, setDraft] = useState<number | null>(null)

  async function save() {
    if (draft == null) return
    const updated = await run(() => api.saveBoostSettings({ defaultTarget: draft }), "Couldn't save the boost temperature")
    if (updated) {
      setBoostTarget(updated.defaultTarget)
      setDraft(null)
    }
  }

  return (
    <Card data-testid="boost-settings">
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Zap className="size-5 text-muted-foreground" />
          <h2>Boost</h2>
        </CardTitle>
        <CardDescription>Where every boost starts. Anyone can still pick another temperature when they boost.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {draft == null ? (
          <>
            <p className="text-sm">
              <span className="text-muted-foreground">Default boost temperature </span>
              <span className="font-medium tabular-nums">{boostTarget.toFixed(1)}°C</span>
            </p>
            <Button variant="outline" className="self-end" onClick={() => setDraft(boostTarget)}>
              Edit
            </Button>
          </>
        ) : (
          <>
            <div className="flex items-center justify-center gap-2">
              <Button variant="ghost" size="icon-lg" className="rounded-full" onClick={() => setDraft(stepTarget(draft, -1))} aria-label="Cooler default">
                <Minus />
              </Button>
              <span className="w-20 text-center text-xl font-bold tabular-nums">{draft.toFixed(1)}°C</span>
              <Button variant="ghost" size="icon-lg" className="rounded-full" onClick={() => setDraft(stepTarget(draft, 1))} aria-label="Warmer default">
                <Plus />
              </Button>
            </div>
            <div className="flex justify-end gap-2">
              <Button type="button" variant="ghost" onClick={() => setDraft(null)}>
                Cancel
              </Button>
              <Button onClick={save}>Save</Button>
            </div>
          </>
        )}
      </CardContent>
    </Card>
  )
}
