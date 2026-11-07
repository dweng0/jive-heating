import { useEffect, useRef, useState } from 'react'
import { GasCard } from './GasCard'
import { energyApi, type EnergyDashboard } from '@/lib/energy'
import { followBusiest, NO_BUSIEST, places, totalsApi, type Busiest, type EnergyTotals, type TodayPeak } from '@/lib/energyDials'
import { followPower, powerApi, type LivePower, type PowerBands } from '@/lib/livePower'
import { useHome } from '@/lib/home'
import { EnergyDials } from './EnergyDials'
import { HouseHistoryChart } from './HouseHistoryChart'
import { OtherAreas } from './OtherAreas'
import { TripMeters } from './TripMeters'
import { UseCard } from './UseCard'

/** Today's kWh, cost and peak are recorded every five minutes; there is no point asking more often. */
const TODAY_EVERY_MS = 5 * 60_000

/**
 * The top of the Energy tab: the dials (today's peak, the whole house, the busiest room or area, the daily target), the
 * history chart, then a live card per room, area and "Everything else". Admins and people with energy access; only
 * admins' cards open their energy pages.
 */
export function LivePowerSection() {
  const { me } = useHome()
  const admin = me.admin
  const [live, setLive] = useState<LivePower | null>(null)
  const [bands, setBands] = useState<PowerBands | null>(null)
  const [peak, setPeak] = useState<TodayPeak | null>(null)
  const [today, setToday] = useState<EnergyDashboard | null>(null)
  const [busiest, setBusiest] = useState<Busiest>(NO_BUSIEST)
  const busiestRef = useRef(busiest)
  const [totals, setTotals] = useState<EnergyTotals | null>(null)
  /** The house's readings of the last half hour, for the trip meter's 5-minute average. */
  const [readings, setReadings] = useState<{ at: number; watts: number | null }[]>([])

  useEffect(() => {
    let cancelled = false
    const loadEvery5 = () => {
      powerApi.summary().then(
        (s) => {
          if (cancelled) return
          setBands(s.bands)
          setPeak(s.todayPeak ?? null)
          setReadings((r) => (r.length > 0 ? r : s.recent.map((p) => ({ at: Date.parse(p.at), watts: p.watts }))))
        },
        () => {},
      )
      totalsApi.get().then((t) => !cancelled && setTotals(t), () => {})
      energyApi.dashboard('today').then((d) => !cancelled && setToday(d), () => {})
    }
    loadEvery5()
    const timer = window.setInterval(loadEvery5, TODAY_EVERY_MS)
    const stop = followPower(
      (p) => {
        setLive(p)
        if (p.at != null) {
          const at = Date.parse(p.at)
          setReadings((r) => [...r.filter((q) => q.at > at - 30 * 60_000 && q.at !== at), { at, watts: p.watts }])
        }
        const next = followBusiest(busiestRef.current, places(p), Date.now())
        busiestRef.current = next
        setBusiest(next)
      },
      () => {},
    )
    return () => {
      cancelled = true
      window.clearInterval(timer)
      stop()
    }
  }, [])

  const watts = live?.watts ?? null
  const rooms = live?.rooms ?? []
  const devices = live?.devices ?? []
  const currency = today?.currency ?? 'GBP'
  const onDial = places(live).find((p) => p.id === busiest.current) ?? null

  const areas = (
    <OtherAreas live={live?.areas ?? []} connected={live != null} today={today} house={watts} currency={currency} admin={admin} />
  )
  // No house meter chosen: nothing to show live but the areas.
  if (today && today.electricityMeter == null) return areas

  return (
    <section data-testid="live-power" className="flex flex-col gap-4">
      <EnergyDials
        watts={watts}
        connected={live != null}
        bands={bands}
        busiest={onDial}
        peak={peak}
        today={today}
        admin={admin}
        counters={<TripMeters totals={totals} readings={readings} />}
      />

      <GasCard />

      <HouseHistoryChart />

      {(rooms.length > 0 || devices.length > 0) && (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4">
          {rooms.map((r) => {
            const day = today?.rooms?.find((d) => d.room === r.room)
            return (
              <UseCard
                key={r.room}
                to={admin ? `/energy/rooms/${encodeURIComponent(r.room)}` : undefined}
                testId={`live-room-${r.room}`}
                name={r.name}
                watts={r.watts}
                house={watts}
                kwh={day?.kwh}
                cost={day?.cost}
                currency={currency}
              />
            )
          })}
          {devices.map((d) => {
            const day = today?.devices?.find((e) => e.meter === d.meter)
            return <UseCard key={d.meter} testId={`live-device-${d.meter}`} name={d.meter} detail="Not in a room" watts={d.watts} house={watts} kwh={day?.kwh} cost={day?.cost} currency={currency} />
          })}
        </div>
      )}

      {areas}

      {/* The rest of the house: only while the house reading is live, as it is the house less the rest. */}
      {live?.elseWatts != null && (
        <div className="grid grid-cols-1 gap-3 sm:grid-cols-2 lg:grid-cols-3 2xl:grid-cols-4">
          <UseCard
            to={admin ? '/energy/else' : undefined}
            testId="live-rest"
            name="Everything else"
            detail="What no plug accounts for"
            watts={live.elseWatts}
            house={watts}
            kwh={today?.everythingElse?.kwh}
            cost={today?.everythingElse?.cost}
            currency={currency}
            muted
          />
        </div>
      )}
    </section>
  )
}
