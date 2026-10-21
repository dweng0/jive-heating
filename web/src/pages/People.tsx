import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { KeyRound, MoreHorizontal, Shield, ShieldOff, Trash2, User as UserIcon, UserPlus } from 'lucide-react'
import { toast } from 'sonner'
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
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { DropdownMenu, DropdownMenuContent, DropdownMenuItem, DropdownMenuSeparator, DropdownMenuTrigger } from '@/components/ui/dropdown-menu'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { Hint, ShowPassword } from '@/components/heating/PasswordHelpers'
import { api, type User } from '@/lib/api'
import { roomsSummary, tickRoom } from '@/lib/format'
import { MIN_PASSWORD } from '@/lib/format'
import { useHome } from '@/lib/home'

/** Admin only: everyone who can sign in. */
export function PeoplePage() {
  const { me, run } = useHome()
  const [users, setUsers] = useState<User[] | null>(null)
  const [adding, setAdding] = useState(false)
  const [resetting, setResetting] = useState<User | null>(null)
  const [removing, setRemoving] = useState<User | null>(null)
  const [opened, setOpened] = useState<string | null>(null)

  const load = useCallback(() => api.users().then(setUsers, (e: Error) => toast.error(e.message)), [])
  useEffect(() => {
    void load()
  }, [load])

  async function act(action: () => Promise<unknown>, failure: string, success?: string) {
    const ok = await run(() => action().then(() => true), failure)
    if (ok) {
      if (success) toast.success(success)
      await load()
    }
    return ok
  }

  return (
    <div className="mx-auto flex max-w-3xl flex-col gap-6">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">People</h1>
          <p className="mt-1 text-muted-foreground">Everyone who can sign in on the phone app or here.</p>
        </div>
        <Button size="lg" className="h-10" onClick={() => setAdding(true)}>
          <UserPlus /> Add person
        </Button>
      </div>

      <Card className="gap-0 divide-y divide-border/60 p-0">
        {users == null
          ? [0, 1].map((i) => (
              <div key={i} className="flex items-center gap-4 p-4">
                <Skeleton className="size-10 rounded-full" />
                <Skeleton className="h-4 w-40" />
              </div>
            ))
          : users.map((u) => {
              const isMe = u.id === me.id
              return (
                <div key={u.id ?? u.username} data-testid={`person-${u.id}`} className="flex items-center gap-4 p-4">
                  <button
                    type="button"
                    onClick={() => setOpened(u.id)}
                    aria-label={`Rooms for ${u.username}`}
                    className="flex min-w-0 flex-1 items-center gap-4 rounded-xl text-left focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
                  >
                    <div className="flex size-10 shrink-0 items-center justify-center rounded-full bg-secondary text-sm font-bold uppercase">
                      {u.username.slice(0, 1)}
                    </div>
                    <div className="min-w-0 flex-1">
                      <div className="flex items-center gap-2">
                        <span className="truncate font-semibold">{u.username}</span>
                        {isMe && <span className="text-sm text-muted-foreground">(you)</span>}
                      </div>
                      <div className="text-xs text-muted-foreground">
                        {roomsSummary(u)}
                        {u.createdAt && ` · added ${new Date(u.createdAt).toLocaleDateString()}`}
                      </div>
                    </div>
                  </button>
                  {u.admin ? (
                    <Badge className="bg-heat-container text-heat-text">
                      <Shield /> Admin
                    </Badge>
                  ) : (
                    <Badge variant="secondary">
                      <UserIcon /> Member
                    </Badge>
                  )}
                  <DropdownMenu>
                    <DropdownMenuTrigger asChild>
                      <Button variant="ghost" size="icon" aria-label={`Options for ${u.username}`}>
                        <MoreHorizontal />
                      </Button>
                    </DropdownMenuTrigger>
                    <DropdownMenuContent align="end">
                      <DropdownMenuItem onSelect={() => setResetting(u)}>
                        <KeyRound /> Reset password
                      </DropdownMenuItem>
                      <DropdownMenuItem
                        onSelect={() =>
                          act(() => api.patchUser(u.id!, { admin: !u.admin }), "Couldn't change admin", u.admin ? `${u.username} is now a member` : `${u.username} is now an admin`)
                        }
                      >
                        {u.admin ? <ShieldOff /> : <Shield />} {u.admin ? 'Remove admin' : 'Make admin'}
                      </DropdownMenuItem>
                      {!isMe && (
                        <>
                          <DropdownMenuSeparator />
                          <DropdownMenuItem variant="destructive" onSelect={() => setRemoving(u)}>
                            <Trash2 /> Remove
                          </DropdownMenuItem>
                        </>
                      )}
                    </DropdownMenuContent>
                  </DropdownMenu>
                </div>
              )
            })}
      </Card>

      <RoomsDialog
        user={users?.find((u) => u.id === opened) ?? null}
        onClose={() => setOpened(null)}
        onSaved={(saved) => setUsers((us) => us && us.map((u) => (u.id === saved.id ? saved : u)))}
      />

      <AddPersonDialog
        open={adding}
        onOpenChange={setAdding}
        onAdd={async (name, password, admin) => {
          if (await act(() => api.createUser(name, password, admin), `Couldn't add ${name}`, `${name} can now sign in`)) setAdding(false)
        }}
      />

      <ResetDialog
        user={resetting}
        onClose={() => setResetting(null)}
        onReset={async (password) => {
          const u = resetting!
          if (await act(() => api.patchUser(u.id!, { password }), "Couldn't reset the password", `${u.username}'s password changed; they're signed out everywhere`)) {
            setResetting(null)
          }
        }}
      />

      <AlertDialog open={removing != null} onOpenChange={(o) => !o && setRemoving(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Remove {removing?.username}?</AlertDialogTitle>
            <AlertDialogDescription>They'll be signed out on every device and won't be able to sign in again.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-white hover:bg-destructive/90"
              onClick={() => removing && act(() => api.deleteUser(removing.id!), `Couldn't remove ${removing.username}`, `${removing.username} removed`)}
            >
              Remove
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

/** The rooms a person can see: a tick box per room for a member, saved on every change. Admins see every room. */
function RoomsDialog({ user, onClose, onSaved }: { user: User | null; onClose: () => void; onSaved: (user: User) => void }) {
  const { rooms, run } = useHome()
  const [saving, setSaving] = useState(false)
  const ids = user?.rooms ?? []

  async function tick(roomId: string, ticked: boolean) {
    if (!user?.id) return
    setSaving(true)
    const saved = await run(() => api.setUserRooms(user.id!, tickRoom(rooms, ids, roomId, ticked)), "Couldn't change their rooms")
    setSaving(false)
    if (saved) onSaved(saved)
  }

  return (
    <Dialog open={user != null} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>{user?.username}'s rooms</DialogTitle>
          <DialogDescription>
            {user?.admin ? 'Sees every room. Admins see and control the whole house.' : 'They see and control only the ticked rooms. Changes save straight away.'}
          </DialogDescription>
        </DialogHeader>
        {user && !user.admin && (
          <div className="flex flex-col gap-1">
            {rooms.length === 0 && <p className="py-2 text-sm text-muted-foreground">There are no rooms yet.</p>}
            {rooms.map((r) => {
              const id = `room-access-${r.id}`
              return (
                <label key={r.id} htmlFor={id} className="flex cursor-pointer items-center gap-3 rounded-xl px-3 py-2.5 hover:bg-secondary">
                  <Checkbox id={id} checked={ids.includes(r.id)} disabled={saving} onCheckedChange={(c) => tick(r.id, c === true)} />
                  <span className="flex-1 font-medium">{r.name}</span>
                </label>
              )
            })}
          </div>
        )}
        <DialogFooter>
          <Button type="button" variant="ghost" onClick={onClose}>
            Done
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )
}

function AddPersonDialog({ open, onOpenChange, onAdd }: { open: boolean; onOpenChange: (o: boolean) => void; onAdd: (name: string, password: string, admin: boolean) => void }) {
  const [name, setName] = useState('')
  const [password, setPassword] = useState('')
  const [admin, setAdmin] = useState(false)
  const [show, setShow] = useState(false)
  useEffect(() => {
    if (open) {
      setName('')
      setPassword('')
      setAdmin(false)
      setShow(false)
    }
  }, [open])
  const nameProblem = usernameProblem(name)
  const passwordProblem = password.length < MIN_PASSWORD ? `${MIN_PASSWORD - password.length} more character${MIN_PASSWORD - password.length === 1 ? '' : 's'} needed (at least ${MIN_PASSWORD})` : null
  const valid = nameProblem == null && passwordProblem == null

  function submit(e: FormEvent) {
    e.preventDefault()
    if (valid) onAdd(name.trim(), password, admin)
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-md">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>Add person</DialogTitle>
            <DialogDescription>Give them the username and password; they can change the password after signing in.</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            <Label htmlFor="new-username">Username</Label>
            <Input id="new-username" autoFocus autoComplete="off" value={name} onChange={(e) => setName(e.target.value)} aria-invalid={name !== '' && nameProblem != null} />
            <Hint problem={name === '' ? null : nameProblem} ok="Letters, numbers, spaces, dots, dashes or underscores." />
          </div>
          <div className="flex flex-col gap-2">
            <Label htmlFor="new-password">Password</Label>
            <Input id="new-password" type={show ? 'text' : 'password'} autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} aria-invalid={password !== '' && passwordProblem != null} />
            <Hint problem={password === '' ? null : passwordProblem} ok={`At least ${MIN_PASSWORD} characters. They can change it later.`} />
            <ShowPassword checked={show} onChange={setShow} />
          </div>
          <label className="flex cursor-pointer items-center gap-4 rounded-xl bg-secondary/60 px-4 py-3">
            <div className="flex-1">
              <div className="font-medium">Admin</div>
              <div className="text-sm text-muted-foreground">Can add and remove people.</div>
            </div>
            <Switch checked={admin} onCheckedChange={setAdmin} />
          </label>
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={!valid}>
              Add person
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function ResetDialog({ user, onClose, onReset }: { user: User | null; onClose: () => void; onReset: (password: string) => void }) {
  const [password, setPassword] = useState('')
  const [show, setShow] = useState(false)
  useEffect(() => {
    setPassword('')
    setShow(false)
  }, [user])
  return (
    <Dialog open={user != null} onOpenChange={(o) => !o && onClose()}>
      <DialogContent className="sm:max-w-sm">
        <form
          onSubmit={(e) => {
            e.preventDefault()
            if (password.length >= MIN_PASSWORD) onReset(password)
          }}
          className="flex flex-col gap-4"
        >
          <DialogHeader>
            <DialogTitle>New password for {user?.username}</DialogTitle>
            <DialogDescription>They'll be signed out everywhere and need this password to sign back in.</DialogDescription>
          </DialogHeader>
          <Input type={show ? 'text' : 'password'} autoFocus autoComplete="new-password" value={password} onChange={(e) => setPassword(e.target.value)} />
          <Hint
            problem={password !== '' && password.length < MIN_PASSWORD ? `${MIN_PASSWORD - password.length} more characters needed` : null}
            ok={`At least ${MIN_PASSWORD} characters.`}
          />
          <ShowPassword checked={show} onChange={setShow} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={password.length < MIN_PASSWORD}>
              Set password
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function usernameProblem(raw: string): string | null {
  const name = raw.trim()
  if (name === '') return 'Enter a username'
  if (name.length > 32) return 'At most 32 characters'
  const bad = [...new Set(name.replace(/[A-Za-z0-9 ._-]/g, ''))]
  if (bad.length) return `Can't use ${bad.map((c) => `"${c}"`).join(' ')} — letters, numbers, spaces, dots, dashes or underscores only`
  return null
}
