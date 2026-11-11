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

/** "1.0 under pace" / "0.4 over pace": in kWh, said by the target beside it. */
export const paceLine = (paceKwh: number) => `${Math.abs(paceKwh).toFixed(1)} ${paceKwh <= 0 ? 'under' : 'over'} pace`

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

/** Use and cost over a stretch of time. */
export interface EnergyTotal {
  kwh: number
  cost: number
}

/** GET /api/energy/totals: the odometer's periods and yesterday by this time. */
export interface EnergyTotals {
  currency: string
  pencePerKwh: number
  today: EnergyTotal
  week: EnergyTotal
  month: EnergyTotal
  year: EnergyTotal
  yesterdaySoFar: EnergyTotal | null
  /** The smart gas meter's latest whole day; null until it has reported one */
  gas?: GasDayTotal | null
  /** The household's date that today is for, such as "2026-11-10" */
  day?: string
  /** The next midnight in the household's timezone, when day ends */
  dayEndsAt?: string
  /** The service's time these totals were worked out */
  at?: string
}

/** Totals as received, with the browser's time they arrived, to tell how far the browser's clock is from the service's. */
export type ReceivedTotals = EnergyTotals & { receivedAt: number }

/** How far ahead of the browser's clock the service's is, in ms; 0 when the totals do not say. */
export function serviceSkew(t: ReceivedTotals): number {
  const at = t.at ? Date.parse(t.at) : NaN
  return Number.isNaN(at) ? 0 : at - t.receivedAt
}

/** A whole day of the smart gas meter, its cost at the gas price, and the day before's kWh (null with none). */
export interface GasDayTotal {
  date: string
  kwh: number
  cost: number
  previousKwh: number | null
}

const weekday = (date: string, offset = 0) => {
  const d = new Date(`${date}T12:00:00Z`)
  d.setUTCDate(d.getUTCDate() + offset)
  return d
}

/** "GAS · THU 5 NOV" for the gas counter's day. */
export const gasDayLabel = (date: string) =>
  `Gas · ${weekday(date).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' }).replace(',', '')}`.toUpperCase()

/** "▲ 4.2 kWh vs Wed": the gas day against the day before; null with no day before. */
export function gasDelta(gas: GasDayTotal): { text: string; more: boolean } | null {
  if (gas.previousKwh == null) return null
  const delta = gas.kwh - gas.previousKwh
  const before = weekday(gas.date, -1).toLocaleDateString('en-GB', { weekday: 'short', timeZone: 'UTC' })
  return { text: `${delta > 0 ? '▲' : '▼'} ${Math.abs(delta).toFixed(1)} kWh vs ${before}`, more: delta > 0 }
}

/** Fired on window after each fetch of the day figures, so other cards showing today (Use and cost) fetch theirs too. */
export const DAY_FIGURES_EVENT = 'energy:day-figures'

export const totalsApi = {
  get: () => request<EnergyTotals>('GET', '/api/energy/totals'),
}

export type UsagePeriod = 'today' | 'week' | 'month' | 'year'

/** The usage counter's periods in the order a tap goes through them, with their labels ("Usage this week"). */
export const USAGE_PERIODS: { value: UsagePeriod; label: string }[] = [
  { value: 'today', label: 'today' },
  { value: 'week', label: 'this week' },
  { value: 'month', label: 'this month' },
  { value: 'year', label: 'this year' },
]

/** The period after [p], back to today after the year. */
export function nextUsagePeriod(p: UsagePeriod): UsagePeriod {
  const i = USAGE_PERIODS.findIndex((q) => q.value === p)
  return USAGE_PERIODS[(i + 1) % USAGE_PERIODS.length].value
}

/** The house's average power over the 5 minutes up to [now] from its live readings; null with none. */
export function fiveMinuteAverage(readings: { at: number; watts: number | null }[], now: number): number | null {
  const recent = readings.filter((r) => r.watts != null && r.at > now - 5 * 60_000 && r.at <= now)
  if (recent.length === 0) return null
  return recent.reduce((sum, r) => sum + r.watts!, 0) / recent.length
}

/**
 * The usage counter: the house's kWh today, this week, this month and this year, rolling on at the last 5 minutes'
 * average power between the recorded figures. [day] is the household's day it counts, as the service says.
 */
export interface Counter {
  day: string
  today: number
  week: number
  month: number
  year: number
  /** When it was last moved on, in the service's time */
  at: number
}

const PERIODS: UsagePeriod[] = ['today', 'week', 'month', 'year']

/** "2026-11-11" for "2026-11-10". */
export function nextDay(day: string): string {
  const d = new Date(`${day}T00:00:00Z`)
  d.setUTCDate(d.getUTCDate() + 1)
  return d.toISOString().slice(0, 10)
}

/** [c] carried into [day]: today starts again from 0, and so do the week on a Monday, the month on the 1st and the year on 1 January. */
export function turnOver(c: Counter, day: string, at: number): Counter {
  const d = new Date(`${day}T00:00:00Z`)
  const first = d.getUTCDate() === 1
  return { day, today: 0, week: d.getUTCDay() === 1 ? 0 : c.week, month: first ? 0 : c.month, year: first && d.getUTCMonth() === 0 ? 0 : c.year, at }
}

/**
 * The usage counter at [now] (the service's time): on from [c] at [watts], and never back. Totals for the counter's day
 * are taken where higher; totals for a later day start it again from them; totals for a day it has left are ignored.
 * At the totals' dayEndsAt it turns over into the next day without waiting for new totals.
 */
export function counterAt(c: Counter | null, t: EnergyTotals, watts: number | null, now: number): Counter {
  const day = t.day ?? dayOf(now)
  const fromTotals = (): Counter => ({ day, today: t.today.kwh, week: t.week.kwh, month: t.month.kwh, year: t.year.kwh, at: now })
  let next: Counter
  if (c == null || day > c.day) next = fromTotals()
  else {
    const kwh = ((watts ?? 0) * Math.max(0, now - c.at)) / 3_600_000_000
    next = { day: c.day, today: c.today + kwh, week: c.week + kwh, month: c.month + kwh, year: c.year + kwh, at: now }
    if (day === c.day) for (const p of PERIODS) next[p] = Math.max(next[p], t[p].kwh)
  }
  const endsAt = t.dayEndsAt ? Date.parse(t.dayEndsAt) : NaN
  if (next.day === day && !Number.isNaN(endsAt) && now >= endsAt) next = turnOver(next, nextDay(day), now)
  return next
}

/**
 * When the dashboard should next fetch its day figures (browser time): five minutes after the last fetch, or just after
 * the household's midnight if that is sooner. [jitter] spreads the midnight fetches out.
 */
export function nextFetchAt(lastFetch: number, t: ReceivedTotals | null, every: number, jitter: number): number {
  const regular = lastFetch + every
  const endsAt = t?.dayEndsAt ? Date.parse(t.dayEndsAt) : NaN
  if (t == null || Number.isNaN(endsAt)) return regular
  return Math.min(regular, endsAt - serviceSkew(t) + jitter)
}

/** "▲ 1.2 kWh vs yesterday" (more than yesterday by now) or "▼ 0.5 kWh vs yesterday"; null with nothing yesterday. */
export function versusYesterday(todayKwh: number, yesterday: EnergyTotal | null): { text: string; more: boolean } | null {
  if (yesterday == null) return null
  const delta = todayKwh - yesterday.kwh
  return { text: `${delta > 0 ? '▲' : '▼'} ${Math.abs(delta).toFixed(1)} kWh vs yesterday`, more: delta > 0 }
}

/** The household's date of [now], as "2026-11-05", in the browser's timezone. */
export const dayOf = (now: number) => new Date(now).toLocaleDateString('en-CA')
