import { useEffect, useState, type FormEvent } from 'react'
import { Pencil, Plus, Trash2 } from 'lucide-react'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { energyApi, type EnergyArea } from '@/lib/energy'
import { useHome } from '@/lib/home'

const byName = (areas: EnergyArea[]) => [...areas].sort((a, b) => a.name.localeCompare(b.name))

/** Named things with meters and no valves, such as "Lights": added, renamed and deleted here. Admins only. */
export function EnergyAreasCard() {
  const { run } = useHome()
  const [areas, setAreas] = useState<EnergyArea[]>([])
  const [adding, setAdding] = useState(false)
  const [renaming, setRenaming] = useState<EnergyArea | null>(null)
  const [deleting, setDeleting] = useState<EnergyArea | null>(null)

  useEffect(() => {
    energyApi.areas().then(setAreas, () => undefined)
  }, [])

  async function add(name: string) {
    const area = await run(() => energyApi.addArea(name), "Couldn't add the area")
    if (area) setAreas((all) => byName([...all, area]))
    return !!area
  }

  async function rename(name: string) {
    const area = await run(() => energyApi.renameArea(renaming!.id, name), "Couldn't rename the area")
    if (area) setAreas((all) => byName(all.map((a) => (a.id === area.id ? area : a))))
    return !!area
  }

  async function remove(area: EnergyArea) {
    const done = await run(() => energyApi.deleteArea(area.id), "Couldn't delete the area")
    if (done) setAreas((all) => all.filter((a) => a.id !== area.id))
  }

  return (
    <Card data-testid="energy-areas">
      <CardHeader>
        <CardTitle>
          <h2>Areas</h2>
        </CardTitle>
        <CardDescription>Electricity that does not belong to a heated room, such as the lights or a garage.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {areas.map((area) => (
          <div key={area.id} className="flex items-center gap-2">
            <div className="min-w-40 flex-1 font-medium">{area.name}</div>
            <Button variant="ghost" size="icon" aria-label={`Rename ${area.name}`} onClick={() => setRenaming(area)}>
              <Pencil />
            </Button>
            <Button variant="ghost" size="icon" aria-label={`Delete ${area.name}`} onClick={() => setDeleting(area)}>
              <Trash2 />
            </Button>
          </div>
        ))}
        <div>
          <Button variant="outline" onClick={() => setAdding(true)}>
            <Plus />
            Add area
          </Button>
        </div>
      </CardContent>

      {adding && <AreaNameDialog title="New area" action="Add area" initial="" onSave={add} onClose={() => setAdding(false)} />}
      {renaming && <AreaNameDialog title={`Rename ${renaming.name}`} action="Rename" initial={renaming.name} onSave={rename} onClose={() => setRenaming(null)} />}

      <AlertDialog open={!!deleting} onOpenChange={(open) => !open && setDeleting(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete {deleting?.name}?</AlertDialogTitle>
            <AlertDialogDescription>The area goes from the Energy tab.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={() => deleting && remove(deleting)}>Delete</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  )
}

/** Stays open with what was typed when [onSave] is refused, such as for a name another area uses. */
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
