import { useState } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Flame, Layers, Plus, Snowflake, Zap } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { GroupDialog } from '@/components/heating/GroupDialog'
import type { Group, Room } from '@/lib/api'
import { boostEnds, formatTemp, groupModeLabel, groupSummary } from '@/lib/format'
import { useHome } from '@/lib/home'
import { cn } from '@/lib/utils'

/** The Groups on the Rooms page: a tile per group, and a way to make a new one. */
export function GroupsSection() {
  const { groups, rooms } = useHome()
  const [adding, setAdding] = useState(false)
  const navigate = useNavigate()
  if (rooms.length === 0) return null

  return (
    <section aria-labelledby="groups-heading" className="flex flex-col gap-4">
      <div className="flex items-center justify-between gap-4">
        <h2 id="groups-heading" className="text-xl font-semibold tracking-tight">
          Groups
        </h2>
        <Button variant="outline" onClick={() => setAdding(true)}>
          <Plus /> New group
        </Button>
      </div>

      {groups.length === 0 ? (
        <Card className="flex flex-row items-center gap-4 border-dashed p-5 text-sm text-muted-foreground">
          <div className="flex size-10 shrink-0 items-center justify-center rounded-xl bg-secondary text-foreground">
            <Layers className="size-5" />
          </div>
          <p>Group rooms, like Upstairs or Bedrooms, to set them all at once.</p>
        </Card>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {groups.map((g) => (
            <GroupCard key={g.id} group={g} rooms={rooms} />
          ))}
        </div>
      )}

      <GroupDialog open={adding} onOpenChange={setAdding} onSaved={(g) => navigate(`/groups/${encodeURIComponent(g.id)}`)} />
    </section>
  )
}

function GroupCard({ group, rooms }: { group: Group; rooms: Room[] }) {
  const heating = group.heating
  const off = group.mode === 'off'
  // The card only lights up when every room heats; otherwise a flame marks the rooms that are.
  const members = group.rooms.flatMap((id) => rooms.filter((r) => r.id === id))
  const heatingRoom = (id: string) => !heating && (group.heatingRooms ?? []).includes(id)
  const ends = boostEnds(rooms.filter((r) => group.rooms.includes(r.id)))
  return (
    <Link
      to={`/groups/${encodeURIComponent(group.id)}`}
      aria-label={`Group ${group.name}`}
      className="group rounded-2xl focus-visible:ring-3 focus-visible:ring-ring/50 focus-visible:outline-none"
    >
      <Card
        className={cn(
          'relative h-full gap-0 overflow-hidden p-5 transition-all group-hover:-translate-y-0.5 group-hover:shadow-lg',
          heating && 'ring-1 ring-heat/40',
        )}
      >
        {heating && <div className="pointer-events-none absolute -top-16 -right-16 size-40 rounded-full bg-heat/20 blur-3xl" />}
        <div className="flex items-start justify-between gap-2">
          <div className="min-w-0">
            <h3 className="truncate text-lg font-semibold">{group.name}</h3>
            <p className="text-sm text-muted-foreground">
              {group.rooms.length === 1 ? '1 room' : `${group.rooms.length} rooms`}
            </p>
          </div>
          <Badge
            variant="secondary"
            className={cn('shrink-0', heating ? 'bg-heat-container text-heat-text' : off ? 'bg-off text-off-foreground' : 'bg-cool text-cool-foreground')}
          >
            {heating ? <Flame /> : off ? null : <Snowflake />}
            {heating ? 'Heating' : groupModeLabel[group.mode]}
          </Badge>
        </div>

        <div className="mt-6 flex items-end justify-between gap-4">
          <div>
            <div className="text-5xl font-bold tracking-tighter tabular-nums">{formatTemp(group.current)}</div>
            <div className="mt-1 text-sm text-muted-foreground">now</div>
          </div>
          {!off && (
            <div className="text-right">
              <div className={cn('text-2xl font-semibold tabular-nums', heating && 'text-heat-text')}>{formatTemp(group.target)}</div>
              <div className="text-sm text-muted-foreground">{group.target == null && group.rooms.length > 0 ? 'targets differ' : 'target'}</div>
            </div>
          )}
        </div>

        <div className="mt-4 flex flex-col gap-0.5 border-t border-border/60 pt-3 text-sm text-muted-foreground">
          <span className="truncate font-medium text-foreground/80">{groupSummary(group)}</span>
          {ends && (
            <span className="flex items-center gap-1 truncate font-medium text-heat-text">
              <Zap className="size-4 shrink-0" /> {ends.label}
            </span>
          )}
          <span className="truncate">
            {members.length > 0
              ? members.map((r, i) => (
                  <span key={r.id}>
                    {i > 0 && ', '}
                    {r.name}
                    {heatingRoom(r.id) && (
                      <Flame className="ml-0.5 inline size-3.5 align-[-2px] text-heat-text" aria-label="heating" role="img" />
                    )}
                  </span>
                ))
              : 'No rooms yet'}
          </span>
        </div>
      </Card>
    </Link>
  )
}
