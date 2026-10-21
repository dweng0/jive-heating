import { useState, type FormEvent } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { Flame, Plus, Snowflake, TriangleAlert } from 'lucide-react'
import { Badge } from '@/components/ui/badge'
import { Button } from '@/components/ui/button'
import { Card } from '@/components/ui/card'
import { Dialog, DialogContent, DialogFooter, DialogHeader, DialogTitle } from '@/components/ui/dialog'
import { Input } from '@/components/ui/input'
import { Skeleton } from '@/components/ui/skeleton'
import { GroupsSection } from '@/components/heating/GroupsSection'
import { SensorInfo, SensorOfflineBadge } from '@/components/heating/RoomSensor'
import { WholeHouseCard } from '@/components/heating/WholeHouseCard'
import { WaitingDevicesCard } from '@/components/heating/WaitingDevicesCard'
import { MonitoredDevicesCard } from '@/components/heating/MonitoredDevicesCard'
import { api, type Room } from '@/lib/api'
import { NO_ROOMS_MEMBER, formatTemp, headline, measuredBySensor, modeLabel, roomHeating, sensorOffline, sensorOfflineNotice, zoneSummary } from '@/lib/format'
import { useHome } from '@/lib/home'
import { cn } from '@/lib/utils'

function greeting(): string {
  const h = new Date().getHours()
  return h < 12 ? 'Good morning' : h < 18 ? 'Good afternoon' : 'Good evening'
}

export function RoomsPage() {
  const { me, rooms, groups, loading, status, live } = useHome()
  const [adding, setAdding] = useState(false)
  /** Bumped when a plug is monitored or put in a room from the waiting devices, so the monitored list reloads. */
  const [monitoredVersion, setMonitoredVersion] = useState(0)

  return (
    <div className="flex flex-col gap-8">
      <div className="flex flex-wrap items-end justify-between gap-4">
        <div className="min-w-0">
          <p className="text-sm font-medium text-muted-foreground">
            {greeting()}, {me.username}
          </p>
          <h1 className="mt-1 text-3xl font-bold tracking-tight break-words sm:text-4xl">
            {loading || rooms.length === 0 ? 'Your rooms' : headline(rooms, groups, status)}
          </h1>
          <p className="mt-1 flex items-center gap-1.5 text-sm text-muted-foreground">
            <span className={cn('size-1.5 rounded-full', live ? 'bg-ok-foreground' : 'bg-muted-foreground/50')} />
            {live ? 'Live' : 'Reconnecting…'}
          </p>
        </div>
        {me.admin && (
          <Button size="lg" className="h-10" onClick={() => setAdding(true)}>
            <Plus /> Add room
          </Button>
        )}
      </div>

      {/* A member sees only the rooms an admin gave them: no whole house, no groups. */}
      {!loading && me.admin && <WholeHouseCard />}

      {!loading && me.admin && <GroupsSection />}

      {!loading && me.admin && <WaitingDevicesCard onMonitored={() => setMonitoredVersion((v) => v + 1)} />}

      {!loading && me.admin && <MonitoredDevicesCard key={monitoredVersion} />}

      {loading ? (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {[0, 1, 2].map((i) => (
            <Skeleton key={i} className="h-48 rounded-2xl" />
          ))}
        </div>
      ) : rooms.length === 0 && !me.admin ? (
        <Card className="flex flex-col items-center gap-3 border-dashed py-16 text-center">
          <div className="flex size-12 items-center justify-center rounded-2xl bg-secondary text-muted-foreground">
            <Flame />
          </div>
          <p className="max-w-sm text-lg font-semibold">{NO_ROOMS_MEMBER}</p>
        </Card>
      ) : rooms.length === 0 ? (
        <Card className="flex flex-col items-center gap-3 border-dashed py-16 text-center">
          <div className="flex size-12 items-center justify-center rounded-2xl bg-heat-container text-heat-text">
            <Flame />
          </div>
          <div className="text-lg font-semibold">No rooms yet</div>
          <p className="max-w-sm text-sm text-muted-foreground">Add a room, then give it the radiator valves that are in it.</p>
          <Button onClick={() => setAdding(true)}>
            <Plus /> Add your first room
          </Button>
        </Card>
      ) : (
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
          {rooms.map((room) => (
            <RoomCard key={room.id} room={room} />
          ))}
        </div>
      )}

      {me.admin && <AddRoomDialog open={adding} onOpenChange={setAdding} />}
    </div>
  )
}

/**
 * A room tile. The whole tile opens the room (the name's link stretches over it), while the
 * sensor info button sits above that link, so it is a real button and not nested in the link.
 */
function RoomCard({ room }: { room: Room }) {
  const heating = roomHeating(room)
  const off = room.mode === 'off'
  const zone = room.zones[0]
  const offline = sensorOffline(room)
  // The offline badge already says this; the footer shows the next notice instead.
  const notices = offline && room.sensor ? room.notices.filter((n) => n !== sensorOfflineNotice(room.sensor!)) : room.notices
  return (
    <Card
      data-testid="room-tile"
      className={cn(
        'group relative h-full gap-0 overflow-hidden p-5 transition-all hover:-translate-y-0.5 hover:shadow-lg has-[a:focus-visible]:ring-3 has-[a:focus-visible]:ring-ring/50',
        heating && 'ring-1 ring-heat/40',
      )}
    >
      {heating && <div className="pointer-events-none absolute -top-16 -right-16 size-40 rounded-full bg-heat/20 blur-3xl" />}
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <h2 className="truncate text-lg font-semibold">
            <Link to={`/rooms/${encodeURIComponent(room.id)}`} className="after:absolute after:inset-0 focus-visible:outline-none">
              {room.name}
            </Link>
          </h2>
          <p className="text-sm text-muted-foreground">
            {room.valves.length === 0 ? 'No valves yet' : `${room.valves.length} valve${room.valves.length === 1 ? '' : 's'}`}
            {room.control === 'independent' && ' · independent'}
          </p>
        </div>
        <Badge
          variant="secondary"
          className={cn('shrink-0', heating ? 'bg-heat-container text-heat-text' : off ? 'bg-off text-off-foreground' : 'bg-cool text-cool-foreground')}
        >
          {heating ? <Flame /> : off ? null : <Snowflake />}
          {heating ? 'Heating' : off ? 'Off' : modeLabel[room.mode]}
        </Badge>
      </div>

      <div className="mt-6 flex items-end justify-between gap-4">
        <div>
          <div className="text-5xl font-bold tracking-tighter tabular-nums">{formatTemp(room.current)}</div>
          <div className="mt-1 flex items-center gap-0.5 text-sm text-muted-foreground">
            <span>now</span>
            {measuredBySensor(room) && room.sensor && <SensorInfo sensor={room.sensor} className="relative z-10" />}
          </div>
        </div>
        {!off && (
          <div className="text-right">
            <div className={cn('text-2xl font-semibold tabular-nums', heating && 'text-heat-text')}>{formatTemp(room.target)}</div>
            <div className="text-sm text-muted-foreground">target</div>
          </div>
        )}
      </div>

      {offline && <SensorOfflineBadge className="mt-3" />}

      <div className="mt-4 flex items-center gap-2 border-t border-border/60 pt-3 text-sm text-muted-foreground">
        {notices.length > 0 ? (
          <>
            <TriangleAlert className="size-4 text-heat-text" />
            <span className="truncate">{notices[0]}</span>
          </>
        ) : (
          <span className="truncate">{zone ? zoneSummary(zone) : '—'}</span>
        )}
      </div>
    </Card>
  )
}

function AddRoomDialog({ open, onOpenChange }: { open: boolean; onOpenChange: (open: boolean) => void }) {
  const { run, putRoom } = useHome()
  const [name, setName] = useState('')
  const navigate = useNavigate()

  async function submit(e: FormEvent) {
    e.preventDefault()
    if (!name.trim()) return
    const room = await run(() => api.createRoom(name.trim()), "Couldn't add room")
    if (room) {
      putRoom(room)
      setName('')
      onOpenChange(false)
      navigate(`/rooms/${encodeURIComponent(room.id)}`)
    }
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="sm:max-w-sm">
        <form onSubmit={submit} className="flex flex-col gap-4">
          <DialogHeader>
            <DialogTitle>New room</DialogTitle>
          </DialogHeader>
          <Input autoFocus placeholder="e.g. Living room" value={name} onChange={(e) => setName(e.target.value)} />
          <DialogFooter>
            <Button type="button" variant="ghost" onClick={() => onOpenChange(false)}>
              Cancel
            </Button>
            <Button type="submit" disabled={!name.trim()}>
              Add room
            </Button>
          </DialogFooter>
        </form>
      </DialogContent>
    </Dialog>
  )
}
