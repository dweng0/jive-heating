import { expect, test, type Page, type WebSocketRoute } from '@playwright/test'

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

function live(watts: number | null, items: Item[]) {
  return { watts, at: new Date().toISOString(), rooms: [], devices: [], elseWatts: null, areas: [], items }
}

async function serve(page: Page, first: object) {
  const sockets: WebSocketRoute[] = []
  await page.routeWebSocket('**/ws', () => {})
  await page.routeWebSocket('**/ws/power', (ws) => {
    sockets.push(ws)
    ws.send(JSON.stringify(first))
  })
  await page.route('**/api/**', async (route) => {
    const { pathname } = new URL(route.request().url())
    if (pathname === '/api/auth/me') return route.fulfill({ json: jane })
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
