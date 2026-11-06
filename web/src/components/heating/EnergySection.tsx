import { useEffect, useState } from 'react'
import { Flame, PlugZap, Receipt, Sigma, Target } from 'lucide-react'
import { Link } from 'react-router-dom'
import { Card, CardContent } from '@/components/ui/card'
import { Skeleton } from '@/components/ui/skeleton'
import { NO_METER, READINGS_MISSING, SET_TARGET, targetDelta, targetProgress, type EnergyTarget, energyApi, energyPlace, formatCost, formatKwh, plugNowWarning, type DeviceEnergy, type EnergyDashboard, type RoomEnergy, type EverythingElse, type EnergyDay, type EnergyPeriod } from '@/lib/energy'
import { IncompleteWarning } from './IncompleteWarning'
import { cn } from '@/lib/utils'
import { useHome } from '@/lib/home'

const PERIODS: { value: EnergyPeriod; label: string }[] = [
  { value: 'today', label: 'Today' },
  { value: 'week', label: 'Week' },
  { value: 'month', label: 'Month' },
]

/** Gas, electricity, standing charges and total for today, the last week or month, with a bar per day. */
export function EnergySection() {
  const { me } = useHome()
  const [period, choosePeriod] = useState<EnergyPeriod>(energyPlace.period)
  const setPeriod = (p: EnergyPeriod) => {
    energyPlace.period = p
    choosePeriod(p)
  }
  const [dashboard, setDashboard] = useState<EnergyDashboard | null>(null)
  const [failed, setFailed] = useState(false)

  useEffect(() => {
    let cancelled = false
    energyApi.dashboard(period).then(
      (d) => {
        if (cancelled) return
        setDashboard(d)
        setFailed(false)
      },
      () => !cancelled && setFailed(true),
    )
    return () => {
      cancelled = true
    }
  }, [period])

  const shown = dashboard?.period === period ? dashboard : null

  return (
    <section data-testid="energy" className="flex flex-col gap-4">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div>
          <h2 className="text-2xl font-bold tracking-tight">Use and cost</h2>
          <p className="text-sm text-muted-foreground">Gas is estimated from the boiler's hours. Costs use today's prices.</p>
        </div>
        <div role="group" aria-label="Energy period" className="flex rounded-xl bg-secondary p-1">
          {PERIODS.map((p) => (
            <button
              key={p.value}
              type="button"
              aria-pressed={period === p.value}
              onClick={() => setPeriod(p.value)}
              className={cn(
                'rounded-lg px-3 py-1.5 text-sm font-medium transition-colors',
                period === p.value ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground',
              )}
            >
              {p.label}
            </button>
          ))}
        </div>
      </div>

      {failed && !shown && <p className="text-muted-foreground">Couldn't load the energy figures.</p>}
      {!shown && !failed && <Skeleton className="h-40 w-full rounded-2xl" />}

      {shown && (
        <>
          <div className="grid grid-cols-2 gap-3 lg:grid-cols-4">
            <Figure testId="energy-gas" icon={<Flame />} label="Gas" cost={formatCost(shown.gasCost, shown.currency)} detail={`${formatKwh(shown.gasKwh)} · ${shown.boilerHours.toFixed(1)} h boiler`} />
            <Figure
              testId="energy-electricity"
              icon={<PlugZap />}
              label="Electricity"
              cost={formatCost(shown.electricityCost, shown.currency)}
              detail={shown.electricityKwh == null ? 'No meter chosen' : formatKwh(shown.electricityKwh)}
            />
            <Figure testId="energy-standing" icon={<Receipt />} label="Standing charges" cost={formatCost(shown.standingCost, shown.currency)} detail="Charged per day" />
            <Figure testId="energy-total" icon={<Sigma />} label="Total" cost={formatCost(shown.totalCost, shown.currency)} detail={shown.incomplete ? 'Some time missing' : PERIODS.find((p) => p.value === period)!.label} />
          </div>
          {shown.electricityMeter == null && <p className="text-sm text-muted-foreground">{NO_METER}</p>}
          {shown.target ? (
            <TargetCard target={shown.target} today={period === 'today'} />
          ) : (
            shown.electricityMeter != null && me.admin && (
              <Link to="/energy/manage" className="text-sm font-medium text-primary underline-offset-4 hover:underline">
                {SET_TARGET}
              </Link>
            )
          )}
          {(shown.devices?.length > 0 || shown.rooms?.length > 0) && (
            <div className="grid gap-3 md:grid-cols-2">
              {shown.rooms?.length > 0 && <Rooms rooms={shown.rooms} devices={shown.devices} rest={shown.everythingElse ?? null} currency={shown.currency} />}
              {shown.devices?.length > 0 && <Devices devices={shown.devices} currency={shown.currency} />}
            </div>
          )}
          {period !== 'today' && <DayBars days={shown.days} currency={shown.currency} dailyTarget={shown.target ? shown.target.kwh / shown.days.length : null} price={shown.electricityKwh ? (shown.electricityCost ?? 0) / shown.electricityKwh : null} />}
        </>
      )}
    </section>
  )
}

/**
 * Use so far against the daily target: the delta in green under target, amber over, a bar of use so far with a mark where
 * use should be by now, and where the day is heading. For a week or month the same over the whole period.
 */
function TargetCard({ target, today }: { target: EnergyTarget; today: boolean }) {
  const under = target.deltaKwh <= 0
  const scale = Math.max(target.kwh, target.usedKwh, target.projectedKwh ?? 0, 0.001)
  return (
    <Card data-testid="energy-target">
      <CardContent className="flex flex-col gap-3 p-4">
        <div className="flex items-center gap-3">
          <div className="flex size-9 shrink-0 items-center justify-center rounded-xl bg-secondary [&_svg]:size-4">
            <Target />
          </div>
          <div className="min-w-0 flex-1">
            <div data-testid="target-delta" data-on-target={under} className={cn('text-lg font-bold tabular-nums', under ? 'text-green-600 dark:text-green-500' : 'text-amber-600 dark:text-amber-500')}>
              {targetDelta(target.deltaKwh, today)}
            </div>
            <div data-testid="target-progress" className="text-sm text-muted-foreground">
              {today ? targetProgress(target) : `${target.usedKwh.toFixed(1)} of ${target.kwh.toFixed(1)} kWh`}
            </div>
          </div>
        </div>
        <div className="relative h-3 w-full overflow-visible rounded-full bg-secondary" aria-hidden>
          <div className={cn('h-full rounded-full', under ? 'bg-green-600/70' : 'bg-amber-500/80')} style={{ width: `${(target.usedKwh / scale) * 100}%` }} />
          <div data-testid="target-expected" title="Where use should be by now" className="absolute -top-1 h-5 w-0.5 bg-foreground" style={{ left: `${(target.expectedKwh / scale) * 100}%` }} />
        </div>
        {target.incomplete && <div className="text-xs text-muted-foreground">{READINGS_MISSING}</div>}
      </CardContent>
    </Card>
  )
}

function Figure({ testId, icon, label, cost, detail }: { testId: string; icon: React.ReactNode; label: string; cost: string; detail: string }) {
  return (
    <Card data-testid={testId}>
      <CardContent className="flex items-center gap-3 p-3 sm:p-4">
        <div className="hidden size-9 shrink-0 items-center justify-center rounded-xl bg-secondary sm:flex [&_svg]:size-4">{icon}</div>
        <div className="min-w-0">
          <div className="text-xs text-muted-foreground sm:text-sm">{label}</div>
          <div className="text-xl font-bold tabular-nums">{cost}</div>
          <div className="truncate text-xs text-muted-foreground">{detail}</div>
        </div>
      </CardContent>
    </Card>
  )
}

/** Monitored plugs' use, "of which" under electricity: already in it, so never added to the total. */
function Devices({ devices, currency }: { devices: DeviceEnergy[]; currency: string }) {
  return (
    <Card data-testid="energy-devices">
      <CardContent className="flex flex-col gap-2 p-4">
        <div className="text-sm text-muted-foreground">Electricity, of which</div>
        <ul className="flex flex-col divide-y">
          {devices.map((d) => (
            <li key={d.meter} data-testid={`device-${d.meter}`} className="flex items-center justify-between gap-3 py-2 first:pt-0 last:pb-0">
              <span className="min-w-0 truncate font-medium">{d.meter}</span>
              <span className="flex shrink-0 items-center gap-2 tabular-nums">
                {d.offlineSince && <span className="text-xs text-heat-text">Offline</span>}
                <span className="text-xs text-muted-foreground">{formatKwh(d.kwh)}</span>
                <span className="font-semibold">{formatCost(d.cost, currency)}</span>
                {d.incomplete && <IncompleteWarning missingMinutes={d.missingMinutes} message={plugNowWarning(d)} />}
              </span>
            </li>
          ))}
        </ul>
      </CardContent>
    </Card>
  )
}

/** Each room's use, "of which" under electricity, then plugs in no room, then everything else last. Never added to the total. */
function Rooms({ rooms, devices, rest, currency }: { rooms: RoomEnergy[]; devices: DeviceEnergy[]; rest: EverythingElse | null; currency: string }) {
  const loose = devices.filter((d) => d.room == null)
  return (
    <Card data-testid="energy-rooms">
      <CardContent className="flex flex-col gap-2 p-4">
        <div className="text-sm text-muted-foreground">Electricity by room, of which</div>
        <ul className="flex flex-col divide-y">
          {rooms.map((r) => (
            <UseRow key={r.room} testId={`energy-room-${r.room}`} name={r.name} kwh={r.kwh} cost={r.cost} currency={currency} missingMinutes={r.incomplete ? r.missingMinutes : null} />
          ))}
          {loose.length > 0 && (
            <UseRow
              testId="energy-room-none"
              name="Not in a room"
              detail={loose.map((d) => d.meter).join(', ')}
              kwh={loose.reduce((sum, d) => sum + d.kwh, 0)}
              cost={loose.reduce((sum, d) => sum + d.cost, 0)}
              currency={currency}
              // Plugs are usually stale together, so the longest is the closest to the time any had no reading.
              missingMinutes={loose.some((d) => d.incomplete) ? Math.max(...loose.map((d) => d.missingMinutes)) : null}
            />
          )}
          {rest && (
            <UseRow testId="energy-room-rest" name="Everything else" kwh={rest.kwh} cost={rest.cost} currency={currency} missingMinutes={rest.incomplete ? rest.missingMinutes : null} />
          )}
        </ul>
      </CardContent>
    </Card>
  )
}

function UseRow({ testId, name, detail, kwh, cost, currency, missingMinutes }: { testId: string; name: string; detail?: string; kwh: number; cost: number; currency: string; missingMinutes: number | null }) {
  return (
    <li data-testid={testId} className="flex items-center justify-between gap-3 py-2 first:pt-0 last:pb-0">
      <span className="min-w-0">
        <span className="block truncate font-medium">{name}</span>
        {detail && <span className="block truncate text-xs text-muted-foreground">{detail}</span>}
      </span>
      <span className="flex shrink-0 items-center gap-2 tabular-nums">
        <span className="text-xs text-muted-foreground">{formatKwh(kwh)}</span>
        <span className="font-semibold">{formatCost(cost, currency)}</span>
        {missingMinutes != null && <IncompleteWarning missingMinutes={missingMinutes} />}
      </span>
    </li>
  )
}

/** A bar per day: gas, electricity and standing charge stacked; incomplete days are hatched and labelled. */
function DayBars({ days, currency, dailyTarget, price }: { days: EnergyDay[]; currency: string; dailyTarget: number | null; price: number | null }) {
  // The target line sits where the day's total would be with electricity at the target.
  const targetTotal = (d: EnergyDay) => (dailyTarget != null && price != null ? d.standingCost + d.gasCost + dailyTarget * price : null)
  const max = Math.max(1, ...days.map((d) => Math.max(d.totalCost, targetTotal(d) ?? 0)))
  const pct = (pence: number | null) => `${((pence ?? 0) / max) * 100}%`
  return (
    <Card>
      <CardContent className="flex flex-col gap-3 p-4">
        <div className="flex h-40 items-end gap-[2px]">
          {days.map((d) => (
            <div
              key={d.date}
              data-testid="energy-day"
              data-incomplete={d.incomplete}
              data-over-target={d.targetDeltaKwh != null && d.targetDeltaKwh > 0}
              title={`${d.date}: ${formatCost(d.totalCost, currency)}${d.targetDeltaKwh != null ? ` · ${targetDelta(d.targetDeltaKwh)}` : ''}${d.incomplete ? ` · Incomplete (${d.missingMinutes} min missing)` : ''}`}
              className={cn('relative flex h-full flex-1 flex-col-reverse', d.incomplete && 'opacity-50')}
            >
              {targetTotal(d) != null && <div data-testid="day-target" className="absolute inset-x-0 h-0.5 bg-foreground/70" style={{ bottom: pct(targetTotal(d)) }} />}
              {d.targetDeltaKwh != null && d.targetDeltaKwh > 0 && <div data-testid="day-over" className="absolute inset-x-0 -top-2 mx-auto size-1.5 rounded-full bg-amber-500" />}
              <div className="bg-muted-foreground/30" style={{ height: pct(d.standingCost) }} />
              <div className="bg-cool-foreground" style={{ height: pct(d.electricityCost) }} />
              <div className="rounded-t-sm bg-heat" style={{ height: pct(d.gasCost) }} />
            </div>
          ))}
        </div>
        <div className="flex flex-wrap gap-x-4 gap-y-1 text-xs text-muted-foreground">
          <span className="flex items-center gap-1.5"><span className="size-3 rounded-sm bg-heat" /> Gas</span>
          <span className="flex items-center gap-1.5"><span className="size-3 rounded-sm bg-cool-foreground" /> Electricity</span>
          <span className="flex items-center gap-1.5"><span className="size-3 rounded-sm bg-muted-foreground/30" /> Standing charges</span>
          {dailyTarget != null && <span className="flex items-center gap-1.5"><span className="h-0.5 w-3 bg-foreground/70" /> Daily target</span>}
          {days.some((d) => (d.targetDeltaKwh ?? 0) > 0) && <span className="flex items-center gap-1.5"><span className="size-1.5 rounded-full bg-amber-500" /> Over target</span>}
          {days.some((d) => d.incomplete) && <span>Faded: Incomplete days (the service or meter was not recording)</span>}
        </div>
      </CardContent>
    </Card>
  )
}
