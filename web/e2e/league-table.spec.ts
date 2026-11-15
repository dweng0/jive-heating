import { expect, test, type Page, type Route, type WebSocketRoute } from '@playwright/test'

// The live league table below the dials on the Energy tab, against a mocked service: every /api call, /ws and
// /ws/power are answered here, so these tests do not depend on the real service.

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[] }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

const today = {
  period: 'today',
  from: new Date().toISOString(),
  to: new Date().toISOString(),
  boilerHours: 0,
  gasKwh: 0,
  gasCost: 0,
  electricityMeter: 'Main Clamp',
  electricityKwh: 8,
  electricityCost: 216,
  gasStandingCost: 30,
  electricityStandingCost: 50,
  standingCost: 80,
  totalCost: 296,
  missingMinutes: 0,
  incomplete: false,
  days: [],
  currency: 'GBP',
  devices: [],
  rooms: [],
  everythingElse: { kwh: 8, cost: 216, missingMinutes: 0, incomplete: false },
}

type Item = { meter: string; name: string; watts: number | null; room?: string | null; area?: string | null; job?: string | null }

function live(watts: number | null, items: Item[], places: { rooms?: object[]; areas?: object[] } = {}) {
  return { watts, at: new Date().toISOString(), rooms: places.rooms ?? [], devices: [], elseWatts: null, areas: places.areas ?? [], items }
}

type Extra = (route: Route, pathname: string) => Promise<void> | undefined

async function serve(page: Page, first: object, opts: { me?: object; extra?: Extra } = {}) {
  const sockets: WebSocketRoute[] = []
  await page.routeWebSocket('**/ws', () => {})
  await page.routeWebSocket('**/ws/power', (ws) => {
    sockets.push(ws)
    ws.send(JSON.stringify(first))
  })
  await page.route('**/api/**', async (route) => {
    const { pathname } = new URL(route.request().url())
    const handled = opts.extra?.(route, pathname)
    if (handled) return handled
    if (pathname === '/api/auth/me') return route.fulfill({ json: opts.me ?? jane })
    if (pathname === '/api/rooms') return route.fulfill({ json: [] })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/groups') return route.fulfill({ json: [] })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/dashboard') return route.fulfill({ json: today })
    if (pathname === '/api/power') return route.fulfill({ json: { bands: null, recent: [] } })
    return route.fulfill({ status: 404, json: { error: 'not mocked' } })
  })
  return { push: (msg: object) => sockets.at(-1)!.send(JSON.stringify(msg)) }
}

const rows = (page: Page) => page.getByTestId('league-table').getByTestId('league-row')

// BDD: League table ranks items by their live watts
test('the league table ranks items by their live watts, then everything else, off and no reading', async ({ page }) => {
  await serve(
    page,
    // The spec's 11000 W house, plus the 3 W lamp that reads as off.
    live(11003, [
      { meter: 'Fridge Plug', name: 'Fridge', watts: 90, room: 'kitchen', job: 'monitored' },
      { meter: 'Kettle Plug', name: 'Kettle', watts: 0, room: 'kitchen', job: 'monitored' },
      { meter: 'Shower Clamp', name: 'Shower', watts: 9500 },
      { meter: 'Lamp Plug', name: 'Lamp', watts: 3, job: 'monitored' },
      { meter: 'Oven Clamp', name: 'Oven', watts: 1200, area: 'kitchen-ring', job: 'area' },
      { meter: 'Garage Plug', name: 'Garage', watts: null },
    ]),
  )
  await page.goto('/energy')

  const table = page.getByTestId('league-table')
  await expect(table).toBeVisible()
  // Straight below the dials.
  const dials = await page.getByTestId('energy-dials').boundingBox()
  const box = await table.boundingBox()
  expect(box!.y).toBeGreaterThan(dials!.y + dials!.height - 1)

  await expect(rows(page).getByTestId('league-name')).toHaveText(['Shower', 'Oven', 'Fridge', 'Everything else', 'Kettle', 'Lamp', 'Garage'])
  await expect(rows(page).getByTestId('league-watts')).toHaveText(['9.50 kW', '1.20 kW', '90 W', '210 W', 'Off', 'Off', 'No reading'])
  await expect(rows(page).getByTestId('league-share')).toHaveText(['86%', '11%', '1%', '2%', '', '', ''])

  // The bar is as long as its share.
  const shower = rows(page).nth(0)
  await expect(shower.getByTestId('league-bar')).toHaveAttribute('style', /width: 86\.\d+%/)
  await expect(rows(page).nth(2).getByTestId('league-bar')).toHaveAttribute('style', /width: 0\.8\d*%/)

  // Off and no reading sit at the bottom; no reading is greyed.
  await expect(rows(page).nth(4)).toHaveAttribute('data-state', 'off')
  await expect(rows(page).nth(6)).toHaveAttribute('data-state', 'no-reading')
  await expect(rows(page).nth(6)).toHaveClass(/opacity-50/)
})

// BDD: League rows swap places as readings arrive
test('league rows slide past each other, count to new figures, flash their share and swap at most every 2 s', async ({ page }) => {
  await page.clock.install()
  const kitchen = (oven: number, kettle: number) =>
    live(5000, [
      { meter: 'Oven Clamp', name: 'Oven', watts: oven, job: 'monitored' },
      { meter: 'Kettle Plug', name: 'Kettle', watts: kettle, job: 'monitored' },
    ])
  const ws = await serve(page, kitchen(1200, 800))
  await page.goto('/energy')
  const names = rows(page).getByTestId('league-name')
  await expect(names).toHaveText(['Oven', 'Kettle', 'Everything else'])

  ws.push(kitchen(1200, 2800))
  await expect(names).toHaveText(['Kettle', 'Oven', 'Everything else'])
  const kettle = rows(page).filter({ hasText: 'Kettle' })
  // It slides from where it was rather than jumping: it starts drawn at its old place and eases over 500 ms.
  await expect(kettle).toHaveAttribute('data-sliding', 'true')
  await expect(kettle).toHaveAttribute('style', /transition: transform 500ms/)
  // The share flashes green as it rises, Oven's would flash red if it fell.
  await expect(kettle.getByTestId('league-share')).toHaveAttribute('data-flash', 'up')
  // Its watts count up: part way through they sit between the old and new figure.
  await page.clock.runFor(200)
  const mid = await kettle.getByTestId('league-watts').textContent()
  const midW = mid!.endsWith('kW') ? parseFloat(mid!) * 1000 : parseFloat(mid!)
  expect(midW).toBeGreaterThan(800)
  expect(midW).toBeLessThan(2800)
  await page.clock.runFor(1000)
  await expect(kettle.getByTestId('league-watts')).toHaveText('2.80 kW')
  await expect(kettle.getByTestId('league-share')).toHaveText('56%')
  await expect(kettle).toHaveAttribute('data-sliding', 'false')
  await expect(kettle.getByTestId('league-share')).not.toHaveAttribute('data-flash', 'up')

  // Oven flickers back above inside 2 s of the swap: they hold their places.
  ws.push(kitchen(2900, 2800))
  await expect(rows(page).filter({ hasText: 'Oven' }).getByTestId('league-share')).toHaveAttribute('data-flash', 'up')
  await page.clock.runFor(300)
  await expect(names).toHaveText(['Kettle', 'Oven', 'Everything else'])
  // Once 2 s have passed since the swap, they change places again without needing another reading.
  await page.clock.runFor(1000)
  await expect(names).toHaveText(['Oven', 'Kettle', 'Everything else'])
  ws.push(kitchen(2900, 1000))
  await expect(rows(page).filter({ hasText: 'Kettle' }).getByTestId('league-share')).toHaveAttribute('data-flash', 'down')
})

// BDD: Toggling the league table to Rooms ranks places instead of items
test('toggling the league table to Rooms ranks rooms, areas and devices in neither, leaves out meters with no job, and is remembered', async ({ page }) => {
  await serve(
    page,
    live(
      6000,
      [
        { meter: 'Kettle Plug', name: 'Kettle', watts: 2000, room: 'kitchen', job: 'monitored' },
        { meter: 'Toaster Plug', name: 'Toaster', watts: 800, room: 'kitchen', job: 'monitored' },
        { meter: 'Shower Clamp', name: 'Shower', watts: 2500, area: 'bathroom', job: 'area' },
        // Inside the bathroom's clamp already, so not counted again.
        { meter: 'Fan Plug', name: 'Fan', watts: 40, area: 'bathroom', job: 'monitored' },
        { meter: 'Fridge Plug', name: 'Fridge', watts: 90, job: 'monitored' },
        { meter: 'Garage Clamp', name: 'Garage Clamp', watts: 400, job: null },
      ],
      { rooms: [{ room: 'kitchen', name: 'Kitchen', watts: 2800 }], areas: [{ area: 'bathroom', name: 'Bathroom', watts: 2500 }] },
    ),
  )
  await page.goto('/energy')
  const table = page.getByTestId('league-table')
  const items = table.getByRole('button', { name: 'Items' })
  const placesBtn = table.getByRole('button', { name: 'Rooms' })
  await expect(items).toHaveAttribute('aria-pressed', 'true')
  await expect(rows(page).getByTestId('league-name')).toContainText(['Shower', 'Kettle', 'Toaster'])

  await placesBtn.click()
  await expect(placesBtn).toHaveAttribute('aria-pressed', 'true')
  // The garage clamp has no job, so it is in no place: its 400 W sits in everything else.
  await expect(rows(page).getByTestId('league-name')).toHaveText(['Kitchen', 'Bathroom', 'Not in a room', 'Everything else'])
  await expect(rows(page).getByTestId('league-watts')).toHaveText(['2.80 kW', '2.50 kW', '90 W', '610 W'])
  await expect(rows(page).getByTestId('league-share')).toHaveText(['47%', '42%', '2%', '10%'])

  // Remembered on this device.
  await page.reload()
  await expect(page.getByTestId('league-table').getByRole('button', { name: 'Rooms' })).toHaveAttribute('aria-pressed', 'true')
  await expect(rows(page).getByTestId('league-name')).toHaveText(['Kitchen', 'Bathroom', 'Not in a room', 'Everything else'])
})

// BDD: League row for a meter with no job opens its settings
test('a league row for a meter with no job says so, and opens its settings for an admin to name it and give it a job', async ({ page }) => {
  const garage = { meter: 'Garage Clamp', name: 'Garage Clamp', watts: 400, job: null }
  const meter = { name: 'Garage Clamp', online: true, energy: 12, power: 400, stale: false, lastReading: null, inUse: false, monitored: false, reportsEnergy: true }
  const calls: { method: string; path: string; body: unknown }[] = []
  await serve(page, live(3000, [garage, { meter: 'Fridge Plug', name: 'Fridge', watts: 90, job: 'monitored' }]), {
    extra: (route, pathname) => {
      const req = route.request()
      if (pathname === '/api/meters') return route.fulfill({ json: [meter] })
      if (pathname === '/api/energy/areas') return route.fulfill({ json: [] })
      if (pathname === '/api/rooms') return route.fulfill({ json: [{ id: 'garage', name: 'Garage', control: 'grouped', valves: [], zones: [] }] })
      if (req.method() === 'PUT' && pathname.startsWith('/api/meters/')) {
        calls.push({ method: req.method(), path: pathname, body: req.postDataJSON() })
        return route.fulfill({ json: meter })
      }
    },
  })
  await page.goto('/energy')
  const row = rows(page).filter({ hasText: 'Garage Clamp' })
  await expect(row.getByTestId('league-note')).toHaveText('No job yet')
  // Other rows have no note.
  await expect(rows(page).filter({ hasText: 'Fridge' }).getByTestId('league-note')).toHaveCount(0)

  await row.getByRole('button', { name: 'Set up Garage Clamp' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('What does Garage Clamp measure?')
  await dialog.getByLabel('Name').fill('Garage')
  await dialog.getByRole('radio', { name: /Monitor one device/ }).check()
  await dialog.getByLabel('Room or area', { exact: true }).selectOption('garage')
  await dialog.getByRole('button', { name: 'Save' }).click()
  await expect(dialog).toBeHidden()
  expect(calls).toEqual([
    { method: 'PUT', path: '/api/meters/Garage%20Clamp/label', body: { label: 'Garage' } },
    { method: 'PUT', path: '/api/meters/Garage%20Clamp/job', body: { job: 'monitored', room: 'garage' } },
  ])
})

test('members with energy access see a meter with no job but cannot change it', async ({ page }) => {
  const member = { id: 'u-sam', username: 'sam', admin: false, energy: true, rooms: [] as string[] }
  await serve(page, live(3000, [{ meter: 'Garage Clamp', name: 'Garage Clamp', watts: 400, job: null }]), { me: member })
  await page.goto('/energy')
  const row = rows(page).filter({ hasText: 'Garage Clamp' })
  await expect(row.getByTestId('league-note')).toHaveText('No job yet')
  await expect(row.getByRole('button')).toHaveCount(0)
})

// BDD: League table greys out when readings stop
test('when the house reading stops the league keeps its last order, greyed, with no shares and says so', async ({ page }) => {
  const items = [
    { meter: 'Oven Clamp', name: 'Oven', watts: 1200, job: 'monitored' },
    { meter: 'Kettle Plug', name: 'Kettle', watts: 2800, job: 'monitored' },
  ]
  const ws = await serve(page, live(5000, items))
  await page.goto('/energy')
  const table = page.getByTestId('league-table')
  await expect(rows(page).getByTestId('league-name')).toHaveText(['Kettle', 'Oven', 'Everything else'])
  await expect(table.getByText('Readings stopped')).toHaveCount(0)

  // The house reading goes stale; the plugs' last figures now say the oven leads, but the order holds.
  ws.push(live(null, [{ ...items[0], watts: 3000 }, { ...items[1], watts: 100 }]))
  await expect(table.getByText('Readings stopped')).toBeVisible()
  await expect(rows(page).getByTestId('league-name')).toHaveText(['Kettle', 'Oven', 'Everything else'])
  await expect(rows(page).getByTestId('league-share')).toHaveText(['—', '—', '—'])
  for (const r of await rows(page).all()) await expect(r).toHaveAttribute('data-stale', 'true')

  // Readings come back: colour, shares and ranking return.
  ws.push(live(5000, [{ ...items[0], watts: 3000 }, { ...items[1], watts: 100 }]))
  await expect(table.getByText('Readings stopped')).toHaveCount(0)
  await expect(rows(page).getByTestId('league-name')).toHaveText(['Oven', 'Kettle', 'Everything else'])
  await expect(rows(page).first()).toHaveAttribute('data-stale', 'false')
})

test('with no meters but the house meter the league table says to add a plug or clamp', async ({ page }) => {
  await serve(page, live(800, []))
  await page.goto('/energy')
  const table = page.getByTestId('league-table')
  await expect(table).toContainText('Add a plug or clamp to see what is using power')
  await expect(rows(page)).toHaveCount(0)
})
