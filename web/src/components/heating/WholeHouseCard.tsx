import { House, X, Zap } from 'lucide-react'
import { Button } from '@/components/ui/button'
import { Card, CardContent } from '@/components/ui/card'
import { api, type Mode } from '@/lib/api'
import { formatTemp } from '@/lib/format'
import { useHome } from '@/lib/home'

const BOOSTS = [30, 60, 120] as const

/** One control for every room: boost everything, or end every boost. */
export function WholeHouseCard() {
  const { rooms, run, refresh, status, boostTarget } = useHome()
  const zones = rooms.flatMap((r) => r.zones).filter((z) => z.valves.length > 0)
  if (zones.length < 2) return null

  const boosted = zones.filter((z) => z.boost).length
  const modes = new Set(zones.map((z) => z.mode))
  const summary =
    boosted === zones.length
      ? 'Every room is boosted'
      : modes.size === 1
        ? { schedule: 'Every room is on its schedule', manual: 'Every room is on manual', off: 'Every room is off' }[[...modes][0] as Mode]
        : 'Rooms are set differently'
  const holiday = status?.holiday?.active

  async function act(action: () => Promise<unknown>, failure: string) {
    const done = await run(action, failure)
    if (done) await refresh()
  }

  return (
    <Card className="gap-0 overflow-hidden bg-gradient-to-br from-heat-container/70 via-card to-card p-0">
      <CardContent className="flex flex-col gap-5 p-5 sm:p-6">
        <div className="flex items-center gap-3">
          <div className="flex size-10 items-center justify-center rounded-xl bg-heat text-primary-foreground shadow-md shadow-heat/30">
            <House className="size-5" />
          </div>
          <div>
            <h2 className="text-lg font-semibold">Whole house</h2>
            <p className="text-sm text-muted-foreground">{summary}</p>
          </div>
        </div>

        {/* Boost only: boost every room for a while, or end every boost. */}
        <div className="flex flex-wrap items-center gap-2">
          <span className="flex items-center gap-1.5 px-1 text-sm font-medium text-muted-foreground">
            <Zap className="size-4" /> Boost all to {formatTemp(boostTarget)}
          </span>
          {BOOSTS.map((m) => (
            <Button
              key={m}
              variant="secondary"
              className="h-10"
              disabled={holiday}
              onClick={() => act(() => api.houseBoost(m, boostTarget), "Couldn't boost")}
            >
              {m < 60 ? `${m} min` : `${m / 60} h`}
            </Button>
          ))}
          <span className="flex-1" />
          <Button
            variant="outline"
            className="h-10"
            disabled={boosted === 0}
            onClick={() => act(() => api.houseCancelBoosts(), "Couldn't end boosts")}
          >
            <X /> Boost off
          </Button>
        </div>
      </CardContent>
    </Card>
  )
}
