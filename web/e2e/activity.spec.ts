import { expect, test, type Page, type WebSocketRoute } from '@playwright/test'

// The activity tray, against a mocked service: every /api call and /ws is answered here, so these
// tests do not depend on the real service or on the order of heating.spec.ts.

interface MockEvent {
  id: number
  at: string
  kind: string
  actor: string | null
  room: string | null
  value: number | string | null
  reason: string | null
  rooms: string[]
  count: number | null
  device: string | null
}

const ago = (ms: number) => new Date(Date.now() - ms).toISOString()
const MINUTE = 60_000

const event = (id: number, overrides: Partial<MockEvent>): MockEvent => ({
  id,
  at: ago(id * MINUTE),
  kind: 'setpoint',
  actor: null,
  room: null,
  value: null,
  reason: null,
  rooms: [],
  count: null,
  device: null,
  ...overrides,
})

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[] }
const STATUS = { heatDemand: true, boiler: { state: 'heating', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

/** Answers the app's calls; `feed` decides what GET /api/activity returns. */
async function serve(page: Page, feed: (url: URL) => { status?: number; events?: MockEvent[] }) {
  const sockets: WebSocketRoute[] = []
  const calls: string[] = []
  await page.routeWebSocket('**/ws', (ws) => {
    sockets.push(ws)
  })
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const { pathname } = url
    if (pathname === '/api/auth/me') return route.fulfill({ json: jane })
    if (pathname === '/api/rooms' || pathname === '/api/groups' || pathname === '/api/valves' || pathname === '/api/sensors') return route.fulfill({ json: [] })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/activity') {
      calls.push(url.search)
      const answer = feed(url)
      if (answer.status) return route.fulfill({ status: answer.status, json: { error: 'down' } })
      return route.fulfill({ json: { events: answer.events ?? [] } })
    }
    return route.fulfill({ status: 404, json: { error: `not mocked: ${pathname}` } })
  })
  return { calls, live: (e: object) => sockets.forEach((ws) => ws.send(JSON.stringify(e))) }
}

const SETPOINT = { kind: 'setpoint', actor: 'John', room: 'Utility Room', value: 21 }

// BDD: The boiler chip opens the activity tray on the web app
test('the boiler chip opens a tray with the boiler state and the newest events', async ({ page }) => {
  // 50 on the first page, 3 more behind "Load older"; ids run newest (highest) first.
  const all = Array.from({ length: 53 }, (_, i) => event(53 - i, { ...SETPOINT, at: ago((i + 1) * MINUTE) }))
  const { calls } = await serve(page, (url) => {
    const before = Number(url.searchParams.get('before') ?? Infinity)
    return { events: all.filter((e) => e.id < before).slice(0, Number(url.searchParams.get('limit'))) }
  })
  await page.goto('/')
  const chip = page.getByTestId('boiler-chip')
  await expect(chip).toBeVisible()

  await chip.click()
  const tray = page.getByRole('dialog')
  // The boiler's state and what it means come first, then the events.
  await expect(tray).toContainText('Boiler heating')
  await expect(tray).toContainText('A room needs heat, so the boiler is firing.')
  await expect(page.getByTestId('activity-event')).toHaveCount(50)

  await page.getByRole('button', { name: 'Load older' }).click()
  await expect(page.getByTestId('activity-event')).toHaveCount(53)
  await expect(page.getByRole('button', { name: 'Load older' })).toHaveCount(0)
  expect(calls).toEqual(['?limit=50', '?limit=50&before=4'])

  await page.keyboard.press('Escape')
  await expect(tray).toHaveCount(0)
  await chip.click()
  await expect(tray).toBeVisible()
  await page.mouse.click(10, 10) // outside the tray
  await expect(tray).toHaveCount(0)
})

// BDD: Activity lines are worded in full on wide screens and briefly on phones
test('lines are a sentence on wide screens and a few words on a phone', async ({ page }) => {
  const events = [
    event(4, { kind: 'boiler', value: 'cycling', reason: 'reached-temp', rooms: ['Utility Room'] }),
    event(3, { kind: 'boiler', value: 'heating', reason: 'calling', rooms: ['Utility Room'] }),
    event(2, { kind: 'boiler', value: 'idle', reason: 'no-demand' }),
    event(1, SETPOINT),
  ]
  await serve(page, () => ({ events }))
  const lines = (kind: 'wide' | 'narrow') => page.getByTestId(`activity-${kind}`).allTextContents()

  await page.setViewportSize({ width: 1024, height: 800 })
  await page.goto('/')
  await page.getByTestId('boiler-chip').click()
  await expect(page.getByTestId('activity-event')).toHaveCount(4)
  await expect(page.getByTestId('activity-wide').first()).toBeVisible()
  await expect(page.getByTestId('activity-narrow').first()).toBeHidden()
  expect(await lines('wide')).toEqual([
    'Utility Room has reached temperature, boiler cycling down',
    'Boiler is now heating',
    'Boiler is now idle',
    'John turned the Utility Room thermostat to 21°',
  ])

  await page.setViewportSize({ width: 360, height: 700 })
  await expect(page.getByTestId('activity-narrow').first()).toBeVisible()
  await expect(page.getByTestId('activity-wide').first()).toBeHidden()
  expect(await lines('narrow')).toEqual(['Utility at temp', 'Boiler heating', 'Boiler idle', 'John · Utility 21°'])
  // Each line stays on one row.
  for (const row of await page.getByTestId('activity-event').all()) {
    expect((await row.boundingBox())!.height).toBeLessThan(40)
  }
  // Screen readers get the full sentence and the time.
  await expect(page.getByTestId('activity-event').last()).toHaveAttribute('aria-label', /^John turned the Utility Room thermostat to 21°, \d+m ago$/)
})

// BDD: Long room names are shortened to fit on phones
test('a long room name is shortened on a phone and keeps its full name', async ({ page }) => {
  await serve(page, () => ({
    events: [event(1, { kind: 'setpoint', actor: 'John', room: 'Master Bedroom Ensuite Dressing Area', value: 21 })],
  }))
  await page.setViewportSize({ width: 360, height: 700 })
  await page.goto('/')
  await page.getByTestId('boiler-chip').click()

  const row = page.getByTestId('activity-event')
  await expect(row).toHaveAttribute('title', 'Master Bedroom Ensuite Dressing Area')
  expect((await row.boundingBox())!.height).toBeLessThan(40)
  // The text is cut with an ellipsis, not wrapped.
  const clipped = await page.getByTestId('activity-narrow').evaluate((el) => el.parentElement!.scrollWidth > el.parentElement!.clientWidth)
  expect(clipped).toBe(true)
})

// BDD: The tray updates live while open
test('a new event appears at the top while the tray is open, with relative times', async ({ page }) => {
  const { live } = await serve(page, () => ({
    events: [event(3, { ...SETPOINT, at: ago(5 * MINUTE + 5000) }), event(2, { ...SETPOINT, at: ago(2 * 3600_000 + 5000) }), event(1, { ...SETPOINT, at: ago(30 * 3600_000) })],
  }))
  await page.goto('/')
  await page.getByTestId('boiler-chip').click()
  const rows = page.getByTestId('activity-event')
  await expect(rows).toHaveCount(3)
  await expect(rows.nth(0)).toContainText('5m ago')
  await expect(rows.nth(1)).toContainText('2h ago')
  await expect(rows.nth(2)).toContainText('yesterday')

  live({ type: 'activity.added', activity: event(4, { kind: 'knob', room: 'Lounge', value: 22, at: new Date().toISOString() }) })
  await expect(rows).toHaveCount(4)
  await expect(rows.first()).toContainText('The Lounge thermostat was turned to 22°')
  await expect(rows.first()).toContainText('now')
})

// BDD: A plug losing contact is in the activity feed
test('a plug going offline and coming back is worded with its name and room', async ({ page }) => {
  await serve(page, () => ({
    events: [
      event(3, { kind: 'offline', room: null, device: 'Kettle Plug', value: 'offline' }),
      event(2, { kind: 'offline', room: "Bedroom 1", device: 'TV Plug', value: 'online' }),
      event(1, { kind: 'offline', room: "Bedroom 1", device: 'TV Plug', value: 'offline' }),
    ],
  }))
  const lines = (kind: 'wide' | 'narrow') => page.getByTestId(`activity-${kind}`).allTextContents()
  await page.setViewportSize({ width: 1024, height: 800 })
  await page.goto('/')
  await page.getByTestId('boiler-chip').click()
  await expect(page.getByTestId('activity-event')).toHaveCount(3)
  expect(await lines('wide')).toEqual(['Kettle Plug went offline', "TV Plug in Bedroom 1 is back online", "TV Plug in Bedroom 1 went offline"])
  await expect(page.getByTestId('activity-event').last()).toHaveAttribute('title', 'TV Plug')

  await page.setViewportSize({ width: 360, height: 700 })
  await expect(page.getByTestId('activity-narrow').first()).toBeVisible()
  expect(await lines('narrow')).toEqual(['Kettle Plug offline', 'TV Plug online', 'TV Plug offline'])
})

// BDD: An empty feed says so
test('an empty feed says nothing has changed yet', async ({ page }) => {
  await serve(page, () => ({ events: [] }))
  await page.goto('/')
  await page.getByTestId('boiler-chip').click()
  const tray = page.getByRole('dialog')
  await expect(tray).toContainText('Nothing has changed yet')
  await expect(tray).not.toContainText('Loading…')
  await expect(page.getByTestId('activity-event')).toHaveCount(0)
})

// BDD: The feed unavailable does not hide the boiler state
test('a failing feed keeps the boiler state and offers a retry', async ({ page }) => {
  let down = true
  await serve(page, () => (down ? { status: 500 } : { events: [event(1, SETPOINT)] }))
  await page.goto('/')
  await page.getByTestId('boiler-chip').click()

  const tray = page.getByRole('dialog')
  await expect(tray).toContainText('A room needs heat, so the boiler is firing.')
  await expect(tray).toContainText("Couldn't load activity")

  down = false
  await page.getByRole('button', { name: 'Retry' }).click()
  await expect(page.getByTestId('activity-event')).toHaveCount(1)
  await expect(tray).not.toContainText("Couldn't load activity")
})
