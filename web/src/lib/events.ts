import { request } from './api'

/** openapi: Event. One MQTT message the service saw. */
export interface LoggedEvent {
  id: number
  at: string
  topic: string
  payload: unknown
  json: boolean
  retained: boolean
  truncated: boolean
}

export interface EventPage {
  events: LoggedEvent[]
  next: string | null
  total: number
}

/** openapi: EventTopic. */
export interface EventTopic {
  topic: string
  count: number
  lastSeen: string
  lastPayload: unknown
  json: boolean
}

export type EventSort = 'at' | 'topic'

/** What the chips ask for; one of each kind. */
export interface EventFilter {
  topic?: string
  device?: string
  field?: string
  /** A Field value chip: [fieldValue.field] equals (or, with [not], differs from) [fieldValue.value]. */
  fieldValue?: { field: string; value: string; not?: boolean }
  text?: string
  range?: string
  retained?: boolean
}

export const PAGE_SIZE = 100

export const RANGES = [
  { value: '1h', label: 'Last hour', hours: 1 },
  { value: '24h', label: 'Last 24 h', hours: 24 },
  { value: '7d', label: 'Last 7 days', hours: 24 * 7 },
  { value: '30d', label: 'Last 30 days', hours: 24 * 30 },
]

/** The chips as page-address parameters: topic, device, field, fv ("contact=false" or "contact!=false"), text, range, retained. */
export function filterFromParams(p: URLSearchParams): EventFilter {
  const f: EventFilter = {}
  if (p.get('topic')) f.topic = p.get('topic')!
  if (p.get('device')) f.device = p.get('device')!
  if (p.get('field')) f.field = p.get('field')!
  const fv = p.get('fv')
  if (fv) {
    const parsed = parseFieldValue(fv)
    if (parsed) f.fieldValue = parsed
  }
  if (p.get('text')) f.text = p.get('text')!
  if (RANGES.some((r) => r.value === p.get('range'))) f.range = p.get('range')!
  if (p.get('retained') === '1') f.retained = true
  if (p.get('retained') === '0') f.retained = false
  return f
}

export function filterToParams(f: EventFilter, into = new URLSearchParams()): URLSearchParams {
  if (f.topic) into.set('topic', f.topic)
  if (f.device) into.set('device', f.device)
  if (f.field) into.set('field', f.field)
  if (f.fieldValue) into.set('fv', `${f.fieldValue.field}${f.fieldValue.not ? '!=' : '='}${f.fieldValue.value}`)
  if (f.text) into.set('text', f.text)
  if (f.range) into.set('range', f.range)
  if (f.retained !== undefined) into.set('retained', f.retained ? '1' : '0')
  return into
}

/** "contact = false", "battery!=50" */
export function parseFieldValue(text: string): { field: string; value: string; not?: boolean } | null {
  const m = text.match(/^\s*([A-Za-z0-9_-]+(?:\.[A-Za-z0-9_-]+)*)\s*(!=|=)\s*(.+?)\s*$/)
  return m ? { field: m[1], value: m[3], ...(m[2] === '!=' ? { not: true } : {}) } : null
}

/** The query string the service understands. */
export function eventQuery(f: EventFilter, sort: EventSort, descending: boolean, cursor?: string | null, extra: { from?: string } = {}): string {
  const q = new URLSearchParams()
  if (f.topic) q.set('topic', f.topic)
  if (f.device) q.set('device', f.device)
  const field = f.fieldValue?.field ?? f.field
  if (field) q.set('field', field)
  if (f.fieldValue) {
    q.set('value', f.fieldValue.value)
    if (f.fieldValue.not) q.set('op', 'ne')
  }
  if (f.text) q.set('text', f.text)
  const hours = RANGES.find((r) => r.value === f.range)?.hours
  if (hours) q.set('from', extra.from ?? new Date(Date.now() - hours * 3600_000).toISOString())
  if (f.retained !== undefined) q.set('retained', String(f.retained))
  q.set('sort', `${sort} ${descending ? 'desc' : 'asc'}`)
  q.set('limit', String(PAGE_SIZE))
  if (cursor) q.set('cursor', cursor)
  return q.toString()
}

export const eventsApi = {
  list: (query: string) => request<EventPage>('GET', `/api/events?${query}`),
  topics: () => request<EventTopic[]>('GET', '/api/events/topics'),
}

/** A payload on one line, cut off after [max] characters. */
export function shortPayload(payload: unknown, max = 100): string {
  const text = typeof payload === 'string' ? payload : JSON.stringify(payload)
  return text.length > max ? `${text.slice(0, max)}…` : text
}

export interface PayloadField {
  path: string
  value: unknown
}

/** Every field of a JSON object payload with its path ("contact", "update.state"); arrays are shown as one value. */
export function payloadFields(payload: unknown, prefix = ''): PayloadField[] {
  if (payload === null || typeof payload !== 'object' || Array.isArray(payload)) return prefix ? [{ path: prefix, value: payload }] : []
  return Object.entries(payload as Record<string, unknown>).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key
    return value !== null && typeof value === 'object' && !Array.isArray(value) ? payloadFields(value, path) : [{ path, value }]
  })
}

/** Whether the service can filter on this field path (letters, digits, _ and -, separated by dots). */
export const filterablePath = (path: string) => /^[A-Za-z0-9_-]+(\.[A-Za-z0-9_-]+)*$/.test(path)

export const valueText = (value: unknown) => (typeof value === 'string' ? value : JSON.stringify(value))

/** The Zigbee2MQTT base topic, found from its bridge topics; "zigbee2mqtt" when none are seen. */
export function z2mBase(topics: EventTopic[]): string {
  return topics.find((t) => t.topic.endsWith('/bridge/devices'))?.topic.replace(/\/bridge\/devices$/, '') ?? 'zigbee2mqtt'
}

export interface TopicNode {
  /** This level's name, e.g. "front_door". */
  name: string
  /** The path down to here, e.g. "z2m/front_door". */
  path: string
  /** Messages here and below. */
  count: number
  lastSeen: string
  /** Set when messages were published on exactly this path. */
  own?: EventTopic
  children: TopicNode[]
}

/** Topics split on "/" into a tree, each level with the count and last seen of everything under it. */
export function topicTree(topics: EventTopic[]): TopicNode[] {
  const root: TopicNode = { name: '', path: '', count: 0, lastSeen: '', children: [] }
  for (const t of topics) {
    let node = root
    const parts = t.topic.split('/')
    parts.forEach((part, i) => {
      const path = parts.slice(0, i + 1).join('/')
      let child = node.children.find((c) => c.name === part)
      if (!child) {
        child = { name: part, path, count: 0, lastSeen: '', children: [] }
        node.children.push(child)
      }
      child.count += t.count
      if (t.lastSeen > child.lastSeen) child.lastSeen = t.lastSeen
      node = child
    })
    node.own = t
  }
  return root.children
}

export const STALE_DAYS = 7

/** "3 min ago", "2 h ago", "9 days ago". */
export function ago(iso: string, now = Date.now()): string {
  const seconds = Math.max(0, Math.round((now - new Date(iso).getTime()) / 1000))
  if (seconds < 60) return `${seconds} s ago`
  if (seconds < 3600) return `${Math.floor(seconds / 60)} min ago`
  if (seconds < 86400) return `${Math.floor(seconds / 3600)} h ago`
  const days = Math.floor(seconds / 86400)
  return `${days} day${days === 1 ? '' : 's'} ago`
}
