// What a meter measures, chosen in one dialog: on Manage energy, and from a league row for a meter with no job yet.

import { useEffect, useState } from 'react'
import { Button } from '@/components/ui/button'
import { Dialog, DialogContent, DialogDescription, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Label } from '@/components/ui/label'
import { MAIN_METER_BUTTON, energyApi, meterLabel, type EnergyArea, type Meter, type MeterJob } from '@/lib/energy'
import { useHome } from '@/lib/home'

/** Runs an action, showing [failure] if it fails and [done] if it works; true when it worked. */
export type Act = <T>(action: () => Promise<T>, failure: string, done?: string) => Promise<boolean>

const selectClass = 'h-9 rounded-md border border-input bg-background px-3 text-sm'

const JOBS: { value: MeterJob['job']; label: string; hint: string }[] = [
  { value: 'house', label: MAIN_METER_BUTTON, hint: 'Measures all electricity coming in' },
  { value: 'area', label: 'Measure an energy area', hint: 'Measures all of an area, such as the lights' },
  { value: 'monitored', label: 'Monitor one device', hint: 'An appliance or circuit, such as the oven, in a room or area' },
  { value: 'none', label: 'Nothing for now', hint: 'Waits to be set up' },
]

/** A meter's job, changed in one step: PUT /api/meters/{meter}/job. */
export function JobDialog({
  meter,
  areas,
  onClose,
  act,
  withLabel = false,
}: {
  meter: Meter | null
  areas: EnergyArea[]
  onClose: () => void
  act: Act
  /** Also offers its label, for setting a new meter up in one go */
  withLabel?: boolean
}) {
  const { rooms } = useHome()
  const [job, setJob] = useState<MeterJob['job']>('none')
  const [place, setPlace] = useState('')
  const [label, setLabel] = useState('')
  useEffect(() => {
    if (!meter) return
    setLabel(meter.label && meter.label !== meter.name ? meter.label : '')
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
            const was = meter.label && meter.label !== meter.name ? meter.label : ''
            if (withLabel && label !== was && !(await act(() => energyApi.label(meter.name, label), "Couldn't rename the meter"))) return
            if (await act(() => energyApi.setJob(meter.name, b), "Couldn't change the job", `${label || meterLabel(meter)} updated`)) onClose()
          }}
          className="flex flex-col gap-4"
        >
          <DialogHeader>
            <DialogTitle>What does {meter ? meterLabel(meter) : ''} measure?</DialogTitle>
            <DialogDescription>What it recorded in its old job stays where it was recorded.</DialogDescription>
          </DialogHeader>
          {withLabel && (
            <div className="flex flex-col gap-1">
              <Label htmlFor="meter-settings-label">Name</Label>
              <Input id="meter-settings-label" maxLength={40} placeholder={meter?.name} value={label} onChange={(e) => setLabel(e.target.value)} />
            </div>
          )}
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
