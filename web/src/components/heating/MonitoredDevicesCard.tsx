import { useEffect, useState } from 'react'
import { Plug, Unplug } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { energyApi, formatPower, type Meter } from '@/lib/energy'
import { useHome } from '@/lib/home'

/**
 * Monitored plugs in no room, each with a room picker. A plug that is not a room's own (a shared kettle)
 * is left here; one put in a room leaves the card and lives in that room's settings. Admins only; hidden
 * when every plug is in a room. "Disconnect" stops monitoring one that is not worth listing.
 */

export function MonitoredDevicesCard() {
  const { rooms, run } = useHome()
  const [meters, setMeters] = useState<Meter[]>([])

  useEffect(() => {
    energyApi.meters().then((ms) => setMeters(ms.filter((m) => m.monitored)), () => undefined)
  }, [])

  /** Stops monitoring and leaves it alone, so it is neither here nor waiting to be set up; it can be brought back from the ignored devices. */
  async function stopMonitoring(meter: Meter) {
    const done = await run(
      () => energyApi.stopMonitoring(meter.name).then(() => energyApi.ignoreDevice(meter.name)),
      "Couldn't stop monitoring the plug",
    )
    if (done) setMeters((ms) => ms.filter((m) => m.name !== meter.name))
  }

  async function chooseRoom(meter: Meter, room: string) {
    const done = room
      ? await run(() => energyApi.putInRoom(room, meter.name), "Couldn't put the plug in the room")
      : meter.room && (await run(() => energyApi.takeOutOfRoom(meter.room!, meter.name), "Couldn't take the plug out of the room"))
    if (done) setMeters((ms) => ms.map((m) => (m.name === meter.name ? { ...m, room: room || null } : m)))
  }

  const unplaced = meters.filter((m) => !m.room)
  if (unplaced.length === 0) return null

  return (
    <Card data-testid="monitored-devices">
      <CardHeader>
        <CardTitle>
          <h2>Monitored devices</h2>
        </CardTitle>
        <CardDescription>Part of the house's electricity. Put a plug in a room to see that room's use; leave a shared one in no room.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {unplaced.map((m) => (
          <div key={m.name} data-testid={`monitored-${m.name}`} className="flex flex-wrap items-center gap-3">
            <div className="flex size-9 items-center justify-center rounded-lg bg-secondary [&_svg]:size-4">
              <Plug />
            </div>
            <div className="min-w-40 flex-1">
              <div className="font-medium">{m.name}</div>
              <div className="text-sm text-muted-foreground">{m.stale || !m.online ? 'not reporting' : `${formatPower(m.power)} now`}</div>
            </div>
            <select
              aria-label={`Room for ${m.name}`}
              value={m.room ?? ''}
              onChange={(e) => chooseRoom(m, e.target.value)}
              className="h-9 rounded-md border border-input bg-background px-3 text-sm"
            >
              <option value="">No room</option>
              {rooms.map((r) => (
                <option key={r.id} value={r.id}>
                  {r.name}
                </option>
              ))}
            </select>
            <Button variant="outline" onClick={() => stopMonitoring(m)}>
              <Unplug />
              Disconnect
            </Button>
          </div>
        ))}
      </CardContent>
    </Card>
  )
}
