import { expect, test, type Page, type WebSocketRoute } from '@playwright/test'

// A room's and everything else's energy dashboard, against a mocked service: every /api call, /ws and /ws/power are
// answered here, so these tests do not depend on the real service.

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[] }
const sam = { id: 'u-sam', username: 'sam', admin: false, rooms: ['kitchen'] }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }
const KITCHEN = { id: 'kitchen', name: 'Kitchen', control: 'grouped', valves: [], zones: [], meters: [], sensor: null, temperatureSource: 'valves' }

function plug(meter: string, power: number | null, kwh: number, cost: number, more: object = {}) {
  return { meter, power, kwh, cost, missingMinutes: 0, incomplete: false, offline: [], offlineSince: null, totalStuckSince: null, ...more }
}

const fridge = plug('Fridge Plug', 40, 1.2, 30)
const kettle = plug('Kettle Plug', 2, 0.4, 10)
const twoPlugs = { room: 'kitchen', currency: 'GBP', kwh: 1.6, cost: 40, missingMinutes: 0, incomplete: false, plugs: [fridge, kettle] }

function live(rest: { kitchen?: number | null; elseWatts?: number | null; watts?: number | null } = {}) {
  return {
    watts: rest.watts === undefined ? 855 : rest.watts,
    at: new Date().toISOString(),
    rooms: [{ room: 'kitchen', name: 'Kitchen', watts: rest.kitchen === undefined ? 42 : rest.kitchen }],
    devices: [],
    elseWatts: rest.elseWatts === undefined ? 666 : rest.elseWatts,
  }
}

const dashboard = {
  period: 'today', from: new Date().toISOString(), to: new Date().toISOString(), boilerHours: 3, gasKwh: 36, gasCost: 396,
  electricityMeter: 'Main Clamp', electricityKwh: 8, electricityCost: 216, gasStandingCost: 30, electricityStandingCost: 50, standingCost: 80,
  totalCost: 692, missingMinutes: 0, incomplete: false, days: [], currency: 'GBP', devices: [],
  rooms: [{ room: 'kitchen', name: 'Kitchen', kwh: 1.6, cost: 40, missingMinutes: 0, incomplete: false }],
  everythingElse: { kwh: 5.6, cost: 151.2, missingMinutes: 0, incomplete: false },
}

const STEPS: Record<string, number> = { '24h': 5 * 60_000, '7d': 3600_000, '30d': 86_400_000 }
const LENGTHS: Record<string, number> = { '24h': 86_400_000, '7d': 7 * 86_400_000, '30d': 30 * 86_400_000 }

type Point = { at: string; watts: number | null; kwh: number | null; cost: number | null; incomplete: boolean }

/** A steady [watts] from [earliest] on, as the service would answer; [gaps] are spans with no reading (as "no point" or a null). */
function usage(url: URL, opts: { earliest: number; meters: string[]; hide?: (meter: string | null, t: number) => boolean | 'null' }) {
  const window = url.searchParams.get('window') ?? '24h'
  const to = Math.min(Date.parse(url.searchParams.get('to') ?? new Date().toISOString()), Date.now())
  const from = to - LENGTHS[window]
  const step = STEPS[window]
  const points = (meter: string | null, watts: number) => {
    const out: Point[] = []
    for (let t = to - ((to - from) % step) ; t > Math.max(from, opts.earliest); t -= step) {
      const hide = opts.hide?.(meter, t)
      if (hide === true) continue
      if (hide === 'null') out.unshift({ at: new Date(t).toISOString(), watts: null, kwh: null, cost: null, incomplete: true })
      else out.unshift({ at: new Date(t).toISOString(), watts, kwh: (watts * step) / 3_600_000_000, cost: (watts * step * 25) / 3_600_000_000, incomplete: false })
    }
    return out
  }
  return {
    currency: 'GBP', from: new Date(from).toISOString(), to: new Date(to).toISOString(), earliest: new Date(opts.earliest).toISOString(),
    room: points(null, 100),
    plugs: opts.meters.map((meter, i) => ({ meter, points: points(meter, i === 0 ? 80 : 20) })),
  }
}

async function serve(page: Page, opts: { plugs?: object | number; me?: typeof jane; first?: object; earliestDays?: number; hide?: (meter: string | null, t: number) => boolean | 'null'; meters?: string[] } = {}) {
  const sockets: WebSocketRoute[] = []
  const earliest = Date.now() - (opts.earliestDays ?? 3) * 86_400_000
  const asked: string[] = []
  await page.routeWebSocket('**/ws', () => {})
  await page.routeWebSocket('**/ws/power', (ws) => {
    sockets.push(ws)
    ws.send(JSON.stringify(opts.first ?? live()))
  })
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const { pathname } = url
    if (pathname === '/api/auth/me') return route.fulfill({ json: opts.me ?? jane })
    if (pathname === '/api/rooms') return route.fulfill({ json: [KITCHEN] })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/groups') return route.fulfill({ json: [] })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/dashboard') return route.fulfill({ json: dashboard })
    if (pathname === '/api/power') return route.fulfill({ json: { bands: null, recent: [] } })
    if (pathname === '/api/rooms/kitchen') return route.fulfill({ json: KITCHEN })
    if (pathname === '/api/rooms/kitchen/plugs') {
      if (opts.plugs === 404) return route.fulfill({ status: 404, json: { error: 'room not found' } })
      return route.fulfill({ json: opts.plugs ?? twoPlugs })
    }
    if (pathname === '/api/rooms/kitchen/plugs/usage') {
      asked.push(url.search)
      return route.fulfill({ json: usage(url, { earliest, meters: opts.meters ?? ['Fridge Plug', 'Kettle Plug'], hide: opts.hide }) })
    }
    if (pathname === '/api/energy/else/usage') {
      const { plugs: _plugs, ...rest } = usage(url, { earliest, meters: [], hide: opts.hide })
      return route.fulfill({ json: rest })
    }
    if (pathname === '/api/rooms/gone' || pathname.startsWith('/api/rooms/gone/')) return route.fulfill({ status: 404, json: { error: 'room not found' } })
    return route.fulfill({ status: 404, json: { error: 'not mocked' } })
  })
  return { push: (msg: object) => sockets.at(-1)!.send(JSON.stringify(msg)), asked }
}

const chart = (page: Page) => page.getByTestId('usage-chart')
const shownTo = async (page: Page) => Number(await chart(page).getAttribute('data-to'))

async function drag(page: Page, dx: number) {
  const box = (await chart(page).boundingBox())!
  const y = box.y + box.height / 2
  await page.mouse.move(box.x + box.width / 2, y)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width / 2 + dx / 2, y, { steps: 4 })
  await page.mouse.move(box.x + box.width / 2 + dx, y, { steps: 4 })
  await page.mouse.up()
}

// BDD: A room card opens the room's energy dashboard
test('a room card opens its dashboard, and back finds the Energy tab where it was left', async ({ page }) => {
  await serve(page)
  await page.setViewportSize({ width: 1000, height: 500 })
  await page.goto('/energy')
  const card = page.getByRole('link', { name: 'Kitchen energy' })
  await page.getByRole('button', { name: 'Week' }).click()
  await card.scrollIntoViewIfNeeded()
  await card.click()
  await expect(page).toHaveURL(/\/energy\/rooms\/kitchen$/)
  await expect(page.getByRole('heading', { name: 'Kitchen' })).toBeVisible()
  await page.getByRole('button', { name: 'Energy', exact: true }).click()
  await expect(page).toHaveURL(/\/energy$/)
  await expect(page.getByRole('button', { name: 'Week' })).toHaveAttribute('aria-pressed', 'true')

  // The address can be reloaded, and back still goes to the Energy tab.
  await page.goto('/energy')
  await card.scrollIntoViewIfNeeded()
  await page.getByRole('button', { name: 'Week' }).click()
  const before = await page.evaluate(() => window.scrollY)
  await card.click()
  await expect(page.getByRole('heading', { name: 'Kitchen' })).toBeVisible()
  await page.getByRole('button', { name: 'Energy', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Week' })).toHaveAttribute('aria-pressed', 'true')
  await expect.poll(() => page.evaluate(() => window.scrollY)).toBe(before)

  await card.click()
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Kitchen' })).toBeVisible()
  await page.getByRole('button', { name: 'Energy', exact: true }).click()
  await expect(page).toHaveURL(/\/energy$/)
})

// BDD: A room card opens the room's energy dashboard
test('a member sent to a room energy address goes to the Rooms screen', async ({ page }) => {
  await serve(page, { me: sam })
  await page.goto('/energy/rooms/kitchen')
  await expect(page).toHaveURL(/\/$/)
})

// BDD: The room dashboard leads with what it is using and costing
test('cards show now, today and cost today, and now follows the live readings', async ({ page }) => {
  const { push } = await serve(page)
  await page.goto('/energy/rooms/kitchen')
  await expect(page.getByTestId('usage-now')).toContainText('42 W')
  await expect(page.getByTestId('usage-today')).toContainText('1.6 kWh')
  await expect(page.getByTestId('usage-cost')).toContainText('£0.40')
  await expect(page.getByTestId('incomplete-warning')).toHaveCount(0)
  push(live({ kitchen: 130 }))
  await expect(page.getByTestId('usage-now')).toContainText('130 W')
  push(live({ kitchen: null }))
  await expect(page.getByTestId('usage-now')).toContainText('No reading')
})

// BDD: The room dashboard leads with what it is using and costing
test('incomplete figures show so far with the triangle', async ({ page }) => {
  await serve(page, { plugs: { ...twoPlugs, incomplete: true, missingMinutes: 40, plugs: [plug('Fridge Plug', 40, 1.2, 30, { incomplete: true, missingMinutes: 40, offline: [{ from: '2026-10-12T14:20:00+01:00', to: '2026-10-12T15:00:00+01:00' }] }), kettle] } })
  await page.goto('/energy/rooms/kitchen')
  await expect(page.getByTestId('usage-cost').getByTestId('incomplete-warning')).toBeVisible()
  await expect(page.getByTestId('usage-cost')).toContainText('£0.40')
})

// BDD: The room dashboard charts each plug in a carousel
test('the carousel shows the room then each plug, with one choice of what to chart', async ({ page }) => {
  await serve(page)
  await page.goto('/energy/rooms/kitchen')
  const title = page.getByTestId('chart-title')
  await expect(title).toHaveText('Kitchen')
  await expect(page.getByTestId('chart-dots').getByRole('button')).toHaveCount(3)
  await page.getByRole('button', { name: 'Use', exact: true }).click()
  await page.getByRole('button', { name: 'Next chart' }).click()
  await expect(title).toHaveText('Fridge Plug')
  await expect(page.getByRole('button', { name: 'Use', exact: true })).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: 'Show Kettle Plug' }).click()
  await expect(title).toHaveText('Kettle Plug')
  await expect(page.getByRole('button', { name: 'Next chart' })).toBeDisabled()
  // A swipe on the heading moves between charts.
  const box = (await page.getByTestId('chart-title').boundingBox())!
  await page.mouse.move(box.x + 20, box.y + 5)
  await page.mouse.down()
  await page.mouse.move(box.x + 150, box.y + 5, { steps: 4 })
  await page.mouse.up()
  await expect(title).toHaveText('Fridge Plug')
})

// BDD: The room dashboard charts each plug in a carousel
test('a room with one plug has its chart alone', async ({ page }) => {
  await serve(page, { plugs: { ...twoPlugs, kwh: 1.2, cost: 30, plugs: [fridge] }, meters: ['Fridge Plug'] })
  await page.goto('/energy/rooms/kitchen')
  await expect(page.getByTestId('chart-title')).toHaveText('Fridge Plug')
  await expect(page.getByTestId('chart-dots')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Next chart' })).toHaveCount(0)
})

// BDD: A chart scrolls back through time
test('dragging scrolls back, loads earlier readings, stops at the earliest, and Now returns', async ({ page }) => {
  const { asked } = await serve(page, { earliestDays: 3 })
  await page.goto('/energy/rooms/kitchen')
  await expect(page.getByTestId('chart-title')).toHaveText('Kitchen')
  await expect.poll(async () => Number(await chart(page).getAttribute('data-points'))).toBeGreaterThan(200)
  const start = await shownTo(page)
  await expect(page.getByTestId('chart-now')).toHaveCount(0)

  await drag(page, 300)
  const back = await shownTo(page)
  expect(back).toBeLessThan(start - 3600_000)
  await expect(page.getByTestId('chart-now')).toBeVisible()
  await expect.poll(() => asked.some((q) => q.includes('to='))).toBe(true)

  // The other charts stay at the same time, and the drag did not move the carousel.
  await expect(page.getByTestId('chart-title')).toHaveText('Kitchen')
  await page.getByRole('button', { name: 'Next chart' }).click()
  expect(Math.abs((await shownTo(page)) - back)).toBeLessThan(1000)

  // It stops with the first reading at the left edge.
  for (let i = 0; i < 12; i++) await drag(page, 400)
  const stop = Number(await chart(page).getAttribute('data-from'))
  expect(Math.abs(stop - (Date.now() - 3 * 86_400_000))).toBeLessThan(60_000)
  await drag(page, 400)
  expect(Math.abs(Number(await chart(page).getAttribute('data-from')) - stop)).toBeLessThan(1000)
  await expect.poll(async () => Number(await chart(page).getAttribute('data-points'))).toBeGreaterThan(200)

  // A wider range keeps the time in view.
  const end = await shownTo(page)
  await page.getByRole('group', { name: 'Time range' }).getByRole('button', { name: '7d' }).click()
  await expect(chart(page)).toHaveAttribute('data-window', '7d')
  const to7 = await shownTo(page)
  const from7 = Number(await chart(page).getAttribute('data-from'))
  expect(from7).toBeLessThanOrEqual(end)
  expect(to7).toBeGreaterThanOrEqual(end - 1000)

  await page.getByTestId('chart-now').click()
  await expect(page.getByTestId('chart-now')).toHaveCount(0)
  expect(await shownTo(page)).toBeGreaterThan(Date.now() - 60_000)
})

// BDD: A chart says what it shows at any point
test('hovering says what a point shows, and a gap says there is no reading', async ({ page }) => {
  const now = Date.now()
  await serve(page, {
    earliestDays: 1,
    // A stretch with null readings around 12 hours ago, and one with no points at all around 6 hours ago.
    hide: (_m, t) => (Math.abs(t - (now - 12 * 3600_000)) < 3600_000 ? 'null' : Math.abs(t - (now - 6 * 3600_000)) < 3600_000 ? true : false),
  })
  await page.goto('/energy/rooms/kitchen')
  await expect.poll(async () => Number(await chart(page).getAttribute('data-points'))).toBeGreaterThan(200)
  const box = (await chart(page).boundingBox())!
  const plot = { left: box.x + 64, width: box.width - 64 - 12 }
  const hover = (hoursAgo: number) => page.mouse.move(plot.left + plot.width * (1 - hoursAgo / 24), box.y + 100)

  await hover(18)
  const point = page.getByTestId('chart-point')
  await expect(point).toContainText('100 W')
  await expect(point).toContainText('kWh')
  await hover(12)
  await expect(point).toContainText('No reading')
  await hover(6)
  await expect(point).toContainText('No reading')
  await page.mouse.move(box.x - 20, box.y - 20)
  await expect(point).toHaveCount(0)
  // The gap breaks the line instead of joining across or dropping to 0.
  const path = await page.locator('[data-testid="usage-chart"] .recharts-line-curve').getAttribute('d')
  expect((path!.match(/M/g) ?? []).length).toBe(3)
})

// BDD: A chart says what it shows at any point
test('a point with a reading missing says so', async ({ page }) => {
  await serve(page, { earliestDays: 1 })
  await page.route('**/api/rooms/kitchen/plugs/usage**', (route) => {
    const at = new Date(Date.now() - 2 * 3600_000).toISOString()
    return route.fulfill({ json: { currency: 'GBP', from: new Date(Date.now() - 86_400_000).toISOString(), to: new Date().toISOString(), earliest: at, room: [{ at, watts: 20, kwh: 0.002, cost: 0.05, incomplete: true }], plugs: [] } })
  })
  await page.goto('/energy/rooms/kitchen')
  await expect(chart(page)).toHaveAttribute('data-points', '1')
  const box = (await chart(page).boundingBox())!
  await page.mouse.move(box.x + 64 + (box.width - 76) * (22 / 24), box.y + 100)
  await expect(page.getByTestId('chart-point')).toContainText('Some readings missing')
  await expect(page.getByTestId('chart-point')).toContainText('20 W')
})

// BDD: A chart with nothing to draw says so
test('a chart with nothing to draw says so, and can still be moved', async ({ page }) => {
  await serve(page, { earliestDays: 3, hide: (m) => m === 'Kettle Plug' })
  await page.goto('/energy/rooms/kitchen')
  await page.getByRole('button', { name: 'Show Kettle Plug' }).click()
  await expect(page.getByTestId('chart-nothing')).toHaveText('Nothing recorded for this time')
  await drag(page, 300)
  await expect(page.getByTestId('chart-now')).toBeVisible()
  await page.getByRole('group', { name: 'Time range' }).getByRole('button', { name: '7d' }).click()
  await expect(chart(page)).toHaveAttribute('data-window', '7d')
  await expect(page.getByTestId('chart-nothing')).toBeVisible()
})

// BDD: A chart with nothing to draw says so
test('samples from before power was kept say so under Power, and draw under Use', async ({ page }) => {
  await serve(page, { earliestDays: 1 })
  await page.route('**/api/rooms/kitchen/plugs/usage**', (route) => {
    const at = (h: number) => new Date(Date.now() - h * 3600_000).toISOString()
    const old = (h: number) => ({ at: at(h), watts: null, kwh: 0.01, cost: 0.25, incomplete: false })
    return route.fulfill({ json: { currency: 'GBP', from: at(24), to: at(0), earliest: at(10), room: [old(10), old(9), old(8)], plugs: [] } })
  })
  await page.goto('/energy/rooms/kitchen')
  await expect(page.getByTestId('chart-nothing')).toBeVisible()
  await page.getByRole('button', { name: 'Use', exact: true }).click()
  await expect(page.getByTestId('chart-nothing')).toHaveCount(0)
  await expect(chart(page)).toHaveAttribute('data-points', '3')
})

// BDD: A table compares the plugs in the room
test('the table starts by cost, sorts by any heading, and picks a plug\'s chart', async ({ page }) => {
  const three = { ...twoPlugs, kwh: 2.1, cost: 55, plugs: [plug('Air Fryer', 300, 0.5, 15), fridge, kettle] }
  await serve(page, { plugs: three, meters: ['Air Fryer', 'Fridge Plug', 'Kettle Plug'] })
  await page.goto('/energy/rooms/kitchen')
  const names = () => page.getByTestId('plug-row').getByRole('rowheader').allInnerTexts()
  const table = page.getByTestId('plug-table')
  await expect(page.getByTestId('plug-row')).toHaveCount(3)
  expect(await names()).toEqual(['Fridge Plug', 'Air Fryer', 'Kettle Plug'])
  await expect(table.getByRole('columnheader', { name: 'Cost today' })).toHaveAttribute('aria-sort', 'descending')
  await expect(page.getByTestId('plug-row').first()).toContainText('40 W')
  await expect(page.getByTestId('plug-row').first()).toContainText('57%')
  await expect(page.getByTestId('plug-row').first()).toContainText('1.2 kWh')
  await expect(page.getByTestId('plug-row').first()).toContainText('£0.30')

  await table.getByRole('button', { name: 'Power now' }).click()
  expect(await names()).toEqual(['Air Fryer', 'Fridge Plug', 'Kettle Plug'])
  await expect(table.getByRole('columnheader', { name: 'Power now' })).toHaveAttribute('aria-sort', 'descending')
  await table.getByRole('button', { name: 'Power now' }).click()
  expect(await names()).toEqual(['Kettle Plug', 'Fridge Plug', 'Air Fryer'])
  await expect(table.getByRole('columnheader', { name: 'Power now' })).toHaveAttribute('aria-sort', 'ascending')

  // The sort stays as new readings arrive.
  await page.waitForResponse('**/api/rooms/kitchen/plugs', { timeout: 20_000 })
  expect(await names()).toEqual(['Kettle Plug', 'Fridge Plug', 'Air Fryer'])

  await table.getByRole('button', { name: 'Fridge Plug' }).click()
  await expect(page.getByTestId('chart-title')).toHaveText('Fridge Plug')
})

// BDD: A table compares the plugs in the room
test('on a phone the table scrolls sideways with the name in view', async ({ page }) => {
  await serve(page)
  await page.setViewportSize({ width: 390, height: 800 })
  await page.goto('/energy/rooms/kitchen')
  const table = page.getByTestId('plug-table')
  await expect(table).toBeVisible()
  const name = page.getByTestId('plug-row').first().getByRole('rowheader')
  const before = (await name.boundingBox())!.x
  await table.locator('..').evaluate((e) => (e.scrollLeft = 200))
  expect(await table.locator('..').evaluate((e) => e.scrollLeft)).toBeGreaterThan(0)
  expect((await name.boundingBox())!.x).toBe(before)
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true)
})

// BDD: The table says when a plug's figures cannot be trusted
test('an offline plug says so, never its last power, and sorts below those with power', async ({ page }) => {
  const since = '2026-10-12T14:20:00+01:00'
  const tv = plug('TV Plug', null, 3, 75, { incomplete: true, offlineSince: since })
  const stuck = plug('Lamp Plug', 60, 0.1, 2.5, { incomplete: true, totalStuckSince: since })
  await serve(page, { plugs: { ...twoPlugs, kwh: 3.5, cost: 87.5, incomplete: true, plugs: [plug('Fridge Plug', 40, 0.4, 10), stuck, tv] }, meters: ['Fridge Plug', 'Lamp Plug', 'TV Plug'] })
  await page.goto('/energy/rooms/kitchen')
  const row = page.getByTestId('plug-row').filter({ hasText: 'TV Plug' })
  await expect(row.getByTestId('plug-state')).toHaveText('Offline')
  await expect(row.getByTestId('plug-power')).toHaveText('Offline')
  await expect(row).toContainText('£0.75')
  await row.getByTestId('incomplete-warning').click()
  await expect(page.locator('[data-slot="popover-content"][data-state="open"]')).toContainText("Cost will update when it's back.")
  await page.keyboard.press('Escape')
  await page.getByTestId('plug-row').filter({ hasText: 'Lamp Plug' }).getByTestId('incomplete-warning').click()
  await expect(page.locator('[data-slot="popover-content"][data-state="open"]')).toContainText("isn't going up")
  await page.keyboard.press('Escape')

  const names = () => page.getByTestId('plug-row').getByRole('rowheader').allInnerTexts()
  const table = page.getByTestId('plug-table')
  await table.getByRole('button', { name: 'Power now' }).click()
  expect((await names()).at(-1)).toBe('TV Plug')
  await table.getByRole('button', { name: 'Power now' }).click()
  expect((await names()).at(-1)).toBe('TV Plug')
})

// BDD: A plug moved out of the room still adds up
test('use by a plug moved out still counts, in a row of its own and with no chart', async ({ page }) => {
  await serve(page, { plugs: { ...twoPlugs, kwh: 1.7, cost: 43, plugs: [fridge] }, meters: ['Fridge Plug'] })
  await page.goto('/energy/rooms/kitchen')
  await expect(page.getByTestId('usage-today')).toContainText('1.7 kWh')
  await expect(page.getByTestId('usage-cost')).toContainText('£0.43')
  const moved = page.getByTestId('plug-moved-out')
  await expect(moved).toContainText('Moved out')
  await expect(moved).toContainText('0.5 kWh')
  await expect(moved).toContainText('£0.13')
  await expect(page.getByTestId('chart-title')).toHaveText('Fridge Plug')
  await expect(page.getByTestId('plug-row')).toHaveCount(1)
})

// BDD: A room with no plugs left has nothing to chart
test('a room with no plugs left still shows what it used, and an unknown room says so', async ({ page }) => {
  await serve(page, { plugs: { ...twoPlugs, kwh: 0.5, cost: 12.5, plugs: [] }, meters: [] })
  await page.goto('/energy/rooms/kitchen')
  await expect(page.getByTestId('usage-today')).toContainText('0.5 kWh')
  await expect(page.getByTestId('no-plugs')).toHaveText('No plugs in this room')
  await expect(page.getByTestId('chart-title')).toHaveText('Kitchen')
  await expect(page.getByTestId('plug-table')).toHaveCount(0)
  await expect(page.getByTestId('usage-now')).toContainText('42 W')

  await page.goto('/energy/rooms/gone')
  await expect(page.getByTestId('room-not-found')).toHaveText('Room not found')
  await page.getByRole('button', { name: 'Energy', exact: true }).click()
  await expect(page).toHaveURL(/\/energy$/)
})

// BDD: Everything else has its own dashboard
test('everything else has cards and one chart, and no table', async ({ page }) => {
  const { push } = await serve(page)
  await page.goto('/energy')
  await page.getByRole('link', { name: 'Everything else energy' }).click()
  await expect(page).toHaveURL(/\/energy\/else$/)
  await expect(page.getByTestId('usage-now')).toContainText('666 W')
  await expect(page.getByTestId('usage-today')).toContainText('5.6 kWh')
  await expect(page.getByTestId('usage-cost')).toContainText('£1.51')
  await expect(page.getByText('What no plug accounts for')).toBeVisible()
  await expect(page.getByTestId('chart-dots')).toHaveCount(0)
  await expect(page.getByTestId('plug-table')).toHaveCount(0)
  await expect.poll(async () => Number(await chart(page).getAttribute('data-points'))).toBeGreaterThan(200)
  await drag(page, 300)
  await expect(page.getByTestId('chart-now')).toBeVisible()
  await page.getByRole('button', { name: 'Cost', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Cost', exact: true })).toHaveAttribute('aria-pressed', 'true')

  push(live({ watts: null, elseWatts: null }))
  await expect(page.getByTestId('usage-now')).toContainText('No reading')
  await expect(page.getByTestId('usage-cost').getByTestId('incomplete-warning')).toBeVisible()
})
