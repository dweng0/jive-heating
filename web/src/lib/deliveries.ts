import { request } from './api'

export type DeliveryStatus = 'queued' | 'sent' | 'retrying' | 'failed'

export interface DeliveryAttempt {
  at: string
  error: string
}

/** openapi: DeliveryEvent. What caused a delivery (a rule's event); absent for heating alerts. */
export interface DeliveryEvent {
  id?: string | null
  topic?: string | null
  payload?: unknown
  at?: string | null
}

/** openapi: Delivery. One notification for one person. */
export interface Delivery {
  id: string
  at: string
  source: string
  ruleId?: string | null
  userId: string
  person: string
  title: string
  body: string
  status: DeliveryStatus
  tries: number
  sentAt?: string | null
  sentTo: string[]
  attempts: DeliveryAttempt[]
  nextAttemptAt?: string | null
  event?: DeliveryEvent | null
}

export interface Deliveries {
  deliveries: Delivery[]
  failed: number
  sources: string[]
  people: { id: string; name: string }[]
}

export type DeliverySort = 'time' | 'rule' | 'person' | 'status' | 'tries'

export interface DeliveryFilter {
  status?: DeliveryStatus
  rule?: string
  person?: string
  from?: string
  to?: string
  sort?: DeliverySort
  dir?: 'asc' | 'desc'
}

export const deliveriesApi = {
  list: (filter: DeliveryFilter = {}) => {
    const query = Object.entries(filter)
      .filter(([, v]) => v)
      .map(([k, v]) => `${k}=${encodeURIComponent(String(v))}`)
      .join('&')
    return request<Deliveries>('GET', `/api/deliveries${query ? `?${query}` : ''}`)
  },
  get: (id: string) => request<Delivery>('GET', `/api/deliveries/${encodeURIComponent(id)}`),
  retry: (id: string) => request<Delivery>('POST', `/api/deliveries/${encodeURIComponent(id)}/retry`, {}),
  retryAll: () => request<{ retried: number }>('POST', '/api/deliveries/retry-all', {}),
  clear: (id: string) => request<void>('DELETE', `/api/deliveries/${encodeURIComponent(id)}`),
}

/** "1 failed notification", "2 failed notifications". */
export const failedText = (n: number) => `${n} failed notification${n === 1 ? '' : 's'}`

/** The last error of a failed delivery, or its status. */
export const lastError = (d: Delivery) => d.attempts[d.attempts.length - 1]?.error ?? ''

export const statusLabel: Record<DeliveryStatus, string> = { queued: 'Queued', sent: 'Sent', retrying: 'Retrying', failed: 'Failed' }
