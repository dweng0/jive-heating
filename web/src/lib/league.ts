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
