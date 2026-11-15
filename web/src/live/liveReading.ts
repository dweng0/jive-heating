// Turns a /ws/power message into what the Live page draws: the flows between solar, battery, grid and home, the
// battery's charge, whether the inverter is connected, and the circuits for "Where it's going".
import type { LivePower } from '@/lib/livePower'

export interface LiveReading {
  at: string
  batteryPct: number | null
  /** Solar and the battery exist: the inverter is connected. Before then they show "Not connected". */
  hasSolar: boolean
  flows: { from: string; to: string; watts: number }[]
  /** What the home is using, W */
  home: number
  circuits: { name: string; watts: number }[]
}

export function toLiveReading(p: LivePower): LiveReading {
  // A service from before flows existed sends none: the house meter is then all grid to home.
  const flows = p.flows ?? (p.watts != null && p.watts > 0 ? [{ from: 'grid' as const, to: 'home' as const, watts: p.watts }] : [])
  const hasSolar = p.batteryPct != null || flows.some((f) => f.from === 'solar' || f.from === 'battery' || f.to === 'battery')
  const circuits = [
    ...p.rooms.map((r) => ({ name: r.name, watts: r.watts ?? 0 })),
    ...(p.areas ?? []).map((a) => ({ name: a.name, watts: a.watts ?? 0 })),
    ...p.devices.map((d) => ({ name: d.meter, watts: d.watts ?? 0 })),
  ]
  return { at: p.at ?? new Date().toISOString(), batteryPct: p.batteryPct ?? null, hasSolar, flows, home: p.watts ?? 0, circuits }
}
