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
}

/**
 * The rows, biggest first: items using power, then "Everything else" (the house use no item accounts for), then items
 * under 5 W as "Off" in name order, then items with no reading. A monitored device inside an energy area whose
 * whole-area meter is reading is already in that meter's figure, so it is not counted twice in everything else.
 */
export function leagueRows(live: LivePower | null): LeagueRow[] {
  const items = live?.items ?? []
  const house = live?.watts ?? null
  const byName = (a: ItemPower, b: ItemPower) => a.name.localeCompare(b.name, undefined, { sensitivity: 'base' })
  const shareOf = (w: number) => (house != null && house > 0 ? Math.min(1, w / house) : null)
  const row = (item: ItemPower, state: LeagueState): LeagueRow => ({
    key: item.meter,
    name: item.name,
    watts: item.watts,
    share: state === 'on' && item.watts != null ? shareOf(item.watts) : null,
    state,
    item,
  })

  const on = items.filter((i) => i.watts != null && i.watts >= OFF_UNDER_W).sort((a, b) => b.watts! - a.watts! || byName(a, b))
  const off = items.filter((i) => i.watts != null && i.watts < OFF_UNDER_W).sort(byName)
  const none = items.filter((i) => i.watts == null).sort(byName)

  const rows = on.map((i) => row(i, 'on'))
  if (house != null) {
    const measuredAreas = new Set(items.filter((i) => i.job === 'area' && i.watts != null && i.area).map((i) => i.area))
    const counted = items
      .filter((i) => i.watts != null && !(i.job === 'monitored' && i.area != null && measuredAreas.has(i.area)))
      .reduce((sum, i) => sum + Math.max(0, i.watts!), 0)
    const rest = Math.max(0, house - counted)
    rows.push({ key: 'everything-else', name: 'Everything else', watts: rest, share: shareOf(rest), state: 'else' })
  }
  return [...rows, ...off.map((i) => row(i, 'off')), ...none.map((i) => row(i, 'no-reading'))]
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
