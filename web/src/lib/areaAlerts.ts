import { request } from './api'

export type AlertAudience = 'me' | 'everyone'

/** GET /api/energy/areas/{id}/alert: all null with none. [by] is the username of the admin who set it up. */
export interface AreaAlert {
  hours: number | null
  notify: AlertAudience | null
  by: string | null
  /** Whether the signed-in admin set it up */
  mine?: boolean
}

const path = (area: string) => `/api/energy/areas/${encodeURIComponent(area)}/alert`

export const areaAlertApi = {
  get: (area: string) => request<AreaAlert>('GET', path(area)),
  set: (area: string, hours: number, notify: AlertAudience) => request<AreaAlert>('PUT', path(area), { hours, notify }),
  clear: (area: string) => request<void>('DELETE', path(area)).then(() => true),
}

/** "2 h", "1 h 30 min", "30 min". */
export function hoursText(hours: number): string {
  const minutes = Math.round(hours * 60)
  const h = Math.floor(minutes / 60)
  const m = minutes % 60
  return [h > 0 ? `${h} h` : null, m > 0 ? `${m} min` : null].filter(Boolean).join(' ')
}

/** "Notifies you after 2 h", "Notifies everyone after 2 h", or "Notifies jane after 2 h" to anyone but jane; null with none. */
export function alertLine(alert: AreaAlert | null, me: string): string | null {
  if (!alert || alert.hours == null) return null
  const who = alert.notify === 'everyone' ? 'everyone' : alert.mine || alert.by === me || alert.by == null ? 'you' : alert.by
  return `Notifies ${who} after ${hoursText(alert.hours)}`
}
