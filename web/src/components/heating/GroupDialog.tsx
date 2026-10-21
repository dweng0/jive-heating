import { useState, type FormEvent } from 'react'
import { Button } from '@/components/ui/button'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { api, type Group } from '@/lib/api'
import { formatTemp } from '@/lib/format'
import { useHome } from '@/lib/home'

/** New group, or edit an existing one's name and rooms. Calls [onSaved] with the saved group. */
export function GroupDialog({
  group,
  open,
  onOpenChange,
  onSaved,
}: {
  group?: Group
  open: boolean
  onOpenChange: (open: boolean) => void
  onSaved?: (group: Group) => void
}) {
  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-sm">
        {/* Mounted only while open, so each opening starts from the group as it is now. */}
        <GroupForm group={group} onDone={() => onOpenChange(false)} onSaved={onSaved} />
      </DialogContent>
    </Dialog>
  )
}

function GroupForm({ group, onDone, onSaved }: { group?: Group; onDone: () => void; onSaved?: (group: Group) => void }) {
  const { rooms, run, putGroup } = useHome()
  const [name, setName] = useState(group?.name ?? '')
  const [chosen, setChosen] = useState<string[]>(group?.rooms ?? [])

  const toggle = (id: string, on: boolean) => setChosen((c) => (on ? [...c.filter((x) => x !== id), id] : c.filter((x) => x !== id)))

  async function submit(e: FormEvent) {
    e.preventDefault()
    const trimmed = name.trim()
    if (!trimmed) return
    // Keep the rooms in the same order as the Rooms page.
    const ids = rooms.map((r) => r.id).filter((id) => chosen.includes(id))
    const saved = await run(
      () => (group ? api.patchGroup(group.id, { name: trimmed, rooms: ids }) : api.createGroup(trimmed, ids)),
      group ? "Couldn't save the group" : "Couldn't add the group",
    )
    if (saved) {
      putGroup(saved)
      onDone()
      onSaved?.(saved)
    }
  }

  return (
    <form onSubmit={submit} className="flex flex-col gap-4">
      <DialogHeader>
        <DialogTitle>{group ? 'Edit group' : 'New group'}</DialogTitle>
        <DialogDescription>Control these rooms together. A room can be in more than one group.</DialogDescription>
      </DialogHeader>
      <div className="flex flex-col gap-2">
        <Label htmlFor="group-name">Group name</Label>
        <Input id="group-name" autoFocus placeholder="e.g. Upstairs" value={name} onChange={(e) => setName(e.target.value)} />
      </div>
      <fieldset className="flex flex-col gap-1">
        <legend className="mb-1 text-sm font-medium">Rooms</legend>
        {rooms.length === 0 && <p className="py-2 text-sm text-muted-foreground">Add some rooms first.</p>}
        <div className="flex max-h-72 flex-col gap-1 overflow-y-auto">
          {rooms.map((r) => (
            <label key={r.id} className="flex min-h-10 cursor-pointer items-center gap-3 rounded-xl px-2 hover:bg-secondary/60">
              <Checkbox checked={chosen.includes(r.id)} onCheckedChange={(v) => toggle(r.id, v === true)} aria-label={r.name} />
              <span className="flex-1 truncate text-sm font-medium">{r.name}</span>
              <span className="text-sm text-muted-foreground tabular-nums">{formatTemp(r.current)}</span>
            </label>
          ))}
        </div>
      </fieldset>
      <DialogFooter>
        <Button type="button" variant="ghost" onClick={onDone}>
          Cancel
        </Button>
        <Button type="submit" disabled={!name.trim()}>
          {group ? 'Save group' : 'Add group'}
        </Button>
      </DialogFooter>
    </form>
  )
}
