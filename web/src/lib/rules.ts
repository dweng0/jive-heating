import { request } from './api'
import { payloadFields, valueText, type EventTopic, type LoggedEvent } from './events'

export type Operator = '=' | '≠' | '>' | '<' | '≥' | '≤' | 'contains' | 'exists'
export type ConditionValue = string | number | boolean

/** openapi: Condition. [value] is left out by "exists". */
export interface Condition {
  field: string
  operator: Operator
  value?: ConditionValue
}

export interface QuietHours {
  from: string
  to: string
  sendSummary: boolean
}

/** openapi: RuleRequest: everything a rule is except what the service fills in. */
export interface RuleRequest {
  name: string
  topic: string
  conditions: Condition[]
  trigger: 'changes to' | 'is'
  title: string
  message: string
  notify: 'me' | 'everyone'
  cooldownSeconds: number
  quietHours: QuietHours | null
  enabled: boolean
}

/** openapi: Rule. */
export interface Rule extends RuleRequest {
  id: string
  by: string
  byUserId: string | null
  createdAt: string
  pausedUntil: string | null
  pausedUntilLocal: string | null
  /** When it last sent a notification; null if never. */
  lastSentAt?: string | null
}

export interface RuleHistoryEntry {
  at: string
  topic: string
  payload: string
  outcome: string
  text: string
  recipients: number
  deliveryIds: string[]
  detail: string | null
}

export type TestStatus = 'would_send' | 'not_met' | 'no_change'

export interface TestEvent {
  id: number
  at: string
  topic: string
  payload: string
  status: TestStatus
  result: string
  title: string | null
  message: string | null
}

export const rulesApi = {
  list: () => request<Rule[]>('GET', '/api/rules'),
  get: (id: string) => request<Rule>('GET', `/api/rules/${encodeURIComponent(id)}`),
  create: (r: RuleRequest) => request<Rule>('POST', '/api/rules', r),
  replace: (id: string, r: RuleRequest) => request<Rule>('PUT', `/api/rules/${encodeURIComponent(id)}`, r),
  remove: (id: string) => request<void>('DELETE', `/api/rules/${encodeURIComponent(id)}`).then(() => true),
  resume: (id: string) => request<Rule>('POST', `/api/rules/${encodeURIComponent(id)}/resume`),
  history: (id: string, limit = 50) => request<RuleHistoryEntry[]>('GET', `/api/rules/${encodeURIComponent(id)}/history?limit=${limit}`),
  test: (r: RuleRequest) => request<{ events: TestEvent[] }>('POST', '/api/rules/test', r),
  suggestions: (device?: string) =>
    request<Suggestion[]>('GET', `/api/rules/suggestions${device ? `?device=${encodeURIComponent(device)}` : ''}`),
  sendTest: (r: RuleRequest) => request<{ title: string; message: string; deliveryIds: string[] }>('POST', '/api/rules/test/send', r),
}

export const TEST_SHOWN = 5

/** How long the service keeps a rule's history, and so how far back lastSentAt can see. */
export const HISTORY_DAYS = 30

/** What a rule with no send in its history says: "Never sent" only when it is younger than the history kept. */
export function notSentText(createdAt: string, now = Date.now()): string {
  const made = Date.parse(createdAt)
  return Number.isFinite(made) && now - made < HISTORY_DAYS * 86_400_000 ? 'Never sent' : `Not sent in the last ${HISTORY_DAYS} days`
}

export const emptyRequest = (): RuleRequest => ({
  name: '',
  topic: '',
  conditions: [],
  trigger: 'changes to',
  title: '',
  message: '',
  notify: 'me',
  cooldownSeconds: 0,
  quietHours: null,
  enabled: true,
})

/** The part of a rule that matters when comparing; the service-filled fields are dropped. */
export function asRequest(r: Rule | RuleRequest): RuleRequest {
  return {
    name: r.name,
    topic: r.topic,
    conditions: r.conditions,
    trigger: r.trigger,
    title: r.title,
    message: r.message,
    notify: r.notify,
    cooldownSeconds: r.cooldownSeconds,
    quietHours: r.quietHours,
    enabled: r.enabled,
  }
}

/** The device a topic is about: its last level ("z2m/front_door" -> "front_door"). */
export const deviceOf = (topic: string) => topic.split('/').pop() ?? topic

/** "front_door" -> "Front door". */
export function humanize(name: string): string {
  const text = name.replace(/[_-]+/g, ' ').trim()
  return text ? text[0].toUpperCase() + text.slice(1) : text
}

export type FieldKind = 'boolean' | 'number' | 'text'

export function kindOf(value: unknown): FieldKind {
  return typeof value === 'boolean' ? 'boolean' : typeof value === 'number' ? 'number' : 'text'
}

/** What a field's operators are: true/false fields get "is true"/"is false", numbers get = ≠ > < ≥ ≤, text gets = ≠ contains; every field gets "exists". */
export const OPERATORS: Record<FieldKind, Operator[]> = {
  boolean: ['=', 'exists'],
  number: ['=', '≠', '>', '<', '≥', '≤', 'exists'],
  text: ['=', '≠', 'contains', 'exists'],
}

/** How an operator reads for the kind of field it tests. */
export function operatorLabel(kind: FieldKind, op: Operator, value?: ConditionValue): string {
  if (kind === 'boolean' && op === '=') return value === false ? 'is false' : 'is true'
  return op === 'exists' ? 'exists' : op
}

/** A condition's value from text typed or picked: booleans and numbers as such when the field is one. */
export function parseValue(kind: FieldKind, text: string): ConditionValue {
  if (kind === 'boolean') return text === 'true'
  if (kind === 'number') {
    const n = Number(text)
    return text.trim() !== '' && Number.isFinite(n) ? n : text
  }
  return text
}

/** The condition "Notify me when…" on a field starts with, from the value seen. */
export function conditionFromValue(field: string, value: unknown): Condition {
  return { field, operator: '=', value: typeof value === 'boolean' || typeof value === 'number' ? value : valueText(value) }
}

export function conditionText(c: Condition): string {
  if (c.operator === 'exists') return `${c.field} exists`
  if (typeof c.value === 'boolean' && c.operator === '=') return `${c.field} is ${c.value}`
  return `${c.field} ${c.operator} ${c.value}`
}

/** The words a notification uses for a condition: "contact is false". */
function conditionWords(c: Condition): string {
  if (c.operator === 'exists') return `${c.field} exists`
  if (c.operator === '=') return `${c.field} is ${c.value}`
  if (c.operator === 'contains') return `${c.field} contains ${c.value}`
  return `${c.field} ${c.operator} ${c.value}`
}

/** A rule's name, title and message from its topic and conditions: "Front door contact false", "front_door", "front_door: contact is false". */
export function defaultTexts(topic: string, conditions: Condition[]): { name: string; title: string; message: string } {
  const last = deviceOf(topic)
  // A wildcard stands for whichever device sent the message.
  const any = last === '+'
  const device = any ? '{{device}}' : last
  const who = any ? 'Any device' : humanize(last)
  const first = conditions[0]
  if (!first) return { name: who, title: device, message: `${device}: {{payload}}` }
  const name = [who, first.field, first.operator === 'exists' ? 'exists' : typeof first.value === 'boolean' || first.operator === '=' ? String(first.value) : `${first.operator} ${first.value}`].join(' ')
  return { name, title: device, message: `${device}: ${conditions.map(conditionWords).join(' and ')}` }
}

/** The fields seen in the recent events on a topic, each with its kind and the distinct values newest first. */
export interface SeenField {
  path: string
  kind: FieldKind
  values: unknown[]
}

export function seenFields(events: LoggedEvent[]): SeenField[] {
  const byPath = new Map<string, SeenField>()
  for (const e of events) {
    if (!e.json) {
      const f = byPath.get('value') ?? { path: 'value', kind: kindOf(e.payload), values: [] }
      if (!f.values.includes(e.payload)) f.values.push(e.payload)
      byPath.set('value', f)
      continue
    }
    for (const { path, value } of payloadFields(e.payload)) {
      const f = byPath.get(path) ?? { path, kind: kindOf(value), values: [] }
      if (value !== null && value !== undefined && !f.values.some((v) => JSON.stringify(v) === JSON.stringify(value))) f.values.push(value)
      byPath.set(path, f)
    }
  }
  return [...byPath.values()]
}

/** Whether an MQTT topic filter ("z2m/+", "z2m/#") matches a topic. */
export function topicMatches(filter: string, topic: string): boolean {
  const f = filter.split('/')
  const t = topic.split('/')
  for (let i = 0; i < f.length; i++) {
    if (f[i] === '#') return true
    if (i >= t.length) return false
    if (f[i] !== '+' && f[i] !== t[i]) return false
  }
  return f.length === t.length
}

/** The wildcard that stands for "any device like this": the last level becomes "+". Null for a topic with one level or one that already has a wildcard there. */
export function widenedTopic(topic: string): string | null {
  const parts = topic.split('/')
  if (parts.length < 2 || parts[parts.length - 1] === '+' || parts[parts.length - 1] === '#') return null
  return [...parts.slice(0, -1), '+'].join('/')
}

/** The topics a filter matches, left alone when they are Zigbee2MQTT's own bridge topics. */
export function matchingTopics(filter: string, topics: EventTopic[]): EventTopic[] {
  return topics.filter((t) => topicMatches(filter, t.topic) && deviceOf(t.topic) !== 'bridge')
}

/** The topics that have [field] in their latest payload. */
export function hasField(topic: EventTopic, field: string): boolean {
  return topic.json && payloadFields(topic.lastPayload).some((f) => f.path === field)
}

/** [text] with every use of the device [from] replaced by [to]. */
export const swapDevice = (text: string, from: string, to: string) => (from && from !== to ? text.split(from).join(to) : text)

// ---- Zigbee2MQTT suggestions ----

/** A rule worth having for a Zigbee2MQTT device, as GET /api/rules/suggestions offers it. */
export interface Suggestion {
  /** The device's friendly name. */
  device: string
  topic: string
  name: string
  conditions: Condition[]
  trigger: 'changes to' | 'is'
  title: string
  message: string
  /** A rule already does this: same topic and first condition. */
  setUp: boolean
}

/** The rule builder's address for a draft; the builder reads these back. */
export function builderAddress(s: { topic: string; conditions: Condition[]; name?: string; title?: string; message?: string; trigger?: string }): string {
  const p = new URLSearchParams({ topic: s.topic })
  const c = s.conditions[0]
  if (c) {
    p.set('field', c.field)
    p.set('op', c.operator)
    if (c.value !== undefined) p.set('value', String(c.value))
  }
  if (s.name) p.set('name', s.name)
  if (s.title) p.set('title', s.title)
  if (s.message) p.set('message', s.message)
  if (s.trigger) p.set('trigger', s.trigger)
  return `/rules/new?${p}`
}

/** A condition from the builder's address: the value is read as the kind the field has. */
export function conditionFromAddress(field: string, op: string | null, value: string | null): Condition {
  const operator = (op && (['=', '≠', '>', '<', '≥', '≤', 'contains', 'exists'] as string[]).includes(op) ? op : '=') as Operator
  if (operator === 'exists' || value === null) return { field, operator }
  const parsed = value === 'true' ? true : value === 'false' ? false : value.trim() !== '' && Number.isFinite(Number(value)) ? Number(value) : value
  return { field, operator, value: parsed }
}
