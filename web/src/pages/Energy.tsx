import { EnergySection } from '@/components/heating/EnergySection'
import { LivePowerSection } from '@/components/heating/LivePowerSection'

/** What the house uses: electricity now first, then use and cost for today, the week or the month. Admin only. */
export function EnergyPage() {
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
