// The Energy tab's car-dashboard gauges and the history chart under them: which room or area the second dial follows,
// the fuel gauge's figures, today's peak, and GET /api/energy/history. Mirrors PowerSummary.todayPeak and HouseHistory
// in openapi.json. For admins and people with energy access.

import { request } from './api'
import type { EnergyTarget } from './energy'
import type { LivePower } from './livePower'

/** A room or area the second dial can follow. */
export interface Place {
  id: string
  name: string
  watts: number | null
}

/** Every room and energy area in a live power message. "Everything else" and devices in no room or area are left out. */
export function places(live: LivePower | null): Place[] {
  if (!live) return []
  return [
    ...live.rooms.map((r) => ({ id: `room:${r.room}`, name: r.name, watts: r.watts })),
    ...(live.areas ?? []).map((a) => ({ id: `area:${a.area}`, name: a.name, watts: a.watts })),
  ]
}

/** What the second dial is on, and who is catching it up since when. */
export interface Busiest {
  current: string | null
  challenger: string | null
  since: number | null
}

export const NO_BUSIEST: Busiest = { current: null, challenger: null, since: null }

/** How far ahead, and for how long, another room must be to take the second dial. */
export const LEAD_SHARE = 0.1
export const LEAD_WATTS = 50
export const LEAD_MS = 30_000

/**
 * The second dial after a reading at [now]: it stays on its room until another is at least 10% and 50 W ahead for 30
 * seconds, so close rooms do not flicker; it moves at once when its room stops reporting.
 */
export function followBusiest(state: Busiest, all: Place[], now: number): Busiest {
  const reporting = all.filter((p) => p.watts != null)
  if (reporting.length === 0) return NO_BUSIEST
  const top = reporting.reduce((a, b) => (b.watts! > a.watts! ? b : a))
  const current = reporting.find((p) => p.id === state.current)
  if (!current) return { current: top.id, challenger: null, since: null }
  if (top.id === current.id) return { current: current.id, challenger: null, since: null }
  const ahead = top.watts! >= current.watts! * (1 + LEAD_SHARE) && top.watts! - current.watts! >= LEAD_WATTS
  if (!ahead) return { current: current.id, challenger: null, since: null }
  if (state.challenger !== top.id || state.since == null) return { current: current.id, challenger: top.id, since: now }
  if (now - state.since >= LEAD_MS) return { current: top.id, challenger: null, since: null }
  return state
}

/** The fuel gauge: a full tank of [target.kwh] at midnight, draining as the house uses electricity. */
export interface Fuel {
  /** 0 (E) to 1 (F): what is left of the target */
  left: number
  /** Where the needle should be by now, 0..1 from E */
  pace: number
  leftKwh: number
  /** Use past the target; 0 until then */
  overKwh: number
  /** Use so far minus expected use so far: below 0 is under pace */
  paceKwh: number
  /** Over 80% of the target used */
  low: boolean
}

export const LOW_FUEL = 0.8

export function fuel(target: Pick<EnergyTarget, 'kwh' | 'usedKwh' | 'expectedKwh'>): Fuel {
  const { kwh, usedKwh, expectedKwh } = target
  const clamp = (v: number) => Math.min(1, Math.max(0, v))
  return {
    left: clamp((kwh - usedKwh) / kwh),
    pace: clamp((kwh - expectedKwh) / kwh),
    leftKwh: Math.max(0, kwh - usedKwh),
    overKwh: Math.max(0, usedKwh - kwh),
    paceKwh: usedKwh - expectedKwh,
    low: usedKwh > kwh * LOW_FUEL,
  }
}

/** "1.0 kWh under pace" / "0.4 kWh over pace". */
export const paceLine = (paceKwh: number) => `${Math.abs(paceKwh).toFixed(1)} kWh ${paceKwh <= 0 ? 'under' : 'over'} pace`

export const NO_TARGET = 'No daily target'
export const NO_PLACES = 'No rooms reporting'

/** What made up today's peak. */
export interface PeakPart {
  kind: 'room' | 'area' | 'device' | 'else'
  id: string | null
  name: string
  watts: number
}

/** The highest five-minute house power since midnight. */
export interface TodayPeak {
  watts: number
  at: string
  parts: PeakPart[]
}

/** "18:05", in the browser's time (the household's, in practice). */
export const clockTime = (at: string) => new Date(at).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })

export type HistoryRange = 'day' | 'week' | 'month' | 'year'

export interface HouseHistoryPoint {
  at: string
  kwh: number | null
  cost: number | null
  watts: number | null
  incomplete: boolean
  targetKwh: number | null
}

export interface HouseHistory {
  currency: string
  range: HistoryRange
  from: string
  to: string
  earliest: string | null
  points: HouseHistoryPoint[]
}

const DAY = 86_400_000

/** How much one range shows at a time, and each of its points. */
export const RANGES: { value: HistoryRange; label: string; span: number; step: number }[] = [
  { value: 'day', label: 'Day', span: DAY, step: 5 * 60_000 },
  { value: 'week', label: 'Week', span: 7 * DAY, step: 3_600_000 },
  { value: 'month', label: 'Month', span: 30 * DAY, step: DAY },
  { value: 'year', label: 'Year', span: 365 * DAY, step: 30.4 * DAY },
]

export const rangeOf = (r: HistoryRange) => RANGES.find((x) => x.value === r)!

/** Where the right edge of the chart can be: not past now, and not so far back that it shows nothing before the first sample. */
export function clampEnd(end: number, span: number, now: number, earliest: number | null): number {
  const least = earliest == null ? now : Math.min(now, earliest + span)
  return Math.min(now, Math.max(least, end))
}

/** A point's time as the chart's tooltip says it: "Thu 5 Nov, 18:05", "Thu 5 Nov", "Nov 2026". */
export function pointLabel(at: string, range: HistoryRange): string {
  const d = new Date(at)
  if (range === 'year') return d.toLocaleDateString('en-GB', { month: 'short', year: 'numeric' })
  const day = d.toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short' })
  if (range === 'month') return day
  return `${day}, ${clockTime(at)}`
}

export const historyApi = {
  get: (range: HistoryRange, to?: number) =>
    request<HouseHistory>('GET', `/api/energy/history?range=${range}${to == null ? '' : `&to=${encodeURIComponent(new Date(to).toISOString())}`}`),
}
