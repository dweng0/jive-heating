import { useEffect, useRef, useState } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { ArrowLeft, ChevronRight, Flame, Minus, Pencil, Plus, Trash2, X, Zap } from 'lucide-react'
import {
  AlertDialog,
  AlertDialogAction,
  AlertDialogCancel,
  AlertDialogContent,
  AlertDialogDescription,
  AlertDialogFooter,
  AlertDialogHeader,
  AlertDialogTitle,
} from '@/components/ui/alert-dialog'
import { Button } from '@/components/ui/button'
import { Card, CardContent, CardDescription, CardHeader, CardTitle } from '@/components/ui/card'
import { Tabs, TabsList, TabsTrigger } from '@/components/ui/tabs'
import { Dial } from '@/components/heating/Dial'
import { GroupDialog } from '@/components/heating/GroupDialog'
import { api, type Group, type Mode, type Room } from '@/lib/api'
import { SensorInfo, SensorOfflineBadge } from '@/components/heating/RoomSensor'
import { boostEnds, boostLength, formatTemp, groupSummary, measuredBySensor, roomHeating, sensorOffline, stepTarget, zoneSummary } from '@/lib/format'
import { useHome } from '@/lib/home'
import { cn } from '@/lib/utils'

const BOOST_MINUTES = [30, 60, 90, 120, 150, 180] as const
const FALLBACK_TARGET = 21

export function GroupPage() {
  const { id } = useParams()
  const { groups, loading } = useHome()
  const group = groups.find((g) => g.id === id)

  if (!group) {
    return loading ? null : (
      <div className="flex flex-col items-center gap-4 py-24 text-center">
        <p className="text-lg font-semibold">That group doesn't exist any more.</p>
        <Button asChild variant="outline">
          <Link to="/">Back to rooms</Link>
        </Button>
      </div>
    )
  }
  return <GroupDetail group={group} />
}

function GroupDetail({ group }: { group: Group }) {
  const { rooms } = useHome()
  const [editing, setEditing] = useState(false)
  const [deleting, setDeleting] = useState(false)
  const members = rooms.filter((r) => group.rooms.includes(r.id))

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center gap-3">
        <Button asChild variant="ghost" size="icon-lg" aria-label="Back to rooms">
          <Link to="/">
            <ArrowLeft />
          </Link>
        </Button>
        <div className="min-w-0">
          <p className="text-sm font-medium text-muted-foreground">Group</p>
          <h1 className="truncate text-3xl font-bold tracking-tight">{group.name}</h1>
        </div>
        <Button variant="ghost" size="icon" onClick={() => setEditing(true)} aria-label="Edit group">
          <Pencil />
        </Button>
        <Button variant="ghost" size="icon" className="ml-auto text-destructive" onClick={() => setDeleting(true)} aria-label="Delete group">
          <Trash2 />
        </Button>
      </div>

      {members.length === 0 ? (
        <Card className="flex flex-col items-center gap-3 border-dashed py-12 text-center text-muted-foreground">
          <p>This group has no rooms yet.</p>
          <Button variant="outline" onClick={() => setEditing(true)}>
            <Pencil /> Choose rooms
          </Button>
        </Card>
      ) : (
        <div className="grid gap-6 lg:grid-cols-[minmax(0,26rem)_1fr]">
          <div className="flex min-w-0 flex-col gap-6">
            <GroupControls group={group} members={members} />
            <GroupBoost group={group} members={members} />
          </div>
          <GroupRooms members={members} onEdit={() => setEditing(true)} />
        </div>
      )}

      <GroupDialog group={group} open={editing} onOpenChange={setEditing} />
      <DeleteGroupDialog group={group} open={deleting} onOpenChange={setDeleting} />
    </div>
  )
}

/** Dial with +/- (sent after a short pause, as manual, to every room), and the mode for every room. */
function GroupControls({ group, members }: { group: Group; members: Room[] }) {
  const { run, putGroup, refresh } = useHome()
  const [pending, setPending] = useState<number | null>(null)
  const timer = useRef<number | undefined>(undefined)
  const shown = pending ?? group.target ?? FALLBACK_TARGET
  const boosted = members.some((r) => r.zones.some((z) => z.boost))
  const off = group.mode === 'off' && !boosted

  useEffect(() => () => window.clearTimeout(timer.current), [])

  async function act(action: () => Promise<Group>, failure: string) {
    const g = await run(action, failure)
    if (g) {
      putGroup(g)
      await refresh()
    }
  }

  function nudge(direction: 1 | -1) {
    const next = stepTarget(shown, direction)
    setPending(next)
    window.clearTimeout(timer.current)
    timer.current = window.setTimeout(async () => {
      await act(() => api.groupMode(group.id, 'manual', next), "Couldn't set the temperature")
      setPending(null)
    }, 700)
  }

  function setMode(mode: Mode) {
    void act(() => api.groupMode(group.id, mode, mode === 'manual' ? shown : undefined), "Couldn't change the rooms")
  }

  return (
    <Card className="gap-0 overflow-hidden">
      <CardContent className="flex flex-col gap-6 p-6">
        <div className="flex items-center justify-center gap-2 sm:gap-6">
          <Button variant="outline" size="icon-lg" className="size-12 rounded-full" disabled={off} onClick={() => nudge(-1)} aria-label="Cooler for the group">
            <Minus className="size-5" />
          </Button>
          <div className="w-full max-w-64">
            <Dial target={group.target == null && pending == null && group.mode === 'mixed' ? null : shown} current={group.current} heating={group.heating} off={off} label={group.name} />
          </div>
          <Button variant="outline" size="icon-lg" className="size-12 rounded-full" disabled={off} onClick={() => nudge(1)} aria-label="Warmer for the group">
            <Plus className="size-5" />
          </Button>
        </div>

        <p className="-mt-2 text-center text-sm text-muted-foreground">
          {group.heating && !off && <Flame className="mr-1 inline size-4 text-heat" />}
          {pending != null
            ? `Setting ${members.length === 1 ? 'the room' : `all ${members.length} rooms`} to ${formatTemp(pending)}…`
            : group.mode === 'mixed'
              ? 'Rooms are set differently'
              : groupSummary(group)}
        </p>

        <Tabs value={group.mode === 'mixed' ? '' : group.mode} onValueChange={(m) => setMode(m as Mode)}>
          <TabsList className="grid h-11 w-full grid-cols-3">
            <TabsTrigger value="schedule">Schedule</TabsTrigger>
            <TabsTrigger value="manual">Manual</TabsTrigger>
            <TabsTrigger value="off">Off</TabsTrigger>
          </TabsList>
        </Tabs>
      </CardContent>
    </Card>
  )
}

/** Boost every room in the group for a while, or cancel their boosts. */
function GroupBoost({ group, members }: { group: Group; members: Room[] }) {
  const { run, putGroup, refresh, status, boostTarget } = useHome()
  const [minutes, setMinutes] = useState<number>(60)
  const [chosen, setTarget] = useState<number | null>(null)
  const target = chosen ?? boostTarget
  const ends = boostEnds(members)
  const holiday = status?.holiday?.active === true

  async function act(action: () => Promise<Group>, failure: string) {
    const g = await run(action, failure)
    if (g) {
      putGroup(g)
      await refresh()
    }
  }

  return (
    <Card className="gap-0 overflow-hidden">
      <CardContent className="flex flex-col gap-4 p-6">
        <div className="flex items-center gap-1.5 text-sm font-medium text-muted-foreground">
          <Zap className="size-4" /> Boost the group
        </div>

        {ends && (
          <div className="flex items-center gap-3 rounded-xl bg-heat-container px-4 py-3 text-heat-text">
            <Zap className="size-5 shrink-0" />
            <div className="min-w-0 flex-1 text-sm">
              <div className="font-semibold">{ends.label}</div>
              {!ends.differ && <div>{ends.count === 1 ? '1 room boosting' : `${ends.count} rooms boosting`}</div>}
            </div>
            <Button
              variant="ghost"
              size="sm"
              className="text-heat-text hover:bg-heat/10"
              onClick={() => act(() => api.groupCancelBoosts(group.id), "Couldn't cancel the boost")}
            >
              <X /> Cancel boost
            </Button>
          </div>
        )}

        <div className="grid grid-cols-3 gap-2" role="group" aria-label="Boost length">
          {BOOST_MINUTES.map((m) => (
            <Button
              key={m}
              type="button"
              variant={m === minutes ? 'default' : 'secondary'}
              aria-pressed={m === minutes}
              className="h-10"
              onClick={() => setMinutes(m)}
            >
              {boostLength(m)}
            </Button>
          ))}
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <div className="flex items-center gap-1 rounded-xl bg-secondary/60 p-1">
            <Button variant="ghost" size="icon-lg" className="rounded-full" onClick={() => setTarget(stepTarget(target, -1))} aria-label="Cooler boost">
              <Minus />
            </Button>
            <span className="w-16 text-center text-xl font-bold tabular-nums">{formatTemp(target)}</span>
            <Button variant="ghost" size="icon-lg" className="rounded-full" onClick={() => setTarget(stepTarget(target, 1))} aria-label="Warmer boost">
              <Plus />
            </Button>
          </div>
          <Button
            className="h-10 flex-1"
            disabled={holiday}
            onClick={() => act(() => api.groupBoost(group.id, minutes, target), "Couldn't boost")}
          >
            <Zap /> Boost {boostLength(minutes)}
          </Button>
        </div>
        {holiday && <p className="text-sm text-muted-foreground">Boost is unavailable during a holiday.</p>}
      </CardContent>
    </Card>
  )
}

function GroupRooms({ members, onEdit }: { members: Room[]; onEdit: () => void }) {
  return (
    <Card>
      <CardHeader className="flex flex-row items-start justify-between gap-4">
        <div>
          <CardTitle>Rooms in this group</CardTitle>
          <CardDescription>Changes here apply to every one of them.</CardDescription>
        </div>
        <Button variant="outline" size="sm" onClick={onEdit}>
          <Pencil /> Edit
        </Button>
      </CardHeader>
      <CardContent className="flex flex-col gap-1">
        {members.map((r) => {
          const heating = roomHeating(r)
          // The row opens the room (the name's link stretches over it); the sensor info button sits above that link.
          return (
            <div
              key={r.id}
              data-testid="group-room"
              className="relative flex items-center gap-3 rounded-xl px-2 py-2.5 hover:bg-secondary/50 has-[a:focus-visible]:ring-3 has-[a:focus-visible]:ring-ring/50"
            >
              <div className={cn('flex size-9 shrink-0 items-center justify-center rounded-lg', heating ? 'bg-heat-container text-heat-text' : 'bg-secondary')}>
                <Flame className={cn('size-4', !heating && 'text-muted-foreground')} />
              </div>
              <div className="min-w-0 flex-1">
                <Link to={`/rooms/${encodeURIComponent(r.id)}`} className="block truncate font-medium after:absolute after:inset-0 focus-visible:outline-none">
                  {r.name}
                </Link>
                <div className="truncate text-xs text-muted-foreground">{r.zones[0] ? zoneSummary(r.zones[0]) : 'No valves yet'}</div>
                {sensorOffline(r) && <SensorOfflineBadge className="mt-1.5" />}
              </div>
              {measuredBySensor(r) && r.sensor && <SensorInfo sensor={r.sensor} className="relative z-10" />}
              <span className="text-lg font-semibold tabular-nums">{formatTemp(r.current)}</span>
              <ChevronRight className="size-4 shrink-0 text-muted-foreground" />
            </div>
          )
        })}
      </CardContent>
    </Card>
  )
}

function DeleteGroupDialog({ group, open, onOpenChange }: { group: Group; open: boolean; onOpenChange: (o: boolean) => void }) {
  const { run, removeGroup } = useHome()
  const navigate = useNavigate()

  async function remove() {
    const ok = await run(() => api.deleteGroup(group.id).then(() => true), "Couldn't delete the group")
    if (ok) {
      removeGroup(group.id)
      navigate('/')
    }
  }

  return (
    <AlertDialog open={open} onOpenChange={onOpenChange}>
      <AlertDialogContent>
        <AlertDialogHeader>
          <AlertDialogTitle>Delete {group.name}?</AlertDialogTitle>
          <AlertDialogDescription>The group goes away. Its rooms, their settings and schedules stay as they are.</AlertDialogDescription>
        </AlertDialogHeader>
        <AlertDialogFooter>
          <AlertDialogCancel>Cancel</AlertDialogCancel>
          <AlertDialogAction onClick={remove} className="bg-destructive text-white hover:bg-destructive/90">
            Delete group
          </AlertDialogAction>
        </AlertDialogFooter>
      </AlertDialogContent>
    </AlertDialog>
  )
}
