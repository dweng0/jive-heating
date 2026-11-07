import { expect, test, type Page } from '@playwright/test'

// The rule builder and the rules list on the web app, against a mocked service.

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[] }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

interface E {
  id: number
  at: string
  topic: string
  payload: unknown
  json: boolean
  retained: boolean
  truncated: boolean
}
interface R {
  id: string
  name: string
  topic: string
  conditions: { field: string; operator: string; value?: unknown }[]
  trigger: string
  title: string
  message: string
  notify: string
  cooldownSeconds: number
  quietHours: null
  enabled: boolean
  by: string
  byUserId: string | null
  createdAt: string
  pausedUntil: string | null
  pausedUntilLocal: string | null
  lastSentAt?: string | null
}

const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString()
const event = (id: number, topic: string, payload: unknown, minutesAgo: number, retained = false): E => ({
  id, at: ago(minutesAgo), topic, payload, json: typeof payload === 'object', retained, truncated: false,
})
const rule = (id: string, name: string, topic: string, extra: Partial<R> = {}): R => ({
  id, name, topic, conditions: [], trigger: 'changes to', title: name, message: name, notify: 'me', cooldownSeconds: 0, quietHours: null,
  enabled: true, by: 'jane', byUserId: 'u-jane', createdAt: ago(1000), pausedUntil: null, pausedUntilLocal: null, ...extra,
})

const get = (payload: unknown, path: string) => path.split('.').reduce<any>((o, k) => (o == null ? undefined : o[k]), payload)

/** Whether a payload meets a condition, the way the service reads it. */
function holds(payload: unknown, c: { field: string; operator: string; value?: unknown }): boolean {
  const v = get(payload, c.field)
  if (c.operator === 'exists') return v !== undefined
  if (v === undefined) return false
  switch (c.operator) {
    case '=': return v === c.value
    case '≠': return v !== c.value
    case '>': return v > (c.value as number)
    case '<': return v < (c.value as number)
    case '≥': return v >= (c.value as number)
    case '≤': return v <= (c.value as number)
    case 'contains': return String(v).includes(String(c.value))
  }
  return false
}

const matches = (filter: string, topic: string) => new RegExp('^' + filter.replace(/\+/g, '[^/]+').replace(/#$/, '.*') + '$').test(topic)

interface Mock {
  rules: R[]
  calls: { method: string; path: string; body?: any }[]
}

/** The mocked service: events, rules, the rule test preview and deliveries. */
async function serve(page: Page, all: E[], rules: R[] = [], history: Record<string, unknown[]> = {}, areas: { id: string; name: string; alert?: unknown }[] = []): Promise<Mock> {
  const mock: Mock = { rules, calls: [] }
  await page.routeWebSocket('**/ws', () => {})
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url
    const q = url.searchParams
    const method = request.method()
    const body = request.postData() ? JSON.parse(request.postData()!) : undefined
    if (pathname === '/api/auth/me') return route.fulfill({ json: jane })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/rooms' || pathname === '/api/groups' || pathname === '/api/meters') return route.fulfill({ json: [] })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/me/webpush') return route.fulfill({ json: { webPush: false } })
    if (pathname === '/api/events/topics') {
      const byTopic = new Map<string, E[]>()
      all.forEach((e) => byTopic.set(e.topic, [...(byTopic.get(e.topic) ?? []), e]))
      return route.fulfill({
        json: [...byTopic.entries()].sort().map(([topic, list]) => {
          const last = list.reduce((a, b) => (a.id > b.id ? a : b))
          return { topic, count: list.length, lastSeen: last.at, lastPayload: last.payload, json: last.json }
        }),
      })
    }
    if (pathname === '/api/events') {
      let rows = [...all]
      if (q.get('topic')) rows = rows.filter((e) => matches(q.get('topic')!, e.topic))
      const device = q.get('device')
      if (device) rows = rows.filter((e) => e.topic === `zigbee2mqtt/${device}` || e.topic.startsWith(`zigbee2mqtt/${device}/`))
      rows.sort((a, b) => b.id - a.id)
      const limit = Number(q.get('limit') ?? 100)
      return route.fulfill({ json: { events: rows.slice(0, limit), next: null, total: rows.length } })
    }
    if (pathname === '/api/energy/areas' && method === 'GET') return route.fulfill({ json: areas.map((a) => ({ id: a.id, name: a.name, meters: [], rooms: [], shares: [] })) })
    const alert = pathname.match(/^\/api\/energy\/areas\/([^/]+)\/alert$/)
    if (alert) return route.fulfill({ json: areas.find((a) => a.id === decodeURIComponent(alert[1]))?.alert ?? { hours: null, notify: null, by: null } })
    if (pathname === '/api/rules/test') {
      mock.calls.push({ method, path: pathname, body })
      const recent = all.filter((e) => matches(body.topic, e.topic)).sort((a, b) => b.id - a.id).slice(0, 6)
      let previous: unknown = undefined
      const events = [...recent].reverse().map((e) => {
        const met = body.conditions.every((c: any) => holds(e.payload, c))
        const status = !met ? 'not_met' : previous === true ? 'no_change' : 'would_send'
        previous = met
        return { id: e.id, at: e.at, topic: e.topic, payload: JSON.stringify(e.payload), status, result: status, title: body.title, message: body.message }
      })
      return route.fulfill({ json: { events: events.reverse() } })
    }
    if (pathname === '/api/rules/test/send') {
      mock.calls.push({ method, path: pathname, body })
      return route.fulfill({ json: { title: body.title, message: body.message, deliveryIds: ['d1'] } })
    }
    if (pathname === '/api/rules' && method === 'GET') return route.fulfill({ json: mock.rules })
    if (pathname === '/api/rules' && method === 'POST') {
      mock.calls.push({ method, path: pathname, body })
      const made = { ...rule(`r${mock.rules.length + 1}`, body.name, body.topic), ...body }
      mock.rules.push(made)
      return route.fulfill({ status: 201, json: made })
    }
    const one = pathname.match(/^\/api\/rules\/([^/]+)(\/history|\/resume)?$/)
    if (one) {
      const found = mock.rules.find((r) => r.id === one[1])
      if (!found) return route.fulfill({ status: 404, json: { error: 'no such rule' } })
      if (one[2] === '/history') return route.fulfill({ json: history[found.id] ?? [] })
      if (one[2] === '/resume') {
        mock.calls.push({ method, path: pathname })
        Object.assign(found, { pausedUntil: null, pausedUntilLocal: null })
        return route.fulfill({ json: found })
      }
      if (method === 'GET') return route.fulfill({ json: found })
      mock.calls.push({ method, path: pathname, body })
      if (method === 'PUT') return route.fulfill({ json: Object.assign(found, body) })
      if (method === 'DELETE') {
        mock.rules.splice(mock.rules.indexOf(found), 1)
        return route.fulfill({ status: 204 })
      }
    }
    return route.fulfill({ status: 404, json: { error: 'not mocked' } })
  })
  return mock
}

const door = (id: number, contact: boolean, minutesAgo: number) => event(id, 'z2m/front_door', { contact, battery: 91, linkquality: 120 }, minutesAgo)

const house = () => [
  door(1, true, 90),
  door(2, false, 80),
  door(3, true, 70),
  door(4, false, 60),
  door(5, false, 50),
  door(6, true, 40),
  door(7, false, 30),
  event(8, 'z2m/hall_button', { action: 'single', battery: 50 }, 20),
  event(9, 'z2m/hall_leak', { water_leak: false, battery: 80 }, 15),
  event(10, 'z2m/back_door', { contact: true, battery: 70 }, 10),
  event(11, 'z2m/bridge/state', 'online', 5, true),
]

// BDD: A rule is built from an event
test('"Notify me when…" on an event field opens the builder with the condition, texts and the last 5 events marked', async ({ page }) => {
  await serve(page, house())
  await page.goto('/events?topic=z2m/front_door')
  await page.getByTestId('event-row').first().click()
  await page.getByTestId('payload-field').filter({ hasText: 'contact' }).getByRole('button', { name: 'Notify me when…' }).click()

  await expect(page).toHaveURL(/\/rules\/new/)
  await expect(page.getByLabel('Topic', { exact: true })).toHaveValue('z2m/front_door')
  const condition = page.getByTestId('condition').first()
  await expect(condition.getByLabel('Field')).toHaveValue('contact')
  await expect(condition.getByLabel('Operator')).toHaveValue('is false')
  await expect(page.getByLabel('Trigger')).toHaveValue('changes to')
  await expect(page.getByLabel('Name')).toHaveValue('Front door contact false')
  await expect(page.getByLabel('Title')).toHaveValue('front_door')
  await expect(page.getByLabel('Message')).toHaveValue('front_door: contact is false')

  // All three are editable.
  await page.getByLabel('Name').fill('Door open')
  await expect(page.getByLabel('Name')).toHaveValue('Door open')

  // The last 5 events on the topic, newest first, each marked whether it would have sent.
  const rows = page.getByTestId('test-event')
  await expect(rows).toHaveCount(5)
  await expect(rows.first()).toContainText('Would send')
  await expect(rows.nth(1)).toContainText('Conditions not met')
  await expect(rows.nth(2)).toContainText('No change')
  await expect(rows.nth(3)).toContainText('Would send')
  await expect(rows.nth(4)).toContainText('Conditions not met')
})

// BDD: Conditions are mapped from the payload's fields
test('a condition picks a field seen on the topic and gets operators and values that fit it', async ({ page }) => {
  const all = [...house(), event(12, 'z2m/hall_button', { action: 'double' }, 19), event(13, 'z2m/hall_button', { action: 'hold' }, 18), event(14, 'z2m/hall_button', { action: 'single' }, 17)]
  await serve(page, all)
  await page.goto('/rules/new?topic=z2m/front_door')
  await page.getByRole('button', { name: 'Add a condition' }).click()
  const condition = page.getByTestId('condition').first()

  // The fields seen on the topic, each showing its recent values.
  const field = condition.getByLabel('Field')
  await expect(field.locator('option')).toHaveText([/^contact/, /^battery/, /^linkquality/])
  await expect(field.locator('option', { hasText: 'contact' })).toHaveText(/false, true|true, false/)
  await expect(field.locator('option', { hasText: 'battery' })).toContainText('91')

  // True/false fields offer "is true"/"is false"; numbers get the comparisons; every field gets "exists".
  await field.selectOption('contact')
  await expect(condition.getByLabel('Operator').locator('option')).toHaveText(['is true', 'is false', 'exists'])
  await field.selectOption('battery')
  await expect(condition.getByLabel('Operator').locator('option')).toHaveText(['=', '≠', '>', '<', '≥', '≤', 'exists'])
  await condition.getByLabel('Operator').selectOption('<')
  await condition.getByLabel('Value').fill('15')
  await expect(page.getByLabel('Message')).toHaveValue('front_door: battery < 15')

  // Text fields get = ≠ contains, and the value offers the values seen or can be typed.
  await page.goto('/rules/new?topic=z2m/hall_button')
  await page.getByRole('button', { name: 'Add a condition' }).click()
  const action = page.getByTestId('condition').first()
  await action.getByLabel('Field').selectOption('action')
  await expect(action.getByLabel('Operator').locator('option')).toHaveText(['=', '≠', 'contains', 'exists'])
  expect(await page.locator('datalist option').evaluateAll((os) => os.map((o) => (o as HTMLOptionElement).value))).toEqual(['single', 'hold', 'double'])
  await action.getByLabel('Operator').selectOption('=')
  await action.getByLabel('Value').fill('hold')
  await expect(action.getByLabel('Value')).toHaveValue('hold')
  await action.getByLabel('Value').fill('triple')
  await expect(page.getByTestId('test-event').first()).toContainText('Conditions not met')
})
