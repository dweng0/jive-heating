import { useEffect, useState } from 'react'
import { Bell, Share } from 'lucide-react'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Switch } from '@/components/ui/switch'
import { api, type NotificationPrefs } from '@/lib/api'
import { useHome } from '@/lib/home'
import { applicationServerKey, currentPushSubscription, needsHomeScreen, pushSupported, thisBrowser } from '@/lib/webPush'

/** Where this browser stands: notifications on or off, blocked, impossible here, or waiting for the home screen app. */
type DeviceState = 'loading' | 'on' | 'off' | 'denied' | 'unsupported' | 'homeScreen'

async function deviceState(): Promise<DeviceState> {
  if (needsHomeScreen(thisBrowser())) return 'homeScreen'
  if (!pushSupported()) return 'unsupported'
  if (Notification.permission === 'denied') return 'denied'
  const subscription = await currentPushSubscription()
  if (!subscription || Notification.permission !== 'granted') return 'off'
  // The service may have lost it (or this browser renewed it): send it again on every visit.
  await api.subscribeWebPush(subscription.toJSON()).catch(() => undefined)
  return 'on'
}

/**
 * Notifications on the web app (Web Push): a switch for this browser, and the person's own
 * choices of which notifications they get (shared with their Android phones). Shown only when
 * the service has web push on. Permission is asked for only when the switch is turned on.
 */
export function NotificationsCard() {
  const { run } = useHome()
  const [publicKey, setPublicKey] = useState<string | null>(null)
  const [prefs, setPrefs] = useState<NotificationPrefs | null>(null)
  const [device, setDevice] = useState<DeviceState>('loading')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    let cancelled = false
    api.webPush().then(
      async (info) => {
        if (cancelled || !info.webPush || !info.publicKey) return
        setPublicKey(info.publicKey)
        api.notificationPrefs().then((p) => !cancelled && setPrefs(p), () => undefined)
        const state = await deviceState()
        if (!cancelled) setDevice(state)
      },
      () => undefined,
    )
    return () => {
      cancelled = true
    }
  }, [])

  if (!publicKey) return null

  async function turnOn(key: string) {
    setBusy(true)
    try {
      // Asked straight from the tap: iPhones only show the prompt for a user's own action.
      const permission = await Notification.requestPermission()
      if (permission !== 'granted') {
        setDevice(permission === 'denied' ? 'denied' : 'off')
        return
      }
      const ok = await run(async () => {
        const registration = await navigator.serviceWorker.ready
        const subscription =
          (await registration.pushManager.getSubscription()) ??
          (await registration.pushManager.subscribe({ userVisibleOnly: true, applicationServerKey: applicationServerKey(key) }))
        await api.subscribeWebPush(subscription.toJSON())
        return true
      }, "Couldn't turn on notifications")
      setDevice(ok ? 'on' : 'off')
    } finally {
      setBusy(false)
    }
  }

  async function turnOff() {
    setBusy(true)
    try {
      const ok = await run(async () => {
        const subscription = await currentPushSubscription()
        if (subscription) {
          await api.unsubscribeWebPush(subscription.endpoint)
          await subscription.unsubscribe().catch(() => undefined)
        }
        return true
      }, "Couldn't turn off notifications")
      if (ok) setDevice('off')
    } finally {
      setBusy(false)
    }
  }

  async function choose(change: Partial<NotificationPrefs>) {
    if (!prefs) return
    const previous = prefs
    const next = { ...prefs, ...change }
    setPrefs(next)
    const saved = await run(() => api.setNotificationPrefs(next), "Couldn't save notification settings")
    setPrefs(saved ?? previous)
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle className="flex items-center gap-2">
          <Bell className="size-5 text-muted-foreground" /> Notifications
        </CardTitle>
        <CardDescription>When someone turns the heating on, and when it has finished.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        {device === 'homeScreen' ? (
          <div className="flex gap-3 rounded-lg bg-secondary p-3 text-sm">
            <Share className="mt-0.5 size-4 shrink-0 text-muted-foreground" />
            <p>
              To get notifications on this phone, tap Share, then “Add to Home Screen”, and open Heating from your home screen.
            </p>
          </div>
        ) : device === 'unsupported' ? (
          <p className="text-sm text-muted-foreground">This browser can’t show notifications.</p>
        ) : (
          device !== 'loading' && (
            <div className="flex flex-col gap-2">
              <Toggle
                title="Notifications on this device"
                description="Get them in this browser, as well as on your other phones."
                checked={device === 'on'}
                disabled={busy || device === 'denied'}
                onChange={(on) => (on ? turnOn(publicKey) : turnOff())}
              />
              {device === 'denied' && (
                <p className="text-sm text-destructive">
                  Notifications are blocked here. To get them, allow notifications for Heating in your phone’s settings (or this browser’s site settings).
                </p>
              )}
            </div>
          )
        )}
        {prefs && (
          <>
            <Toggle
              title="Someone turns the heating on"
              description="When someone else, or a thermostat knob, starts heating a room you can see."
              checked={prefs.heatingOn}
              onChange={(heatingOn) => choose({ heatingOn })}
            />
            <Toggle
              title="Heating finished"
              description="When the rooms that were turned on are up to temperature."
              checked={prefs.heatingFinished}
              onChange={(heatingFinished) => choose({ heatingFinished })}
            />
            <Toggle
              title="Boiler turned off or on by an admin"
              description="When an admin forces the boiler off, or lets it heat again."
              checked={prefs.boilerOverride}
              onChange={(boilerOverride) => choose({ boilerOverride })}
            />
          </>
        )}
      </CardContent>
    </Card>
  )
}

function Toggle({
  title,
  description,
  checked,
  disabled,
  onChange,
}: {
  title: string
  description: string
  checked: boolean
  disabled?: boolean
  onChange: (on: boolean) => void
}) {
  return (
    <label className="flex cursor-pointer items-center gap-4">
      <div className="flex-1">
        <div className="font-medium">{title}</div>
        <div className="text-sm text-muted-foreground">{description}</div>
      </div>
      <Switch aria-label={title} checked={checked} disabled={disabled} onCheckedChange={onChange} />
    </label>
  )
}
