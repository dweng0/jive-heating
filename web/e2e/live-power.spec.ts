import { expect, test, type Page, type WebSocketRoute } from '@playwright/test'

// The live power dial and its room cards, against a mocked service: every /api call, /ws and /ws/power are
// answered here, so these tests do not depend on the real service.

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[] }
const sam = { id: 'u-sam', username: 'sam', admin: false, rooms: [] as string[] }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

function today(meter: string | null = 'Main Clamp') {
  return {
    period: 'today',
    from: new Date().toISOString(),
    to: new Date().toISOString(),
    boilerHours: 3,
    gasKwh: 36,
    gasCost: 396,
    electricityMeter: meter,
    electricityKwh: meter ? 8 : null,
    electricityCost: meter ? 216 : null,
    gasStandingCost: 30,
    electricityStandingCost: meter ? 50 : null,
    standingCost: 80,
    totalCost: 692,
    missingMinutes: 0,
    incomplete: false,
    days: [],
    currency: 'GBP',
    devices: [
      { meter: 'TV Plug', kwh: 0.9, cost: 24.3, missingMinutes: 0, incomplete: false, room: 'lounge' },
      { meter: 'Lamp Plug', kwh: 0.3, cost: 8.1, missingMinutes: 0, incomplete: false, room: 'lounge' },
      { meter: 'Fridge Plug', kwh: 1.2, cost: 32.4, missingMinutes: 0, incomplete: false, room: null },
    ],
    rooms: [{ room: 'lounge', name: 'Lounge', kwh: 1.2, cost: 32.4, missingMinutes: 0, incomplete: false }],
    everythingElse: { kwh: 5.6, cost: 151.2, missingMinutes: 0, incomplete: false },
  }
}

function live(watts: number | null, rest: { lounge?: number | null; fridge?: number | null; elseWatts?: number | null } = {}) {
  return {
    watts,
    at: new Date().toISOString(),
    rooms: [{ room: 'lounge', name: 'Lounge', watts: rest.lounge === undefined ? 400 : rest.lounge }],
    devices: [{ meter: 'Fridge Plug', watts: rest.fridge === undefined ? 100 : rest.fridge }],
    elseWatts: rest.elseWatts === undefined ? (watts == null ? null : Math.max(0, watts - 500)) : rest.elseWatts,
  }
}

const minutesAgo = (m: number) => new Date(Date.now() - m * 60_000).toISOString()

async function serve(page: Page, opts: { bands?: { green: number; red: number } | null; first?: object; meter?: string | null; me?: typeof jane } = {}) {
  const sockets: WebSocketRoute[] = []
  await page.routeWebSocket('**/ws', () => {})
  await page.routeWebSocket('**/ws/power', (ws) => {
    sockets.push(ws)
    ws.send(JSON.stringify(opts.first ?? live(500)))
  })
  await page.route('**/api/**', async (route) => {
    const { pathname } = new URL(route.request().url())
    if (pathname === '/api/auth/me') return route.fulfill({ json: opts.me ?? jane })
    if (pathname === '/api/rooms') return route.fulfill({ json: [] })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/groups') return route.fulfill({ json: [] })
    if (pathname === '/api/history') return route.fulfill({ json: { from: minutesAgo(1440), to: minutesAgo(0), rooms: [], house: [], boiler: [] } })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/dashboard') return route.fulfill({ json: today(opts.meter === undefined ? 'Main Clamp' : opts.meter) })
    if (pathname === '/api/power') {
      const recent = [25, 20, 15, 10, 5].map((m, i) => ({ at: minutesAgo(m), watts: 400 + i * 100 }))
      return route.fulfill({ json: { bands: opts.bands === undefined ? { green: 350, red: 4000 } : opts.bands, recent } })
    }
    return route.fulfill({ status: 404, json: { error: 'not mocked' } })
  })
  const push = (msg: object) => sockets.at(-1)!.send(JSON.stringify(msg))
  return { push }
}

const shown = async (page: Page) => Number(await page.getByTestId('power-dial').getAttribute('data-shown'))

// BDD: The dial moves smoothly
test('the dial sweeps to a new reading instead of jumping', async ({ page }) => {
  const { push } = await serve(page)
  await page.goto('/energy')
  await expect(page.getByTestId('power-now')).toHaveText('500 W')
  await expect.poll(() => shown(page)).toBe(500)

  // Watch the needle every frame while 3000 W comes in.
  const watched = page.evaluate(
    () =>
      new Promise<number[]>((resolve) => {
        const dial = document.querySelector('[data-testid=power-dial]')!
        const seen: number[] = []
        const started = performance.now()
        const frame = () => {
          seen.push(Number(dial.getAttribute('data-shown')))
          if (performance.now() - started < 1600) requestAnimationFrame(frame)
          else resolve(seen)
        }
        requestAnimationFrame(frame)
      }),
  )
  push(live(3000))
  const seen = await watched
  const between = seen.filter((w) => w > 500 && w < 3000)
  // Many steps on the way, not one jump; always rising, never past 3000.
  expect(between.length).toBeGreaterThan(5)
  seen.slice(1).forEach((w, i) => expect(w).toBeGreaterThanOrEqual(seen[i]))
  expect(Math.max(...seen)).toBeLessThanOrEqual(3000)
  expect(seen.at(-1)).toBe(3000)
  await expect(page.getByTestId('power-now')).toHaveText('3.00 kW')
})

// BDD: The dial colours follow the household's own use
test("the dial is coloured by the household's own bands", async ({ page }) => {
  const { push } = await serve(page, { first: live(300) })
  await page.goto('/energy')
  const dial = page.getByTestId('power-dial')
  await expect(dial.getByTestId('power-bands')).toBeVisible()
  await expect(dial).toHaveAttribute('data-zone', 'low')
  push(live(1500))
  await expect(dial).toHaveAttribute('data-zone', 'mid')
  push(live(4500))
  await expect(dial).toHaveAttribute('data-zone', 'high')
})

// BDD: The dial colours follow the household's own use
test('with under a day of readings the dial is one neutral colour', async ({ page }) => {
  const { push } = await serve(page, { bands: null, first: live(300) })
  await page.goto('/energy')
  const dial = page.getByTestId('power-dial')
  await expect(dial).toHaveAttribute('data-zone', 'neutral')
  push(live(4500))
  await expect.poll(() => shown(page)).toBe(4500)
  await expect(dial).toHaveAttribute('data-zone', 'neutral')
  await expect(dial.getByTestId('power-bands')).toHaveCount(0)
})

// BDD: The dial shows today's use
test("under the dial are today's kWh, cost and the last 30 minutes", async ({ page }) => {
  const { push } = await serve(page)
  await page.goto('/energy')
  const section = page.getByTestId('live-power')
  await expect(section.getByTestId('live-today')).toContainText('8.0 kWh')
  await expect(section.getByTestId('live-today')).toContainText('£2.16')
  // Five readings from the service, then the one on connect.
  await expect(section.getByTestId('power-sparkline')).toHaveAttribute('data-points', '6')
  push(live(900))
  await expect(section.getByTestId('power-sparkline')).toHaveAttribute('data-points', '7')
})

// BDD: Room cards show live and daily use
test('room cards show live watts, today and their share of the house', async ({ page }) => {
  const { push } = await serve(page, { first: live(2000, { lounge: 500, fridge: 100, elseWatts: 1400 }) })
  await page.goto('/energy')
  const lounge = page.getByTestId('live-room-lounge')
  await expect(lounge).toContainText('Lounge')
  await expect(lounge.getByTestId('live-watts')).toHaveText('500 W')
  await expect(lounge.getByTestId('live-day')).toContainText('1.2 kWh')
  await expect(lounge.getByTestId('live-day')).toContainText('£0.32')
  await expect(lounge.getByTestId('live-share')).toHaveAttribute('data-share', '25')
  await expect(lounge).toContainText('25% of the house')
  // A monitored device in no room has its own card.
  await expect(page.getByTestId('live-device-Fridge Plug').getByTestId('live-watts')).toHaveText('100 W')
  // Only the rooms the service lists: those with monitored plugs.
  await expect(page.locator('[data-testid^=live-room-]')).toHaveCount(1)

  push(live(2000, { lounge: 1000, fridge: 100, elseWatts: 900 }))
  await expect(lounge.getByTestId('live-watts')).toHaveText('1.00 kW')
  await expect(lounge.getByTestId('live-share')).toHaveAttribute('data-share', '50')
})

// BDD: Everything else is the unattributed remainder
test('everything else shows the remainder, and goes while the house reading is stale', async ({ page }) => {
  const { push } = await serve(page, { first: live(2000, { lounge: 1000, fridge: 200, elseWatts: 800 }) })
  await page.goto('/energy')
  const rest = page.getByTestId('live-rest')
  await expect(rest.getByTestId('live-watts')).toHaveText('800 W')
  await expect(rest.getByTestId('live-day')).toContainText('5.6 kWh')
  await expect(rest.getByTestId('live-day')).toContainText('£1.51')
  push(live(null, { lounge: 1000, fridge: 200, elseWatts: null }))
  await expect(rest).toHaveCount(0)
})

// BDD: A stale house reading is not shown as live
test('a stale house reading reads "No reading" and the cards show no share', async ({ page }) => {
  const { push } = await serve(page, { first: live(1200, { lounge: 300 }) })
  await page.goto('/energy')
  await expect(page.getByTestId('power-now')).toHaveText('1.20 kW')
  push(live(null, { lounge: null, fridge: null }))
  await expect(page.getByTestId('power-now')).toHaveText('No reading')
  await expect(page.getByTestId('power-dial')).toHaveAttribute('data-zone', 'none')
  const lounge = page.getByTestId('live-room-lounge')
  await expect(lounge.getByTestId('live-watts')).toHaveText('–')
  await expect(lounge.getByTestId('live-share')).toHaveAttribute('data-share', '')
})

test('with no house meter chosen there is no live dial', async ({ page }) => {
  await serve(page, { meter: null })
  await page.goto('/energy')
  await expect(page.getByRole('heading', { name: 'Use and cost' })).toBeVisible()
  await expect(page.getByTestId('live-power')).toHaveCount(0)
})

// BDD: Energy has its own tab
test('energy has its own tab beside the dashboard', async ({ page }) => {
  await serve(page)
  await page.goto('/')
  const nav = page.getByRole('navigation')
  await expect(nav.getByRole('link', { name: 'Dashboard' })).toBeVisible()
  await nav.getByRole('link', { name: 'Energy' }).click()
  await expect(page).toHaveURL(/\/energy$/)
  await expect(page.getByRole('heading', { name: 'Energy', exact: true })).toBeVisible()
  // The live dial first, then the totals.
  const dial = await page.getByTestId('live-power').boundingBox()
  const totals = await page.getByTestId('energy').boundingBox()
  expect(totals!.y).toBeGreaterThan(dial!.y)

  // The Dashboard shows only heating.
  await nav.getByRole('link', { name: 'Dashboard' }).click()
  await expect(page.getByRole('heading', { name: 'Dashboard' })).toBeVisible()
  await expect(page.getByTestId('live-power')).toHaveCount(0)
  await expect(page.getByTestId('energy')).toHaveCount(0)

  // On a phone it is in the menu.
  await page.setViewportSize({ width: 390, height: 800 })
  await page.getByRole('button', { name: 'Account menu' }).click()
  await expect(page.getByRole('menuitem', { name: 'Energy' })).toBeVisible()
})

// BDD: Energy has its own tab
test('members have no energy tab', async ({ page }) => {
  await serve(page, { me: sam })
  await page.goto('/')
  await expect(page.getByRole('navigation').getByRole('link', { name: 'Rooms' })).toBeVisible()
  await expect(page.getByRole('link', { name: 'Energy' })).toHaveCount(0)
  await page.goto('/energy')
  await expect(page).toHaveURL(/\/$/)
})

// BDD: The dial's scale fits the household's biggest appliances
test('the dial runs to 8 kW on a square-root scale', async ({ page }) => {
  const { push } = await serve(page, { first: live(500) })
  await page.goto('/energy')
  const dial = page.getByTestId('power-dial')
  await expect(dial).toHaveAttribute('data-max', '8000')
  await expect(dial.getByTestId('dial-tick')).toHaveText(['0', '0.5', '1', '2', '4', '6', '8'])
  await expect.poll(() => shown(page)).toBe(500)
  await expect(dial).toHaveAttribute('data-fraction', '0.250')
  push(live(2000))
  await expect(dial).toHaveAttribute('data-fraction', '0.500')
  push(live(7000))
  await expect.poll(() => shown(page)).toBe(7000)
  await expect(dial).toHaveAttribute('data-max', '8000')
  // Above 8 kW the scale grows instead of pinning the needle.
  push(live(9000))
  await expect(dial).toHaveAttribute('data-max', '10000')
  await expect(dial.getByTestId('dial-tick').last()).toHaveText('10')
})
