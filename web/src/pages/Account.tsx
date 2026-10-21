import { useState, type FormEvent } from 'react'
import { KeyRound, LogOut, MonitorSmartphone } from 'lucide-react'
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
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { AndroidAppCard } from '@/components/heating/AndroidAppCard'
import { NotificationsCard } from '@/components/heating/NotificationsCard'
import { BoostSettingsCard } from '@/components/heating/BoostSettingsCard'
import { EnergyPricesCard } from '@/components/heating/EnergyPricesCard'
import { IgnoredDevicesCard } from '@/components/heating/IgnoredDevicesCard'
import { ShowPassword } from '@/components/heating/PasswordHelpers'
import { api } from '@/lib/api'
import { MIN_PASSWORD } from '@/lib/format'
import { useHome } from '@/lib/home'

export function AccountPage({ onSignOut, onSignedOutEverywhere }: { onSignOut: () => void; onSignedOutEverywhere: () => void }) {
  const { me, run } = useHome()
  const [current, setCurrent] = useState('')
  const [next, setNext] = useState('')
  const [confirm, setConfirm] = useState('')
  const [everywhere, setEverywhere] = useState(false)
  const [show, setShow] = useState(false)
  const isService = me.id == null
  const valid = current !== '' && next.length >= MIN_PASSWORD && next === confirm

  async function change(e: FormEvent) {
    e.preventDefault()
    if (!valid) return
    // The server ends every other session and sets a fresh cookie for this browser.
    const ok = await run(() => api.changePassword(current, next).then(() => true), "Couldn't change password")
    if (ok) {
      setCurrent('')
      setNext('')
      setConfirm('')
      toast.success('Password changed. Your other devices are signed out.')
    }
  }

  async function signOutEverywhere() {
    const ok = await run(() => api.logoutAll().then(() => true), "Couldn't sign out everywhere")
    if (ok) {
      await api.logout().catch(() => undefined)
      onSignedOutEverywhere()
    }
  }

  return (
    <div className="mx-auto flex max-w-2xl flex-col gap-6">
      <div className="flex items-center gap-3">
        <h1 className="text-3xl font-bold tracking-tight">{me.username}</h1>
        <Badge variant="secondary">{me.admin ? 'Admin' : 'Member'}</Badge>
      </div>

      {!isService && (
        <Card>
          <CardHeader>
            <CardTitle className="flex items-center gap-2">
              <KeyRound className="size-5 text-muted-foreground" /> Change password
            </CardTitle>
            <CardDescription>You stay signed in here; every other device is signed out.</CardDescription>
          </CardHeader>
          <CardContent>
            <form onSubmit={change} className="flex flex-col gap-4">
              <Field id="current" label="Current password">
                <Input id="current" type={show ? 'text' : 'password'} autoComplete="current-password" value={current} onChange={(e) => setCurrent(e.target.value)} />
              </Field>
              <Field id="new" label="New password">
                <Input id="new" type={show ? 'text' : 'password'} autoComplete="new-password" value={next} onChange={(e) => setNext(e.target.value)} />
              </Field>
              <Field id="confirm" label="Confirm new password">
                <Input id="confirm" type={show ? 'text' : 'password'} autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
              </Field>
              <ShowPassword checked={show} onChange={setShow} label="Show passwords" />
              <div className="flex items-center gap-3">
                <p className="text-xs text-muted-foreground">At least {MIN_PASSWORD} characters.</p>
                <Button type="submit" className="ml-auto" disabled={!valid}>
                  Change password
                </Button>
              </div>
            </form>
          </CardContent>
        </Card>
      )}

      {!isService && <NotificationsCard />}

      <AndroidAppCard />

      {me.admin && <BoostSettingsCard />}
      {me.admin && <EnergyPricesCard />}

      {me.admin && <IgnoredDevicesCard />}

      <Card>
        <CardHeader>
          <CardTitle>Sessions</CardTitle>
        </CardHeader>
        <CardContent className="flex flex-col gap-3">
          <Row icon={<LogOut />} title="Sign out" description="Sign out of this browser." action={<Button variant="outline" onClick={onSignOut}>Sign out</Button>} />
          {!isService && (
            <Row
              icon={<MonitorSmartphone />}
              title="Sign out everywhere"
              description="Phones and browsers using your account will need to sign in again."
              action={
                <Button variant="destructive" onClick={() => setEverywhere(true)}>
                  Sign out everywhere
                </Button>
              }
            />
          )}
        </CardContent>
      </Card>

      <AlertDialog open={everywhere} onOpenChange={setEverywhere}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Sign out everywhere?</AlertDialogTitle>
            <AlertDialogDescription>You'll be signed out here and on every other device using your account.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={signOutEverywhere}>Sign out everywhere</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}

function Field({ id, label, children }: { id: string; label: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{label}</Label>
      {children}
    </div>
  )
}

function Row({ icon, title, description, action }: { icon: React.ReactNode; title: string; description: string; action: React.ReactNode }) {
  return (
    <div className="flex flex-wrap items-center gap-4">
      <div className="flex size-9 items-center justify-center rounded-lg bg-secondary [&_svg]:size-4">{icon}</div>
      <div className="min-w-48 flex-1">
        <div className="font-medium">{title}</div>
        <div className="text-sm text-muted-foreground">{description}</div>
      </div>
      {action}
    </div>
  )
}
