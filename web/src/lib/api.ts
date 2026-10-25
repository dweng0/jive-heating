// Mirrors the schemas in the service's src/main/resources/openapi.json (the contract).
// The web app never holds a token: sign-in sets an HttpOnly cookie, and every change carries
// X-Heat-Client: web, which the service requires before it trusts the cookie for writes.

import { currentPushSubscription } from './webPush'

export type Mode = 'schedule' | 'manual' | 'off'
export type Control = 'grouped' | 'independent'
export type Day = 'monday' | 'tuesday' | 'wednesday' | 'thursday' | 'friday' | 'saturday' | 'sunday'
export const DAYS: Day[] = ['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday']

export interface Boost { target: number; endsAt: string }
export interface Override { target: number; until: string }
export interface NextChange { at: string; target: number }

export interface Zone {
  id: string
  valves: string[]
  mode: Mode
  manualTarget: number
  target: number | null
  current: number | null
  heating: boolean
  callingForHeat: boolean
  nextChange?: NextChange | null
  boost?: Boost | null
  override?: Override | null
}

export interface Room {
  id: string
  name: string
  control: Control
  mode: Mode
  current: number | null
  target: number | null
  heating: boolean
  valves: string[]
  zones: Zone[]
  notices: string[]
  smartTemperatureControl: boolean
  /** The room's temperature sensor, by name, or null when its valves measure it */
  sensor?: string | null
  /** "sensor" while an online sensor measures the room; "valves" otherwise, including while its sensor is offline */
  temperatureSource?: TemperatureSource
}

export type TemperatureSource = 'valves' | 'sensor'

/** A non-valve Zigbee device that reports temperature (e.g. a smoke alarm), usable as a room's sensor. */
export interface Sensor {
  name: string
  /** Room name, null when unassigned */
  room: string | null
  online: boolean
  temperature: number | null
  battery: number | null
  lowBattery: boolean
}

export interface Valve {
  name: string
  room: string | null
  online: boolean
  currentTemperature: number | null
  reportedSetpoint: number | null
  runningState: string | null
  battery: number | null
  lowBattery: boolean
  openWindow: boolean
  childLock: boolean
}

export interface Block { start: string; target: number }
export interface DaySchedule { blocks: Block[] }
export type WeekSchedule = Record<Day, DaySchedule>

export interface Holiday { from: string; until: string; target: number; active: boolean }
/**
 * What the boiler is doing. heating: a room calls and the boiler fires. cycling: nothing calls but it
 * finishes its minimum run. resting: a room calls but it waits out its minimum rest. idle: nothing calls.
 */
export type BoilerStateName = 'heating' | 'cycling' | 'resting' | 'idle'
export interface BoilerState {
  state: BoilerStateName
  /** When cycling or resting ends; null otherwise */
  until: string | null
}
export interface Status {
  heatDemand: boolean
  /** Missing from older services; fall back to heatDemand */
  boiler?: BoilerState
  holiday: Holiday | null
  serverTime: string
  timezone: string
  boilerOverride?: BoilerOverride | null
}

/** Recorded temperatures: raw 5-minute points for 24h, 30-minute averages for 7d, 2-hour for 30d. */
export type HistoryRange = '24h' | '7d' | '30d'
export interface HistoryPoint { at: string; current: number | null; target?: number | null }
export interface RoomHistory { id: string; name: string; points: HistoryPoint[] }
/** What the boiler did from..to; to is null for the state it is in now. */
export interface BoilerPeriod { state: BoilerStateName; from: string; to: string | null }
export interface History {
  from: string
  to: string
  rooms: RoomHistory[]
  /** Median of the rooms at each time */
  house: HistoryPoint[]
  boiler: BoilerPeriod[]
}

export interface User {
  id: string | null
  username: string
  admin: boolean
  createdAt?: string | null
  /** Ids of the rooms this person can see; an admin has every room */
  rooms?: string[]
}
export interface Session { token: string; user: User }

/** A named set of rooms controlled together. A room may be in several groups. */
export type GroupMode = Mode | 'mixed'
export interface Group {
  id: string
  name: string
  /** Room ids */
  rooms: string[]
  /** Average of its rooms' current temperatures */
  current: number | null
  /** Shared target when every room agrees, else null */
  target: number | null
  mode: GroupMode
  /** Every room in the group heating */
  heating: boolean
  /** Ids of its rooms that are heating */
  heatingRooms: string[]
}

/** One line of the activity feed; the client words it (see lib/activity.ts). */
export interface ActivityEvent {
  id: number
  at: string
  kind: 'setpoint' | 'mode' | 'boost' | 'holiday' | 'knob' | 'schedule' | 'boiler' | 'offline' | string
  /** The person's display name; null when nobody did it (a knob, a schedule, the boiler). */
  actor: string | null
  room: string | null
  /** A target (number) or a state (string). */
  value: number | string | null
  reason: string | null
  rooms: string[]
  /** How many boiler runs a collapsed "cycled" line stands for. */
  count: number | null
  /** The plug an "offline" event is about; null for valves and sensors (their room is named) and other kinds. */
  device: string | null
}

export interface LiveEvent {
  type: 'room.updated' | 'room.deleted' | 'status.updated' | 'valve.updated' | 'groups.updated' | 'sensor.updated' | 'access.updated' | string
  room?: Room
  roomId?: string
  status?: Status
  valve?: Valve
  /** groups.updated carries every group */
  groups?: Group[]
  sensor?: Sensor
  /** activity.added */
  activity?: ActivityEvent
  /** boiler.override: sent to every signed-in user */
  boilerOverride?: BoilerOverrideStatus
}

export class ApiError extends Error {
  readonly status: number
  constructor(status: number, message: string) {
    super(message)
    this.status = status
  }
}

/** Called once when the service rejects the session (expired, password changed, removed). */
let onSessionEnded: () => void = () => {}
export function setSessionEndedHandler(handler: () => void) {
  onSessionEnded = handler
}

function lockedMessage(seconds: number | null): string {
  if (seconds == null) return 'Too many attempts. Try again later.'
  if (seconds < 90) return 'Too many attempts. Try again in a minute.'
  return `Too many attempts. Try again in ${Math.ceil(seconds / 60)} minutes.`
}

export async function request<T>(method: string, path: string, body?: unknown, opts: { publicCall?: boolean } = {}): Promise<T> {
  const headers: Record<string, string> = { Accept: 'application/json' }
  if (method !== 'GET') headers['X-Heat-Client'] = 'web'
  if (body !== undefined) headers['Content-Type'] = 'application/json'
  let response: Response
  try {
    response = await fetch(path, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
      credentials: 'same-origin',
    })
  } catch {
    throw new ApiError(0, "Can't reach the heating service.")
  }
  if (response.status === 401 && !opts.publicCall && path !== '/api/auth/password') onSessionEnded()
  if (!response.ok) {
    if (response.status === 429) {
      const after = response.headers.get('Retry-After')
      throw new ApiError(429, lockedMessage(after ? Number(after) : null))
    }
    const text = await response.text()
    let message = text || response.statusText
    try {
      message = (JSON.parse(text) as { error?: string }).error ?? message
    } catch {
      /* not JSON */
    }
    throw new ApiError(response.status, message)
  }
  if (response.status === 204) return undefined as T
  return (await response.json()) as T
}

/** openapi: BoostSettings */
export interface BoostSettings {
  /** What a boost heats to when no target is given, and where boost pickers start. */
  defaultTarget: number
}

export interface NotificationPrefs {
  heatingOn: boolean
  heatingFinished: boolean
  boilerOverride: boolean
}

/** openapi: BoilerOverride. An admin forced the boiler off; no room heats until it is turned back on. */
export interface BoilerOverride {
  by: string
  since: string
}
export interface BoilerOverrideStatus {
  override: BoilerOverride | null
}

const enc = encodeURIComponent
const zone = (id: string) => `/api/zones/${enc(id)}`

export const api = {
  // Sign-in
  authStatus: () => request<{ setupRequired: boolean }>('GET', '/api/auth/status', undefined, { publicCall: true }),
  login: (username: string, password: string) =>
    request<Session>('POST', '/api/auth/login', { username, password, web: true }, { publicCall: true }),
  setup: (serviceToken: string, username: string, password: string) =>
    request<Session>('POST', '/api/auth/setup', { serviceToken, username, password, web: true }, { publicCall: true }),
  // Signing out stops this browser's notifications: the service forgets its push subscription.
  logout: async () => {
    const push = await currentPushSubscription()
    await request<void>('POST', '/api/auth/logout', push ? { webPushEndpoint: push.endpoint } : {}, { publicCall: true })
    await push?.unsubscribe().catch(() => undefined)
  },
  me: () => request<User>('GET', '/api/auth/me'),
  changePassword: (current: string, next: string) => request<Session>('POST', '/api/auth/password', { current, new: next }),
  logoutAll: () => request<void>('POST', '/api/auth/logout-all', {}),

  // Notifications: which ones this person wants, and this browser's Web Push subscription
  notificationPrefs: () => request<NotificationPrefs>('GET', '/api/me/notifications'),
  setNotificationPrefs: (prefs: NotificationPrefs) => request<NotificationPrefs>('PUT', '/api/me/notifications', prefs),
  webPush: () => request<{ webPush: boolean; publicKey?: string }>('GET', '/api/me/webpush'),
  subscribeWebPush: (subscription: PushSubscriptionJSON) => request<void>('PUT', '/api/me/webpush', subscription),
  unsubscribeWebPush: (endpoint: string) => request<void>('DELETE', '/api/me/webpush', { endpoint }),

  activity: (limit = 50, before?: number) =>
    request<{ events: ActivityEvent[] }>('GET', `/api/activity?limit=${limit}${before == null ? '' : `&before=${before}`}`),

  history: (range: HistoryRange) => request<History>('GET', `/api/history?range=${range}`),

  // People (admin)
  users: () => request<User[]>('GET', '/api/admin/users'),
  createUser: (username: string, password: string, admin: boolean) =>
    request<User>('POST', '/api/admin/users', { username, password, admin }),
  patchUser: (id: string, patch: { password?: string; admin?: boolean }) => request<User>('PATCH', `/api/admin/users/${enc(id)}`, patch),
  deleteUser: (id: string) => request<void>('DELETE', `/api/admin/users/${enc(id)}`),
  /** Replace the rooms a member can see (the full list). Admins see every room and answer 409. */
  setUserRooms: (id: string, rooms: string[]) => request<User>('PUT', `/api/admin/users/${enc(id)}/rooms`, { rooms }),

  // Whole house
  houseMode: (mode: Mode, target?: number) => request<Room[]>('POST', '/api/house', { mode, target }),
  houseBoost: (minutes: number, target?: number) => request<Room[]>('POST', '/api/house', { boost: minutes, target }),
  houseCancelBoosts: () => request<Room[]>('DELETE', '/api/house/boost'),

  // Groups: the same controls as the whole house, limited to the group's rooms
  groups: () => request<Group[]>('GET', '/api/groups'),
  createGroup: (name: string, rooms: string[]) => request<Group>('POST', '/api/groups', { name, rooms }),
  patchGroup: (id: string, patch: { name?: string; rooms?: string[] }) => request<Group>('PATCH', `/api/groups/${enc(id)}`, patch),
  deleteGroup: (id: string) => request<void>('DELETE', `/api/groups/${enc(id)}`),
  groupMode: (id: string, mode: Mode, target?: number) => request<Group>('POST', `/api/groups/${enc(id)}/control`, { mode, target }),
  groupBoost: (id: string, minutes: number, target?: number) =>
    request<Group>('POST', `/api/groups/${enc(id)}/control`, { boost: minutes, target }),
  groupCancelBoosts: (id: string) => request<Group>('DELETE', `/api/groups/${enc(id)}/boost`),

  // Apps
  apps: () => request<{ android: { size: number; builtAt: string } | null }>('GET', '/api/app'),

  // Heating
  status: () => request<Status>('GET', '/api/status'),
  /** Whether an admin has forced the boiler off. Every user may read it; only admins may change it. */
  boiler: () => request<BoilerOverrideStatus>('GET', '/api/boiler'),
  boilerOff: () => request<BoilerOverrideStatus>('POST', '/api/boiler/off', {}),
  boilerOn: () => request<BoilerOverrideStatus>('POST', '/api/boiler/on', {}),
  rooms: () => request<Room[]>('GET', '/api/rooms'),
  createRoom: (name: string) => request<Room>('POST', '/api/rooms', { name }),
  patchRoom: (id: string, patch: { name?: string; control?: Control; sourceValve?: string; smartTemperatureControl?: boolean }) =>
    request<Room>('PATCH', `/api/rooms/${enc(id)}`, patch),
  deleteRoom: (id: string) => request<void>('DELETE', `/api/rooms/${enc(id)}`),
  assignValve: (roomId: string, valve: string) => request<Room>('PUT', `/api/rooms/${enc(roomId)}/valves/${enc(valve)}`),
  removeValve: (roomId: string, valve: string) => request<Room>('DELETE', `/api/rooms/${enc(roomId)}/valves/${enc(valve)}`),
  setMode: (zoneId: string, mode: Mode, target?: number) => request<Room>('PUT', `${zone(zoneId)}/mode`, { mode, target }),
  boostSettings: () => request<BoostSettings>('GET', '/api/settings/boost'),
  saveBoostSettings: (settings: BoostSettings) => request<BoostSettings>('PUT', '/api/settings/boost', settings),
  boost: (zoneId: string, minutes: number, target?: number) => request<Room>('POST', `${zone(zoneId)}/boost`, { minutes, target }),
  cancelBoost: (zoneId: string) => request<Room>('DELETE', `${zone(zoneId)}/boost`),
  schedule: (zoneId: string) => request<WeekSchedule>('GET', `${zone(zoneId)}/schedule`),
  saveDay: (zoneId: string, day: Day, blocks: Block[]) => request<WeekSchedule>('PUT', `${zone(zoneId)}/schedule/${day}`, { blocks }),
  copyDay: (zoneId: string, from: Day, to: Day[]) => request<WeekSchedule>('POST', `${zone(zoneId)}/schedule/copy`, { from, to }),
  startHoliday: (until: string, target?: number, from?: string) => request<Status>('PUT', '/api/holiday', { until, target, from }),
  endHoliday: () => request<Status>('DELETE', '/api/holiday'),
  sensors: (unassignedOnly = false) => request<Sensor[]>('GET', unassignedOnly ? '/api/sensors?unassigned=true' : '/api/sensors'),
  /** Make [sensor] the room's sensor, replacing any previous one. */
  assignSensor: (roomId: string, sensor: string) => request<Room>('PUT', `/api/rooms/${enc(roomId)}/sensor/${enc(sensor)}`),
  removeSensor: (roomId: string, sensor: string) => request<Room>('DELETE', `/api/rooms/${enc(roomId)}/sensor/${enc(sensor)}`),
  valves: (unassignedOnly = false) => request<Valve[]>('GET', unassignedOnly ? '/api/valves?unassigned=true' : '/api/valves'),
  patchValve: (name: string, patch: { openWindow?: boolean; childLock?: boolean }) => request<Valve>('PATCH', `/api/valves/${enc(name)}`, patch),
}

/** Live events from /ws (the cookie authenticates it), reconnecting with backoff until [stop] is called. */
export function followLive(onEvent: (e: LiveEvent) => void, onConnected: (connected: boolean) => void): () => void {
  let socket: WebSocket | null = null
  let stopped = false
  let backoff = 1000
  let timer: number | undefined

  const connect = () => {
    if (stopped) return
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
    socket = new WebSocket(`${scheme}://${location.host}/ws`)
    socket.onopen = () => {
      backoff = 1000
      onConnected(true)
    }
    socket.onmessage = (m) => {
      try {
        onEvent(JSON.parse(m.data as string) as LiveEvent)
      } catch {
        /* ignore malformed */
      }
    }
    socket.onclose = () => {
      onConnected(false)
      if (stopped) return
      timer = window.setTimeout(connect, backoff)
      backoff = Math.min(backoff * 2, 30_000)
    }
  }
  connect()
  return () => {
    stopped = true
    window.clearTimeout(timer)
    socket?.close()
  }
}
