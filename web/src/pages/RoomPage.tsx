import { useEffect, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, BatteryLow, Gauge, Lock, Pencil, Plug, Plus, RefreshCw, Thermometer, Trash2, TriangleAlert, Unplug, WifiOff } from 'lucide-react'
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
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Separator } from '@/components/ui/separator'
import { Skeleton } from '@/components/ui/skeleton'
import { Switch } from '@/components/ui/switch'
import { SENSOR_OFFLINE, SensorInfo, SensorOfflineBadge } from '@/components/heating/RoomSensor'
import { IncompleteWarning } from '@/components/heating/IncompleteWarning'
import { NoHistory, RangePicker, RoomEnergyChart, RoomHistoryChart } from '@/components/heating/HistoryChart'
import { useHistory } from '@/lib/history'
import { ScheduleEditor } from '@/components/heating/ScheduleEditor'
import { ZonePanel } from '@/components/heating/ZonePanel'
import { api, type HistoryRange, type Room, type Sensor, type Valve } from '@/lib/api'
import { formatTemp, measuredBySensor, sensorOffline, seriesColor } from '@/lib/format'
import { useHome } from '@/lib/home'
import { energyApi, formatCost, type Meter } from '@/lib/energy'
import { plugLine, plugWarning, roomPlugsApi, roomWarning, type RoomPlugs, type RoomEnergyHistory } from '@/lib/roomPlugs'

export function RoomPage() {
  const { id } = useParams()
  const { rooms, loading } = useHome()
  const room = rooms.find((r) => r.id === id)

  if (!room) {
    return loading ? null : (
      <div className="flex flex-col items-center gap-4 py-24 text-center">
        <p className="text-lg font-semibold">That room doesn't exist any more.</p>
        <Button asChild variant="outline">
          <Link to="/">Back to rooms</Link>
        </Button>
      </div>
    )
  }
  return <RoomDetail room={room} />
}

function RoomDetail({ room }: { room: Room }) {
  // Members control the room (mode, target, boost, schedule); setting it up is for admins.
  const { me } = useHome()
  const [renaming, setRenaming] = useState(false)
  const independent = room.control === 'independent'
  const sensorInfo = measuredBySensor(room) && room.sensor ? <SensorInfo sensor={room.sensor} /> : undefined
  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center gap-3">
        <Button asChild variant="ghost" size="icon-lg" aria-label="Back to rooms">
          <Link to="/">
            <ArrowLeft />
          </Link>
        </Button>
        <h1 className="truncate text-3xl font-bold tracking-tight">{room.name}</h1>
        {me.admin && (
          <Button variant="ghost" size="icon" onClick={() => setRenaming(true)} aria-label="Rename room">
            <Pencil />
          </Button>
        )}
      </div>

      {sensorOffline(room) && <SensorOfflineBadge className="-mt-3 self-start" />}

      {room.notices.map((n) => (
        <div key={n} className="flex items-center gap-3 rounded-xl bg-heat-container px-4 py-3 text-sm text-heat-text">
          <TriangleAlert className="size-4 shrink-0" /> {n}
        </div>
      ))}

      {room.zones.length === 0 ? (
        <Card className="border-dashed py-12 text-center text-muted-foreground">
          {me.admin ? 'Add a valve below to start heating this room.' : 'This room has no valves yet.'}
        </Card>
      ) : (
        <div className="grid gap-6 lg:grid-cols-[minmax(0,26rem)_1fr]">
          <div className="flex min-w-0 flex-col gap-6">
            {room.zones.map((z) => (
              <ZonePanel key={z.id} zone={z} title={independent ? z.valves.join(', ') : undefined} currentInfo={sensorInfo} />
            ))}
          </div>
          <div className="flex min-w-0 flex-col gap-6">
            {room.zones.map((z) => (
              <div key={z.id} className="flex flex-col gap-2">
                {independent && <h3 className="px-1 text-sm font-semibold text-muted-foreground">{z.valves.join(', ')}</h3>}
                <ScheduleEditor zoneId={z.id} />
              </div>
            ))}
          </div>
        </div>
      )}

      {/* The service gives members only their own rooms' history, without the boiler. */}
      {room.zones.length > 0 && <HistoryCard room={room} />}
      {me.admin && <ElectricityCard room={room} />}

      {me.admin && (
        <div className="grid gap-6 lg:grid-cols-2">
          <div className="flex min-w-0 flex-col gap-6">
            <ValvesCard room={room} />
            <SensorCard room={room} />
            <PlugsCard room={room} />
          </div>
          <SettingsCard room={room} />
        </div>
      )}

      {me.admin && <RenameDialog room={room} open={renaming} onOpenChange={setRenaming} />}
    </div>
  )
}

/**
 * The room's plugs' electricity use as bars, under its heating history. Admin only; hidden for a room with no plug use,
 * but once shown a range with none says so, so the range can be changed back.
 */
function ElectricityCard({ room }: { room: Room }) {
  const [range, setRange] = useState<HistoryRange>('24h')
  const [history, setHistory] = useState<RoomEnergyHistory | null>(null)
  const [shown, setShown] = useState(false)
  useEffect(() => {
    let cancelled = false
    const load = () =>
      roomPlugsApi.history(room.id, range).then((h) => {
        if (cancelled) return
        setHistory(h)
        if (h.points.length > 0) setShown(true)
      }, () => undefined)
    load()
    // Plugs are recorded every five minutes.
    const timer = window.setInterval(load, 5 * 60_000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [room.id, range])
  // Hidden while it first loads too, so a room without plugs never flashes the card.
  if (!shown || !history) return null
  return (
    <Card data-testid="room-electricity">
      <CardHeader className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-semibold">Electricity</h2>
        <RangePicker range={range} onChange={setRange} />
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {history.points.length > 0 ? <RoomEnergyChart history={history} range={range} /> : <p className="py-8 text-center text-muted-foreground">No plug use in this time</p>}
        {history.points.some((p) => p.incomplete) && (
          <span className="flex items-center gap-2 text-sm text-muted-foreground">
            <span className="size-3 rounded-sm" style={{ background: seriesColor(0), opacity: 0.4 }} /> Some readings missing
          </span>
        )}
      </CardContent>
    </Card>
  )
}

/** The room's temperature and target over time, under its controls. */
function HistoryCard({ room }: { room: Room }) {
  const [range, setRange] = useState<HistoryRange>('24h')
  const { history, failed } = useHistory(range)
  const empty = history != null && !history.rooms.some((r) => r.id === room.id && r.points.length > 0)
  return (
    <Card>
      <CardHeader className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-lg font-semibold">History</h2>
        <RangePicker range={range} onChange={setRange} />
      </CardHeader>
      <CardContent className="flex flex-col gap-3">
        {failed && !history && <p className="text-muted-foreground">Couldn't load the history. It will try again in a few minutes.</p>}
        {!history && !failed && <Skeleton className="h-60 w-full rounded-xl" />}
        {history && empty && <NoHistory className="py-8" />}
        {history && !empty && (
          <>
            <RoomHistoryChart history={history} roomId={room.id} range={range} />
            <div className="flex flex-wrap gap-x-4 gap-y-2 text-sm">
              <span className="flex items-center gap-2 font-semibold">
                <span className="h-[3px] w-5 rounded-full" style={{ background: seriesColor(0) }} /> Temperature
              </span>
              <span className="flex items-center gap-2 text-muted-foreground">
                <span className="w-5 border-t-2 border-dashed border-muted-foreground" /> Target
              </span>
              {history.boiler.length > 0 && (
                <span className="flex items-center gap-2 text-muted-foreground">
                  <span className="size-3 rounded-sm bg-heat/40" /> Boiler heating
                </span>
              )}
            </div>
          </>
        )}
      </CardContent>
    </Card>
  )
}

function ValvesCard({ room }: { room: Room }) {
  const { run, putRoom } = useHome()
  const [valves, setValves] = useState<Valve[]>([])
  const [adding, setAdding] = useState(false)

  useEffect(() => {
    api.valves().then(setValves, () => undefined)
  }, [room])

  const inRoom = valves.filter((v) => room.valves.includes(v.name))
  const unassigned = valves.filter((v) => v.room == null)

  async function assign(name: string) {
    const r = await run(() => api.assignValve(room.id, name), "Couldn't add the valve")
    if (r) putRoom(r)
    setAdding(false)
  }

  async function remove(name: string) {
    const r = await run(() => api.removeValve(room.id, name), "Couldn't remove the valve")
    if (r) putRoom(r)
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-4">
        <div>
          <CardTitle>Valves</CardTitle>
          <CardDescription>Radiator valves in this room.</CardDescription>
        </div>
        <Button variant="outline" size="sm" onClick={() => setAdding(true)} disabled={unassigned.length === 0}>
          <Plus /> Add valve
        </Button>
      </CardHeader>
      <CardContent className="flex flex-col gap-1">
        {room.valves.length === 0 && <p className="py-4 text-sm text-muted-foreground">No valves yet.</p>}
        {room.valves.map((name) => {
          const v = inRoom.find((x) => x.name === name)
          return (
            <div key={name} className="flex items-center gap-3 rounded-xl px-2 py-2.5 hover:bg-secondary/50">
              <div className="flex size-9 items-center justify-center rounded-lg bg-secondary">
                <Thermometer className="size-4" />
              </div>
              <div className="min-w-0 flex-1">
                <div className="truncate font-medium">{name}</div>
                <div className="flex flex-wrap items-center gap-x-3 text-xs text-muted-foreground">
                  {v ? (
                    <>
                      <span className="tabular-nums">{formatTemp(v.currentTemperature)} now</span>
                      <span>{v.runningState === 'heat' ? 'open' : 'idle'}</span>
                      {v.battery != null && <span>battery {v.battery}%</span>}
                    </>
                  ) : (
                    <span>loading…</span>
                  )}
                </div>
              </div>
              {v && !v.online && (
                <Badge variant="secondary" className="bg-danger text-danger-foreground">
                  <WifiOff /> Offline
                </Badge>
              )}
              {v?.lowBattery && (
                <Badge variant="secondary" className="bg-heat-container text-heat-text">
                  <BatteryLow /> Low
                </Badge>
              )}
              {v?.childLock && <Lock className="size-4 text-muted-foreground" aria-label="Child lock on" />}
              <Button variant="ghost" size="icon" onClick={() => remove(name)} aria-label={`Remove ${name}`}>
                <Unplug />
              </Button>
            </div>
          )
        })}
      </CardContent>

      <Dialog open={adding} onOpenChange={setAdding}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>Add a valve to {room.name}</DialogTitle>
          </DialogHeader>
          <div className="flex flex-col gap-1">
            {unassigned.map((v) => (
              <button key={v.name} onClick={() => assign(v.name)} className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-left hover:bg-secondary">
                <Thermometer className="size-4 text-muted-foreground" />
                <span className="flex-1 font-medium">{v.name}</span>
                <span className="text-sm text-muted-foreground tabular-nums">{formatTemp(v.currentTemperature)}</span>
              </button>
            ))}
          </div>
        </DialogContent>
      </Dialog>
    </Card>
  )
}

/** The room's temperature sensor: added and removed like a valve, but at most one; picking another replaces it. */
function SensorCard({ room }: { room: Room }) {
  const { run, putRoom } = useHome()
  const [sensors, setSensors] = useState<Sensor[]>([])
  const [picking, setPicking] = useState(false)

  useEffect(() => {
    api.sensors().then(setSensors, () => undefined)
  }, [room])

  const current = room.sensor ? sensors.find((s) => s.name === room.sensor) : undefined
  const unassigned = sensors.filter((s) => s.room == null)
  const offline = sensorOffline(room)

  function openPicker() {
    api.sensors().then(setSensors, () => undefined)
    setPicking(true)
  }

  async function assign(name: string) {
    const r = await run(() => api.assignSensor(room.id, name), "Couldn't add the sensor")
    if (r) putRoom(r)
    setPicking(false)
  }

  async function remove(name: string) {
    const r = await run(() => api.removeSensor(room.id, name), "Couldn't remove the sensor")
    if (r) putRoom(r)
  }

  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-4">
        <div>
          <CardTitle>Temperature sensor</CardTitle>
          <CardDescription>Measures the room instead of the valves’ own sensors.</CardDescription>
        </div>
        <Button variant="outline" size="sm" onClick={openPicker} disabled={unassigned.length === 0}>
          {room.sensor ? <RefreshCw /> : <Plus />} {room.sensor ? 'Change sensor' : 'Add sensor'}
        </Button>
      </CardHeader>
      <CardContent className="flex flex-col gap-1">
        {!room.sensor && <p className="py-4 text-sm text-muted-foreground">No sensor. The valves measure the room.</p>}
        {room.sensor && (
          <div className="flex items-center gap-3 rounded-xl px-2 py-2.5 hover:bg-secondary/50">
            <div className="flex size-9 items-center justify-center rounded-lg bg-secondary">
              <Gauge className="size-4" />
            </div>
            <div className="min-w-0 flex-1">
              <div className="truncate font-medium">{room.sensor}</div>
              <div className="flex flex-wrap items-center gap-x-3 text-xs text-muted-foreground">
                {current ? (
                  <>
                    <span className="tabular-nums">{formatTemp(current.temperature)} now</span>
                    {current.battery != null && <span>battery {current.battery}%</span>}
                  </>
                ) : (
                  <span>loading…</span>
                )}
              </div>
            </div>
            {current && !current.online && (
              <Badge variant="secondary" className="bg-danger text-danger-foreground">
                <WifiOff /> Offline
              </Badge>
            )}
            {current?.lowBattery && (
              <Badge variant="secondary" className="bg-heat-container text-heat-text">
                <BatteryLow /> Low battery
              </Badge>
            )}
            <Button variant="ghost" size="icon" onClick={() => remove(room.sensor!)} aria-label={`Remove ${room.sensor}`}>
              <Unplug />
            </Button>
          </div>
        )}
        {offline && (
          <div role="status" className="mt-1 flex items-start gap-3 rounded-xl bg-danger px-3 py-2.5 text-sm text-danger-foreground">
            <WifiOff className="mt-0.5 size-4 shrink-0" />
            <div>
              <div className="font-semibold">{SENSOR_OFFLINE}</div>
              <div>The room switches back to {room.sensor} as soon as it reports again.</div>
            </div>
          </div>
        )}
      </CardContent>

      <Dialog open={picking} onOpenChange={setPicking}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>{room.sensor ? `Replace ${room.sensor}` : `Add a sensor to ${room.name}`}</DialogTitle>
            <DialogDescription>
              {room.sensor
                ? `${room.name} has one sensor. Picking another replaces ${room.sensor}, which becomes unassigned.`
                : 'The sensor measures the room; its valves heat to its reading.'}
            </DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-1">
            {unassigned.length === 0 && <p className="py-2 text-sm text-muted-foreground">No other sensors are free.</p>}
            {unassigned.map((s) => (
              <button
                key={s.name}
                onClick={() => assign(s.name)}
                className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-left hover:bg-secondary focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
              >
                <Gauge className="size-4 shrink-0 text-muted-foreground" />
                <span className="min-w-0 flex-1">
                  <span className="block truncate font-medium">{s.name}</span>
                  <span className="flex flex-wrap gap-x-2 text-xs text-muted-foreground">
                    {s.battery != null && <span>battery {s.battery}%</span>}
                    {s.lowBattery && <span className="text-heat-text">low battery</span>}
                    {!s.online && <span className="text-danger-foreground">offline</span>}
                  </span>
                </span>
                <span className="text-sm text-muted-foreground tabular-nums">{formatTemp(s.temperature)}</span>
              </button>
            ))}
          </div>
        </DialogContent>
      </Dialog>
    </Card>
  )
}

/** The room's monitored plugs: power now and cost today, the room's cost today above them. Admin only. */
function PlugsCard({ room }: { room: Room }) {
  const { run } = useHome()
  const [plugs, setPlugs] = useState<RoomPlugs | null>(null)
  const [meters, setMeters] = useState<Meter[]>([])
  const [adding, setAdding] = useState(false)

  function load() {
    roomPlugsApi.get(room.id).then(setPlugs, () => undefined)
  }

  useEffect(load, [room.id])

  const free = meters.filter((m) => m.monitored && m.room !== room.id)

  function openPicker() {
    energyApi.meters().then(setMeters, () => undefined)
    setAdding(true)
  }

  async function add(name: string) {
    setAdding(false)
    if (await run(() => energyApi.putInRoom(room.id, name), "Couldn't add the plug")) load()
  }

  async function remove(name: string) {
    if (await run(() => energyApi.takeOutOfRoom(room.id, name), "Couldn't take the plug out")) load()
  }

  return (
    <Card data-testid="room-plugs">
      <CardHeader className="flex flex-row items-start justify-between gap-4">
        <div>
          <CardTitle>Plugs</CardTitle>
          <CardDescription>Monitored plugs in this room. Their use is part of the main meter’s.</CardDescription>
        </div>
        <Button variant="outline" size="sm" onClick={openPicker}>
          <Plus /> Add plug
        </Button>
      </CardHeader>
      <CardContent className="flex flex-col gap-1">
        {plugs && plugs.plugs.length === 0 && <p className="py-4 text-sm text-muted-foreground">No plugs yet.</p>}
        {plugs && plugs.plugs.length > 0 && (
          <div data-testid="room-plugs-total" className="flex items-center gap-2 px-2 pb-2 text-sm">
            <span className="font-medium tabular-nums">Today {formatCost(plugs.cost, plugs.currency)}</span>
            {plugs.incomplete && <IncompleteWarning missingMinutes={plugs.missingMinutes} message={roomWarning(plugs.plugs)} />}
          </div>
        )}
        {plugs?.plugs.map((p) => (
          <div key={p.meter} data-testid={`room-plug-${p.meter}`} className="flex items-center gap-3 rounded-xl px-2 py-2.5 hover:bg-secondary/50">
            <div className="flex size-9 items-center justify-center rounded-lg bg-secondary">
              <Plug className="size-4" />
            </div>
            <div className="min-w-0 flex-1">
              <div className="truncate font-medium">{p.meter}</div>
              <div className="flex items-center gap-2 text-xs text-muted-foreground">
                <span className="tabular-nums">{plugLine(p, plugs.currency)}</span>
                {p.incomplete && <IncompleteWarning missingMinutes={p.missingMinutes} message={plugWarning(p)} />}
              </div>
            </div>
            <Button variant="ghost" size="icon" onClick={() => remove(p.meter)} aria-label={`Take ${p.meter} out of ${room.name}`}>
              <Unplug />
            </Button>
          </div>
        ))}
      </CardContent>

      <Dialog open={adding} onOpenChange={setAdding}>
        <DialogContent className="sm:max-w-sm">
          <DialogHeader>
            <DialogTitle>Add a plug to {room.name}</DialogTitle>
            <DialogDescription>Monitored plugs. One in another room moves here.</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-1">
            {free.length === 0 && <p className="py-2 text-sm text-muted-foreground">No other monitored plugs. Monitor one from the Rooms page.</p>}
            {free.map((m) => (
              <button key={m.name} onClick={() => add(m.name)} className="flex items-center gap-3 rounded-xl px-3 py-2.5 text-left hover:bg-secondary focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none">
                <Plug className="size-4 shrink-0 text-muted-foreground" />
                <span className="min-w-0 flex-1 truncate font-medium">{m.name}</span>
              </button>
            ))}
          </div>
        </DialogContent>
      </Dialog>
    </Card>
  )
}

function SettingsCard({ room }: { room: Room }) {
  const { run, putRoom } = useHome()
  const navigate = useNavigate()
  const [deleting, setDeleting] = useState(false)
  const canSplit = room.valves.length > 1

  async function patch(p: Parameters<typeof api.patchRoom>[1]) {
    const r = await run(() => api.patchRoom(room.id, p), "Couldn't change the room")
    if (r) putRoom(r)
  }

  async function remove() {
    const ok = await run(() => api.deleteRoom(room.id).then(() => true), "Couldn't delete the room")
    if (ok) navigate('/')
  }

  return (
    <Card>
      <CardHeader>
        <CardTitle>Room settings</CardTitle>
      </CardHeader>
      <CardContent className="flex flex-col gap-4">
        <Toggle
          title="Smart temperature control"
          description="The valves’ built-in SONOFF regulation as they near the target."
          checked={room.smartTemperatureControl}
          onChange={(v) => patch({ smartTemperatureControl: v })}
        />
        {canSplit && (
          <>
            <Separator />
            <Toggle
              title="Control valves separately"
              description={
                room.control === 'independent'
                  ? 'Each valve has its own target and schedule. Turning this off keeps the first valve’s schedule.'
                  : 'Give each valve its own target and schedule.'
              }
              checked={room.control === 'independent'}
              onChange={(v) => patch(v ? { control: 'independent' } : { control: 'grouped', sourceValve: room.zones[0]?.valves[0] })}
            />
          </>
        )}
        <Separator />
        <div className="flex items-center gap-4">
          <div className="flex-1">
            <div className="font-medium">Delete room</div>
            <div className="text-sm text-muted-foreground">Its valves become unassigned and turn off.</div>
          </div>
          <Button variant="destructive" onClick={() => setDeleting(true)}>
            <Trash2 /> Delete
          </Button>
        </div>
      </CardContent>

      <AlertDialog open={deleting} onOpenChange={setDeleting}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete {room.name}?</AlertDialogTitle>
            <AlertDialogDescription>Its schedule is lost and its valves turn off until you add them to another room.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={remove} className="bg-destructive text-white hover:bg-destructive/90">
              Delete room
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Card>
  )
}

function Toggle({ title, description, checked, onChange }: { title: string; description: string; checked: boolean; onChange: (v: boolean) => void }) {
  return (
    <label className="flex cursor-pointer items-center gap-4">
      <div className="flex-1">
        <div className="font-medium">{title}</div>
        <div className="text-sm text-muted-foreground">{description}</div>
      </div>
      <Switch checked={checked} onCheckedChange={onChange} />
    </label>
  )
}

function RenameDialog({ room, open, onOpenChange }: { room: Room; open: boolean; onOpenChange: (o: boolean) => void }) {
  const { run, putRoom } = useHome()
  const [name, setName] = useState(room.name)
  useEffect(() => setName(room.name), [room.name, open])

  async function submit(e: FormEvent) {
    e.preventDefault()
    const r = await run(() => api.patchRoom(room.id, { name: name.trim() }), "Couldn't rename")
    if (r) {
      putRoom(r)
      onOpenChange(false)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-sm">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>Rename room</DialogTitle>
          </DialogHeader>
          <Input autoFocus value={name} onChange={(e) => setName(e.target.value)} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={!name.trim() || name.trim() === room.name}>
              Save
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
