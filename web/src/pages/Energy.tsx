import { useEffect } from 'react'
import { Link } from 'react-router-dom'
import { Settings2 } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { EnergySection } from '@/components/heating/EnergySection'
import { EnergyFlowCard } from '@/components/heating/EnergyFlowCard'
import { LivePowerSection } from '@/components/heating/LivePowerSection'
import { energyPlace } from '@/lib/energy'
import { useHome } from '@/lib/home'

/** What the house uses: the dials and history first, then use and cost for today, the week or the month. Admins and energy access. */
export function EnergyPage() {
  const { me } = useHome()
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
      <div className="flex flex-wrap items-end justify-between gap-3">
        <div>
          <h1 className="text-3xl font-bold tracking-tight">Energy</h1>
          <p className="text-muted-foreground">What the house is using now, and what it has cost</p>
        </div>
        {me.admin && (
          <Button asChild variant="outline">
            <Link to="/energy/manage">
              <Settings2 />
              Manage
            </Link>
          </Button>
        )}
      </div>
      <LivePowerSection />
      <EnergySection />
      <EnergyFlowCard />
    </div>
  )
}
