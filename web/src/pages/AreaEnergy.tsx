import { useCallback, useEffect, useState } from 'react'
import { Pencil, Trash2 } from 'lucide-react'
import { useNavigate, useParams } from 'react-router-dom'
import { IncompleteWarning } from '@/components/heating/IncompleteWarning'
import { AreaAlertControls } from '@/components/heating/AreaAlertCard'
import { AreaNameDialog } from '@/components/heating/OtherAreas'
import { PlugTable } from '@/components/heating/PlugTable'
import { UsageCarousel, type UsageSlide } from '@/components/heating/UsageCarousel'
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
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Checkbox } from '@/components/ui/checkbox'
import { Label } from '@/components/ui/label'
import { ApiError } from '@/lib/api'
import { alsoCountedIn, energyApi, type EnergyArea, type EnergyDashboard, type Meter, meterLabel } from '@/lib/energy'
import { WHOLE, usageApi, type UsageWindow } from '@/lib/energyUsage'
import { useHome } from '@/lib/home'
import type { RoomPlugs } from '@/lib/roomPlugs'
import { BackToEnergy, Figures, nowText, useLivePower } from './RoomEnergy'

/** A plug's power is not on the live feed, only its area's; this keeps the table close behind it. */
const METERS_EVERY_MS = 15_000
/** Today's kWh and cost are recorded every five minutes; there is no point asking more often. */
const TODAY_EVERY_MS = 5 * 60_000

/**
 * One energy area's electricity, on the same dashboard as a room's: the whole area first in the carousel, then each
 * plug; the table has a "Rest of" row for what its clamp measures that no plug does. Its settings are below. Admin only.
 */
export function AreaEnergyPage() {
  const id = useParams().id!
  const navigate = useNavigate()
  const { run } = useHome()
  const live = useLivePower()
  const [areas, setAreas] = useState<EnergyArea[] | null>(null)
  const [today, setToday] = useState<EnergyDashboard | null>(null)
  const [meters, setMeters] = useState<Meter[]>([])
  const [slide, setSlide] = useState(0)
  const [renaming, setRenaming] = useState(false)
  const [deleting, setDeleting] = useState(false)

  const loadAreas = useCallback(() => energyApi.areas().then(setAreas, (e) => e instanceof ApiError && setAreas([])), [])
  const loadToday = useCallback(() => energyApi.dashboard('today').then(setToday, () => {}), [])

  useEffect(() => {
    loadAreas()
    loadToday()
    const loadMeters = () => energyApi.meters().then(setMeters, () => {})
    loadMeters()
    const meterTimer = window.setInterval(loadMeters, METERS_EVERY_MS)
    const todayTimer = window.setInterval(loadToday, TODAY_EVERY_MS)
    return () => {
      window.clearInterval(meterTimer)
      window.clearInterval(todayTimer)
    }
  }, [id, loadAreas, loadToday])

  const load = useCallback((window: UsageWindow, to?: number) => usageApi.area(id, window, to), [id])

  const area = areas?.find((a) => a.id === id)
  if (areas && !area) {
    return (
      <div className="flex flex-col gap-4">
        <BackToEnergy />
        <p data-testid="area-not-found" className="font-medium">Area not found</p>
      </div>
    )
  }

  const day = today?.areas?.find((a) => a.area === id)
  const currency = today?.currency ?? 'GBP'
  const plugNames = day?.plugs.map((p) => p.meter) ?? []
  /** A meter as the admin named it: its label, else its name. */
  const shownAs = (name: string) => {
    const m = meters.find((x) => x.name === name)
    return m ? meterLabel(m) : name
  }
  // With no clamp and one plug, the plug's chart alone: the area's would only repeat it.
  const slides: UsageSlide[] =
    !area?.wholeMeter && plugNames.length === 1
      ? [{ key: plugNames[0], name: shownAs(plugNames[0]) }]
      : [{ key: WHOLE, name: area?.name ?? 'Area' }, ...plugNames.map((m) => ({ key: m, name: shownAs(m) }))]
  const others = (areas ?? []).filter((a) => a.id !== id)
  const overlaps = others.filter((a) => area?.overlaps?.includes(a.id))

  // The table's plugs: today's use from the dashboard, power now from the meters.
  const table: RoomPlugs | null = day
    ? {
        room: id,
        currency,
        kwh: day.kwh,
        cost: day.cost,
        missingMinutes: day.missingMinutes,
        incomplete: day.incomplete,
        plugs: day.plugs.map((p) => {
          const m = meters.find((x) => x.name === p.meter)
          const offline = m != null && (!m.online || m.stale)
          return {
            meter: p.meter,
            label: shownAs(p.meter),
            power: offline ? null : (m?.power ?? null),
            kwh: p.kwh,
            cost: p.cost,
            missingMinutes: 0,
            incomplete: false,
            offline: [],
            offlineSince: offline ? (m!.lastReading ?? new Date().toISOString()) : null,
            totalStuckSince: null,
          }
        }),
      }
    : null

  async function rename(name: string) {
    const renamed = await run(() => energyApi.renameArea(id, name), "Couldn't rename the area")
    if (renamed) loadAreas()
    return !!renamed
  }

  async function remove() {
    if (await run(() => energyApi.deleteArea(id), "Couldn't delete the area")) navigate('/energy', { replace: true })
  }

  async function share(other: EnergyArea, shared: boolean) {
    const done = await run(
      () => (shared ? energyApi.shareCircuit(id, other.id) : energyApi.stopSharingCircuit(id, other.id)),
      "Couldn't change the shared circuit",
    )
    if (done) loadAreas()
  }

  return (
    <div className="flex flex-col gap-4">
      <BackToEnergy />
      <div>
        <h1 className="text-3xl font-bold tracking-tight">{area?.name ?? ' '}</h1>
        {overlaps.length > 0 && (
          <p data-testid="area-overlaps" className="text-sm text-muted-foreground">
            {alsoCountedIn(overlaps.map((a) => a.name))}
          </p>
        )}
      </div>
      <Figures
        now={nowText(live, live?.areas?.find((a) => a.area === id)?.watts)}
        kwh={day?.kwh}
        cost={day?.cost}
        currency={currency}
        warning={day?.incomplete ? <IncompleteWarning missingMinutes={day.missingMinutes} /> : undefined}
      />
      {area && day && <UsageCarousel load={load} slides={slides} slide={slide} onSlide={setSlide} />}
      {table && table.plugs.length > 0 && (
        <PlugTable
          plugs={table}
          onPick={(meter) => setSlide(slides.findIndex((s) => s.key === meter))}
          rest={area?.wholeMeter && day?.rest != null ? { name: `Rest of ${area.name}`, kwh: day.rest, cost: day.restCost ?? 0 } : undefined}
        />
      )}

      {area && (
        <Card data-testid="area-settings">
          <CardHeader>
            <CardTitle>Settings</CardTitle>
            <CardDescription>{area.wholeMeter ? `Measured by ${area.wholeMeter}` : 'No clamp: the sum of its plugs'}</CardDescription>
          </CardHeader>
          <CardContent className="flex flex-col gap-4">
            {others.length > 0 && (
              <fieldset className="flex flex-col gap-2">
                <legend className="mb-1 text-sm font-medium">Shares a circuit with</legend>
                <p className="text-xs text-muted-foreground">Use on a shared circuit is measured by both areas and cannot be split, so it is not guessed.</p>
                {others.map((o) => {
                  const checked = area.overlaps?.includes(o.id) ?? false
                  return (
                    <div key={o.id} className="flex items-center gap-2">
                      <Checkbox id={`overlap-${o.id}`} checked={checked} onCheckedChange={(v) => share(o, v === true)} />
                      <Label htmlFor={`overlap-${o.id}`}>{o.name}</Label>
                    </div>
                  )
                })}
              </fieldset>
            )}
            <AreaAlertControls area={area.id} name={area.name} />
            <div className="flex flex-wrap gap-2">
              <Button variant="outline" onClick={() => setRenaming(true)}>
                <Pencil /> Rename area
              </Button>
              <Button variant="outline" onClick={() => setDeleting(true)}>
                <Trash2 /> Delete area
              </Button>
            </div>
          </CardContent>
        </Card>
      )}

      {renaming && area && <AreaNameDialog title={`Rename ${area.name}`} action="Rename" initial={area.name} onSave={rename} onClose={() => setRenaming(false)} />}

      <AlertDialog open={deleting} onOpenChange={setDeleting}>
        <AlertDialogContent>
          <AlertDialogHeader>
            <AlertDialogTitle>Delete {area?.name}?</AlertDialogTitle>
            <AlertDialogDescription>
              What it recorded is kept, and the house figure does not change. Its clamp goes back to waiting to be set up, and its plugs stay monitored in no room.
            </AlertDialogDescription>
          </AlertDialogHeader>
          <AlertDialogFooter>
            <AlertDialogCancel>Cancel</AlertDialogCancel>
            <AlertDialogAction onClick={remove}>Delete</AlertDialogAction>
          </AlertDialogFooter>
        </AlertDialogContent>
      </AlertDialog>
    </div>
  )
}
