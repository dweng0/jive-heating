import { expect, test, type Page } from '@playwright/test'

// The event explorer on the web app, against a mocked service.

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[] }
const sam = { id: 'u-sam', username: 'sam', admin: false, rooms: [] as string[] }
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

const ago = (minutes: number) => new Date(Date.now() - minutes * 60_000).toISOString()
const event = (id: number, topic: string, payload: unknown, minutesAgo: number, retained = false): E => ({
  id, at: ago(minutesAgo), topic, payload, json: typeof payload === 'object', retained, truncated: false,
})

/** The mocked service: /api/events filters, sorts and pages `all` the way the real one does (cursor = offset). */
async function serve(page: Page, me: typeof jane, all: E[]) {
  const calls: string[] = []
  await page.routeWebSocket('**/ws', () => {})
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url
    const q = url.searchParams
    if (pathname === '/api/auth/me') return route.fulfill({ json: me })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/rooms' || pathname === '/api/groups' || pathname === '/api/meters') return route.fulfill({ json: [] })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/me/webpush') return route.fulfill({ json: { webPush: false } })
    if (pathname.startsWith('/api/events') && !me.admin) return route.fulfill({ status: 403, json: { error: 'admins only' } })
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
      calls.push(url.search)
      let rows = [...all]
      const topic = q.get('topic')
      if (topic) {
        const re = new RegExp('^' + topic.replace(/\+/g, '[^/]+').replace(/#$/, '.*') + '$')
        rows = rows.filter((e) => re.test(e.topic))
      }
      const device = q.get('device')
      if (device) rows = rows.filter((e) => e.topic === `zigbee2mqtt/${device}` || e.topic.startsWith(`zigbee2mqtt/${device}/`))
      const field = q.get('field')
      const value = q.get('value')
      if (field) {
        const get = (p: unknown) => field.split('.').reduce<any>((o, k) => (o == null ? undefined : o[k]), p)
        rows = rows.filter((e) => e.json && get(e.payload) !== undefined && (value == null || String(get(e.payload)) === value))
      }
      if (q.get('from')) rows = rows.filter((e) => e.at >= q.get('from')!)
      if (q.get('retained')) rows = rows.filter((e) => e.retained === (q.get('retained') === 'true'))
      if (q.get('text')) rows = rows.filter((e) => (e.topic + JSON.stringify(e.payload)).toLowerCase().includes(q.get('text')!.toLowerCase()))
      const [by, dir] = (q.get('sort') ?? 'at desc').split(' ')
      rows.sort((a, b) => (by === 'topic' ? a.topic.localeCompare(b.topic) || a.id - b.id : a.id - b.id))
      if (dir !== 'asc') rows.reverse()
      const limit = Number(q.get('limit') ?? 100)
      const start = q.get('cursor') ? Number(q.get('cursor')) : 0
      const events = rows.slice(start, start + limit)
      const next = start + limit < rows.length ? String(start + limit) : null
      return route.fulfill({ json: { events, next, total: rows.length } })
    }
    if (pathname === '/api/rules/suggestions') return route.fulfill({ json: [] })
    return route.fulfill({ status: 404, json: { error: 'not mocked' } })
  })
  return { calls }
}

const door = (id: number, contact: boolean, minutesAgo: number) =>
  event(id, 'zigbee2mqtt/Front Door', { contact, battery: 91, update: { state: 'idle' } }, minutesAgo)

const house = () => [
  event(1, 'zigbee2mqtt/bridge/devices', [{ friendly_name: 'Front Door' }], 60 * 24 * 10, true),
  door(2, true, 60 * 30),
  door(3, false, 30),
  event(4, 'zigbee2mqtt/Hall Button', { action: 'single' }, 20),
  event(5, 'home/mode', 'away', 60 * 24 * 9, true),
  event(6, 'garage/temp', { temperature: 18.5 }, 10),
]

// BDD: An admin opens the event explorer
test('admins see the newest events, new ones arrive at the top until paused, and members do not see Events', async ({ page }) => {
  const all = house()
  const { calls } = await serve(page, jane, all)
  await page.goto('/')
  await page.getByRole('link', { name: 'Events' }).first().click()
  await expect(page).toHaveURL(/\/events/)
  await expect(page.getByRole('columnheader')).toHaveText(['Time', 'Topic', 'Payload'])
  const rows = page.getByTestId('event-row')
  await expect(rows).toHaveCount(6)
  await expect(rows.first()).toContainText('garage/temp')
  await expect(rows.first()).toContainText('{"temperature":18.5}')
  await expect(rows.last()).toContainText('zigbee2mqtt/bridge/devices')
  await expect(rows.last().getByText('retained')).toBeVisible()
  await expect(rows.first().getByText('retained')).toHaveCount(0)

  // A new event arrives at the top.
  all.push(event(7, 'garage/temp', { temperature: 19 }, 0))
  await expect(rows).toHaveCount(7, { timeout: 8000 })
  await expect(rows.first()).toContainText('{"temperature":19}')

  // Pause stops the updates; Resume brings the missed events in.
  await page.getByRole('button', { name: 'Pause' }).click()
  all.push(event(8, 'garage/temp', { temperature: 20 }, 0))
  const seen = calls.length
  await page.waitForTimeout(3500)
  expect(calls.length).toBe(seen)
  await expect(rows).toHaveCount(7)
  await page.getByRole('button', { name: 'Resume' }).click()
  await expect(rows).toHaveCount(8, { timeout: 8000 })
  await expect(rows.first()).toContainText('{"temperature":20}')
})

// BDD: An admin opens the event explorer
test('new events do not jump in while the admin has scrolled down', async ({ page }) => {
  const all = Array.from({ length: 60 }, (_, i) => event(i + 1, `bulk/${i}`, { n: i }, 60 - i))
  await serve(page, jane, all)
  await page.goto('/events')
  const rows = page.getByTestId('event-row')
  await expect(rows).toHaveCount(60)
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
  all.push(event(61, 'bulk/new', { n: 61 }, 0))
  await page.waitForTimeout(3500)
  await expect(rows).toHaveCount(60)
  await page.evaluate(() => window.scrollTo(0, 0))
  await expect(rows).toHaveCount(61, { timeout: 8000 })
})

// BDD: An admin opens the event explorer
test('members do not see Events', async ({ page }) => {
  await serve(page, sam, house())
  await page.goto('/')
  await expect(page.getByRole('link', { name: 'Events' })).toHaveCount(0)
  await page.goto('/events')
  await expect(page).toHaveURL(/\/$/)
})

// BDD: Events are filtered with chips
test('chips filter the events, are removed one by one or cleared, and live in the page address', async ({ page }) => {
  const { calls } = await serve(page, jane, house())
  await page.goto('/events')
  const rows = page.getByTestId('event-row')
  const search = page.getByRole('combobox', { name: 'Search events' })
  const chips = page.getByTestId('event-chips')

  await search.fill('front')
  await page.getByRole('option', { name: 'Topic: zigbee2mqtt/Front Door' }).click()
  await search.fill('contact')
  await page.getByRole('option', { name: 'Field: contact' }).click()
  await search.click()
  await page.getByRole('option', { name: 'Last 24 h' }).click()
  await expect(chips).toContainText('Topic: zigbee2mqtt/Front Door')
  await expect(chips).toContainText('Field: contact')
  await expect(chips).toContainText('Last 24 h')
  await expect(rows).toHaveCount(1)
  await expect(page.getByTestId('event-count')).toHaveText('1 event')
  expect(calls.at(-1)).toContain('topic=zigbee2mqtt%2FFront+Door')
  expect(calls.at(-1)).toContain('field=contact')
  expect(calls.at(-1)).toContain('from=')

  // The chips are in the address.
  await expect(page).toHaveURL(/topic=zigbee2mqtt%2FFront\+Door/)
  await expect(page).toHaveURL(/field=contact/)
  await expect(page).toHaveURL(/range=24h/)
  await page.reload()
  await expect(chips).toContainText('Field: contact')
  await expect(rows).toHaveCount(1)

  // Remove one, then clear.
  await page.getByRole('button', { name: 'Remove Last 24 h' }).click()
  await expect(chips).not.toContainText('Last 24 h')
  await expect(rows).toHaveCount(2)
  await expect(page.getByTestId('event-count')).toHaveText('2 events')
  await page.getByRole('button', { name: 'Clear' }).click()
  await expect(chips.getByTestId('event-chip')).toHaveCount(0)
  await expect(page.getByTestId('event-count')).toHaveText('6 events')

  // Device, Field value, Text and Retained chips, and wildcard topics.
  await search.fill('front')
  await page.getByRole('option', { name: 'Device: Front Door' }).click()
  await expect(rows).toHaveCount(2)
  await search.fill('contact = false')
  await page.getByRole('option', { name: 'contact = false', exact: true }).click()
  await expect(chips).toContainText('contact = false')
  await expect(rows).toHaveCount(1)
  await page.getByRole('button', { name: 'Clear' }).click()
  await search.fill('zigbee2mqtt/+')
  await page.getByRole('option', { name: 'Topic: zigbee2mqtt/+' }).click()
  await expect(rows).toHaveCount(3)
  await page.getByRole('button', { name: 'Clear' }).click()
  await search.click()
  await page.getByRole('option', { name: 'Retained', exact: true }).click()
  await expect(rows).toHaveCount(2)
  await page.getByRole('button', { name: 'Clear' }).click()
  await search.fill('away')
  await page.getByRole('option', { name: 'Text: away' }).click()
  await expect(rows).toHaveCount(1)
})

// BDD: Events are sorted and paged
test('columns sort the events and the next 100 load as the admin scrolls', async ({ page }) => {
  const all = Array.from({ length: 250 }, (_, i) => event(i + 1, `bulk/${String(i % 50).padStart(2, '0')}`, { n: i }, 300 - i))
  const { calls } = await serve(page, jane, all)
  await page.goto('/events')
  const rows = page.getByTestId('event-row')
  await expect(rows).toHaveCount(100)
  await expect(page.getByTestId('event-count')).toHaveText('250 events')
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
  await expect(rows).toHaveCount(200)
  await page.evaluate(() => window.scrollTo(0, document.body.scrollHeight))
  await expect(rows).toHaveCount(250)

  await page.getByRole('button', { name: 'Topic' }).click()
  await expect(page.getByRole('columnheader', { name: 'Topic' })).toHaveAttribute('aria-sort', 'ascending')
  await expect(rows).toHaveCount(100)
  await expect(rows.first()).toContainText('bulk/00')
  expect(calls.at(-1)).toContain('sort=topic+asc')
  await page.getByRole('button', { name: 'Topic' }).click()
  await expect(page.getByRole('columnheader', { name: 'Topic' })).toHaveAttribute('aria-sort', 'descending')
  await expect(rows.first()).toContainText('bulk/49')

  await page.getByRole('button', { name: 'Time' }).click()
  await expect(page.getByRole('columnheader', { name: 'Time' })).toHaveAttribute('aria-sort', 'descending')
  await expect(rows.first()).toContainText('{"n":249}')
  await page.getByRole('button', { name: 'Time' }).click()
  await expect(rows.first()).toContainText('{"n":0}')

  // Never more than a page at a time.
  expect(calls.every((c) => new URLSearchParams(c).get('limit') === '100')).toBe(true)
  expect(calls.some((c) => c.includes('cursor='))).toBe(true)
})

// BDD: An event's payload is shown in full
test('opening an event shows its whole payload with field paths and actions', async ({ page, context }) => {
  await context.grantPermissions(['clipboard-read', 'clipboard-write'])
  await serve(page, jane, house())
  await page.goto('/events?topic=zigbee2mqtt%2FFront+Door')
  const rows = page.getByTestId('event-row')
  await expect(rows).toHaveCount(2)
  await rows.first().click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('zigbee2mqtt/Front Door')
  await expect(dialog.getByTestId('payload-json')).toContainText('"battery": 91')
  await expect(dialog.getByTestId('payload-field')).toHaveCount(3)
  await expect(dialog.getByTestId('payload-field').nth(0)).toContainText('contact')
  await expect(dialog.getByTestId('payload-field').nth(2)).toContainText('update.state')

  await dialog.getByRole('button', { name: 'Copy' }).click()
  expect(JSON.parse(await page.evaluate(() => navigator.clipboard.readText()))).toEqual({ contact: false, battery: 91, update: { state: 'idle' } })

  // Notify me when… opens the rule builder with the topic and field.
  await dialog.getByTestId('payload-field').nth(2).getByRole('button', { name: 'Notify me when…' }).click()
  await expect(page).toHaveURL(/\/rules\/new\?topic=zigbee2mqtt%2FFront\+Door&field=update\.state&value=idle/)
  await expect(page.getByLabel('Topic', { exact: true })).toHaveValue('zigbee2mqtt/Front Door')
  await expect(page.getByTestId('condition').first().getByLabel('Field')).toHaveValue('update.state')

  // Filter by this adds a Field value chip; Show this topic filters to the topic.
  await page.goto('/events')
  await expect(rows).toHaveCount(6)
  await page.getByTestId('event-row').filter({ hasText: 'Front Door' }).first().click()
  await page.getByTestId('payload-field').nth(0).getByRole('button', { name: 'Filter by this' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await expect(page.getByTestId('event-chips')).toContainText('contact = false')
  await expect(rows).toHaveCount(1)
  await page.getByRole('button', { name: 'Clear' }).click()
  await rows.filter({ hasText: 'Hall Button' }).click()
  await page.getByRole('button', { name: 'Show this topic' }).click()
  await expect(page.getByTestId('event-chips')).toContainText('Topic: zigbee2mqtt/Hall Button')
  await expect(rows).toHaveCount(1)
})

// BDD: Topics are browsed as a tree
test('topics are browsed as a tree and choosing one filters the table', async ({ page }) => {
  await serve(page, jane, house())
  await page.goto('/events')
  await page.getByRole('tab', { name: 'Topics' }).click()
  const tree = page.getByTestId('topic-tree')
  await expect(tree.getByTestId('topic-node')).toHaveCount(3) // zigbee2mqtt, home, garage
  const z2m = tree.getByTestId('topic-node').filter({ hasText: 'zigbee2mqtt' }).first()
  await expect(z2m).toContainText('4 messages')
  await z2m.getByRole('button', { name: 'Expand zigbee2mqtt' }).click()
  const front = tree.getByTestId('topic-node').filter({ hasText: 'Front Door' })
  await expect(front).toContainText('2 messages')
  await expect(front).toContainText('"contact":false')
  await expect(front).toContainText('ago')
  await expect(front).not.toHaveAttribute('data-stale', 'true')
  // A topic with no message for 7 days is greyed.
  await expect(tree.getByTestId('topic-node').filter({ hasText: 'home' })).toHaveAttribute('data-stale', 'true')
  await expect(tree.getByTestId('topic-node').filter({ hasText: 'garage' })).not.toHaveAttribute('data-stale', 'true')

  await front.getByRole('button', { name: 'Show zigbee2mqtt/Front Door' }).click()
  await expect(page.getByTestId('event-chips')).toContainText('Topic: zigbee2mqtt/Front Door')
  await expect(page.getByTestId('event-row')).toHaveCount(2)
})
