import { useCallback, useEffect, useState } from 'react'
import { Check, Loader2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Switch } from '@/components/ui/switch'
import { NotificationsCard } from '@/components/heating/NotificationsCard'
import { ApiError } from '@/lib/api'
import {
  NO_BRIDGE_TEXT,
  bridgeLabel,
  brokerBody,
  brokerFormError,
  fieldFor,
  setupApi,
  type BridgeFound,
  type BrokerField,
  type BrokerForm,
  type BrokerView,
} from '@/lib/setup'

const STEPS = ['Broker', 'Zigbee2MQTT', 'Test notification'] as const
type Step = 0 | 1 | 2

const message = (e: unknown, fallback: string) => (e instanceof ApiError ? e.message : fallback)

/**
 * The connection setup steps (Broker, Zigbee2MQTT, Test notification). Shown instead of the Rooms screen on a new
 * install, and again from Settings > Connection. [onFinish] runs after "Finish" has marked setup complete.
 */
export function ConnectionSetup({ onFinish }: { onFinish: () => void }) {
  const [step, setStep] = useState<Step>(0)

  return (
    <div className="mx-auto max-w-xl space-y-6" data-testid="connection-setup">
      <div>
        <h1 className="text-2xl font-bold tracking-tight">Connect your home</h1>
        <p className="text-muted-foreground">Point the app at your MQTT broker and Zigbee2MQTT. Each step can be tested before it is saved.</p>
      </div>
      <ol className="flex gap-2 text-sm" aria-label="Setup steps">
        {STEPS.map((name, i) => (
          <li
            key={name}
            aria-current={i === step ? 'step' : undefined}
            className={`flex-1 rounded-lg border px-3 py-2 ${i === step ? 'border-heat bg-heat-container text-heat-text font-medium' : 'text-muted-foreground'}`}
          >
            {i + 1}. {name}
          </li>
        ))}
      </ol>
      {step === 0 && <BrokerStep onSaved={() => setStep(1)} />}
      {step === 1 && <ZigbeeStep onBack={() => setStep(0)} onSaved={() => setStep(2)} />}
      {step === 2 && <NotificationStep onBack={() => setStep(1)} onFinish={onFinish} />}
    </div>
  )
}

function Field({ id, label, error, children }: { id: string; label: string; error?: string | null; children: React.ReactNode }) {
  return (
    <div className="space-y-1.5">
      <Label htmlFor={id}>{label}</Label>
      {children}
      {error && (
        <p role="alert" data-testid={`error-${id}`} className="text-sm text-destructive">
          {error}
        </p>
      )}
    </div>
  )
}

function BrokerStep({ onSaved }: { onSaved: () => void }) {
  const [saved, setSaved] = useState<BrokerView | null>(null)
  const [form, setForm] = useState<BrokerForm>({ host: '', port: '1883', tls: false, username: '', password: '', clearPassword: false })
  const [error, setError] = useState<{ field: BrokerField; message: string } | null>(null)
  const [tested, setTested] = useState(false)
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    setupApi.broker().then(
      (b) => {
        setSaved(b)
        setForm({ host: b.host, port: String(b.port), tls: b.tls, username: b.username ?? '', password: '', clearPassword: false })
      },
      () => undefined,
    )
  }, [])

  const change = (patch: Partial<BrokerForm>) => {
    setForm((f) => ({ ...f, ...patch }))
    setTested(false)
    setError(null)
  }

  /** Test with what is typed; true when the broker is fine. A failure is shown beside the field it points to. */
  const test = async (): Promise<boolean> => {
    const bad = brokerFormError(form)
    if (bad) {
      setError(bad)
      return false
    }
    try {
      const result = await setupApi.testBroker(brokerBody(form))
      if (result.ok) {
        setTested(true)
        setError(null)
        return true
      }
      setError({ field: fieldFor(result.reason), message: result.message })
    } catch (e) {
      setError({ field: 'host', message: message(e, "Couldn't test the broker.") })
    }
    return false
  }

  const run = async (then?: () => Promise<void>) => {
    setBusy(true)
    try {
      if (await test()) await then?.()
    } finally {
      setBusy(false)
    }
  }

  const save = () =>
    run(async () => {
      try {
        setSaved(await setupApi.saveBroker(brokerBody(form)))
        onSaved()
      } catch (e) {
        setError({ field: 'host', message: message(e, "Couldn't save the broker.") })
      }
    })

  const err = (field: BrokerField) => (error?.field === field ? error.message : null)

  return (
    <Card>
      <CardHeader>
        <CardTitle>Broker</CardTitle>
        <CardDescription>The MQTT broker Zigbee2MQTT publishes to, such as Mosquitto.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        <Field id="broker-host" label="Host" error={err('host')}>
          <Input id="broker-host" value={form.host} onChange={(e) => change({ host: e.target.value })} placeholder="mosquitto" autoCapitalize="none" />
        </Field>
        <Field id="broker-port" label="Port" error={err('port')}>
          <Input id="broker-port" inputMode="numeric" value={form.port} onChange={(e) => change({ port: e.target.value })} />
        </Field>
        <Field id="broker-tls" label="Secure connection (TLS)" error={err('tls')}>
          <div>
            <Switch id="broker-tls" checked={form.tls} onCheckedChange={(tls) => change({ tls, port: tls && form.port === '1883' ? '8883' : !tls && form.port === '8883' ? '1883' : form.port })} />
          </div>
        </Field>
        <Field id="broker-username" label="Username (leave empty for none)" error={err('username')}>
          <Input id="broker-username" value={form.username} onChange={(e) => change({ username: e.target.value })} autoCapitalize="none" />
        </Field>
        <Field id="broker-password" label="Password" error={err('password')}>
          <Input
            id="broker-password"
            type="password"
            value={form.password}
            disabled={form.clearPassword}
            placeholder={saved?.passwordSet && !form.clearPassword ? 'Saved. Type to change.' : ''}
            onChange={(e) => change({ password: e.target.value })}
            autoComplete="new-password"
          />
          {saved?.passwordSet && (
            <label className="flex items-center gap-2 text-sm text-muted-foreground">
              <input type="checkbox" checked={form.clearPassword} onChange={(e) => change({ clearPassword: e.target.checked, password: '' })} />
              Remove the saved password
            </label>
          )}
        </Field>
        <div className="flex flex-wrap items-center gap-2">
          <Button variant="outline" disabled={busy} onClick={() => run()}>
            {busy && <Loader2 className="animate-spin" />} Test connection
          </Button>
          <Button disabled={busy} onClick={save}>
            Save and continue
          </Button>
          {tested && (
            <span data-testid="broker-ok" className="flex items-center gap-1 text-sm text-heat-text">
              <Check className="size-4" /> Connected
            </span>
          )}
        </div>
      </CardContent>
    </Card>
  )
}

function ZigbeeStep({ onBack, onSaved }: { onBack: () => void; onSaved: () => void }) {
  const [found, setFound] = useState<BridgeFound[] | null>(null)
  const [chosen, setChosen] = useState<string | null>(null)
  const [typed, setTyped] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const look = useCallback(() => {
    setFound(null)
    setupApi.discover().then(
      (list) => {
        setFound(list)
        // One bridge is offered, not chosen for her: with two or more she must pick.
        setChosen(null)
      },
      () => setFound([]),
    )
  }, [])
  useEffect(look, [look])

  const base = typed.trim() !== '' ? typed.trim() : chosen

  async function save() {
    if (!base) return
    setBusy(true)
    setError(null)
    try {
      await setupApi.saveBaseTopic(base)
      onSaved()
    } catch (e) {
      setError(message(e, "Couldn't save the base topic."))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Zigbee2MQTT</CardTitle>
        <CardDescription>Which Zigbee2MQTT on the broker runs your valves and meters.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {found == null && (
          <p className="flex items-center gap-2 text-sm text-muted-foreground">
            <Loader2 className="size-4 animate-spin" /> Looking for Zigbee2MQTT on the broker…
          </p>
        )}
        {found != null && found.length === 0 && (
          <p data-testid="no-bridge" className="text-sm text-muted-foreground">
            {NO_BRIDGE_TEXT}
          </p>
        )}
        {found != null && found.length > 0 && (
          <fieldset className="space-y-2">
            <legend className="mb-1 text-sm font-medium">Found on this broker</legend>
            {found.map((b) => (
              <label key={b.baseTopic} className="flex items-center gap-2 rounded-lg border px-3 py-2 text-sm">
                <input
                  type="radio"
                  name="bridge"
                  checked={chosen === b.baseTopic && typed.trim() === ''}
                  onChange={() => {
                    setChosen(b.baseTopic)
                    setTyped('')
                  }}
                />
                {bridgeLabel(b)}
              </label>
            ))}
          </fieldset>
        )}
        <Field id="base-topic" label="Or type its base topic" error={error}>
          <Input id="base-topic" value={typed} onChange={(e) => setTyped(e.target.value)} placeholder="zigbee2mqtt" autoCapitalize="none" />
        </Field>
        <div className="flex flex-wrap gap-2">
          <Button variant="ghost" onClick={onBack}>
            Back
          </Button>
          <Button variant="outline" onClick={look}>
            Look again
          </Button>
          <Button disabled={!base || busy} onClick={save}>
            Save and continue
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}

function NotificationStep({ onBack, onFinish }: { onBack: () => void; onFinish: () => void }) {
  const [result, setResult] = useState<{ sent: boolean; registered: boolean } | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function send() {
    setBusy(true)
    setError(null)
    try {
      setResult(await setupApi.testNotification())
    } catch (e) {
      setError(message(e, "Couldn't send the test notification."))
    } finally {
      setBusy(false)
    }
  }

  async function finish() {
    setBusy(true)
    try {
      await setupApi.complete()
      onFinish()
    } catch (e) {
      setError(message(e, "Couldn't finish setup."))
    } finally {
      setBusy(false)
    }
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Test notification</CardTitle>
        <CardDescription>Check that your phones and browsers hear from your home.</CardDescription>
      </CardHeader>
      <CardContent className="space-y-4">
        {result?.sent && (
          <p data-testid="test-sent" className="text-sm text-heat-text">
            Sent. Look for “Test notification” on your phones and browsers.
          </p>
        )}
        {result && !result.registered && (
          <div data-testid="no-device" className="space-y-3">
            <p className="text-sm text-muted-foreground">You have no phone or browser registered for notifications yet. Turn them on here, then send the test again.</p>
            <NotificationsCard />
          </div>
        )}
        {error && (
          <p role="alert" className="text-sm text-destructive">
            {error}
          </p>
        )}
        <div className="flex flex-wrap gap-2">
          <Button variant="ghost" onClick={onBack}>
            Back
          </Button>
          <Button variant="outline" disabled={busy} onClick={send}>
            Send test notification
          </Button>
          <Button disabled={busy} onClick={finish}>
            Finish
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
