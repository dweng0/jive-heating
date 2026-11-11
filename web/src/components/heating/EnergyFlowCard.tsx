import { useEffect, useMemo, useState } from 'react'
import { Workflow } from 'lucide-react'
import { Card, CardContent } from '@/components/ui/card'
import { symbol } from '@/lib/energy'
import { DAY_FIGURES_EVENT } from '@/lib/energyDials'
import { bandPath, FLOW_PERIODS, flowApi, flowReadout, layoutFlow, NO_HOUSE_METER_FLOW, type EnergyFlow, type FlowPeriod } from '@/lib/energyFlow'
import { cn } from '@/lib/utils'

const PERIOD_KEY = 'energy.flowPeriod'
const WIDTH = 800
const NODE = 12
/** Above and below the diagram, so the top and bottom labels are not cut off. */
const PAD = 12
/** Margins for the sources' labels, left of them, and the last column's, right of them. */
const LABEL_ROOM = 150

const COLOUR: Record<string, string> = { electricity: 'var(--power-neutral)', gas: 'var(--heat)' }
const colourOf = (source: string) => COLOUR[source] ?? 'var(--muted-foreground)'

function storedPeriod(): FlowPeriod {
  try {
    const p = localStorage.getItem(PERIOD_KEY)
    return FLOW_PERIODS.some((q) => q.value === p) ? (p as FlowPeriod) : 'today'
  } catch {
    return 'today'
  }
}

/**
 * "Where it went": the house's energy as a Sankey diagram, from electricity and gas through rooms, areas and heating
 * to plugs, each band as wide as its kWh. Hovering or tapping a node or band says its kWh, cost and share of its source;
 * estimated bands (gas shared between rooms) are hatched.
 */
export function EnergyFlowCard() {
  const [period, setPeriod] = useState<FlowPeriod>(storedPeriod)
  const [flow, setFlow] = useState<EnergyFlow | null>(null)
  const [focus, setFocus] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const load = () => flowApi.get(period).then((f) => !cancelled && setFlow(f), () => {})
    load()
    window.addEventListener(DAY_FIGURES_EVENT, load)
    return () => {
      cancelled = true
      window.removeEventListener(DAY_FIGURES_EVENT, load)
    }
  }, [period])

  function choose(p: FlowPeriod) {
    setPeriod(p)
    setFocus(null)
    try {
      localStorage.setItem(PERIOD_KEY, p)
    } catch {
      /* not remembered */
    }
  }

  const tallest = flow ? Math.max(1, ...[0, 1, 2, 3].map((c) => flow.nodes.filter((n) => n.kind === (c === 0 ? 'source' : c === 2 ? 'device' : 'place')).length)) : 1
  const height = Math.max(220, tallest * 30)
  const laid = useMemo(() => (flow ? layoutFlow(flow, WIDTH - LABEL_ROOM * 2, height, NODE) : null), [flow, height])
  const currency = symbol(flow?.currency ?? 'GBP')
  const hasElectricity = flow?.nodes.some((n) => n.id === 'electricity') ?? false
  const sourceOf = (id: string) => flow?.nodes.find((n) => n.id === id) ?? null

  let readout: string | null = null
  if (laid && focus) {
    const node = laid.nodes.find((n) => `node:${n.id}` === focus)
    const link = laid.links.find((l) => `link:${l.from}>${l.to}` === focus)
    if (node) {
      const src = sourceOf(node.source)
      readout = flowReadout(node, src?.kwh ?? null, src?.name ?? null, currency)
    } else if (link) {
      const to = laid.nodes.find((n) => n.id === link.to)!
      const src = sourceOf(link.source)
      const cost = to.kwh > 0 ? (to.cost * link.kwh) / to.kwh : 0
      readout = flowReadout({ name: `${laid.nodes.find((n) => n.id === link.from)!.name} → ${to.name}`, kwh: link.kwh, cost }, src?.kwh ?? null, src?.name ?? null, currency) + (link.estimated ? ' · estimated' : '')
    }
  }

  return (
    <Card data-testid="energy-flow">
      <CardContent className="flex flex-col gap-4">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <h2 className="flex items-center gap-2 text-xl font-semibold">
            <Workflow className="size-5 text-muted-foreground" /> Where it went
          </h2>
          <div role="group" aria-label="Flow period" className="flex rounded-lg bg-secondary p-0.5">
            {FLOW_PERIODS.map((p) => (
              <button
                key={p.value}
                type="button"
                aria-pressed={period === p.value}
                onClick={() => choose(p.value)}
                className={cn('rounded-md px-2.5 py-1 text-sm font-medium transition-colors', period === p.value ? 'bg-card text-foreground shadow-sm' : 'text-muted-foreground hover:text-foreground')}
              >
                {p.label}
              </button>
            ))}
          </div>
        </div>
        {flow && !hasElectricity && <p className="text-sm text-muted-foreground">{NO_HOUSE_METER_FLOW}</p>}
        <p data-testid="flow-readout" className="min-h-5 text-sm tabular-nums text-muted-foreground" aria-live="polite">
          {readout ?? 'Hover or tap a band to see what it used.'}
        </p>
        {laid && laid.nodes.length > 0 && (
          <svg viewBox={`0 0 ${WIDTH} ${height + PAD * 2}`} className="h-auto w-full" role="img" aria-label="Where the house's energy went" onMouseLeave={() => setFocus(null)}>
            <defs>
              {Object.entries(COLOUR).map(([id, colour]) => (
                <pattern key={id} id={`hatch-${id}`} width="6" height="6" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
                  <rect width="6" height="6" fill={colour} opacity="0.18" />
                  <line x1="0" y1="0" x2="0" y2="6" stroke={colour} strokeWidth="2.5" opacity="0.5" />
                </pattern>
              ))}
            </defs>
            <g transform={`translate(${LABEL_ROOM},${PAD})`}>
              {laid.links.map((l) => {
                const key = `link:${l.from}>${l.to}`
                return (
                  <path
                    key={key}
                    data-testid="flow-link"
                    data-from={l.from}
                    data-to={l.to}
                    d={bandPath(l)}
                    fill={l.estimated ? `url(#hatch-${l.source})` : colourOf(l.source)}
                    opacity={focus == null || focus === key ? (l.estimated ? 1 : 0.35) : 0.12}
                    onMouseEnter={() => setFocus(key)}
                    onClick={() => setFocus(key)}
                    className="cursor-pointer transition-opacity"
                  />
                )
              })}
              {laid.nodes.map((n) => {
                const key = `node:${n.id}`
                // Sources are labelled in the margin to their left, the rest to their right.
                const left = n.column === 0
                return (
                  <g key={n.id} data-testid="flow-node" data-name={n.name} onMouseEnter={() => setFocus(key)} onClick={() => setFocus(key)} className="cursor-pointer">
                    <rect x={n.x} y={n.y} width={NODE} height={n.height} rx="2" fill={colourOf(n.source)} />
                    <text
                      x={left ? n.x - 6 : n.x + NODE + 6}
                      y={n.y + n.height / 2}
                      dominantBaseline="middle"
                      textAnchor={left ? 'end' : 'start'}
                      className="fill-foreground text-[13px]"
                      style={{ paintOrder: 'stroke', stroke: 'var(--card)', strokeWidth: 3 }}
                    >
                      {n.name} <tspan className="fill-muted-foreground">{n.kwh.toFixed(1)} kWh</tspan>
                    </text>
                  </g>
                )
              })}
            </g>
          </svg>
        )}
      </CardContent>
    </Card>
  )
}
