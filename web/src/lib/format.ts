import type { BoilerState, BoilerStateName, Group, GroupMode, Mode, Room, Status, User, Zone } from './api'

export const MIN_TARGET = 5
export const MAX_TARGET = 30
export const MIN_PASSWORD = 10

export const formatTemp = (t: number | null | undefined) => (t == null ? '–' : `${t.toFixed(1)}°`)

export const stepTarget = (t: number, direction: 1 | -1) =>
  Math.min(MAX_TARGET, Math.max(MIN_TARGET, Math.round((t + direction * 0.5) * 2) / 2))

/** Chart colour for a room by its position in the history; past eight, rooms are drawn in muted grey. */
export const seriesColor = (index: number) => (index < 8 ? `var(--series-${index + 1})` : 'var(--muted-foreground)')

export const modeLabel: Record<Mode, string> = { schedule: 'Schedule', manual: 'Manual', off: 'Off' }

export function time(iso: string): string {
  return new Date(iso).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
}

export function dateTime(iso: string): string {
  return new Date(iso).toLocaleString([], { weekday: 'short', day: 'numeric', month: 'short', hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })
}

/** One line under a zone's target: what it's doing and until when. */
export function zoneSummary(zone: Zone): string {
  if (zone.boost) return `Boost to ${formatTemp(zone.boost.target)} until ${time(zone.boost.endsAt)}`
  if (zone.override) return `Held at ${formatTemp(zone.override.target)} until ${time(zone.override.until)}`
  if (zone.mode === 'off') return 'Off'
  if (zone.mode === 'manual') return `Manual at ${formatTemp(zone.manualTarget)}`
  if (zone.nextChange) return `${formatTemp(zone.nextChange.target)} at ${time(zone.nextChange.at)}`
  return 'Following schedule'
}

export const roomHeating = (room: Room) => room.heating || room.zones.some((z) => z.heating)

export const groupModeLabel: Record<GroupMode, string> = { ...modeLabel, mixed: 'Mixed' }

/** One line for a group: its shared mode and target, or "Mixed" when its rooms differ. */
export function groupSummary(group: Group): string {
  if (group.rooms.length === 0) return 'No rooms yet'
  if (group.mode === 'mixed') return group.target == null ? 'Mixed' : `Mixed · ${formatTemp(group.target)}`
  if (group.mode === 'off') return 'Off'
  if (group.mode === 'manual') return `Manual at ${formatTemp(group.target)}`
  return group.target == null ? 'Schedule' : `Schedule · ${formatTemp(group.target)}`
}

export const boostLength = (minutes: number) => (minutes < 60 ? `${minutes} min` : `${minutes / 60} h`)

/** The room has a sensor but is measured by its valves, because the sensor is offline or silent. */
export const sensorOffline = (room: Room) => room.sensor != null && room.temperatureSource !== 'sensor'

/** The room is measured by its (online) sensor. */
export const measuredBySensor = (room: Room) => room.sensor != null && room.temperatureSource === 'sensor'

/** The service's room notice while [sensor] is offline; the offline badge says the same. */
export const sensorOfflineNotice = (sensor: string) => `${sensor} is offline`

/**
 * When the boosts in [rooms] end: "Boosting until 15:30" when they all end together, or the
 * count and the latest end ("2 rooms boosting · until 15:30") when they differ. Null if none boosts.
 */
export function boostEnds(rooms: Room[]): { count: number; label: string; differ: boolean } | null {
  const ends = rooms.flatMap((r) => r.zones.flatMap((z) => (z.boost ? [z.boost.endsAt] : [])))
  if (ends.length === 0) return null
  const count = rooms.filter((r) => r.zones.some((z) => z.boost)).length
  const latest = time(ends.reduce((a, b) => (Date.parse(b) > Date.parse(a) ? b : a)))
  const differ = new Set(ends.map(time)).size > 1
  const label = differ ? `${count === 1 ? '1 room' : `${count} rooms`} boosting · until ${latest}` : `Boosting until ${latest}`
  return { count, label, differ }
}

/** What the boiler is doing, from the status; older services only say whether demand is on. */
export function boilerState(status: Status | null | undefined): BoilerState {
  if (status?.boiler) return status.boiler
  return { state: status?.heatDemand ? 'heating' : 'idle', until: null }
}

/** "3:12" until [until] from [now] (ms), or null once it has passed. */
export function countdown(until: string, now: number): string | null {
  const left = Math.ceil((Date.parse(until) - now) / 1000)
  if (!(left > 0)) return null
  return `${Math.floor(left / 60)}:${String(left % 60).padStart(2, '0')}`
}

/**
 * The boiler chip's label, with a countdown while cycling or resting. Once the countdown is over it
 * says "any moment" until the service's next status.updated arrives.
 */
export function boilerLabel(boiler: BoilerState, now: number): string {
  const left = () => (boiler.until ? countdown(boiler.until, now) : null) ?? 'any moment'
  switch (boiler.state) {
    case 'heating':
      return 'Heating'
    case 'cycling':
      return `Finishing run · ${left()}`
    case 'resting':
      return `Resting · ${left()}`
    default:
      return 'Idle'
  }
}

/** What each boiler state means, for the chip's note. */
export const boilerExplanation: Record<BoilerStateName, string> = {
  heating: 'A room needs heat, so the boiler is firing.',
  cycling:
    "No room needs heat any more, but the boiler finishes its minimum 4-minute run so it doesn't switch on and off too often (short-cycling wears it and wastes gas). It turns off when the countdown ends.",
  resting:
    'A room wants heat, but the boiler only stopped a moment ago. It rests for 5 minutes between runs to avoid short-cycling, then starts.',
  idle: 'No room needs heat right now.',
}

/** The room is asking for heat: one of its zones calls for heat, or its valves report heating. */
export const roomCallingForHeat = (room: Room) => room.heating || room.zones.some((z) => z.callingForHeat)

/**
 * The temperature [room] is heating to: a zone's boost target while boosted, else the room's target
 * (each zone's own target for independent rooms). Null when its zones differ or it has none.
 */
export function roomHeatTarget(room: Room): number | null {
  const targets = room.zones.map((z) => (z.boost ? z.boost.target : room.control === 'independent' ? z.target : room.target))
  return targets.length === 0 ? room.target : sharedTarget(targets)
}

/** The one target all of [targets] share, or null when they differ or any is unknown. */
function sharedTarget(targets: (number | null)[]): number | null {
  const first = targets[0]
  return first != null && targets.every((t) => t === first) ? first : null
}

/**
 * The rooms page headline: which rooms are heating and to what, or else what the boiler is doing.
 * "Heating Bedroom 3 to 20.0°", "Heating the Kids group to 21.0°", "Heating 3 rooms", "Boiler resting",
 * "On holiday", "Heating is off", "All rooms comfortable".
 */
export function headline(rooms: Room[], groups: Group[], status: Status | null | undefined): string {
  const heating = rooms.filter(roomCallingForHeat)
  const to = (target: number | null) => (target == null ? '' : ` to ${formatTemp(target)}`)

  if (heating.length === 1) return `Heating ${heating[0].name}${to(roomHeatTarget(heating[0]))}`
  if (heating.length > 1) {
    const target = sharedTarget(heating.map(roomHeatTarget))
    const ids = new Set(heating.map((r) => r.id))
    const group = groups.find((g) => g.rooms.length === ids.size && g.rooms.every((id) => ids.has(id)))
    return group ? `Heating the ${group.name} group${to(target)}` : `Heating ${heating.length} rooms${to(target)}`
  }

  const boiler = boilerState(status).state
  if (boiler === 'cycling') return 'Boiler finishing its run'
  if (boiler === 'resting') return 'Boiler resting'
  if (status?.holiday?.active) return 'On holiday'
  if (rooms.length > 0 && rooms.every((r) => r.mode === 'off')) return 'Heating is off'
  return 'All rooms comfortable'
}

/** What a member sees before an admin has given them any rooms. */
export const NO_ROOMS_MEMBER = 'No rooms yet. Ask an admin to give you access.'

/** How many rooms a person sees, for the People list. */
export function roomsSummary(user: User): string {
  if (user.admin) return 'Sees every room'
  const n = user.rooms?.length ?? 0
  return n === 0 ? 'Sees no rooms' : `Sees ${n} room${n === 1 ? '' : 's'}`
}

/** A member's full new room list after ticking [roomId] on or off, in the order of [rooms] (every room). */
export function tickRoom(rooms: Room[], ids: string[], roomId: string, ticked: boolean): string[] {
  return rooms.map((r) => r.id).filter((id) => (id === roomId ? ticked : ids.includes(id)))
}
