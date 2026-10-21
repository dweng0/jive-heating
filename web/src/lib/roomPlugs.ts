// A room's monitored plugs for its settings page. Mirrors the RoomPlugs and RoomPlug schemas in
// openapi.json. Admin only. Costs are in the currency's minor unit (pence).

import { request, type HistoryRange } from './api'
import { formatCost, formatPower, plugNowWarning } from './energy'
import { time } from './format'

export interface OfflinePeriod {
  from: string
  to: string
}

/** One plug today: power now (null while offline or stale) and its use and cost so far. */
export interface RoomPlug {
  meter: string
  power: number | null
  kwh: number
  cost: number
  missingMinutes: number
  incomplete: boolean
  /** When it had no reading today */
  offline: OfflinePeriod[]
  /** Set while the plug is offline now: when it went. It is then also incomplete */
  offlineSince: string | null
  /** Set while it uses power but its energy total is not going up: when the total last changed. It is then also incomplete */
  totalStuckSince: string | null
}

/** Today so far: the room's sum, "of which" the house meter's use, and its plugs by name. */
export interface RoomPlugs {
  room: string
  currency: string
  kwh: number
  cost: number
  missingMinutes: number
  incomplete: boolean
  plugs: RoomPlug[]
}

/** A room's use over one hour or day from `at`. Incomplete when a plug had no reading for some of it; kwh null when for all. */
export interface EnergyBar {
  at: string
  kwh: number | null
  incomplete: boolean
}

/** The room's use over time from the plug use recorded in it. Mirrors RoomEnergyHistory in openapi.json. */
export interface RoomEnergyHistory {
  room: string
  from: string
  to: string
  points: EnergyBar[]
}

export const roomPlugsApi = {
  get: (room: string) => request<RoomPlugs>('GET', `/api/rooms/${encodeURIComponent(room)}/plugs`),
  history: (room: string, range: HistoryRange) =>
    request<RoomEnergyHistory>('GET', `/api/rooms/${encodeURIComponent(room)}/plugs/history?range=${range}`),
}

/** "85 W now · £0.11 today", or "Offline · £0.11 today" while offline now, never its last power. */
export const plugLine = (plug: RoomPlug, currency: string) =>
  `${plug.offlineSince ? 'Offline' : `${formatPower(plug.power)} now`} · ${formatCost(plug.cost, currency)} today`

const COULD_NOT_COUNT = 'Some use could not be counted.'

/** "14:20 to 15:00", several joined with "and". */
const periods = (offline: OfflinePeriod[]) => offline.map((p) => `${time(p.from)} to ${time(p.to)}`).join(' and ')

/** "Was offline 14:20 to 15:00. Some use could not be counted.", or the offline-now or stuck-total message. */
export const plugWarning = (plug: RoomPlug) => plugNowWarning(plug) ?? `Was offline ${periods(plug.offline)}. ${COULD_NOT_COUNT}`

/** "Fridge Plug was offline 14:20 to 15:00. Some use could not be counted.", naming each plug affected. */
export const roomWarning = (plugs: RoomPlug[]) =>
  `${plugs
    .filter((p) => p.incomplete)
    .map((p) =>
      p.offlineSince
        ? `${p.meter} offline since ${time(p.offlineSince)}`
        : p.totalStuckSince
          ? `${p.meter} using power but its energy total isn't going up`
          : `${p.meter} was offline ${periods(p.offline)}`,
    )
    .join('; ')}. ${COULD_NOT_COUNT}`
