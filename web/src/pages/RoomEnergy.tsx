import { useCallback, useEffect, useState, type ReactNode } from 'react'
import { ArrowLeft, Info } from 'lucide-react'
import { Popover, PopoverContent, PopoverTrigger } from '@/components/ui/popover'
import { useLocation, useNavigate, useParams } from 'react-router-dom'
import { IncompleteWarning } from '@/components/heating/IncompleteWarning'
import { PlugTable } from '@/components/heating/PlugTable'
import { UsageCarousel, type UsageSlide } from '@/components/heating/UsageCarousel'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { ApiError, request, type Room } from '@/lib/api'
import { energyApi, formatCost, formatKwh, formatPower, ONLY_MEASURED, readsLow, type EnergyDashboard } from '@/lib/energy'
import { WHOLE, usageApi, type UsageWindow } from '@/lib/energyUsage'
import { followPower, type LivePower } from '@/lib/livePower'
import { roomPlugsApi, roomWarning, type RoomPlugs } from '@/lib/roomPlugs'

/** A plug's power is not on the live feed, only its room's; this keeps the table close behind it. */
const PLUGS_EVERY_MS = 15_000
/** Today's kWh and cost are recorded every five minutes; there is no point asking more often. */
const TODAY_EVERY_MS = 5 * 60_000

export function useLivePower() {
  const [live, setLive] = useState<LivePower | null>(null)
  useEffect(() => followPower(setLive, () => {}), [])
  return live
}

export function BackToEnergy() {
  const navigate = useNavigate()
  const location = useLocation()
  return (
    <Button type="button" variant="ghost" size="sm" className="self-start" onClick={() => (location.key === 'default' ? navigate('/energy') : navigate(-1))}>
      <ArrowLeft /> Energy
    </Button>
  )
}

function Figure({ testId, label, value, warning }: { testId: string; label: string; value: string; warning?: ReactNode }) {
  return (
    <Card data-testid={testId}>
      <CardContent className="flex flex-col gap-1 p-4">
        <div className="text-sm text-muted-foreground">{label}</div>
        <div className="flex items-center gap-2 text-2xl font-bold tabular-nums">
          {value}
          {warning}
        </div>
      </CardContent>
    </Card>
  )
}

export function Figures({ now, kwh, cost, currency, warning }: { now: string; kwh: number | undefined; cost: number | undefined; currency: string; warning?: ReactNode }) {
  return (
    <div className="grid grid-cols-1 gap-3 sm:grid-cols-3">
      <Figure testId="usage-now" label="Now" value={now} />
      <Figure testId="usage-today" label="Today" value={formatKwh(kwh)} warning={warning} />
      <Figure testId="usage-cost" label="Cost today" value={formatCost(cost, currency)} warning={warning} />
    </div>
  )
}

/** The small info icon beside a room's name: its figures are only what its plugs and meters measured. */
function OnlyMeasured() {
  return (
    <Popover>
      <PopoverTrigger
        aria-label="About these figures"
        className="inline-flex rounded-full text-muted-foreground hover:text-foreground focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none [&_svg]:size-5"
      >
        <Info />
      </PopoverTrigger>
      <PopoverContent data-testid="only-measured" className="max-w-80 text-sm">
        {ONLY_MEASURED}
      </PopoverContent>
    </Popover>
  )
}

/** "Now": its power, "No reading" with nothing reporting, never an old figure; nothing yet while connecting. */
export const nowText = (live: LivePower | null, watts: number | null | undefined) => (live == null ? '–' : watts == null ? 'No reading' : formatPower(watts))

/** One room's electricity: what it is using and costing, each plug over time, and its plugs side by side. Admin only. */
export function RoomEnergyPage() {
  const id = useParams().id!
  const live = useLivePower()
  const [room, setRoom] = useState<Room | null>(null)
  const [plugs, setPlugs] = useState<RoomPlugs | null>(null)
  const [missing, setMissing] = useState(false)
  const [slide, setSlide] = useState(0)

  useEffect(() => {
    let cancelled = false
    request<Room>('GET', `/api/rooms/${encodeURIComponent(id)}`).then(
      (r) => !cancelled && setRoom(r),
      (e) => !cancelled && e instanceof ApiError && e.status === 404 && setMissing(true),
    )
    const loadPlugs = () => roomPlugsApi.get(id).then((p) => !cancelled && setPlugs(p), () => {})
    loadPlugs()
    const timer = window.setInterval(loadPlugs, PLUGS_EVERY_MS)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [id])

  const load = useCallback((window: UsageWindow, to?: number) => usageApi.room(id, window, to), [id])

  if (missing) {
    return (
      <div className="flex flex-col gap-4">
        <BackToEnergy />
        <p data-testid="room-not-found" className="font-medium">Room not found</p>
      </div>
    )
  }

  const meters = plugs?.plugs.map((p) => p.meter) ?? []
  // A room with one plug has its chart alone: the room's would only repeat it.
  const slides: UsageSlide[] = meters.length === 1 ? [{ key: meters[0], name: meters[0] }] : [{ key: WHOLE, name: room?.name ?? 'Room' }, ...meters.map((m) => ({ key: m, name: m }))]
  const roomPower = live?.rooms.find((r) => r.room === id)

  return (
    <div className="flex flex-col gap-4">
      <BackToEnergy />
      <div className="flex items-center gap-2">
        <h1 className="text-3xl font-bold tracking-tight">{room?.name ?? ' '}</h1>
        <OnlyMeasured />
      </div>
      <Figures
        now={nowText(live, roomPower?.watts)}
        kwh={plugs?.kwh}
        cost={plugs?.cost}
        currency={plugs?.currency ?? 'GBP'}
        warning={plugs?.incomplete ? <IncompleteWarning missingMinutes={plugs.missingMinutes} message={roomWarning(plugs.plugs)} /> : undefined}
      />
      {plugs && <UsageCarousel load={load} slides={slides} slide={slide} onSlide={setSlide} />}
      {plugs && meters.length === 0 && <p data-testid="no-plugs" className="text-muted-foreground">No plugs in this room</p>}
      {plugs && meters.length > 0 && <PlugTable plugs={plugs} onPick={(meter) => setSlide(slides.findIndex((s) => s.key === meter))} />}
    </div>
  )
}

/** What no plug accounts for: the same dashboard as a room's, with one chart and no plugs to compare. Admin only. */
export function EverythingElseEnergyPage() {
  const live = useLivePower()
  const [today, setToday] = useState<EnergyDashboard | null>(null)

  useEffect(() => {
    let cancelled = false
    const loadToday = () => energyApi.dashboard('today').then((d) => !cancelled && setToday(d), () => {})
    loadToday()
    const timer = window.setInterval(loadToday, TODAY_EVERY_MS)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [])

  const rest = today?.everythingElse
  const noReading = live != null && live.elseWatts == null
  const load = useCallback((window: UsageWindow, to?: number) => usageApi.everythingElse(window, to), [])

  return (
    <div className="flex flex-col gap-4">
      <BackToEnergy />
      <div>
        <h1 className="text-3xl font-bold tracking-tight">Everything else</h1>
        <p className="text-muted-foreground">What no plug accounts for</p>
        {(rest?.sharedCircuits?.length ?? 0) > 0 && (
          <p data-testid="shared-circuits" className="text-sm text-muted-foreground">
            {readsLow(rest!.sharedCircuits!)}
          </p>
        )}
      </div>
      <Figures
        now={nowText(live, live?.elseWatts)}
        kwh={rest?.kwh}
        cost={rest?.cost}
        currency={today?.currency ?? 'GBP'}
        warning={rest?.incomplete || noReading ? <IncompleteWarning missingMinutes={rest?.missingMinutes ?? 0} /> : undefined}
      />
      <UsageCarousel load={load} slides={[{ key: WHOLE, name: 'Everything else' }]} slide={0} onSlide={() => {}} />
    </div>
  )
}
