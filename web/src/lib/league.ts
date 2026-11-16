// The live league table: every meter but the house meter ranked by its live watts, like one side of an order book.

import type { ItemPower, LivePower } from './livePower'

/** Under this a meter counts as off. */
export const OFF_UNDER_W = 5

export type LeagueState = 'on' | 'else' | 'off' | 'no-reading'

export interface LeagueRow {
  key: string
  name: string
  watts: number | null
  /** Share of the house's power now (0..1); null without a live house reading or when off */
  share: number | null
  state: LeagueState
  item?: ItemPower
  /** kWh since the household's midnight as of the reading; null when nothing records it */
  todayKwh?: number | null
}

/** Whether the table ranks items (each meter) or rooms (each place). */
export type LeagueView = 'items' | 'rooms'

/** Something the table can rank: an item or a place, with its live watts. */
interface Entry {
  key: string
  name: string
  watts: number | null
  item?: ItemPower
  todayKwh?: number | null
}

/**
 * Ranks [entries] biggest first: those using power, then "Everything else" ([rest], when there is a house reading),
 * then those under 5 W as "Off" in name order, then those with no reading.
 */
function rank(entries: Entry[], house: number | null, rest: number | null, restToday: number | null = null): LeagueRow[] {
  const byName = (a: Entry, b: Entry) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })
  const shareOf = (w: number) => (house != null && house > 0 ? Math.min(1, w / house) : null)
  const row = (e: Entry, state: LeagueState): LeagueRow => ({
    key: e.key,
    name: e.name,
    watts: e.watts,
    share: state === 'on' && e.watts != null ? shareOf(e.watts) : null,
    state,
    item: e.item,
    todayKwh: e.todayKwh ?? null,
  })
  const on = entries.filter((e) => e.watts != null && e.watts >= OFF_UNDER_W).sort((a, b) => b.watts! - a.watts! || byName(a, b))
  const off = entries.filter((e) => e.watts != null && e.watts < OFF_UNDER_W).sort(byName)
  const none = entries.filter((e) => e.watts == null).sort(byName)
  const rows = on.map((e) => row(e, 'on'))
  if (rest != null) rows.push({ key: 'everything-else', name: 'Everything else', watts: rest, share: shareOf(rest), state: 'else', todayKwh: restToday })
  return [...rows, ...off.map((e) => row(e, 'off')), ...none.map((e) => row(e, 'no-reading'))]
}

/** Areas whose whole-area meter is reading: their monitored devices are already inside that meter's figure. */
function measuredAreas(items: ItemPower[]): Set<string> {
  return new Set(items.filter((i) => i.job === 'area' && i.watts != null && i.area).map((i) => i.area!))
}

/** The house use none of [counted] accounts for, never counting a device inside a measured area twice. */
function restOf(house: number | null, items: ItemPower[], counted: ItemPower[]): number | null {
  if (house == null) return null
  const measured = measuredAreas(items)
  const sum = counted
    .filter((i) => i.watts != null && !(i.job === 'monitored' && i.area != null && measured.has(i.area)))
    .reduce((s, i) => s + Math.max(0, i.watts!), 0)
  return Math.max(0, house - sum)
}

/** The house's kWh today none of [counted] accounts for, counted like [restOf]; null without the house's. */
function restTodayOf(houseToday: number | null, items: ItemPower[], counted: ItemPower[]): number | null {
  if (houseToday == null) return null
  const measured = measuredAreas(items)
  const sum = counted
    .filter((i) => i.todayKwh != null && !(i.job === 'monitored' && i.area != null && measured.has(i.area)))
    .reduce((s, i) => s + Math.max(0, i.todayKwh!), 0)
  return Math.max(0, houseToday - sum)
}

/** Adds up [items]' kWh today; null when none of them has a figure. */
function totalToday(items: ItemPower[]): number | null {
  const known = items.filter((i) => i.todayKwh != null)
  return known.length ? known.reduce((s, i) => s + i.todayKwh!, 0) : null
}

/** Adds up [items]' readings; null when none of them has one. */
function total(items: ItemPower[]): number | null {
  const reading = items.filter((i) => i.watts != null)
  return reading.length ? reading.reduce((s, i) => s + i.watts!, 0) : null
}

/**
 * The rows, biggest first: items using power, then "Everything else" (the house use no item accounts for), then items
 * under 5 W as "Off" in name order, then items with no reading. A monitored device inside an energy area whose
 * whole-area meter is reading is already in that meter's figure, so it is not counted twice in everything else.
 */
export function leagueRows(live: LivePower | null, view: LeagueView = 'items'): LeagueRow[] {
  if (view === 'rooms') return leagueRoomRows(live)
  const items = live?.items ?? []
  const house = live?.watts ?? null
  return rank(
    items.map((i) => ({ key: i.meter, name: i.name, watts: i.watts, item: i, todayKwh: i.todayKwh ?? null })),
    house,
    restOf(house, items, items),
    restTodayOf(live?.houseTodayKwh ?? null, items, items),
  )
}

/**
 * The rows by place: a room (its monitored devices), an energy area (its whole-area meter while that is reading, else
 * its monitored devices in no room), "Not in a room" for monitored devices in neither, and "Everything else". Meters
 * with no job belong to no place and are left out, so their power falls in everything else.
 */
function leagueRoomRows(live: LivePower | null): LeagueRow[] {
  const items = live?.items ?? []
  const house = live?.watts ?? null
  const placed = items.filter((i) => i.job === 'area' || i.job === 'monitored')
  const monitored = placed.filter((i) => i.job === 'monitored')
  const roomName = new Map((live?.rooms ?? []).map((r) => [r.room, r.name]))
  const areaName = new Map((live?.areas ?? []).map((a) => [a.area, a.name]))
  const entries: Entry[] = []

  for (const room of new Set(monitored.filter((i) => i.room).map((i) => i.room!))) {
    const inRoom = monitored.filter((i) => i.room === room)
    entries.push({ key: `room:${room}`, name: roomName.get(room) ?? room, watts: total(inRoom), todayKwh: totalToday(inRoom) })
  }
  const areas = new Set(placed.filter((i) => i.area && (i.job === 'area' || !i.room)).map((i) => i.area!))
  for (const area of areas) {
    const whole = placed.find((i) => i.job === 'area' && i.area === area && i.watts != null)
    const plugs = monitored.filter((i) => i.area === area && !i.room)
    const watts = whole ? whole.watts : total(plugs)
    entries.push({ key: `area:${area}`, name: areaName.get(area) ?? area, watts, todayKwh: whole ? (whole.todayKwh ?? null) : totalToday(plugs) })
  }
  const loose = monitored.filter((i) => !i.room && !i.area)
  if (loose.length) entries.push({ key: 'not-in-a-room', name: 'Not in a room', watts: total(loose), todayKwh: totalToday(loose) })

  return rank(entries, house, restOf(house, items, placed), restTodayOf(live?.houseTodayKwh ?? null, items, placed))
}

/** A row changes place at most this often, so figures flickering around each other do not swap back and forth. */
export const SWAP_EVERY_MS = 2000

/** The order the table is drawn in, and when each row last changed place. */
export interface Placing {
  order: string[]
  movedAt: Record<string, number>
}

export const NO_PLACING: Placing = { order: [], movedAt: {} }

/**
 * Moves the drawn order towards [desired] by swapping neighbours, except that a row which changed place less than 2 s
 * ago holds its place (and nothing passes it) until its time is up. New rows go straight to their place; gone rows drop.
 */
export function placeRows(prev: Placing, desired: string[], now: number): Placing {
  const rank = new Map(desired.map((k, i) => [k, i]))
  const order = prev.order.filter((k) => rank.has(k))
  const held = new Set(order)
  desired.forEach((k, i) => {
    if (!held.has(k)) order.splice(Math.min(i, order.length), 0, k)
  })
  const locked = (k: string) => now - (prev.movedAt[k] ?? -Infinity) < SWAP_EVERY_MS
  const moved = new Set<string>()
  for (let swapped = true; swapped; ) {
    swapped = false
    for (let i = 0; i + 1 < order.length; i++) {
      const a = order[i]
      const b = order[i + 1]
      if (rank.get(a)! > rank.get(b)! && !locked(a) && !locked(b)) {
        order[i] = b
        order[i + 1] = a
        moved.add(a).add(b)
        swapped = true
      }
    }
  }
  const movedAt: Record<string, number> = {}
  for (const k of order) {
    if (moved.has(k)) movedAt[k] = now
    else if (prev.movedAt[k] != null) movedAt[k] = prev.movedAt[k]
  }
  return { order, movedAt }
}

/** When the next held row is free to move, or null when the drawn order already matches [desired]. */
export function nextUnlock(p: Placing, desired: string[], now: number): number | null {
  if (p.order.join('\n') === desired.join('\n')) return null
  const times = Object.values(p.movedAt).map((t) => t + SWAP_EVERY_MS).filter((t) => t > now)
  return times.length ? Math.min(...times) : null
}
