import { useCallback, useEffect, useState, type FormEvent, type ReactNode } from 'react'
import { Link } from 'react-router-dom'
import { ArrowLeft, Gauge, Pencil, Plus, Plug, Trash2, TriangleAlert, Unplug, Wrench } from 'lucide-react'
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
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { Skeleton } from '@/components/ui/skeleton'
import { WaitingDevicesCard } from '@/components/heating/WaitingDevicesCard'
import {
  MAIN_METER_BUTTON,
  REVERSED,
  energyApi,
  formatCost,
  formatPower,
  meterLabel,
  type EnergyArea,
  type EnergySettings,
  type Meter,
  type MeterJob,
} from '@/lib/energy'
import { useHome } from '@/lib/home'

const selectClass = 'h-9 rounded-md border border-input bg-background px-3 text-sm'

/** Everything that measures electricity, set up in one place: the main meter, the daily target, areas, monitored devices and new devices. Admin only. */
export function EnergyManagePage() {
  const { run } = useHome()
  const [settings, setSettings] = useState<EnergySettings | null>(null)
  const [meters, setMeters] = useState<Meter[]>([])
  const [areas, setAreas] = useState<EnergyArea[]>([])
  const [version, setVersion] = useState(0)
  const [renaming, setRenaming] = useState<Meter | null>(null)
  const [jobFor, setJobFor] = useState<Meter | null>(null)

  const refresh = useCallback(() => {
    energyApi.settings().then(setSettings, () => undefined)
    energyApi.meters().then(setMeters, () => undefined)
    energyApi.areas().then((a) => setAreas([...a].sort((x, y) => x.name.localeCompare(y.name))), () => undefined)
    setVersion((v) => v + 1)
  }, [])

  useEffect(refresh, [refresh])

  /** Runs [action]; on success says [done] and loads everything again. */
  async function act<T>(action: () => Promise<T>, failure: string, done?: string) {
    const result = await run(action, failure)
    if (result !== undefined) {
      if (done) toast.success(done)
      refresh()
    }
    return result !== undefined
  }

  const rowProps = { onRename: setRenaming, onJob: setJobFor }

  return (
    <div className="flex flex-col gap-8">
      <div>
        <Link to="/energy" className="mb-2 inline-flex items-center gap-1 text-sm text-muted-foreground hover:text-foreground">
          <ArrowLeft className="size-4" /> Energy
        </Link>
        <h1 className="text-3xl font-bold tracking-tight">Manage energy</h1>
        <p className="text-muted-foreground">The main meter, the daily target, areas, and every clamp and plug</p>
      </div>

      {settings == null ? (
        <Skeleton className="h-40 w-full rounded-2xl" />
      ) : (
        <>
          {settings.notices.length > 0 && (
            <Card data-testid="energy-notices" className="border-amber-500/50">
              <CardContent className="flex flex-col gap-1 p-4 text-sm">
                {settings.notices.map((n) => (
                  <div key={n} className="flex items-center gap-2">
                    <TriangleAlert className="size-4 text-amber-600" /> {n}
                  </div>
                ))}
              </CardContent>
            </Card>
          )}
          <MainMeter settings={settings} meters={meters} act={act} {...rowProps} />
          <DailyTarget settings={settings} act={act} />
          <Areas areas={areas} meters={meters} act={act} {...rowProps} />
          <Monitored areas={areas} meters={meters} act={act} {...rowProps} />
          {/* The card titles itself "Waiting to be set up" while something waits. */}
          <section data-testid="manage-waiting" className="flex flex-col gap-3">
            {meters.every((m) => m.inUse || m.monitored || m.measures) && (
              <div>
                <h2 className="text-xl font-bold tracking-tight">Waiting to be set up</h2>
                <p className="text-sm text-muted-foreground">No meters are waiting. New clamps and plugs paired in Zigbee2MQTT show here.</p>
              </div>
            )}
            <WaitingDevicesCard key={version} onMonitored={refresh} />
          </section>
        </>
      )}

      <RenameDialog meter={renaming} onClose={() => setRenaming(null)} act={act} />
      <JobDialog meter={jobFor} areas={areas} onClose={() => setJobFor(null)} act={act} />
    </div>
  )
}

type Act = <T>(action: () => Promise<T>, failure: string, done?: string) => Promise<boolean>
type RowActions = { onRename: (m: Meter) => void; onJob: (m: Meter) => void }

function Section({ title, description, testId, action, children }: { title: string; description?: string; testId: string; action?: ReactNode; children: ReactNode }) {
  return (
    <section data-testid={testId} className="flex flex-col gap-3">
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h2 className="text-xl font-bold tracking-tight">{title}</h2>
          {description && <p className="text-sm text-muted-foreground">{description}</p>}
        </div>
        {action}
      </div>
      {children}
    </section>
  )
}

/** "412 W now", or why there is no reading. */
function meterNow(m: Meter): string {
  if (!m.online) return 'Offline'
  if (m.stale) return 'Not reporting'
  return `${formatPower(m.power)} now`
}

/** One meter: its label (and Zigbee2MQTT name when it has another), power now, a backwards clamp's note, Rename and Change job. */
function MeterRow({ meter, onRename, onJob, children }: { meter: Meter; children?: ReactNode } & RowActions) {
  const label = meterLabel(meter)
  return (
    <div data-testid={`meter-${meter.name}`} className="flex flex-wrap items-center gap-3">
      <div className="flex size-9 items-center justify-center rounded-lg bg-secondary [&_svg]:size-4">{meter.clamp || meter.measures || meter.inUse ? <Gauge /> : <Plug />}</div>
      <div className="min-w-40 flex-1">
        <div className="font-medium">{label}</div>
        <div className="text-sm text-muted-foreground">
          {label !== meter.name && <span>{meter.name} · </span>}
          <span data-testid="meter-now">{meterNow(meter)}</span>
        </div>
        {meter.reversed && (
          <div data-testid="meter-reversed" className="flex items-center gap-1 text-xs text-amber-700 dark:text-amber-500">
            <TriangleAlert className="size-3" /> {REVERSED}
          </div>
        )}
      </div>
      {children}
      <Button variant="ghost" size="sm" aria-label={`Rename ${label}`} onClick={() => onRename(meter)}>
        <Pencil /> Rename
      </Button>
      <Button variant="outline" size="sm" aria-label={`Change job of ${label}`} onClick={() => onJob(meter)}>
        <Wrench /> Change job
      </Button>
    </div>
  )
}

function MainMeter({ settings, meters, act, ...row }: { settings: EnergySettings; meters: Meter[]; act: Act } & RowActions) {
  const [choosing, setChoosing] = useState(false)
  const [stopping, setStopping] = useState(false)
  const main = meters.find((m) => m.name === settings.electricityMeter)
  // Only meters that report energy and do no other job can be the main meter.
  const candidates = meters.filter((m) => m.reportsEnergy !== false && !m.inUse && !m.measures && !m.monitored)
  return (
    <Section
      title="Main meter"
      testId="manage-main"
      description="The clamp that measures all electricity coming in"
      action={
        <div className="flex gap-2">
          <Button variant="outline" onClick={() => setChoosing(true)}>Change</Button>
          {main && (
            <Button variant="ghost" onClick={() => setStopping(true)}>
              Stop using
            </Button>
          )}
        </div>
      }
    >
      <Card>
        <CardContent className="p-4">{main ? <MeterRow meter={main} {...row} /> : <p className="text-sm text-muted-foreground">No main meter chosen.</p>}</CardContent>
      </Card>

      <Dialog open={choosing} onOpenChange={setChoosing}>
        <DialogContent>
          <DialogHeader>
            <DialogTitle>Choose the main meter</DialogTitle>
            <DialogDescription>{MAIN_METER_BUTTON}: it measures all electricity coming in. What the old one recorded is kept.</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-2">
            {candidates.length === 0 && <p className="text-sm text-muted-foreground">No other meter can be the main meter. Free one up with "Change job" first.</p>}
            {candidates.map((m) => (
              <Button
                key={m.name}
                variant="outline"
                className="h-auto justify-between py-2"
                onClick={async () => {
                  setChoosing(false)
                  await act(() => energyApi.useForHouse(m.name), "Couldn't choose the main meter", `${meterLabel(m)} is now the main meter`)
                }}
              >
                <span>{meterLabel(m)}</span>
                <span className="text-xs text-muted-foreground">{meterNow(m)}</span>
              </Button>
            ))}
          </div>
        </DialogContent>
      </Dialog>

      <AlertDialog open={stopping} onOpenChange={setStopping}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Stop using {main ? meterLabel(main) : ''} as the main meter?</AlertDialogTitle>
            <AlertDialogDescription>The house's electricity will not be counted until another is chosen. What it recorded is kept.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={() => main && act(() => energyApi.stopUsingForHouse(main.name), "Couldn't stop using the meter", 'No main meter now')}>Stop using</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Section>
  )
}

function DailyTarget({ settings, act }: { settings: EnergySettings; act: Act }) {
  const [editing, setEditing] = useState(false)
  const [value, setValue] = useState('')
  const target = settings.dailyKwhTarget ?? null

  async function save(e: FormEvent) {
    e.preventDefault()
    const kwh = Number(value)
    if (await act(() => energyApi.setDailyTarget(kwh), "Couldn't set the target", 'Daily target saved')) setEditing(false)
  }

  return (
    <Section title="Daily target" testId="manage-target" description="How much electricity the house aims to use each day">
      <Card>
        <CardContent className="flex flex-wrap items-center gap-3 p-4">
          {editing ? (
            <form onSubmit={save} className="flex flex-wrap items-end gap-2">
              <div className="flex flex-col gap-1">
                <Label htmlFor="daily-target">kWh a day</Label>
                <Input id="daily-target" type="number" inputMode="decimal" step="0.1" min="0.1" max="1000" required value={value} onChange={(e) => setValue(e.target.value)} className="w-32" />
              </div>
              <Button type="submit">Save</Button>
              <Button type="button" variant="ghost" onClick={() => setEditing(false)}>
                Cancel
              </Button>
            </form>
          ) : (
            <>
              <div className="min-w-40 flex-1">
                <div data-testid="target-value" className="text-lg font-bold tabular-nums">
                  {target == null ? 'No target' : `${target.toFixed(1)} kWh a day`}
                </div>
                {target != null && (
                  <div data-testid="target-cost" className="text-sm text-muted-foreground">
                    {formatCost(target * settings.electricityPencePerKwh, settings.currency)} a day at today's price
                  </div>
                )}
              </div>
              <Button
                variant="outline"
                onClick={() => {
                  setValue(target == null ? '' : String(target))
                  setEditing(true)
                }}
              >
                {target == null ? 'Set a target' : 'Change'}
              </Button>
              {target != null && (
                <Button variant="ghost" onClick={() => act(() => energyApi.setDailyTarget(null), "Couldn't clear the target", 'Daily target cleared')}>
                  Clear
                </Button>
              )}
            </>
          )}
        </CardContent>
      </Card>
    </Section>
  )
}

function Areas({ areas, meters, act, ...row }: { areas: EnergyArea[]; meters: Meter[]; act: Act } & RowActions) {
  const [adding, setAdding] = useState(false)
  const [renaming, setRenaming] = useState<EnergyArea | null>(null)
  const [deleting, setDeleting] = useState<EnergyArea | null>(null)
  return (
    <Section
      title="Areas"
      testId="manage-areas"
      description="Electricity that does not belong to a heated room, such as all the lights"
      action={
        <Button variant="outline" onClick={() => setAdding(true)}>
          <Plus /> Add area
        </Button>
      }
    >
      {areas.length === 0 && <p className="text-sm text-muted-foreground">No areas yet.</p>}
      {areas.map((area) => {
        const clamp = meters.find((m) => m.measures === area.id)
        const plugs = meters.filter((m) => m.area === area.id)
        const free = meters.filter((m) => !m.inUse && !m.monitored && !m.measures)
        const loosePlugs = meters.filter((m) => m.monitored && m.area !== area.id)
        const others = areas.filter((a) => a.id !== area.id)
        return (
          <Card key={area.id} data-testid={`manage-area-${area.id}`}>
            <CardContent className="flex flex-col gap-4 p-4">
              <div className="flex flex-wrap items-center gap-2">
                <div className="min-w-40 flex-1">
                  <div className="text-lg font-semibold">{area.name}</div>
                  <div data-testid="area-now" className="text-sm text-muted-foreground">
                    {clamp ? `${meterNow(clamp)} on ${meterLabel(clamp)}` : 'Sum of its plugs'}
                  </div>
                </div>
                <Button variant="ghost" size="sm" aria-label={`Rename ${area.name}`} onClick={() => setRenaming(area)}>
                  <Pencil /> Rename
                </Button>
                <Button variant="ghost" size="sm" aria-label={`Delete ${area.name}`} onClick={() => setDeleting(area)}>
                  <Trash2 /> Delete
                </Button>
              </div>

              <div className="flex flex-col gap-2">
                <div className="text-sm font-medium">Whole-area meter</div>
                {clamp ? (
                  <MeterRow meter={clamp} {...row}>
                    <Button variant="ghost" size="sm" onClick={() => act(() => energyApi.setJob(clamp.name, { job: 'none' }), "Couldn't remove the meter", `${meterLabel(clamp)} no longer measures ${area.name}`)}>
                      Remove
                    </Button>
                  </MeterRow>
                ) : null}
                <select
                  aria-label={`Whole-area meter for ${area.name}`}
                  className={selectClass}
                  value=""
                  onChange={(e) => {
                    const m = meters.find((x) => x.name === e.target.value)
                    if (m) act(() => energyApi.setJob(m.name, { job: 'area', area: area.id }), "Couldn't choose the meter", `${meterLabel(m)} now measures ${area.name}`)
                  }}
                >
                  <option value="">{clamp ? 'Replace with…' : 'Choose a clamp…'}</option>
                  {free.map((m) => (
                    <option key={m.name} value={m.name}>
                      {meterLabel(m)}
                    </option>
                  ))}
                </select>
              </div>

              <div className="flex flex-col gap-2">
                <div className="text-sm font-medium">Plugs</div>
                {plugs.length === 0 && <div className="text-sm text-muted-foreground">No plugs in this area.</div>}
                {plugs.map((p) => (
                  <MeterRow key={p.name} meter={p} {...row}>
                    <Button variant="ghost" size="sm" onClick={() => act(() => energyApi.takeOutOfArea(area.id, p.name), "Couldn't take the plug out", `${meterLabel(p)} taken out of ${area.name}`)}>
                      Take out
                    </Button>
                  </MeterRow>
                ))}
                {loosePlugs.length > 0 && (
                  <select
                    aria-label={`Add a plug to ${area.name}`}
                    className={selectClass}
                    value=""
                    onChange={(e) => e.target.value && act(() => energyApi.putInArea(area.id, e.target.value), "Couldn't add the plug", `Plug added to ${area.name}`)}
                  >
                    <option value="">Add a monitored plug…</option>
                    {loosePlugs.map((m) => (
                      <option key={m.name} value={m.name}>
                        {meterLabel(m)}
                      </option>
                    ))}
                  </select>
                )}
              </div>

              {others.length > 0 && (
                <div className="flex flex-col gap-2">
                  <div className="text-sm font-medium">Shares a circuit with</div>
                  <div className="flex flex-wrap gap-3">
                    {others.map((o) => {
                      const shared = area.overlaps?.includes(o.id) ?? false
                      return (
                        <label key={o.id} className="flex items-center gap-2 text-sm">
                          <input
                            type="checkbox"
                            checked={shared}
                            onChange={() =>
                              act(
                                () => (shared ? energyApi.stopSharingCircuit(area.id, o.id) : energyApi.shareCircuit(area.id, o.id)),
                                "Couldn't change the shared circuit",
                              )
                            }
                          />
                          {o.name}
                        </label>
                      )
                    })}
                  </div>
                </div>
              )}
            </CardContent>
          </Card>
        )
      })}

      <NameDialog
        open={adding}
        title="Add area"
        submit="Add area"
        initial=""
        onClose={() => setAdding(false)}
        onSave={(name) => act(() => energyApi.addArea(name), "Couldn't add the area", `${name} added`)}
      />
      <NameDialog
        open={renaming != null}
        title="Rename area"
        submit="Rename"
        initial={renaming?.name ?? ''}
        onClose={() => setRenaming(null)}
        onSave={(name) => act(() => energyApi.renameArea(renaming!.id, name), "Couldn't rename the area", 'Area renamed')}
      />
      <AlertDialog open={deleting != null} onOpenChange={(o) => !o && setDeleting(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete {deleting?.name}?</AlertDialogTitle>
            <AlertDialogDescription>Its clamp waits to be set up again and its plugs stay monitored in no room. What was recorded is kept.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction
              className="bg-destructive text-white hover:bg-destructive/90"
              onClick={() => deleting && act(() => energyApi.deleteArea(deleting.id), "Couldn't delete the area", `${deleting.name} deleted`)}
            >
              Delete
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Section>
  )
}

function Monitored({ areas, meters, act, ...row }: { areas: EnergyArea[]; meters: Meter[]; act: Act } & RowActions) {
  const { rooms } = useHome()
  const [stopping, setStopping] = useState<Meter | null>(null)
  const monitored = meters.filter((m) => m.monitored)
  return (
    <Section title="Monitored devices" testId="manage-monitored" description="Plugs and clamps on one appliance or circuit, counted inside the main meter's figure">
      <Card>
        <CardContent className="flex flex-col gap-4 p-4">
          {monitored.length === 0 && <p className="text-sm text-muted-foreground">No devices are monitored.</p>}
          {monitored.map((m) => (
            <MeterRow key={m.name} meter={m} {...row}>
              <select
                aria-label={`Place of ${meterLabel(m)}`}
                className={selectClass}
                value={m.area ? `area:${m.area}` : (m.room ?? '')}
                onChange={(e) => {
                  const v = e.target.value
                  const job: MeterJob = v.startsWith('area:') ? { job: 'monitored', area: v.slice(5) } : v ? { job: 'monitored', room: v } : { job: 'monitored' }
                  act(() => energyApi.setJob(m.name, job), "Couldn't move the device")
                }}
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
              <Button variant="ghost" size="sm" onClick={() => setStopping(m)}>
                <Unplug /> Stop monitoring
              </Button>
            </MeterRow>
          ))}
        </CardContent>
      </Card>
      <AlertDialog open={stopping != null} onOpenChange={(o) => !o && setStopping(null)}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Stop monitoring {stopping ? meterLabel(stopping) : ''}?</AlertDialogTitle>
            <AlertDialogDescription>It goes back to waiting to be set up. What it recorded is kept and still counted.</AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={() => stopping && act(() => energyApi.stopMonitoring(stopping.name), "Couldn't stop monitoring", `${meterLabel(stopping)} is no longer monitored`)}>
              Stop monitoring
            </AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </Section>
  )
}

function NameDialog({ open, title, submit, initial, onClose, onSave }: { open: boolean; title: string; submit: string; initial: string; onClose: () => void; onSave: (name: string) => Promise<boolean> }) {
  const [name, setName] = useState(initial)
  useEffect(() => {
    if (open) setName(initial)
  }, [open, initial])
  return (
    <Dialog open={open} onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <form
          onSubmit={async (e) => {
            e.preventDefault()
            if (await onSave(name.trim())) onClose()
          }}
          className="flex flex-col gap-4"
        >
          <DialogHeader>
            <DialogTitle>{title}</DialogTitle>
          </DialogHeader>
          <div className="flex flex-col gap-1">
            <Label htmlFor="manage-area-name">Area name</Label>
            <Input id="manage-area-name" required value={name} onChange={(e) => setName(e.target.value)} />
          </div>
          <DialogFooter>
            <Button type="submit">{submit}</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

function RenameDialog({ meter, onClose, act }: { meter: Meter | null; onClose: () => void; act: Act }) {
  const [label, setLabel] = useState('')
  useEffect(() => {
    if (meter) setLabel(meter.label && meter.label !== meter.name ? meter.label : '')
  }, [meter])
  return (
    <Dialog open={meter != null} onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <form
          onSubmit={async (e) => {
            e.preventDefault()
            if (meter && (await act(() => energyApi.label(meter.name, label), "Couldn't rename the meter", 'Meter renamed'))) onClose()
          }}
          className="flex flex-col gap-4"
        >
          <DialogHeader>
            <DialogTitle>Rename {meter?.name}</DialogTitle>
            <DialogDescription>Leave it empty to use its Zigbee2MQTT name.</DialogDescription>
          </DialogHeader>
          <div className="flex flex-col gap-1">
            <Label htmlFor="meter-label">Name</Label>
            <Input id="meter-label" maxLength={40} placeholder={meter?.name} value={label} onChange={(e) => setLabel(e.target.value)} />
          </div>
          <DialogFooter>
            <Button type="submit">Save</Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}

const JOBS: { value: MeterJob['job']; label: string; hint: string }[] = [
  { value: 'house', label: MAIN_METER_BUTTON, hint: 'Measures all electricity coming in' },
  { value: 'area', label: 'Measure an energy area', hint: 'Measures all of an area, such as the lights' },
  { value: 'monitored', label: 'Monitor one device', hint: 'An appliance or circuit, such as the oven, in a room or area' },
  { value: 'none', label: 'Nothing for now', hint: 'Waits to be set up' },
]

/** A meter's job, changed in one step: PUT /api/meters/{meter}/job. */
function JobDialog({ meter, areas, onClose, act }: { meter: Meter | null; areas: EnergyArea[]; onClose: () => void; act: Act }) {
  const { rooms } = useHome()
  const [job, setJob] = useState<MeterJob['job']>('none')
  const [place, setPlace] = useState('')
  useEffect(() => {
    if (!meter) return
    setJob(meter.inUse ? 'house' : meter.measures ? 'area' : meter.monitored ? 'monitored' : 'none')
    setPlace(meter.measures ? meter.measures : meter.area ? `area:${meter.area}` : (meter.room ?? ''))
  }, [meter])
  const noEnergy = meter?.reportsEnergy === false

  function body(): MeterJob | null {
    if (job === 'house' || job === 'none') return { job }
    if (job === 'area') {
      const area = place.replace(/^area:/, '')
      return areas.some((a) => a.id === area) ? { job, area } : null
    }
    return place.startsWith('area:') ? { job, area: place.slice(5) } : place ? { job, room: place } : { job }
  }

  return (
    <Dialog open={meter != null} onOpenChange={(o) => !o && onClose()}>
      <DialogContent>
        <form
          onSubmit={async (e) => {
            e.preventDefault()
            const b = body()
            if (!meter || !b) return
            if (await act(() => energyApi.setJob(meter.name, b), "Couldn't change the job", `${meterLabel(meter)} updated`)) onClose()
          }}
          className="flex flex-col gap-4"
        >
          <DialogHeader>
            <DialogTitle>What does {meter ? meterLabel(meter) : ''} measure?</DialogTitle>
            <DialogDescription>What it recorded in its old job stays where it was recorded.</DialogDescription>
          </DialogHeader>
          <div role="radiogroup" aria-label="Job" className="flex flex-col gap-2">
            {JOBS.map((j) => {
              const disabled = noEnergy && (j.value === 'house' || j.value === 'monitored')
              return (
                <label key={j.value} className="flex items-start gap-3 rounded-lg border p-3 text-sm has-[:checked]:border-primary" aria-disabled={disabled}>
                  <input
                    type="radio"
                    name="job"
                    value={j.value}
                    disabled={disabled}
                    checked={job === j.value}
                    onChange={() => {
                      setJob(j.value)
                      setPlace(j.value === 'area' ? (areas[0]?.id ?? '') : '')
                    }}
                    className="mt-1"
                  />
                  <span>
                    <span className="block font-medium">{j.label}</span>
                    <span className="block text-muted-foreground">{disabled ? 'This meter does not report energy' : j.hint}</span>
                  </span>
                </label>
              )
            })}
          </div>
          {job === 'area' && (
            <select aria-label="Area" className={selectClass} value={place.replace(/^area:/, '')} onChange={(e) => setPlace(e.target.value)}>
              {areas.length === 0 && <option value="">Add an area first</option>}
              {areas.map((a) => (
                <option key={a.id} value={a.id}>
                  {a.name}
                </option>
              ))}
            </select>
          )}
          {job === 'monitored' && (
            <select aria-label="Room or area" className={selectClass} value={place} onChange={(e) => setPlace(e.target.value)}>
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
          )}
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={onClose}>
              Cancel
            </Button>
            <Button type="submit" disabled={body() == null}>
              Save
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
