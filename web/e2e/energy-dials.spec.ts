import { expect, test, type Page, type WebSocketRoute } from '@playwright/test'

// The Energy tab's car-dashboard dials, the history chart under them, and energy access for members, against a mocked
// service: every /api call, /ws and /ws/power are answered here.

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[], energy: true }
const sam = { id: 'u-sam', username: 'sam', admin: false, rooms: [] as string[], energy: true }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

type Target = { kwh: number; usedKwh: number; expectedKwh: number } | null

function today(target: Target) {
  return {
    period: 'today',
    from: new Date().toISOString(),
    to: new Date().toISOString(),
    boilerHours: 3,
    gasKwh: 36,
    gasCost: 396,
    electricityMeter: 'Main Clamp',
    electricityKwh: target?.usedKwh ?? 8,
    electricityCost: 135,
    gasStandingCost: 30,
    electricityStandingCost: 50,
    standingCost: 80,
    totalCost: 611,
    missingMinutes: 0,
    incomplete: false,
    days: [],
    currency: 'GBP',
    devices: [],
    rooms: [],
    everythingElse: { kwh: 1, cost: 27, missingMinutes: 0, incomplete: false },
    target: target && {
      ...target,
      deltaKwh: target.usedKwh - target.expectedKwh,
      deltaCost: (target.usedKwh - target.expectedKwh) * 27,
      onTarget: target.usedKwh <= target.expectedKwh,
      projectedKwh: null,
      incomplete: false,
    },
  }
}

function live(house: number | null, rooms: Record<string, number | null>, lights: number | null = 150) {
  return {
    watts: house,
    at: new Date().toISOString(),
    rooms: Object.entries(rooms).map(([name, watts]) => ({ room: name.toLowerCase(), name, watts })),
    devices: [{ meter: 'Fridge Plug', watts: 2000 }],
    areas: [{ area: 'lights', name: 'Lights', watts: lights }],
    elseWatts: house == null ? null : 3000,
  }
}

const PEAK = {
  watts: 6200,
  at: new Date(new Date().setHours(18, 5, 0, 0)).toISOString(),
  parts: [
    { kind: 'room', id: 'kitchen', name: 'Kitchen', watts: 3500 },
    { kind: 'else', id: null, name: 'Everything else', watts: 2300 },
    { kind: 'area', id: 'lights', name: 'Lights', watts: 400 },
  ],
}

const HOUR = 3_600_000

const TOTALS = {
  currency: 'GBP',
  pencePerKwh: 27,
  today: { kwh: 8.2, cost: 221.4 },
  week: { kwh: 51, cost: 1377 },
  month: { kwh: 190, cost: 5130 },
  year: { kwh: 2100, cost: 56700 },
  yesterdaySoFar: { kwh: 7, cost: 189 },
}

/** A week of hourly points ending at [to]; the history the chart is given. */
function history(range: string, to: number | null) {
  const end = to ?? Date.now()
  const step = range === 'week' ? HOUR : range === 'month' ? 24 * HOUR : range === 'year' ? 30 * 24 * HOUR : 5 * 60_000
  const count = range === 'week' ? 168 : range === 'month' ? 30 : range === 'year' ? 12 : 288
  const last = Math.floor(end / step) * step
  const points = Array.from({ length: count }, (_, i) => {
    const at = last - (count - 1 - i) * step
    return { at: new Date(at).toISOString(), kwh: 0.5, cost: 13.5, watts: 500 + (i % 10) * 50, incomplete: false, targetKwh: range === 'month' ? 12 : null }
  })
  return { currency: 'GBP', range, from: points[0].at, to: new Date(end).toISOString(), earliest: new Date(Date.now() - 400 * 24 * HOUR).toISOString(), points }
}

async function serve(page: Page, opts: { me?: typeof jane; first?: object; target?: Target; peak?: object | null; totals?: () => object; recent?: object[] } = {}) {
  const sockets: WebSocketRoute[] = []
  const asked: { range: string; to: string | null }[] = []
  await page.routeWebSocket('**/ws', () => {})
  await page.routeWebSocket('**/ws/power', (ws) => {
    sockets.push(ws)
    ws.send(JSON.stringify(opts.first ?? live(2400, { Kitchen: 900, Utility: 300 })))
  })
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const { pathname } = url
    if (pathname === '/api/auth/me') return route.fulfill({ json: opts.me ?? jane })
    if (pathname === '/api/rooms') return route.fulfill({ json: [] })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/groups') return route.fulfill({ json: [] })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/energy/areas') {
      if (!(opts.me ?? jane).admin) return route.fulfill({ status: 403, json: { error: 'admins only' } })
      return route.fulfill({ json: [{ id: 'lights', name: 'Lights', wholeMeter: null, overlaps: [] }] })
    }
    if (pathname === '/api/dashboard') return route.fulfill({ json: today(opts.target === undefined ? { kwh: 12, usedKwh: 5, expectedKwh: 6 } : opts.target) })
    if (pathname === '/api/power') return route.fulfill({ json: { bands: { green: 350, red: 4000 }, recent: opts.recent ?? [], todayPeak: opts.peak === undefined ? PEAK : opts.peak } })
    if (pathname === '/api/energy/totals') return route.fulfill({ json: (opts.totals ?? (() => TOTALS))() })
    if (pathname === '/api/energy/history') {
      const range = url.searchParams.get('range') ?? 'day'
      const to = url.searchParams.get('to')
      asked.push({ range, to })
      return route.fulfill({ json: history(range, to ? Date.parse(to) : null) })
    }
    return route.fulfill({ status: 404, json: { error: 'not mocked' } })
  })
  const push = (msg: object) => sockets.at(-1)!.send(JSON.stringify(msg))
  return { push, asked }
}

// BDD: The house dial shows the whole house now
test('the house dial shows the whole house now', async ({ page }) => {
  await serve(page)
  await page.goto('/energy')
  await expect(page.getByTestId('power-now')).toHaveText('2.40 kW')
  await expect(page.getByTestId('energy-dials')).toContainText('Whole house')
  await expect(page.getByTestId('power-dial').getByTestId('power-bands')).toHaveCount(1)
})

// BDD: The second dial follows the busiest room or area
test('the second dial follows the busiest room or area', async ({ page }) => {
  const { push } = await serve(page)
  await page.goto('/energy')
  await expect(page.getByTestId('busiest-name')).toHaveText('Kitchen')
  await expect(page.getByTestId('busiest-now')).toHaveText('900 W')
  // The Fridge plug (in no room) and "Everything else" use more but are never on it; both dials share one scale.
  await expect(page.getByTestId('busiest-dial')).toHaveAttribute('data-max', (await page.getByTestId('power-dial').getAttribute('data-max'))!)
  push(live(2400, { Kitchen: null, Utility: null }, null))
  await expect(page.getByTestId('busiest-now')).toHaveText('No rooms reporting')
})

// BDD: The second dial does not flicker between close rooms
test('the second dial does not flicker between close rooms', async ({ page }) => {
  await page.clock.install()
  const { push } = await serve(page)
  await page.goto('/energy')
  await expect(page.getByTestId('busiest-name')).toHaveText('Kitchen')

  // Less than 10% ahead: stays.
  push(live(2400, { Kitchen: 900, Utility: 950 }))
  await page.clock.fastForward(40_000)
  push(live(2400, { Kitchen: 900, Utility: 950 }))
  await expect(page.getByTestId('busiest-now')).toHaveText('900 W')
  await expect(page.getByTestId('busiest-name')).toHaveText('Kitchen')

  // Far enough ahead, but not yet for 30 seconds.
  push(live(2400, { Kitchen: 900, Utility: 1100 }))
  await page.clock.fastForward(20_000)
  push(live(2400, { Kitchen: 900, Utility: 1100 }))
  await expect(page.getByTestId('busiest-name')).toHaveText('Kitchen')
  await page.clock.fastForward(11_000)
  push(live(2400, { Kitchen: 900, Utility: 1100 }))
  await expect(page.getByTestId('busiest-name')).toHaveText('Utility')

  // Its room stops reporting: moves at once.
  push(live(2400, { Kitchen: 900, Utility: null }))
  await expect(page.getByTestId('busiest-name')).toHaveText('Kitchen')
})

// BDD: The fuel gauge drains towards the daily target
test('the fuel gauge drains towards the daily target', async ({ page }) => {
  await serve(page)
  await page.goto('/energy')
  const gauge = page.getByTestId('fuel-gauge')
  await expect(gauge).toHaveAttribute('data-left', (7 / 12).toFixed(3))
  await expect(gauge).toHaveAttribute('data-pace', (6 / 12).toFixed(3))
  await expect(gauge.getByTestId('fuel-left')).toHaveText('7.0 kWh left')
  await expect(gauge.getByTestId('fuel-used')).toHaveText('of 12.0 kWh · 1.0 under pace')
  await expect(gauge.getByTestId('fuel-pace-line')).toHaveText('1.0 under pace')
  await expect(gauge.getByTestId('fuel-low')).toHaveAttribute('data-on', 'false')
  // The arc is the fuel left, from E to the needle.
  await expect(gauge.getByTestId('fuel-level')).toHaveCount(1)
})

test('the fuel gauge warns when low and rests on E past the target', async ({ page }) => {
  await serve(page, { target: { kwh: 12, usedKwh: 13, expectedKwh: 9 } })
  await page.goto('/energy')
  const gauge = page.getByTestId('fuel-gauge')
  await expect(gauge).toHaveAttribute('data-left', '0.000')
  await expect(gauge.getByTestId('fuel-left')).toHaveText('1.0 kWh over')
  await expect(gauge.getByTestId('fuel-used')).toHaveText('of 12.0 kWh')
  await expect(gauge.getByTestId('fuel-low')).toHaveAttribute('data-on', 'true')
})

test('with no target the fuel gauge offers admins to set one', async ({ page }) => {
  await serve(page, { target: null })
  await page.goto('/energy')
  const gauge = page.getByTestId('fuel-gauge')
  await expect(gauge.getByTestId('fuel-left')).toHaveText('No daily target')
  await expect(gauge.getByRole('link', { name: 'Set a daily target' })).toHaveAttribute('href', '/energy/manage')
})

// BDD: Today's peak is the highest five-minute power since midnight
test("the peak gauge reads today's peak and when", async ({ page }) => {
  await serve(page)
  await page.goto('/energy')
  await expect(page.getByTestId('peak-now')).toHaveText('6.20 kW')
  await expect(page.getByTestId('peak-at')).toHaveText('at 18:05')
})

// BDD: Today's peak says what was using the power
test('hovering or tapping the peak gauge shows what was using the power', async ({ page }) => {
  await serve(page)
  await page.goto('/energy')
  await page.getByTestId('peak-gauge').hover()
  const parts = page.getByTestId('peak-part')
  await expect(parts).toHaveCount(3)
  await expect(parts.nth(0)).toContainText('Kitchen')
  await expect(parts.nth(0)).toContainText('3.50 kW · 56%')
  await expect(parts.nth(1)).toContainText('Everything else')
  await expect(parts.nth(2)).toContainText('Lights')
  await page.mouse.move(0, 0)
  await expect(page.getByTestId('peak-parts')).toHaveCount(0)
  await page.getByTestId('peak-gauge').tap({ force: true }).catch(() => page.getByTestId('peak-gauge').click())
  await expect(page.getByTestId('peak-parts')).toBeVisible()
})

const chart = (page: Page) => page.getByTestId('history-chart')
const chartEnd = async (page: Page) => Number(await chart(page).getAttribute('data-end'))

// BDD: The chart can be dragged back in time
test('the chart can be dragged back in time', async ({ page }) => {
  const { asked } = await serve(page)
  await page.goto('/energy')
  await page.getByRole('group', { name: 'Chart range' }).getByRole('button', { name: 'Week' }).click()
  await expect(chart(page)).toHaveAttribute('data-range', 'week')
  await expect.poll(async () => Number(await chart(page).getAttribute('data-points'))).toBeGreaterThan(100)
  const atNow = await chartEnd(page)
  await expect(page.getByTestId('history-now')).toHaveCount(0)

  // Drag right by half the chart: half a week earlier, and the earlier half loaded.
  await chart(page).scrollIntoViewIfNeeded()
  const box = (await chart(page).boundingBox())!
  await page.mouse.move(box.x + box.width * 0.25, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width * 0.5, box.y + box.height / 2, { steps: 5 })
  await page.mouse.move(box.x + box.width * 0.75, box.y + box.height / 2, { steps: 5 })
  await page.mouse.up()
  const dragged = await chartEnd(page)
  expect(atNow - dragged).toBeGreaterThan(3 * 24 * HOUR)
  expect(atNow - dragged).toBeLessThan(4 * 24 * HOUR)
  await expect.poll(() => asked.some((a) => a.range === 'week' && a.to != null)).toBe(true)
  await expect.poll(async () => Number(await chart(page).getAttribute('data-points'))).toBeGreaterThan(160)

  // Never past now: dragging left a long way stops at now.
  await page.mouse.move(box.x + box.width * 0.9, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width * 0.05, box.y + box.height / 2, { steps: 5 })
  await page.mouse.move(box.x - box.width, box.y + box.height / 2, { steps: 5 })
  await page.mouse.up()
  expect(await chartEnd(page)).toBeLessThanOrEqual(Date.now())
  expect(await chartEnd(page)).toBeGreaterThan(Date.now() - HOUR)

  // Back a bit, then Month keeps the right edge, and "Now" returns.
  await page.mouse.move(box.x + box.width * 0.2, box.y + box.height / 2)
  await page.mouse.down()
  await page.mouse.move(box.x + box.width * 0.6, box.y + box.height / 2, { steps: 5 })
  await page.mouse.up()
  const back = await chartEnd(page)
  await page.getByRole('group', { name: 'Chart range' }).getByRole('button', { name: 'Month' }).click()
  await expect(chart(page)).toHaveAttribute('data-range', 'month')
  expect(await chartEnd(page)).toBe(back)
  await page.getByTestId('history-now').click()
  expect(await chartEnd(page)).toBeGreaterThan(Date.now() - 60_000)
  await expect(page.getByTestId('history-now')).toHaveCount(0)
})

// BDD: The chart says what each point used and cost
test('the chart says what each point used and cost', async ({ page }) => {
  await serve(page)
  await page.goto('/energy')
  await page.getByRole('group', { name: 'Chart range' }).getByRole('button', { name: 'Month' }).click()
  await expect(chart(page).locator('.history-bar .recharts-rectangle').first()).toBeVisible()
  await expect(chart(page).locator('.history-target')).toHaveCount(1)
  await chart(page).scrollIntoViewIfNeeded()
  const box = (await chart(page).boundingBox())!
  await page.mouse.move(box.x + box.width * 0.6, box.y + box.height * 0.6)
  const readout = page.getByTestId('history-readout')
  await expect(readout).toContainText('Used0.5 kWh')
  await expect(readout).toContainText('Cost£0.14')
  await expect(readout).toContainText('Target12.0 kWh')

  await page.getByRole('group', { name: 'Chart range' }).getByRole('button', { name: 'Day' }).click()
  await expect(chart(page).locator('.history-line')).toHaveCount(1)
  await page.mouse.move(box.x + box.width * 0.5, box.y + box.height * 0.6)
  await expect(readout).toContainText('Power')
  await expect(readout).not.toContainText('Target')
})

// BDD: The Energy tab shows for people with energy access
test('a member with energy access sees the energy tab read only', async ({ page }) => {
  await serve(page, { me: sam, target: null })
  await page.goto('/')
  await page.getByRole('link', { name: 'Energy' }).click()
  await expect(page).toHaveURL(/\/energy$/)
  await expect(page.getByTestId('energy-dials')).toBeVisible()
  await expect(page.getByTestId('history-chart')).toBeVisible()
  await expect(page.getByRole('link', { name: 'Manage' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Set a daily target' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Add area' })).toHaveCount(0)
  // Cards open nothing.
  await expect(page.getByTestId('live-area-lights')).toBeVisible()
  await expect(page.getByRole('link', { name: 'Lights energy' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Kitchen energy' })).toHaveCount(0)
  await expect(page.getByTestId('live-room-kitchen')).toBeVisible()
})

test('a member without energy access has no energy tab', async ({ page }) => {
  await serve(page, { me: { ...sam, energy: false } })
  await page.goto('/energy')
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('link', { name: 'Energy' })).toHaveCount(0)
})

// BDD: The trip counter ticks up with the house's use
test("the trip counter ticks up with the house's use", async ({ page }) => {
  await page.clock.install()
  let recorded = 8.2
  const steady = (w: number) => ({ ...live(1200, { Kitchen: 900, Utility: 300 }), watts: w })
  const { push } = await serve(page, { first: steady(1200), totals: () => ({ ...TOTALS, today: { kwh: recorded, cost: recorded * 27 } }) })
  await page.goto('/energy')
  const trip = page.getByTestId('trip-kwh')
  const counter = page.getByTestId('usage-today')
  await expect(counter).toContainText('Usage today')
  await expect(trip).toHaveAttribute('data-value', '8.2000')
  await expect(page.getByTestId('trip-cost')).toHaveText('£2.21')
  // A minute at 1200 W is 0.02 kWh.
  for (let i = 0; i < 6; i++) {
    await page.clock.fastForward(10_000)
    push(steady(1200))
  }
  await expect.poll(async () => Number(await trip.getAttribute('data-value'))).toBeCloseTo(8.22, 2)
  // The recorded figure arriving lower does not roll it back; higher is taken.
  await page.clock.fastForward(5 * 60_000)
  await expect.poll(async () => Number(await trip.getAttribute('data-value'))).toBeGreaterThanOrEqual(8.22)
  recorded = 9.5
  await page.clock.fastForward(5 * 60_000)
  await expect.poll(async () => Number(await trip.getAttribute('data-value'))).toBeGreaterThanOrEqual(9.5)
  // Tapped, it shows the cost in pounds to four decimals, and tapped again the usage; remembered.
  await counter.click()
  await expect(counter).toContainText('Cost today')
  const pounds = page.getByTestId('trip-pounds')
  await expect(pounds).toHaveAttribute('data-value', /^2\.\d{4}$/)
  await expect(pounds).toHaveAttribute('aria-label', /^£2\.\d{4}$/)
  await page.reload()
  await expect(page.getByTestId('usage-today')).toContainText('Cost today')
  await page.getByTestId('usage-today').click()
  await expect(page.getByTestId('usage-today')).toContainText('Usage today')
})

// BDD: The odometer totals the day, week, month or year
test('the odometer totals the day, week, month or year', async ({ page }) => {
  await serve(page, { first: { ...live(0, { Kitchen: 0, Utility: 0 }), watts: 0 } })
  await page.goto('/energy')
  const odometer = page.getByTestId('odometer-kwh')
  const periods = page.getByRole('group', { name: 'Odometer period' })
  await periods.getByRole('button', { name: 'Year' }).click()
  await expect(odometer).toHaveAttribute('data-value', '2100.0')
  await expect(page.getByTestId('odometer-cost')).toHaveText('£567.00')
  await periods.getByRole('button', { name: 'Month' }).click()
  await expect(odometer).toHaveAttribute('data-value', '190.0')
  await periods.getByRole('button', { name: 'Day' }).click()
  await expect(odometer).toHaveAttribute('data-value', '8.2')
  // Remembered.
  await periods.getByRole('button', { name: 'Week' }).click()
  await page.reload()
  await expect(page.getByRole('group', { name: 'Odometer period' }).getByRole('button', { name: 'Week' })).toHaveAttribute('aria-pressed', 'true')
  await expect(page.getByTestId('odometer-kwh')).toHaveAttribute('data-value', '51.0')
})

// BDD: Today is compared with yesterday at the same time
test('today is compared with yesterday at the same time', async ({ page }) => {
  await serve(page, { first: { ...live(0, { Kitchen: 0, Utility: 0 }), watts: 0 } })
  await page.goto('/energy')
  await expect(page.getByTestId('vs-yesterday')).toHaveText('▲ 1.2 kWh vs yesterday')
  await expect(page.getByTestId('vs-yesterday')).toHaveAttribute('data-more', 'true')
})

test('less than yesterday is green, and nothing yesterday says nothing', async ({ page }) => {
  await serve(page, { first: { ...live(0, { Kitchen: 0, Utility: 0 }), watts: 0 }, totals: () => ({ ...TOTALS, yesterdaySoFar: { kwh: 8.7, cost: 235 } }) })
  await page.goto('/energy')
  await expect(page.getByTestId('vs-yesterday')).toHaveText('▼ 0.5 kWh vs yesterday')
  await expect(page.getByTestId('vs-yesterday')).toHaveAttribute('data-more', 'false')
})
