import { useEffect, useState } from 'react'
import { DoorOpen, Gauge, Heater, Thermometer } from 'lucide-react'
import { toast } from 'sonner'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { api, type Sensor, type Valve } from '@/lib/api'
import {
  energyApi,
  formatPower,
  MAIN_METER_BUTTON,
  MAIN_METER_HINT,
  meterKind,
  MONITOR_BUTTON,
  MONITOR_HINT,
  NOT_NOW,
  ROOM_PICKER_HINT,
  roomPickerTitle,
  type Meter,
} from '@/lib/energy'
import { useHome } from '@/lib/home'

/**
 * Devices paired in Zigbee2MQTT that nothing uses yet: valves and sensors (added from a room) and
 * electricity meters, which can be made the house meter or monitored here. A plug just monitored is
 * offered a room straight away. Admins only; hidden when nothing waits.
 */
export function WaitingDevicesCard({ onMonitored }: { onMonitored?: () => void }) {
  const { rooms, run } = useHome()
  const [valves, setValves] = useState<Valve[]>([])
  const [sensors, setSensors] = useState<Sensor[]>([])
  const [meters, setMeters] = useState<Meter[]>([])
  /** The plug just monitored, while its room is being picked. */
  const [placing, setPlacing] = useState<string | null>(null)

  useEffect(() => {
    // Each list is optional: an older service without meters still shows valves and sensors.
    api.valves(true).then(setValves, () => undefined)
    api.sensors(true).then(setSensors, () => undefined)
    energyApi.meters(true).then(setMeters, () => undefined)
  }, [])

  async function chooseForHouse(meter: string) {
    const settings = await run(() => energyApi.useForHouse(meter), "Couldn't choose the meter")
    if (settings) {
      setMeters((ms) => ms.filter((m) => m.name !== meter))
      toast.success(`${meter} now measures the house's electricity`)
    }
  }

  async function monitor(meter: string) {
    const monitored = await run(() => energyApi.monitor(meter), "Couldn't monitor the device")
    if (monitored) {
      setMeters((ms) => ms.filter((m) => m.name !== meter))
      toast.success(`Monitoring ${meter}`)
      onMonitored?.()
      setPlacing(meter)
    }
  }

  /** Puts [meter] in [room]; if that fails it stays monitored, in no room, and the error is shown. */
  async function putInRoom(meter: string, room: string) {
    setPlacing(null)
    await run(() => energyApi.putInRoom(room, meter), "Couldn't put the plug in the room")
    onMonitored?.()
  }

  async function ignore(name: string) {
    const done = await run(() => energyApi.ignoreDevice(name), "Couldn't ignore the device")
    if (done) {
      setValves((vs) => vs.filter((v) => v.name !== name))
      setSensors((ss) => ss.filter((s) => s.name !== name))
      setMeters((ms) => ms.filter((m) => m.name !== name))
    }
  }

  const picker = (
    <Dialog open={placing !== null} onOpenChange={(open) => !open && setPlacing(null)}>
      <DialogContent className="sm:max-w-sm">
        <DialogHeader>
          <DialogTitle>{placing && roomPickerTitle(placing)}</DialogTitle>
          <DialogDescription>{ROOM_PICKER_HINT}</DialogDescription>
        </DialogHeader>
        <div className="flex flex-col gap-1">
          {rooms.map((r) => (
            <button
              key={r.id}
              onClick={() => placing && putInRoom(placing, r.id)}
              className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-left hover:bg-secondary focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
            >
              <DoorOpen className="size-4 shrink-0 text-muted-foreground" />
              <span className="min-w-0 flex-1 truncate font-medium">{r.name}</span>
            </button>
          ))}
        </div>
        <DialogFooter>
          <Button variant="outline" onClick={() => setPlacing(null)}>
            {NOT_NOW}
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  )

  if (valves.length + sensors.length + meters.length === 0) return picker

  return (
    <Card data-testid="waiting-devices">
      <CardHeader>
        <CardTitle>
          <h2>Waiting to be set up</h2>
        </CardTitle>
        <CardDescription>New in Zigbee2MQTT. Add valves and sensors from a room; choose an electricity meter for the house or monitor a plug.</CardDescription>
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {meters.map((m) => (
          <div key={m.name} data-testid={`meter-${m.name}`} className="flex flex-wrap items-center gap-3">
            <div className="flex size-9 items-center justify-center rounded-lg bg-secondary [&_svg]:size-4">
              <Gauge />
            </div>
            <div className="min-w-40 flex-1">
              <div className="font-medium">{m.name}</div>
              <div className="text-sm text-muted-foreground">
                {meterKind(m)} · {m.stale || !m.online ? 'not reporting' : `${formatPower(m.power)} now`}
              </div>
            </div>
            <div className="flex flex-col items-start gap-1">
              <Button variant="outline" onClick={() => chooseForHouse(m.name)}>
                {MAIN_METER_BUTTON}
              </Button>
              <span className="text-xs text-muted-foreground">{MAIN_METER_HINT}</span>
            </div>
            <div className="flex flex-col items-start gap-1">
              <Button variant="outline" onClick={() => monitor(m.name)}>
                {MONITOR_BUTTON}
              </Button>
              <span className="text-xs text-muted-foreground">{MONITOR_HINT}</span>
            </div>
            <IgnoreButton name={m.name} onIgnore={ignore} />
          </div>
        ))}
        {valves.map((v) => (
          <Device key={`valve-${v.name}`} icon={<Heater />} name={v.name} kind="Radiator valve" onIgnore={ignore} />
        ))}
        {sensors.map((s) => (
          <Device key={`sensor-${s.name}`} icon={<Thermometer />} name={s.name} kind="Temperature sensor" onIgnore={ignore} />
        ))}
      </CardContent>
      {picker}
    </Card>
  )
}

function IgnoreButton({ name, onIgnore }: { name: string; onIgnore: (name: string) => void }) {
  return (
    <Button variant="ghost" aria-label={`Ignore ${name}`} onClick={() => onIgnore(name)}>
      Ignore
    </Button>
  )
}

function Device({ icon, name, kind, onIgnore }: { icon: React.ReactNode; name: string; kind: string; onIgnore: (name: string) => void }) {
  return (
    <div className="flex items-center gap-3">
      <div className="flex size-9 items-center justify-center rounded-lg bg-secondary [&_svg]:size-4">{icon}</div>
      <div className="flex-1">
        <div className="font-medium">{name}</div>
        <div className="text-sm text-muted-foreground">{kind} · add it from a room</div>
      </div>
      <IgnoreButton name={name} onIgnore={onIgnore} />
    </div>
  )
}
