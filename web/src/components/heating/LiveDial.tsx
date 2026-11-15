import { useRef } from 'react'
import type { LivePower } from '@/lib/livePower'
import { toLiveReading } from '@/live/liveReading'

/** The arc fills towards at least this, or the busiest the home has been since the page opened. */
const MIN_SCALE_W = 3000

/** Watts as the Live picture shows them: "400 W", "1.2 kW". */
function fmt(w: number): string {
  const a = Math.abs(w)
  return a < 1000 ? `${Math.round(a)} W` : `${(a / 1000).toFixed(1)} kW`
}

/** A point on a circle of radius [r] round (cx, cy), [deg] clockwise from straight up. */
function at(cx: number, cy: number, r: number, deg: number): [number, number] {
  const t = (deg * Math.PI) / 180
  return [cx + r * Math.sin(t), cy - r * Math.cos(t)]
}

/** An SVG arc from [from]° to [to]° (clockwise from straight up). */
function arc(cx: number, cy: number, r: number, from: number, to: number): string {
  const [x1, y1] = at(cx, cy, r, from)
  const [x2, y2] = at(cx, cy, r, to)
  const large = Math.abs(to - from) > 180 ? 1 : 0
  const sweep = to > from ? 1 : 0
  return `M ${x1} ${y1} A ${r} ${r} 0 ${large} ${sweep} ${x2} ${y2}`
}

const HOME = { from: -125, to: 125 }
/** Half the sweep of each side arc, either side of level. */
const SIDE = 28
const HOME_R = 112
const SIDE_R = 300

/**
 * The house at a glance, like a car's instrument panel: the home's use in the middle on an arc that fills towards the
 * busiest it has been, the grid on the left (importing orange, exporting green) and the battery's charge on the right.
 */
export function LiveDial({ live }: { live: LivePower | null }) {
  const peak = useRef(MIN_SCALE_W)
  if (live == null || live.watts == null) return null
  const r = toLiveReading(live)
  const flow = (from: string, to: string) => r.flows.filter((f) => f.from === from && f.to === to).reduce((s, f) => s + f.watts, 0)
  const home = r.home
  peak.current = Math.max(peak.current, home)
  const scale = Math.ceil(peak.current / 1000) * 1000
  const fill = Math.min(1, home / scale)
  const imported = r.flows.filter((f) => f.from === 'grid').reduce((s, f) => s + f.watts, 0)
  const exported = r.flows.filter((f) => f.to === 'grid').reduce((s, f) => s + f.watts, 0)
  const grid = imported - exported
  const own = flow('solar', 'home') + flow('battery', 'home')
  const ownPct = home > 0 ? Math.round((own / home) * 100) : 0
  const gridColour = grid >= 0 ? 'var(--grid, #f59f5a)' : 'var(--solar, #3ddc84)'
  const gridFill = Math.min(1, Math.abs(grid) / scale)
  const battery = r.batteryPct

  // Side arcs: left from the bottom up to the top, right likewise, each a 2 × 46° sweep round the middle.
  const W = 800
  const H = 300
  const cx = W / 2
  const cy = 152
  return (
    <div data-testid="live-dial" className="live-dial">
      <svg viewBox={`0 0 ${W} ${H}`} role="img" aria-label="Home, grid and battery right now" className="h-auto w-full">
        <defs>
          <filter id="dialGlow" x="-50%" y="-50%" width="200%" height="200%">
            <feGaussianBlur stdDeviation="4" result="b" />
            <feMerge>
              <feMergeNode in="b" />
              <feMergeNode in="SourceGraphic" />
            </feMerge>
          </filter>
        </defs>
        {/* Home: the big arc in the middle. */}
        <path d={arc(cx, cy, HOME_R, HOME.from, HOME.to)} className="dial-track" strokeWidth="14" fill="none" strokeLinecap="round" />
        {fill > 0.005 && (
          <path
            d={arc(cx, cy, HOME_R, HOME.from, HOME.from + (HOME.to - HOME.from) * fill)}
            stroke="var(--home-arc, #4aa3ff)"
            strokeWidth="14"
            fill="none"
            strokeLinecap="round"
            filter="url(#dialGlow)"
            className="dial-value"
          />
        )}
        <circle
          cx={at(cx, cy, HOME_R, HOME.from + (HOME.to - HOME.from) * fill)[0]}
          cy={at(cx, cy, HOME_R, HOME.from + (HOME.to - HOME.from) * fill)[1]}
          r="9"
          className="dial-knob"
        />
        <text x={cx} y={cy + 8} textAnchor="middle" className="dial-big" data-testid="dial-home">
          {fmt(home)}
        </text>
        <text x={cx} y={cy + 36} textAnchor="middle" className="dial-sub">
          Using now
        </text>
        <text x={cx} y={cy + 70} textAnchor="middle" className="dial-own" data-testid="dial-own">
          {`${ownPct}% your own`}
        </text>
        <text x={at(cx, cy, HOME_R, HOME.from)[0]} y={at(cx, cy, HOME_R, HOME.from)[1] + 26} textAnchor="middle" className="dial-tick">
          0
        </text>
        <text x={at(cx, cy, HOME_R, HOME.to)[0]} y={at(cx, cy, HOME_R, HOME.to)[1] + 26} textAnchor="middle" className="dial-tick">
          {fmt(scale)}
        </text>

        {/* Grid: the arc on the left. */}
        <path d={arc(cx, cy, SIDE_R, -90 - SIDE, -90 + SIDE)} className="dial-track dial-side" strokeWidth="10" fill="none" strokeLinecap="round" />
        {gridFill > 0.005 && (
          <path
            d={arc(cx, cy, SIDE_R, -90 - SIDE, -90 - SIDE + 2 * SIDE * gridFill)}
            stroke={gridColour}
            strokeWidth="10"
            fill="none"
            strokeLinecap="round"
            filter="url(#dialGlow)"
            className="dial-value dial-side"
          />
        )}
        <text x={cx - 240} y={cy - 22} textAnchor="start" className="dial-k dial-side">
          Grid
        </text>
        <text x={cx - 240} y={cy + 12} textAnchor="start" className="dial-mid dial-side" style={{ fill: gridColour }} data-testid="dial-grid">
          {fmt(grid)}
        </text>
        <text x={cx - 240} y={cy + 34} textAnchor="start" className="dial-sub dial-side" data-testid="dial-grid-state">
          {grid < 0 ? 'Exporting' : 'Importing'}
        </text>

        {/* Battery: the arc on the right. */}
        <path d={arc(cx, cy, SIDE_R, 90 + SIDE, 90 - SIDE)} className="dial-track dial-side" strokeWidth="10" fill="none" strokeLinecap="round" />
        {battery != null && battery > 0 && (
          <path
            d={arc(cx, cy, SIDE_R, 90 + SIDE, 90 + SIDE - 2 * SIDE * Math.min(1, battery / 100))}
            stroke="var(--battery, #4aa3ff)"
            strokeWidth="10"
            fill="none"
            strokeLinecap="round"
            filter="url(#dialGlow)"
            className="dial-value dial-side"
          />
        )}
        <text x={cx + 240} y={cy - 22} textAnchor="end" className="dial-k dial-side">
          Battery
        </text>
        <text
          x={cx + 240}
          y={cy + 12}
          textAnchor="end"
          className={battery == null ? 'dial-sub dial-side' : 'dial-mid dial-side'}
          style={battery == null ? undefined : { fill: 'var(--battery, #4aa3ff)' }}
          data-testid="dial-battery"
        >
          {battery == null ? 'Not connected' : `${Math.round(battery)}%`}
        </text>
        {battery == null && (
          <text x={cx + 240} y={cy + 34} textAnchor="end" className="dial-sub dial-side">
            Arrives with the inverter
          </text>
        )}
      </svg>
      {/* On a phone the side figures are too small inside the picture: they sit in a row under it instead. */}
      <div className="dial-legs" aria-hidden>
        <span>
          <b style={{ color: gridColour }}>{fmt(grid)}</b> Grid {grid < 0 ? 'exporting' : 'importing'}
        </span>
        <span>
          <b style={battery == null ? undefined : { color: 'var(--battery, #4aa3ff)' }}>{battery == null ? 'Not connected' : `${Math.round(battery)}%`}</b>{' '}
          Battery
        </span>
      </div>
    </div>
  )
}
