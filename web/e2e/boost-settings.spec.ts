import { expect, test, type Page } from '@playwright/test'

// The household's default boost temperature, against a mocked service: every /api call and /ws is
// answered here, so these tests do not depend on the real service.

interface MockUser { id: string; username: string; admin: boolean; rooms: string[] }

const jane: MockUser = { id: 'u-jane', username: 'jane', admin: true, rooms: ['lounge', 'bedroom'] }
const sam: MockUser = { id: 'u-sam', username: 'sam', admin: false, rooms: ['lounge'] }

function room(id: string, name: string, target: number) {
  return {
    id,
    name,
    control: 'grouped',
    mode: 'manual',
    current: 17.5,
    target,
    heating: false,
    valves: [`${name} TRV`],
    zones: [{ id, valves: [`${name} TRV`], mode: 'manual', manualTarget: target, target, current: 17.5, heating: false, callingForHeat: false }],
    notices: [],
    smartTemperatureControl: true,
    sensor: null,
    temperatureSource: 'valves',
  }
}

const ROOMS = [room('lounge', 'Lounge', 18), room('bedroom', 'Bedroom', 18)]
const GROUP = { id: 'upstairs', name: 'Upstairs', rooms: ['lounge', 'bedroom'], current: 17.5, target: 18, mode: 'manual', heating: false, heatingRooms: [] }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

/** Serves [me] with a default boost temperature of [defaultTarget]; returns every boost and settings change sent. */
async function serve(page: Page, me: MockUser, defaultTarget: number) {
  const boosts: { path: string; body: unknown }[] = []
  const puts: unknown[] = []
  await page.routeWebSocket('**/ws', () => {})
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    const method = request.method()
    if (pathname === '/api/auth/me') return route.fulfill({ json: me })
    if (pathname === '/api/settings/boost' && method === 'PUT') {
      if (!me.admin) return route.fulfill({ status: 403, json: { error: 'admins only' } })
      const body = request.postDataJSON() as { defaultTarget: number }
      puts.push(body)
      defaultTarget = body.defaultTarget
      return route.fulfill({ json: { defaultTarget } })
    }
    if (pathname === '/api/settings/boost') return route.fulfill({ json: { defaultTarget } })
    if (pathname === '/api/rooms') return route.fulfill({ json: ROOMS })
    if (pathname.startsWith('/api/rooms/')) return route.fulfill({ json: ROOMS.find((r) => pathname.endsWith(r.id)) })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/groups') return route.fulfill({ json: [GROUP] })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/history') return route.fulfill({ json: { from: new Date().toISOString(), to: new Date().toISOString(), rooms: [], house: [], boiler: [] } })
    if (pathname === '/api/activity') return route.fulfill({ json: { events: [], next: null } })
    if (method === 'POST' && /boost$|\/api\/house$|\/control$/.test(pathname)) {
      boosts.push({ path: pathname, body: request.postDataJSON() })
      if (pathname.startsWith('/api/zones/')) return route.fulfill({ json: ROOMS[0] })
      if (pathname.startsWith('/api/groups/')) return route.fulfill({ json: GROUP })
      return route.fulfill({ json: ROOMS })
    }
    return route.fulfill({ status: 404, json: { error: `not mocked: ${method} ${pathname}` } })
  })
  return { boosts, puts }
}

// BDD: The apps start a boost at the household default
test('the apps start a boost at the household default', async ({ page }) => {
  const { boosts } = await serve(page, jane, 20)

  // Whole house: the default itself.
  await page.goto('/')
  await expect(page.getByText('Boost all to 20.0°')).toBeVisible()
  await page.getByRole('button', { name: '1 h' }).first().click()
  await expect.poll(() => boosts.at(-1)).toEqual({ path: '/api/house', body: { boost: 60, target: 20 } })

  // A room: the higher of the default and the room's target.
  await page.goto('/rooms/lounge')
  await expect(page.getByText('Boost to 20.0°')).toBeVisible()
  await page.getByRole('button', { name: '1 h' }).click()
  await expect.poll(() => boosts.at(-1)).toEqual({ path: '/api/zones/lounge/boost', body: { minutes: 60, target: 20 } })

  // A group: the picker starts at the default and can still be changed before boosting.
  await page.goto('/groups/upstairs')
  await expect(page.getByText('20.0°', { exact: true })).toBeVisible()
  await page.getByRole('button', { name: 'Warmer boost' }).click()
  await expect(page.getByText('20.5°', { exact: true })).toBeVisible()
})

// BDD: An admin sets the default boost temperature in the app
test('an admin sets the default boost temperature on the account page', async ({ page }) => {
  const { puts } = await serve(page, jane, 22)
  await page.goto('/account')

  const card = page.getByTestId('boost-settings')
  await expect(card.getByRole('heading', { name: 'Boost' })).toBeVisible()
  await expect(card).toContainText('Default boost temperature 22.0°C')

  await card.getByRole('button', { name: 'Edit' }).click()
  await card.getByRole('button', { name: 'Cooler default' }).click()
  await card.getByRole('button', { name: 'Cooler default' }).click()
  await card.getByRole('button', { name: 'Cooler default' }).click()
  await card.getByRole('button', { name: 'Save' }).click()

  await expect.poll(() => puts.at(-1)).toEqual({ defaultTarget: 20.5 })
  await expect(card).toContainText('Default boost temperature 20.5°C')

  // New boosts start there straight away.
  await page.goto('/')
  await expect(page.getByText('Boost all to 20.5°')).toBeVisible()
})

test('members do not see the boost section', async ({ page }) => {
  await serve(page, sam, 22)
  await page.goto('/account')
  await expect(page.getByRole('heading', { name: 'Account' }).or(page.getByText('Member')).first()).toBeVisible()
  await expect(page.getByTestId('boost-settings')).toHaveCount(0)
})
