import { useEffect } from 'react'
import { EnergySection } from '@/components/heating/EnergySection'
import { LivePowerSection } from '@/components/heating/LivePowerSection'
import { energyPlace } from '@/lib/energy'

/** What the house uses: electricity now first, then use and cost for today, the week or the month. Admin only. */
export function EnergyPage() {
  // Back from a room's dashboard: to where it was, once the page has grown tall enough to scroll there.
  useEffect(() => {
    const y = energyPlace.scrollY
    energyPlace.scrollY = null
    if (y == null) return
    let frame = 0
    const until = performance.now() + 2000
    const step = () => {
      window.scrollTo(0, y)
      if (window.scrollY < y && performance.now() < until) frame = requestAnimationFrame(step)
    }
    frame = requestAnimationFrame(step)
    return () => cancelAnimationFrame(frame)
  }, [])

  return (
    <div className="flex flex-col gap-6">
      <div>
        <h1 className="text-3xl font-bold tracking-tight">Energy</h1>
        <p className="text-muted-foreground">What the house is using now, and what it has cost</p>
      </div>
      <LivePowerSection />
      <EnergySection />
    </div>
  )
}
