// The live power dial: the house meter's watts streamed on /ws/power, and its colour bands and last 30
// minutes from GET /api/power. Mirrors the LivePower and PowerSummary schemas in openapi.json. Admins and energy access.

import { request } from './api'
import type { TodayPeak } from './energyDials'

export interface RoomPower {
  room: string
  name: string
  /** Sum of its plugs reporting now; null when none is */
  watts: number | null
}

export interface AreaPower {
  area: string
  name: string
  /** Its clamp's power, or the sum of its plugs reporting now; null with no reading */
  watts: number | null
}

export interface DevicePower {
  meter: string
  watts: number | null
}

export interface LivePower {
  /** House W; null with no reading in the last 2 minutes */
  watts: number | null
  at: string | null
  rooms: RoomPower[]
  devices: DevicePower[]
  /** Energy areas (older services leave it out) */
  areas?: AreaPower[]
  /** House W no room or device accounts for; null while watts is */
  elseWatts: number | null
  /** Where the power goes now, between solar, battery, grid and home (older services leave it out) */
  flows?: PowerFlow[]
  /** Battery charge %; null without an inverter reading */
  batteryPct?: number | null
  /** The weather outside as the Live picture draws it; null without a weather source or a recent reading */
  weather?: LiveWeather | null
  /** Every meter but the house meter, for the league table (older services leave it out) */
  items?: ItemPower[]
  /** The house meter's kWh since the household's midnight, as of this message */
  houseTodayKwh?: number | null
  /** Electricity price, in the currency's minor unit a kWh */
  pencePerKwh?: number
  currency?: string
}

export interface ItemPower {
  meter: string
  /** Its label, else its name */
  name: string
  /** Null without a live reading */
  watts: number | null
  room?: string | null
  area?: string | null
  /** "area" (an area's whole-area meter), "monitored" (a monitored device) or null (no job yet) */
  job?: string | null
  /** kWh since the household's midnight, as of this message; null for a meter with no job (older services leave it out) */
  todayKwh?: number | null
}

export interface LiveWeather {
  look: 'clear' | 'cloudy' | 'overcast' | 'rain' | 'snow' | 'frost'
  temperatureC?: number | null
  /** When it was observed */
  at: string
}

export interface PowerFlow {
  from: 'solar' | 'battery' | 'grid'
  to: 'home' | 'battery' | 'grid'
  /** Above 0 */
  watts: number
}

export interface PowerBands {
  /** Green up to here: the typical overnight load */
  green: number
  /** Red from here: the household's top tenth */
  red: number
}

export interface PowerPoint {
  at: string
  watts: number | null
}

export interface PowerSummary {
  /** Null with under a day of readings: one neutral colour */
  bands: PowerBands | null
  recent: PowerPoint[]
  /** Highest five-minute house power since midnight; null before today's first sample (older services leave it out) */
  todayPeak?: TodayPeak | null
}

export const powerApi = {
  summary: () => request<PowerSummary>('GET', '/api/power'),
}

/** Live power from /ws/power (the cookie authenticates it), reconnecting with backoff until stopped. */
export function followPower(onPower: (p: LivePower) => void, onConnected: (connected: boolean) => void): () => void {
  let socket: WebSocket | null = null
  let stopped = false
  let backoff = 1000
  let timer: number | undefined

  const connect = () => {
    if (stopped) return
    const scheme = location.protocol === 'https:' ? 'wss' : 'ws'
    socket = new WebSocket(`${scheme}://${location.host}/ws/power`)
    socket.onopen = () => {
      backoff = 1000
      onConnected(true)
    }
    socket.onmessage = (m) => {
      try {
        onPower(JSON.parse(m.data as string) as LivePower)
      } catch {
        /* ignore malformed */
      }
    }
    socket.onclose = () => {
      onConnected(false)
      if (stopped) return
      timer = window.setTimeout(connect, backoff)
      backoff = Math.min(backoff * 2, 30_000)
    }
  }
  connect()
  return () => {
    stopped = true
    window.clearTimeout(timer)
    socket?.close()
  }
}

export type PowerZone = 'low' | 'mid' | 'high' | 'neutral'

/** Green up to the overnight load, red from the household's top tenth, amber between; neutral with no bands. */
export function powerZone(watts: number, bands: PowerBands | null): PowerZone {
  if (bands == null) return 'neutral'
  if (watts <= bands.green) return 'low'
  if (watts >= bands.red) return 'high'
  return 'mid'
}

/** How long the needle takes to reach a new reading. */
export const SWEEP_MS = 1000

/**
 * Where the needle is [elapsed] ms into a sweep from [from] to [to]: eases out, and never passes [to] on the
 * way, so a rise does not look like a spike.
 */
export function sweep(from: number, to: number, elapsed: number): number {
  const t = Math.min(1, Math.max(0, elapsed / SWEEP_MS))
  const eased = 1 - (1 - t) ** 3
  return from + (to - from) * eased
}

const DIAL_MAX = 8000

/** The dial's full scale: 8 kW, room for an oven, shower or tumble dryer; above that, the next whole 2 kW. */
export function dialMax(watts: number | null): number {
  return Math.max(DIAL_MAX, Math.ceil((watts ?? 0) / 2000) * 2000)
}

/** How far round the dial [watts] is (0..1): a square-root scale, so 500 W is a quarter of the way and low use still moves. */
export function dialFraction(watts: number, max: number): number {
  return Math.sqrt(Math.min(max, Math.max(0, watts)) / max)
}

/** The dial's marks: 0, 0.5, 1, 2 kW, then every 2 kW to [max]. */
export function dialTicks(max: number): number[] {
  const ticks = [0, 500, 1000]
  for (let w = 2000; w <= max; w += 2000) ticks.push(w)
  return ticks
}

/** "850 W" below a kilowatt, "5.45 kW" from one. */
export function formatWatts(watts: number | null | undefined): string {
  if (watts == null) return '–'
  if (Math.abs(watts) < 1000) return `${Math.round(watts)} W`
  return `${(watts / 1000).toFixed(2)} kW`
}
