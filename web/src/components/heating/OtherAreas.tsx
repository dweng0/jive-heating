import { useEffect, useState, type FormEvent } from 'react'
import { Plus } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { energyApi, type EnergyArea, type EnergyDashboard } from '@/lib/energy'
import { useHome } from '@/lib/home'
import type { AreaPower } from '@/lib/livePower'
import { UseCard } from './UseCard'

const byName = (areas: EnergyArea[]) => [...areas].sort((a, b) => a.name.localeCompare(b.name))

/**
 * "Other areas" on the Energy tab, below the room cards and above "Everything else": a live card per energy area,
 * each opening its dashboard, and "Add area". With no areas there is only "Add area". Admins only.
 */
export function OtherAreas({ live, connected, today, house, currency }: { live: AreaPower[]; connected: boolean; today: EnergyDashboard | null; house: number | null; currency: string }) {
  const { run } = useHome()
  const [areas, setAreas] = useState<EnergyArea[]>([])
  const [adding, setAdding] = useState(false)

  useEffect(() => {
    energyApi.areas().then((a) => setAreas(byName(a)), () => undefined)
  }, [])

  async function add(name: string) {
    const area = await run(() => energyApi.addArea(name), "Couldn't add the area")
    if (area) setAreas((all) => byName([...all, area]))
    return !!area
  }

  const addButton = (
    <Button variant="outline" onClick={() => setAdding(true)}>
      <Plus />
      Add area
    </Button>
  )

  return (
    <section data-testid="energy-areas" className="flex flex-col gap-3">
      {areas.length === 0 ? (
        <div>{addButton}</div>
      ) : (
        <>
          <div className="flex flex-wrap items-end justify-between gap-3">
            <div>
              <h2 className="text-xl font-bold tracking-tight">Other areas</h2>
              <p className="text-sm text-muted-foreground">Electricity that does not belong to a heated room</p>
            </div>
            {addButton}
          </div>
          <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3">
            {areas.map((a) => {
              const now = live.find((p) => p.area === a.id)
              const day = today?.areas?.find((d) => d.area === a.id)
              return (
                <UseCard
                  key={a.id}
                  to={`/energy/areas/${encodeURIComponent(a.id)}`}
                  testId={`live-area-${a.id}`}
                  name={a.name}
                  watts={now?.watts ?? null}
                  noReading={connected && now?.watts == null}
                  house={house}
                  kwh={day?.kwh}
                  cost={day?.cost}
                  currency={currency}
                />
              )
            })}
          </div>
        </>
      )}
      {adding && <AreaNameDialog title="New area" action="Add area" initial="" onSave={add} onClose={() => setAdding(false)} />}
    </section>
  )
}

export /** Stays open with what was typed when [onSave] is refused, such as for a name another area uses. */
function AreaNameDialog(props: { title: string; action: string; initial: string; onSave: (name: string) => Promise<boolean>; onClose: () => void }) {
  const { title, action, initial, onSave, onClose } = props
  const [name, setName] = useState(initial)

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (name.trim() && (await onSave(name.trim()))) onClose()
  }

  return (
    <Dialog open onOpenChange={(open) => !open && onClose()}>
      <DialogContent className="sm:max-w-sm">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
          </DialogHeader>
          <Input autoFocus aria-label="Area name" placeholder="e.g. Lights" value={name} onChange={(e) => setName(e.target.value)} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={!name.trim()}>
              {action}
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
