import { useEffect, useState, type FormEvent } from 'react'
import { Flame, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { ShowPassword } from '@/components/heating/PasswordHelpers'
import { api, type User } from '@/lib/api'
import { MIN_PASSWORD } from '@/lib/format'

/** Sign in, or first-run setup of the admin account when the service has no users yet. */
export function SignInPage({ notice, onSignedIn }: { notice?: string; onSignedIn: (me: User) => void }) {
  const [setup, setSetup] = useState<boolean | null>(null)
  const [username, setUsername] = useState('')
  const [password, setPassword] = useState('')
  const [confirm, setConfirm] = useState('')
  const [serviceToken, setServiceToken] = useState('')
  const [busy, setBusy] = useState(false)
  const [show, setShow] = useState(false)
  const [error, setError] = useState<string | undefined>(notice)

  useEffect(() => {
    api.authStatus().then(
      (s) => setSetup(s.setupRequired),
      (e: Error) => {
        setSetup(false)
        setError(e.message)
      },
    )
  }, [])

  const ready = setup
    ? serviceToken.trim() !== '' && username.trim() !== '' && password.length >= MIN_PASSWORD && password === confirm
    : username.trim() !== '' && password !== ''

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!ready || busy) return
    setBusy(true)
    setError(undefined)
    try {
      const session = setup
        ? await api.setup(serviceToken.trim(), username.trim(), password)
        : await api.login(username.trim(), password)
      onSignedIn(session.user)
    } catch (err) {
      setError((err as Error).message)
      setPassword('')
      setConfirm('')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="flex min-h-svh items-center justify-center bg-[radial-gradient(ellipse_at_top,var(--heat-container),transparent_60%)] p-6">
      <Card className="w-full max-w-sm shadow-xl shadow-heat/5">
        <CardHeader className="items-center text-center">
          <div className="mx-auto mb-2 flex size-12 items-center justify-center rounded-2xl bg-heat text-primary-foreground shadow-lg shadow-heat/30">
            <Flame className="size-6" />
          </div>
          <CardTitle className="text-2xl font-bold tracking-tight">{setup ? 'Set up your heating' : 'Welcome home'}</CardTitle>
          <CardDescription>
            {setup
              ? "No one has an account yet. Create the admin account — you'll need the service token from the server's .env this once."
              : 'Sign in with the same account as the phone app.'}
          </CardDescription>
        </CardHeader>
        <CardContent>
          {setup === null ? (
            <div className="flex justify-center py-8">
              <Loader2 className="size-5 animate-spin text-muted-foreground" />
            </div>
          ) : (
            <form onSubmit={submit} className="flex flex-col gap-4">
              {setup && (
                <Field label="Service token" id="token">
                  <Input id="token" type={show ? 'text' : 'password'} autoComplete="off" value={serviceToken} onChange={(e) => setServiceToken(e.target.value)} />
                </Field>
              )}
              <Field label="Username" id="username">
                <Input id="username" autoComplete="username" autoFocus value={username} onChange={(e) => setUsername(e.target.value)} />
              </Field>
              <Field label="Password" id="password">
                <Input
                  id="password"
                  type={show ? 'text' : 'password'}
                  autoComplete={setup ? 'new-password' : 'current-password'}
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                />
              </Field>
              {setup && (
                <>
                  <Field label="Confirm password" id="confirm">
                    <Input id="confirm" type={show ? 'text' : 'password'} autoComplete="new-password" value={confirm} onChange={(e) => setConfirm(e.target.value)} />
                  </Field>
                  <p className="text-xs text-muted-foreground">At least {MIN_PASSWORD} characters. A few random words work well.</p>
                </>
              )}
              <ShowPassword checked={show} onChange={setShow} label={setup ? 'Show passwords' : 'Show password'} />
              {error && (
                <p role="alert" className="rounded-lg bg-danger px-3 py-2 text-sm text-danger-foreground">
                  {error}
                </p>
              )}
              <Button type="submit" size="lg" className="h-11 text-base" disabled={!ready || busy}>
                {busy && <Loader2 className="animate-spin" />}
                {setup ? 'Create admin account' : 'Sign in'}
              </Button>
            </form>
          )}
        </CardContent>
      </Card>
    </div>
  )
}

function Field({ label, id, children }: { label: string; id: string; children: React.ReactNode }) {
  return (
    <div className="flex flex-col gap-2">
      <Label htmlFor={id}>{label}</Label>
      {children}
    </div>
  )
}
