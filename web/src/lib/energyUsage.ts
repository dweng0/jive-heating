// A room's or everything else's electricity over time, for its energy dashboard. Mirrors the RoomUsage and
// EverythingElseUsage schemas in openapi.json. Admin only. Costs are in the currency's minor unit (pence).

import { request } from './api'
import { formatCost, formatPower, minorUnit } from './energy'
import type { RoomPlug, RoomPlugs } from './roomPlugs'

export type UsageWindow = '24h' | '7d' | '30d'
export type UsageMetric = 'watts' | 'kwh' | 'cost'

export interface UsagePoint {
  /** The end of its five minutes, or the start of its hour or day */
  at: string
  watts: number | null
  kwh: number | null
  cost: number | null
  incomplete: boolean
}

export interface UsagePage {
  currency: string
  from: string
  to: string
  earliest: string | null
  room: UsagePoint[]
  plugs?: { meter: string; points: UsagePoint[] }[]
}

const query = (window: UsageWindow, to?: number) => `window=${window}${to == null ? '' : `&to=${encodeURIComponent(new Date(to).toISOString())}`}`

export const usageApi = {
  room: (room: string, window: UsageWindow, to?: number) =>
    request<UsagePage>('GET', `/api/rooms/${encodeURIComponent(room)}/plugs/usage?${query(window, to)}`),
  area: (area: string, window: UsageWindow, to?: number) =>
    request<UsagePage>('GET', `/api/energy/areas/${encodeURIComponent(area)}/usage?${query(window, to)}`),
  everythingElse: (window: UsageWindow, to?: number) => request<UsagePage>('GET', `/api/energy/else/usage?${query(window, to)}`),
}

const HOUR = 3600_000

export const WINDOWS: { value: UsageWindow; label: string; length: number; step: number }[] = [
  { value: '24h', label: '24h', length: 24 * HOUR, step: 5 * 60_000 },
  { value: '7d', label: '7d', length: 7 * 24 * HOUR, step: HOUR },
  { value: '30d', label: '30d', length: 30 * 24 * HOUR, step: 24 * HOUR },
]

export const METRICS: { value: UsageMetric; label: string }[] = [
  { value: 'watts', label: 'Power' },
  { value: 'kwh', label: 'Use' },
  { value: 'cost', label: 'Cost' },
]

/** The series a chart can draw, by plug name; the room or everything else itself under [WHOLE]. */
export const WHOLE = ''

export interface LoadedUsage {
  series: Record<string, UsagePoint[]>
  /** Everything from here on has been read */
  loadedFrom: number
  earliest: number | null
  currency: string
}

/**
 * [page] added to what is [loaded]. An hour or day cut in two by the start of the page is left for the next page, which
 * asks up to the start of the first whole one, so it is never counted from only part of its samples.
 */
export function withPage(loaded: LoadedUsage | null, page: UsagePage, step: number): LoadedUsage {
  const earliest = page.earliest == null ? null : Date.parse(page.earliest)
  const from = Date.parse(page.from)
  const first = page.room[0] ? Date.parse(page.room[0].at) : null
  const cut = first != null && first < from && earliest != null && earliest < from
  const start = !cut ? from : page.room[1] ? Date.parse(page.room[1].at) : Math.min(Date.parse(page.to), first + step)
  const kept = (points: UsagePoint[]) => (cut ? points.filter((p) => Date.parse(p.at) >= start) : points)
  const series = { ...loaded?.series }
  const add = (key: string, points: UsagePoint[]) => {
    const byAt = new Map((series[key] ?? []).map((p) => [p.at, p]))
    kept(points).forEach((p) => byAt.set(p.at, p))
    series[key] = [...byAt.values()].sort((a, b) => Date.parse(a.at) - Date.parse(b.at))
  }
  add(WHOLE, page.room)
  page.plugs?.forEach((p) => add(p.meter, p.points))
  return { series, loadedFrom: Math.min(loaded?.loadedFrom ?? start, start), earliest, currency: page.currency }
}

/** Where a chart ending at [end] may go: no later than now, and no further back than shows [earliest] at its left edge. */
export function clampEnd(end: number, now: number, earliest: number | null, length: number): number {
  const first = earliest == null ? now : Math.min(now, earliest + length)
  return Math.min(now, Math.max(first, end))
}

export type UsageRow = { t: number; watts: number | null; kwh: number | null; cost: number | null; point: UsagePoint | null }

/** A chart's rows from [from] to [to]: the line breaks where there is no reading, or where points are over two steps apart. */
export function usageRows(points: UsagePoint[], step: number, from: number, to: number): UsageRow[] {
  const rows: UsageRow[] = []
  let last: number | null = null
  for (const p of points) {
    const t = Date.parse(p.at)
    if (last != null && t - last > 2 * step) rows.push({ t: last + step, watts: null, kwh: null, cost: null, point: null })
    last = t
    rows.push({ t, watts: p.watts, kwh: p.kwh, cost: p.cost, point: p })
  }
  // One either side, so the line runs off the edges instead of stopping short of them.
  const inside = rows.map((r, i) => (r.t >= from && r.t <= to ? i : -1)).filter((i) => i >= 0)
  if (inside.length === 0) return []
  return rows.slice(Math.max(0, inside[0] - 1), inside[inside.length - 1] + 2)
}

/** The row at [t], if one is within a step of it. */
export function rowAt(rows: UsageRow[], t: number, step: number): UsageRow | null {
  let best: UsageRow | null = null
  for (const r of rows) if (r.point && (!best || Math.abs(r.t - t) < Math.abs(best.t - t))) best = r
  return best && Math.abs(best.t - t) <= step ? best : null
}

/** A five-minute point's use is small: "0.008 kWh", "1.6 kWh". */
export const pointKwh = (kwh: number | null) => (kwh == null ? '–' : `${kwh < 1 ? kwh.toFixed(3) : kwh.toFixed(1)} kWh`)

/** A five-minute point's cost is under a penny: "0.21p", "£1.25". */
export const pointCost = (cost: number | null, currency: string) =>
  cost != null && cost < 10 ? `${cost.toFixed(2)}${minorUnit(currency)}` : formatCost(cost, currency)

export const metricValue = (metric: UsageMetric, value: number, currency: string) =>
  metric === 'watts' ? formatPower(value) : metric === 'kwh' ? pointKwh(value) : pointCost(value, currency)

export type PlugColumn = 'meter' | 'state' | 'power' | 'share' | 'kwh' | 'cost'
export interface PlugSort {
  column: PlugColumn
  descending: boolean
}

/** A table starts with the biggest cost first. */
export const FIRST_SORT: PlugSort = { column: 'cost', descending: true }

/** A heading picked: sorts by it, and again reverses it. Words read A to Z first, figures biggest first. */
export const nextSort = (sort: PlugSort, column: PlugColumn): PlugSort =>
  sort.column === column ? { column, descending: !sort.descending } : { column, descending: column !== 'meter' && column !== 'state' }

export const plugState = (plug: RoomPlug) => (plug.offlineSince ? 'Offline' : 'Online')

/** Share of the room's use today, "75%"; null when the room has used nothing. */
export const plugShare = (plug: RoomPlug, plugs: RoomPlugs) => (plugs.kwh > 0 ? Math.round((plug.kwh / plugs.kwh) * 100) : null)

/** [plugs] by [sort]; a plug with no reading of power is below every plug with one, whichever way power is sorted. */
export function sortPlugs(plugs: RoomPlug[], sort: PlugSort): RoomPlug[] {
  const sign = sort.descending ? -1 : 1
  return [...plugs].sort((a, b) => {
    const byName = a.meter.localeCompare(b.meter)
    switch (sort.column) {
      case 'meter':
        return sign * byName
      case 'state':
        return sign * plugState(a).localeCompare(plugState(b)) || byName
      case 'power':
        if (a.power == null || b.power == null) return (a.power == null ? 1 : 0) - (b.power == null ? 1 : 0) || byName
        return sign * (a.power - b.power) || byName
      case 'share':
      case 'kwh':
        return sign * (a.kwh - b.kwh) || byName
      case 'cost':
        return sign * (a.cost - b.cost) || byName
    }
  })
}

/** What plugs since moved out used here today: the room's figures less those of the plugs in it now; null when nothing. */
export function movedOut(plugs: RoomPlugs): { kwh: number; cost: number } | null {
  const kwh = plugs.kwh - plugs.plugs.reduce((sum, p) => sum + p.kwh, 0)
  const cost = plugs.cost - plugs.plugs.reduce((sum, p) => sum + p.cost, 0)
  return kwh >= 0.005 ? { kwh, cost: Math.max(0, cost) } : null
}
