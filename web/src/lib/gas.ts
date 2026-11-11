import { request } from './api'

/** GET /api/energy/gas: a day of the smart gas meter, half-hour by half-hour. */
export interface GasDay {
  date: string
  kwh: number
  cost: number
  currency: string
  halfHours: { at: string; kwh: number }[]
  /** The nearest earlier and later days with readings */
  previous: string | null
  next: string | null
  /** The meter's kWh over the boiler's burning hours on the last 7 metered days; null with under 5 hours */
  measuredBoilerKw: number | null
  /** The setting the gas estimate uses */
  boilerAverageKw: number
}

export const gasApi = {
  day: (date?: string) => request<GasDay>('GET', `/api/energy/gas${date ? `?date=${date}` : ''}`),
}

/** "Thu 5 Nov" for a gas day. */
export const gasDayTitle = (date: string) =>
  new Date(`${date}T12:00:00Z`).toLocaleDateString('en-GB', { weekday: 'short', day: 'numeric', month: 'short', timeZone: 'UTC' }).replace(',', '')

/** "18:30" for a half-hour's start, in the browser's timezone. */
export const halfHourTime = (at: string) => new Date(at).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit', hourCycle: 'h23' })

/** The busiest half-hour, or null on a day with no use. */
export function busiest(day: GasDay): { at: string; kwh: number } | null {
  const top = day.halfHours.reduce<{ at: string; kwh: number } | null>((a, h) => (a == null || h.kwh > a.kwh ? h : a), null)
  return top && top.kwh > 0 ? top : null
}

