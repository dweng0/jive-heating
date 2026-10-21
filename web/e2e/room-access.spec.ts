import { expect, test, type Page, type WebSocketRoute } from '@playwright/test'

// Room access, against a mocked service: every /api call and /ws is answered here, so these
// tests do not depend on the real service or on the order of heating.spec.ts.

interface MockUser { id: string; username: string; admin: boolean; rooms: string[] }

const zone = (id: string) => ({
  id,
  valves: [`${id} TRV`],
  mode: 'schedule',
  manualTarget: 20,
  target: 20,
  current: 19.5,
  heating: false,
  callingForHeat: false,
})

const room = (id: string, name: string) => ({
  id,
  name,
  control: 'grouped',
  mode: 'schedule',
  current: 19.5,
  target: 20,
  heating: false,
  valves: [`${id} TRV`],
  zones: [zone(id)],
  notices: [],
  smartTemperatureControl: true,
  sensor: null,
  temperatureSource: 'valves',
})

const ROOMS = [room('lounge', 'Lounge'), room('bedroom', 'Bedroom')]
const ALL = ROOMS.map((r) => r.id)
const day = { blocks: [{ start: '00:00', target: 16 }, { start: '07:00', target: 20 }] }
const WEEK = Object.fromEntries(['monday', 'tuesday', 'wednesday', 'thursday', 'friday', 'saturday', 'sunday'].map((d) => [d, day]))
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

/** Paths a member's app must never call: the service answers them 403 for members. */
const ADMIN_ONLY = /^\/api\/(house|groups|dashboard|status|valves|sensors)(\/|$)/

/**
 * History as the service gives it to [me]: an hour of points for each room they can see; the
 * whole-house line and the boiler only for admins.
 */
function historyFor(me: MockUser) {
  const to = Date.now()
  const from = to - 24 * 3600_000
  const points = [3, 2, 1].map((h) => ({ at: new Date(to - h * 3600_000).toISOString(), current: 19.5, target: 20 }))
  return {
    from: new Date(from).toISOString(),
    to: new Date(to).toISOString(),
    rooms: ROOMS.filter((r) => me.rooms.includes(r.id)).map((r) => ({ id: r.id, name: r.name, points })),
    house: me.admin ? points.map(({ at, current }) => ({ at, current })) : [],
    boiler: me.admin ? [{ state: 'heating', from: points[0].at, to: points[1].at }] : [],
  }
}

/**
 * Serves the room-access contract for [me]: the user view carries `rooms`, GET /api/rooms only
 * returns rooms [me] can see, members get 403 from the admin-only paths, and
 * PUT /api/admin/users/{id}/rooms replaces a member's rooms. Returns what was called, and
 * `live` to send events down the app's /ws.
 */
async function serve(page: Page, me: MockUser, others: MockUser[] = []) {
  const users = [me, ...others]
  const forbidden: string[] = []
  const roomPuts: { id: string; rooms: string[] }[] = []
  const sockets: WebSocketRoute[] = []
  await page.routeWebSocket('**/ws', (ws) => {
    sockets.push(ws)
  })
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    const method = request.method()
    if (!me.admin && ADMIN_ONLY.test(pathname)) {
      forbidden.push(`${method} ${pathname}`)
      return route.fulfill({ status: 403, json: { error: 'admins only' } })
    }
    if (pathname === '/api/auth/me') return route.fulfill({ json: me })
    if (pathname === '/api/rooms') return route.fulfill({ json: ROOMS.filter((r) => me.rooms.includes(r.id)) })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/groups') return route.fulfill({ json: [] })
    if (pathname === '/api/valves' || pathname === '/api/sensors') return route.fulfill({ json: [] })
    if (pathname === '/api/history') return route.fulfill({ json: historyFor(me) })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (/^\/api\/zones\/[^/]+\/schedule$/.test(pathname)) return route.fulfill({ json: WEEK })
    if (pathname === '/api/admin/users') return route.fulfill({ json: users })
    const put = pathname.match(/^\/api\/admin\/users\/([^/]+)\/rooms$/)
    if (put && method === 'PUT') {
      const user = users.find((u) => u.id === decodeURIComponent(put[1]))!
      if (user.admin) return route.fulfill({ status: 409, json: { error: 'admins see every room' } })
      user.rooms = (request.postDataJSON() as { rooms: string[] }).rooms
      roomPuts.push({ id: user.id, rooms: user.rooms })
      return route.fulfill({ json: user })
    }
    return route.fulfill({ status: 404, json: { error: `not mocked: ${method} ${pathname}` } })
  })
  const live = (event: object) => sockets.forEach((ws) => ws.send(JSON.stringify(event)))
  return { forbidden, roomPuts, live }
}

const jane: MockUser = { id: 'u-jane', username: 'jane', admin: true, rooms: ALL }

// BDD: An admin ticks each member's rooms on the people screen
test("an admin ticks each member's rooms on the people screen", async ({ page }) => {
  const sam: MockUser = { id: 'u-sam', username: 'sam', admin: false, rooms: ['bedroom'] }
  const { roomPuts } = await serve(page, { ...jane }, [sam])
  await page.goto('/people')

  // Admins see every room: no tick boxes.
  await expect(page.getByTestId('person-u-jane')).toContainText('Sees every room')
  await page.getByRole('button', { name: 'Rooms for jane' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('Sees every room')
  await expect(dialog.getByRole('checkbox')).toHaveCount(0)
  await page.keyboard.press('Escape')

  // A member: every room listed, ticked for the rooms they can see.
  await expect(page.getByTestId('person-u-sam')).toContainText('Sees 1 room')
  await page.getByRole('button', { name: 'Rooms for sam' }).click()
  await expect(dialog.getByRole('checkbox')).toHaveCount(2)
  await expect(dialog.getByRole('checkbox', { name: 'Bedroom' })).toBeChecked()
  await expect(dialog.getByRole('checkbox', { name: 'Lounge' })).not.toBeChecked()

  // Each change saves straight away, with the full new list.
  await dialog.getByRole('checkbox', { name: 'Lounge' }).click()
  await expect.poll(() => roomPuts.at(-1)).toEqual({ id: 'u-sam', rooms: ['lounge', 'bedroom'] })
  await expect(dialog.getByRole('checkbox', { name: 'Lounge' })).toBeChecked()
  await dialog.getByRole('checkbox', { name: 'Bedroom' }).click()
  await expect.poll(() => roomPuts.at(-1)).toEqual({ id: 'u-sam', rooms: ['lounge'] })
  await expect(dialog.getByRole('checkbox', { name: 'Bedroom' })).not.toBeChecked()
  expect(roomPuts).toHaveLength(2)
  await page.keyboard.press('Escape')
  await expect(page.getByTestId('person-u-sam')).toContainText('Sees 1 room')
})

// BDD: A member's app shows only their rooms
test("a member's app shows only their rooms", async ({ page }) => {
  const sam: MockUser = { id: 'u-sam', username: 'sam', admin: false, rooms: ['bedroom'] }
  const { forbidden } = await serve(page, sam)
  await page.goto('/')

  await expect(page.getByTestId('room-tile')).toHaveCount(1)
  await expect(page.getByTestId('room-tile')).toContainText('Bedroom')
  await expect(page.getByRole('heading', { name: 'Whole house' })).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'Groups' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'Dashboard' })).toHaveCount(0)
  await expect(page.getByRole('link', { name: 'People' })).toHaveCount(0)
  await expect(page.getByTestId('boiler-chip')).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Holiday' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Add room' })).toHaveCount(0)

  // The room keeps its controls (mode, target, boost, schedule) but not its setup.
  await page.getByRole('link', { name: 'Bedroom' }).click()
  await expect(page.getByRole('heading', { name: 'Bedroom' })).toBeVisible()
  await expect(page.getByRole('tab', { name: 'Manual' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Warmer' }).first()).toBeVisible()
  await expect(page.getByRole('button', { name: '1 h' })).toBeVisible()
  await expect(page.getByText('Each block holds its temperature until the next one starts.')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Rename room' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Add valve' })).toHaveCount(0)
  await expect(page.getByRole('button', { name: /Add sensor|Change sensor/ })).toHaveCount(0)
  await expect(page.getByRole('button', { name: 'Delete' })).toHaveCount(0)
  // Their room's history, without the boiler (that is the whole house).
  await expect(page.getByRole('heading', { name: 'History' })).toBeVisible()
  await expect(page.locator('.temperature-line')).toHaveCount(1)
  await expect(page.getByText('Boiler heating')).toHaveCount(0)

  // Pages a member cannot use send them back to their rooms.
  await page.goto('/dashboard')
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByTestId('room-tile')).toHaveCount(1)
  expect(forbidden).toEqual([])
})

// BDD: Live updates only carry rooms a member can see
test('a member sees rooms given or taken away straight away', async ({ page }) => {
  const sam: MockUser = { id: 'u-sam', username: 'sam', admin: false, rooms: ['bedroom'] }
  const { forbidden, live } = await serve(page, sam)
  await page.goto('/')
  await expect(page.getByTestId('room-tile')).toHaveCount(1)

  // An admin gives sam the Lounge: the service tells sam's app, which fetches sam and the rooms again.
  sam.rooms = ['lounge', 'bedroom']
  live({ type: 'access.updated' })
  await expect(page.getByTestId('room-tile')).toHaveCount(2)
  await expect(page.getByTestId('room-tile').first()).toContainText('Lounge')

  // And takes the Bedroom away.
  sam.rooms = ['lounge']
  live({ type: 'access.updated' })
  await expect(page.getByTestId('room-tile')).toHaveCount(1)
  await expect(page.getByTestId('room-tile')).toContainText('Lounge')
  expect(forbidden).toEqual([])
})

test('a member with no rooms is told to ask an admin', async ({ page }) => {
  const { forbidden } = await serve(page, { id: 'u-kit', username: 'kit', admin: false, rooms: [] })
  await page.goto('/')
  await expect(page.getByText('No rooms yet. Ask an admin to give you access.')).toBeVisible()
  await expect(page.getByRole('button', { name: /Add (your first )?room/ })).toHaveCount(0)
  expect(forbidden).toEqual([])
})
