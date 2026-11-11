import { request } from './api'

/** A source (electricity, gas), a place (room, area, heating) or a device (plug) in the energy flow. */
export interface FlowNode {
  id: string
  name: string
  kind: 'source' | 'place' | 'device'
  kwh: number
  /** Minor units at today's price */
  cost: number
  estimated?: boolean
}

export interface FlowLink {
  from: string
  to: string
  kwh: number
  /** Shared out (gas between rooms by hours calling), not measured */
  estimated?: boolean
}

export interface EnergyFlow {
  currency: string
  from: string
  to: string
  nodes: FlowNode[]
  links: FlowLink[]
}

export type FlowPeriod = 'today' | 'yesterday' | 'week' | 'month'

export const FLOW_PERIODS: { value: FlowPeriod; label: string }[] = [
  { value: 'today', label: 'Today' },
  { value: 'yesterday', label: 'Yesterday' },
  { value: 'week', label: 'Week' },
  { value: 'month', label: 'Month' },
]

export const flowApi = {
  get: (period: FlowPeriod) => request<EnergyFlow>('GET', `/api/energy/flow?period=${period}`),
}

export interface PlacedNode extends FlowNode {
  column: number
  x: number
  y: number
  height: number
  /** The source it flows from: electricity or gas */
  source: string
}

export interface PlacedLink extends FlowLink {
  /** Top of the band where it leaves its node and where it reaches the next */
  y0: number
  y1: number
  x0: number
  x1: number
  width: number
  source: string
}

/**
 * Lays the flow out as a Sankey diagram [width] by [height]: sources in the first column, each node a column after
 * what flows into it, stacked with [gap] between; every node and band as tall as its kWh on one scale.
 */
export function layoutFlow(flow: EnergyFlow, width: number, height: number, nodeWidth = 12, gap = 10): { nodes: PlacedNode[]; links: PlacedLink[] } {
  const column = new Map<string, number>()
  const source = new Map<string, string>()
  flow.nodes.filter((n) => n.kind === 'source').forEach((n) => {
    column.set(n.id, 0)
    source.set(n.id, n.id)
  })
  // Each node a column after what flows into it; links come parent first, but go round until nothing moves.
  for (let moved = true, guard = 0; moved && guard < 10; guard++) {
    moved = false
    for (const l of flow.links) {
      const c = column.get(l.from)
      if (c == null) continue
      if ((column.get(l.to) ?? -1) < c + 1) {
        column.set(l.to, c + 1)
        source.set(l.to, source.get(l.from)!)
        moved = true
      }
    }
  }
  const columns = Math.max(1, ...[...column.values()].map((c) => c + 1))
  const byColumn = Array.from({ length: columns }, (_, c) => flow.nodes.filter((n) => column.get(n.id) === c))
  const scale = Math.min(...byColumn.map((nodes) => {
    const kwh = nodes.reduce((a, n) => a + n.kwh, 0)
    return kwh > 0 ? (height - gap * Math.max(0, nodes.length - 1)) / kwh : Infinity
  }))
  const k = Number.isFinite(scale) ? scale : 0
  const step = columns > 1 ? (width - nodeWidth) / (columns - 1) : 0
  const placed = new Map<string, PlacedNode>()
  byColumn.forEach((nodes, c) => {
    let y = 0
    nodes.forEach((n) => {
      const h = Math.max(1, n.kwh * k)
      placed.set(n.id, { ...n, column: c, x: c * step, y, height: h, source: source.get(n.id) ?? n.id })
      y += h + gap
    })
  })
  const out = new Map<string, number>()
  const into = new Map<string, number>()
  const links: PlacedLink[] = []
  for (const l of flow.links) {
    const a = placed.get(l.from)
    const b = placed.get(l.to)
    if (!a || !b) continue
    const w = l.kwh * k
    const y0 = a.y + (out.get(a.id) ?? 0)
    const y1 = b.y + (into.get(b.id) ?? 0)
    out.set(a.id, (out.get(a.id) ?? 0) + w)
    into.set(b.id, (into.get(b.id) ?? 0) + w)
    links.push({ ...l, y0, y1, x0: a.x + nodeWidth, x1: b.x, width: w, source: a.source })
  }
  return { nodes: [...placed.values()], links }
}

/** A band from one node to the next: a ribbon with curved top and bottom edges. */
export function bandPath(l: PlacedLink): string {
  const mid = (l.x0 + l.x1) / 2
  const w = Math.max(l.width, 0.5)
  return `M${l.x0},${l.y0} C${mid},${l.y0} ${mid},${l.y1} ${l.x1},${l.y1} L${l.x1},${l.y1 + w} C${mid},${l.y1 + w} ${mid},${l.y0 + w} ${l.x0},${l.y0 + w} Z`
}

/** "Kitchen · 3.0 kWh · £0.72 · 25% of electricity" */
export function flowReadout(n: { name: string; kwh: number; cost: number }, sourceKwh: number | null, sourceName: string | null, currencySymbol: string): string {
  const parts = [n.name, `${n.kwh.toFixed(1)} kWh`, `${currencySymbol}${(n.cost / 100).toFixed(2)}`]
  if (sourceKwh && sourceName && sourceKwh > 0 && n.name !== sourceName) parts.push(`${Math.round((n.kwh / sourceKwh) * 100)}% of ${sourceName.toLowerCase()}`)
  return parts.join(' · ')
}

export const NO_HOUSE_METER_FLOW = 'Choose a house meter to see where electricity goes'
