import { useEffect, useState } from 'react'
import { Plug, Unplug } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { energyApi, formatPower, type EnergyArea, type Meter, meterLabel
} from '@/lib/energy'
import { useHome } from '@/lib/home'

/**
 * Monitored plugs in no room, each with a room picker. A plug that is not a room's own (a shared kettle)
 * is left here; one put in a room leaves the card and lives in that room's settings. Admins only; hidden
 * when every plug is in a room. "Disconnect" stops monitoring one that is not worth listing.
 */

export function MonitoredDevicesCard() {
  const { rooms, run } = useHome()
  const [meters, setMeters] = useState<Meter[]>([])
  const [areas, setAreas] = useState<EnergyArea[]>([])

  useEffect(() => {
    energyApi.meters().then((ms) => setMeters(ms.filter((m) => m.monitored)), () => undefined)
    energyApi.areas().then(setAreas, () => undefined)
  }, [])

  /** Stops monitoring and leaves it alone, so it is neither here nor waiting to be set up; it can be brought back from the ignored devices. */
  async function stopMonitoring(meter: Meter) {
    const done = await run(
      () => energyApi.stopMonitoring(meter.name).then(() => energyApi.ignoreDevice(meter.name)),
      "Couldn't stop monitoring the plug",
    )
    if (done) setMeters((ms) => ms.filter((m) => m.name !== meter.name))
  }

  /** [choice] is a room id, "area:<id>" for an energy area (which takes it out of any room), or "" for neither. */
  async function chooseRoom(meter: Meter, choice: string) {
    if (choice.startsWith('area:')) {
      const area = choice.slice('area:'.length)
      const done = await run(() => energyApi.putInArea(area, meter.name), "Couldn't put the plug in the area")
      if (done) setMeters((ms) => ms.map((m) => (m.name === meter.name ? { ...m, room: null, area } : m)))
      return
    }
    const room = choice
    const done = room
      ? await run(() => energyApi.putInRoom(room, meter.name), "Couldn't put the plug in the room")
      : meter.room && (await run(() => energyApi.takeOutOfRoom(meter.room!, meter.name), "Couldn't take the plug out of the room"))
    if (done) setMeters((ms) => ms.map((m) => (m.name === meter.name ? { ...m, room: room || null } : m)))
  }

  // A plug in an energy area is placed too: it lives on the area's dashboard.
  const unplaced = meters.filter((m) => !m.room && !m.area)
  if (unplaced.length === 0) return null

  return (
    <Card data-testid="monitored-devices">
      <CardHeader>
        <CardTitle>
          <h2>Monitored devices</h2>
        </CardTitle>
        <CardDescription>Part of the house's electricity. Put a plug in a room or an energy area to see its use; leave a shared one in neither.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {unplaced.map((m) => (
          <div key={m.name} data-testid={`monitored-${m.name}`} className="flex flex-wrap items-center gap-3">
            <div className="flex size-9 items-center justify-center rounded-lg bg-secondary [&_svg]:size-4">
              <Plug />
            </div>
            <div className="min-w-40 flex-1">
              <div className="font-medium">{meterLabel(m)}</div>
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
              {areas.length > 0 && (
                <optgroup label="Energy areas">
                  {areas.map((a) => (
                    <option key={a.id} value={`area:${a.id}`}>
                      {a.name}
                    </option>
                  ))}
                </optgroup>
              )}
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
