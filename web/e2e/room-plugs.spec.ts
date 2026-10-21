import { expect, test, type Page } from '@playwright/test'

// The Plugs section of a room's settings, against a mocked service: every /api call and /ws is
// answered here, so these tests do not depend on the real service.

test.use({ timezoneId: 'Europe/London' })

interface MockUser { id: string; username: string; admin: boolean; rooms: string[] }

const jane: MockUser = { id: 'u-jane', username: 'jane', admin: true, rooms: [] }
const sam: MockUser = { id: 'u-sam', username: 'sam', admin: false, rooms: ['kitchen'] }

const KITCHEN = {
  id: 'kitchen',
  name: 'Kitchen',
  control: 'grouped',
  mode: 'schedule',
  current: 19.5,
  target: 20,
  heating: false,
  valves: [],
  zones: [],
  notices: [],
  smartTemperatureControl: true,
  sensor: null,
  temperatureSource: 'valves',
}
const LOUNGE = { ...KITCHEN, id: 'lounge', name: 'Lounge' }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

/** Paths a member's app must never call: the service answers them 403 for members. */
const ADMIN_ONLY = /^\/api\/(house|groups|dashboard|status|valves|sensors|meters|settings\/energy|rooms\/[^/]+\/(meters|plugs))(\/|$|\?)/

const meter = (name: string, power: number | null) => ({ name, online: true, energy: 10, power, stale: false, lastReading: '2026-10-12T15:55:00+01:00', inUse: false, monitored: true })

/** Each plug's power now and use today: the Fridge Plug was offline 14:20 to 15:00. */
const PLUGS: Record<string, object> = {
  'Fridge Plug': {
    meter: 'Fridge Plug', power: 85, kwh: 0.4, cost: 10.8, missingMinutes: 40, incomplete: true,
    offline: [{ from: '2026-10-12T14:20:00+01:00', to: '2026-10-12T15:00:00+01:00' }],
  },
  'Kettle Plug': { meter: 'Kettle Plug', power: 0, kwh: 4.23, cost: 114.2, missingMinutes: 0, incomplete: false, offline: [] },
  'Lamp Plug': { meter: 'Lamp Plug', power: 12, kwh: 0.1, cost: 2.7, missingMinutes: 0, incomplete: false, offline: [] },
}

/** The Fridge Plug as it is while offline now: no power, offline since 14:20, so its cost is only so far. */
const FRIDGE_OFFLINE_NOW = {
  meter: 'Fridge Plug', power: null, kwh: 0.4, cost: 10.8, missingMinutes: 0, incomplete: true, offline: [],
  offlineSince: '2026-10-12T14:20:00+01:00',
}

/** The Kettle Plug while its total is stuck: using 2 kW but its energy total has not moved since 15:10. */
const KETTLE_TOTAL_STUCK = { ...(PLUGS['Kettle Plug'] as object), power: 2000, incomplete: true, totalStuckSince: '2026-10-12T15:10:00+01:00' }

async function serve(page: Page, me: MockUser, plugsByName: Record<string, object> = PLUGS) {
  const forbidden: string[] = []
  const sent: string[] = []
  /** Meter name to room id; the Lamp Plug is monitored and in no room. */
  const meterRooms = new Map<string, string | null>([['Fridge Plug', 'kitchen'], ['Kettle Plug', 'kitchen'], ['Lamp Plug', null]])
  /** Room id to its hourly bars: 0.1 kWh an hour, the 14:00 hour incomplete while a plug had no reading. */
  const power: Record<string, { at: string; kwh: number | null; incomplete: boolean }[]> = {
    kitchen: [
      { at: '2026-10-12T13:00:00+01:00', kwh: 0.1, incomplete: false },
      { at: '2026-10-12T14:00:00+01:00', kwh: 0.087, incomplete: true },
      { at: '2026-10-12T15:00:00+01:00', kwh: 0.1, incomplete: false },
    ],
  }
  await page.routeWebSocket('**/ws', () => {})
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    const method = request.method()
    if (!me.admin && ADMIN_ONLY.test(pathname)) {
      forbidden.push(`${method} ${pathname}`)
      return route.fulfill({ status: 403, json: { error: 'admins only' } })
    }
    if (pathname === '/api/auth/me') return route.fulfill({ json: me })
    if (pathname === '/api/rooms') return route.fulfill({ json: me.admin ? [KITCHEN, LOUNGE] : [KITCHEN] })
    if (pathname === '/api/rooms/kitchen') return route.fulfill({ json: KITCHEN })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/groups') return route.fulfill({ json: [] })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/history') return route.fulfill({ json: { from: STATUS.serverTime, to: STATUS.serverTime, rooms: [], house: [], boiler: [] } })
    if (pathname === '/api/valves' || pathname === '/api/sensors') return route.fulfill({ json: [] })
    if (pathname === '/api/meters') {
      return route.fulfill({ json: Object.keys(PLUGS).map((name) => ({ ...meter(name, 0), room: meterRooms.get(name) ?? null })) })
    }
    const history = pathname.match(/^\/api\/rooms\/([^/]+)\/plugs\/history$/)
    if (history) {
      const range = new URL(request.url()).searchParams.get('range') ?? '24h'
      sent.push(`GET history ${history[1]} ${range}`)
      return route.fulfill({ json: { room: history[1], from: '2026-10-11T16:00:00+01:00', to: '2026-10-12T16:00:00+01:00', points: power[history[1]] ?? [] } })
    }
    const plugs = pathname.match(/^\/api\/rooms\/([^/]+)\/plugs$/)
    if (plugs) {
      const inRoom = Object.keys(plugsByName).filter((name) => meterRooms.get(name) === plugs[1]).map((name) => plugsByName[name] as { kwh: number; cost: number; missingMinutes: number; incomplete: boolean })
      const missingMinutes = Math.max(0, ...inRoom.map((p) => p.missingMinutes))
      return route.fulfill({
        json: {
          room: plugs[1],
          currency: 'GBP',
          kwh: inRoom.reduce((a, p) => a + p.kwh, 0),
          cost: inRoom.reduce((a, p) => a + p.cost, 0),
          missingMinutes,
          incomplete: inRoom.some((p) => p.incomplete),
          plugs: inRoom,
        },
      })
    }
    const inRoom = pathname.match(/^\/api\/rooms\/([^/]+)\/meters\/([^/]+)$/)
    if (inRoom) {
      const name = decodeURIComponent(inRoom[2])
      sent.push(`${method} ${decodeURIComponent(pathname)}`)
      if (method === 'PUT') {
        meterRooms.set(name, inRoom[1])
        return route.fulfill({ json: { ...meter(name, 0), room: inRoom[1] } })
      }
      meterRooms.set(name, null)
      return route.fulfill({ status: 204 })
    }
    return route.fulfill({ status: 404, json: { error: `not mocked: ${method} ${pathname}` } })
  })
  return { forbidden, sent, power }
}

// BDD: A plug is put in a room from the room's settings
test("a plug is put in a room from the room's settings", async ({ page }) => {
  const { sent } = await serve(page, jane)
  await page.goto('/rooms/kitchen')

  const section = page.getByTestId('room-plugs')
  await expect(section.getByText('Plugs', { exact: true })).toBeVisible()
  // The room's cost today above its plugs, with the same triangle saying which plug was affected.
  const total = section.getByTestId('room-plugs-total')
  await expect(total).toContainText('Today £1.25')
  await expect(total.getByRole('button', { name: 'Cost incomplete' })).toHaveAccessibleDescription(
    'Fridge Plug was offline 14:20 to 15:00. Some use could not be counted.',
  )

  const fridge = section.getByTestId('room-plug-Fridge Plug')
  await expect(fridge).toContainText('85 W now · £0.11 today')
  // Read by a screen reader without a tap; a tap shows the same message.
  const warning = fridge.getByRole('button', { name: 'Cost incomplete' })
  await expect(warning).toHaveAccessibleDescription('Was offline 14:20 to 15:00. Some use could not be counted.')
  await warning.click()
  await expect(page.getByRole('dialog').or(page.locator('[data-slot=popover-content]'))).toContainText('Was offline 14:20 to 15:00. Some use could not be counted.')
  await page.keyboard.press('Escape')

  const kettle = section.getByTestId('room-plug-Kettle Plug')
  await expect(kettle).toContainText('0 W now · £1.14 today')
  await expect(kettle.getByRole('button', { name: 'Cost incomplete' })).toHaveCount(0)

  // "Add plug" offers monitored plugs not in this room.
  await section.getByRole('button', { name: 'Add plug' }).click()
  const picker = page.getByRole('dialog')
  await expect(picker.getByRole('button')).toHaveText([/Lamp Plug/, /Close/])
  await picker.getByRole('button', { name: /Lamp Plug/ }).click()
  await expect.poll(() => sent.at(-1)).toBe('PUT /api/rooms/kitchen/meters/Lamp Plug')
  await expect(section.getByTestId('room-plug-Lamp Plug')).toContainText('12 W now · £0.03 today')

  // And taken out again.
  await section.getByRole('button', { name: 'Take Kettle Plug out of Kitchen' }).click()
  await expect.poll(() => sent.at(-1)).toBe('DELETE /api/rooms/kitchen/meters/Kettle Plug')
  await expect(section.getByTestId('room-plug-Kettle Plug')).toHaveCount(0)
})

// BDD: A plug that is offline now says so
test("a plug that is offline now says so in the room's settings", async ({ page }) => {
  await serve(page, jane, { ...PLUGS, 'Fridge Plug': FRIDGE_OFFLINE_NOW })
  await page.goto('/rooms/kitchen')

  const fridge = page.getByTestId('room-plug-Fridge Plug')
  // Never its last power.
  await expect(fridge).toContainText('Offline · £0.11 today')
  await expect(fridge).not.toContainText(' W ')
  const warning = fridge.getByRole('button', { name: 'Cost incomplete' })
  await expect(warning).toHaveAccessibleDescription("Offline since 14:20. Cost will update when it's back.")
  await warning.click()
  await expect(page.locator('[data-slot=popover-content]')).toContainText("Offline since 14:20. Cost will update when it's back.")
  await page.keyboard.press('Escape')
  // The room's total says which plug is offline.
  await expect(page.getByTestId('room-plugs-total').getByRole('button', { name: 'Cost incomplete' })).toHaveAccessibleDescription(
    'Fridge Plug offline since 14:20. Some use could not be counted.',
  )
})

// BDD: A plug whose energy total stops going up is flagged
test("a plug whose energy total stops going up is flagged in the room's settings", async ({ page }) => {
  const plugs = { ...PLUGS, 'Kettle Plug': KETTLE_TOTAL_STUCK }
  await serve(page, jane, plugs)
  await page.goto('/rooms/kitchen')

  const stuck = "Using power but its energy total isn't going up. Cost may be too low."
  const kettle = page.getByTestId('room-plug-Kettle Plug')
  // Its power is real, so it is still shown.
  await expect(kettle).toContainText('2.0 kW now')
  const warning = kettle.getByRole('button', { name: 'Cost incomplete' })
  await expect(warning).toHaveAccessibleDescription(stuck)
  await warning.click()
  await expect(page.locator('[data-slot=popover-content]')).toContainText(stuck)
  await page.keyboard.press('Escape')
  await expect(page.getByTestId('room-plugs-total').getByRole('button', { name: 'Cost incomplete' })).toHaveAccessibleDescription(
    /Kettle Plug using power but its energy total isn't going up/,
  )

  // The total goes up again: the triangle goes.
  plugs['Kettle Plug'] = PLUGS['Kettle Plug']
  await page.reload()
  await expect(kettle).toContainText('£1.14 today')
  await expect(kettle.getByRole('button', { name: 'Cost incomplete' })).toHaveCount(0)
})

// BDD: A room's electricity is charted over time
test("a room's electricity is charted over time on its page", async ({ page }) => {
  const { sent, power } = await serve(page, jane)
  await page.goto('/rooms/kitchen')

  const card = page.getByTestId('room-electricity')
  await expect(card.getByRole('heading', { name: 'Electricity' })).toBeVisible()
  await expect(card.getByRole('img', { name: 'Electricity used by the room’s plugs over the last 24 hours, one bar per hour' })).toBeVisible()
  expect(sent).toContain('GET history kitchen 24h')
  // An incomplete hour is faded, and the key says why.
  await expect(card).toContainText('Some readings missing')

  // The same range picker as the heating history.
  await card.getByRole('button', { name: '7 days' }).click()
  await expect(card.getByRole('img', { name: 'Electricity used by the room’s plugs over the last 7 days, one bar per day' })).toBeVisible()
  await expect.poll(() => sent).toContain('GET history kitchen 7d')

  // No plug use in the range: no card.
  power.kitchen = []
  await page.reload()
  await expect(page.getByTestId('room-plugs')).toBeVisible()
  await expect(page.getByTestId('room-electricity')).toHaveCount(0)
})

// BDD: A plug is put in a room from the room's settings
test("members do not see a room's plugs", async ({ page }) => {
  const { forbidden } = await serve(page, sam)
  await page.goto('/rooms/kitchen')
  await expect(page.getByRole('heading', { name: 'Kitchen' })).toBeVisible()
  await expect(page.getByTestId('room-plugs')).toHaveCount(0)
  expect(forbidden.filter((f) => f.includes('/plugs'))).toEqual([])
})
