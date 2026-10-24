import { expect, test, type Page } from '@playwright/test'

// Energy prices, electricity meters and the energy dashboard, against a mocked service: every
// /api call and /ws is answered here, so these tests do not depend on the real service.

interface MockUser { id: string; username: string; admin: boolean; rooms: string[] }

const jane: MockUser = { id: 'u-jane', username: 'jane', admin: true, rooms: ['lounge'] }
const sam: MockUser = { id: 'u-sam', username: 'sam', admin: false, rooms: ['lounge'] }

const LOUNGE = {
  id: 'lounge',
  name: 'Lounge',
  control: 'grouped',
  mode: 'schedule',
  current: 19.5,
  target: 20,
  heating: false,
  valves: ['Lounge TRV'],
  zones: [{ id: 'lounge', valves: ['Lounge TRV'], mode: 'schedule', manualTarget: 20, target: 20, current: 19.5, heating: false, callingForHeat: false }],
  notices: [],
  smartTemperatureControl: true,
  sensor: null,
  temperatureSource: 'valves',
}
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

/** Paths a member's app must never call: the service answers them 403 for members. */
const ADMIN_ONLY = /^\/api\/(house|groups|dashboard|status|valves|sensors|meters|settings\/energy|rooms\/[^/]+\/meters)(\/|$|\?)/

function history() {
  const to = Date.now()
  const points = [3, 2, 1].map((h) => ({ at: new Date(to - h * 3600_000).toISOString(), current: 19.5, target: 20 }))
  return {
    from: new Date(to - 24 * 3600_000).toISOString(),
    to: new Date(to).toISOString(),
    rooms: [{ id: 'lounge', name: 'Lounge', points }],
    house: points.map(({ at, current }) => ({ at, current })),
    boiler: [],
  }
}

function day(date: string, electricity: boolean, incomplete = false) {
  return {
    date,
    boilerHours: 3,
    gasKwh: 36,
    gasCost: 396,
    electricityKwh: electricity ? 8 : null,
    electricityCost: electricity ? 216 : null,
    standingCost: electricity ? 80 : 30,
    totalCost: electricity ? 692 : 426,
    missingMinutes: incomplete ? 120 : 0,
    incomplete,
  }
}

/** What each monitored plug used: the Garage Plug was stale for ten minutes. */
const DEVICES: Record<string, object> = {
  'Fridge Plug': { meter: 'Fridge Plug', kwh: 1.2, cost: 32.4, missingMinutes: 0, incomplete: false },
  'Garage Plug': { meter: 'Garage Plug', kwh: 0.6, cost: 16.2, missingMinutes: 10, incomplete: true },
  'Lamp Plug': { meter: 'Lamp Plug', kwh: 0.3, cost: 8.1, missingMinutes: 15, incomplete: true },
  'Charger Plug': { meter: 'Charger Plug', kwh: 2.0, cost: 54.0, missingMinutes: 0, incomplete: false },
  // Offline since 14:20, so its cost is only so far.
  'TV Plug': { meter: 'TV Plug', kwh: 0.5, cost: 13.5, missingMinutes: 0, incomplete: true, offlineSince: '2026-10-12T14:20:00+01:00' },
  // Using power, but its energy total has not moved since 15:10.
  'Heater Plug': { meter: 'Heater Plug', kwh: 0.2, cost: 5.4, missingMinutes: 0, incomplete: true, totalStuckSince: '2026-10-12T15:10:00+01:00' },
}

const KITCHEN = { ...LOUNGE, id: 'kitchen', name: 'Kitchen', valves: [], zones: [] }

/** Each room holding a monitored plug, by name: the sum of its plugs, like the service. */
function roomEnergy(devices: { meter: string; kwh: number; cost: number; missingMinutes: number; incomplete: boolean; room: string | null }[], rooms: { id: string; name: string }[]) {
  return rooms
    .filter((r) => devices.some((d) => d.room === r.id))
    .sort((a, b) => a.name.localeCompare(b.name))
    .map((r) => {
      const plugs = devices.filter((d) => d.room === r.id)
      const missingMinutes = Math.max(0, ...plugs.map((d) => d.missingMinutes))
      return { room: r.id, name: r.name, kwh: plugs.reduce((a, d) => a + d.kwh, 0), cost: plugs.reduce((a, d) => a + d.cost, 0), missingMinutes, incomplete: missingMinutes > 0 }
    })
}

/** The energy dashboard for [period]: 3 boiler hours and 8.0 kWh a day; yesterday is incomplete. */
function dashboard(period: string, meter: string | null, currency = 'GBP', monitored: string[] = [], meterRooms = new Map<string, string>(), rooms: { id: string; name: string }[] = []) {
  const n = period === 'month' ? 30 : period === 'week' ? 7 : 1
  const days = Array.from({ length: n }, (_, i) => {
    const date = new Date(Date.UTC(2026, 9, 12 - (n - 1 - i))).toISOString().slice(0, 10)
    return day(date, meter != null, n > 1 && i === n - 2)
  })
  const electricity = meter != null
  const devices = [...monitored].sort().map((name) => ({
    ...((DEVICES[name] ?? { meter: name, kwh: 0, cost: 0, missingMinutes: 0, incomplete: false }) as { meter: string; kwh: number; cost: number; missingMinutes: number; incomplete: boolean }),
    room: meterRooms.get(name) ?? null,
  }))
  return {
    period,
    from: `${days[0].date}T00:00:00+01:00`,
    to: '2026-10-12T12:00:00+01:00',
    boilerHours: 3 * n,
    gasKwh: 36 * n,
    gasCost: 396 * n,
    electricityMeter: meter,
    electricityKwh: electricity ? 8 * n : null,
    electricityCost: electricity ? 216 * n : null,
    gasStandingCost: 30 * n,
    electricityStandingCost: electricity ? 50 * n : null,
    standingCost: (electricity ? 80 : 30) * n,
    totalCost: (electricity ? 692 : 426) * n,
    missingMinutes: n > 1 ? 120 : 0,
    incomplete: n > 1,
    days,
    currency,
    devices,
    rooms: roomEnergy(devices, rooms),
    everythingElse: electricity ? everythingElse(8 * n, 216 * n, n > 1 ? 120 : 0, devices) : null,
  }
}

/** The house meter's use no monitored plug accounts for, never below 0, like the service. */
function everythingElse(kwh: number, cost: number, houseMissing: number, devices: { kwh: number; cost: number; missingMinutes: number }[]) {
  const missingMinutes = Math.max(houseMissing, ...devices.map((d) => d.missingMinutes))
  return {
    kwh: Math.max(0, kwh - devices.reduce((a, d) => a + d.kwh, 0)),
    cost: Math.max(0, cost - devices.reduce((a, d) => a + d.cost, 0)),
    missingMinutes,
    incomplete: missingMinutes > 0,
  }
}

/** Serves [me] with the given energy settings and meters; returns what was sent and refused. */
async function serve(page: Page, me: MockUser, meters: object[] = [], rooms: object[] = [LOUNGE]) {
  const forbidden: string[] = []
  const puts: { path: string; body: unknown }[] = []
  const dashboards: string[] = []
  const ignored = new Set<string>()
  const monitored = new Set<string>()
  /** Meter name to room id. */
  const meterRooms = new Map<string, string>()
  /** Meters that fail to be put in any room. */
  const unplaceable = new Set<string>()
  const settings = {
    gasPencePerKwh: 11.0,
    gasStandingPencePerDay: 30.0,
    boilerAverageKw: 12.0,
    electricityPencePerKwh: 27.0,
    electricityStandingPencePerDay: 50.0,
    electricityMeter: null as string | null,
    currency: 'GBP',
    notices: [] as string[],
  }
  await page.routeWebSocket('**/ws', () => {})
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url
    const method = request.method()
    if (!me.admin && ADMIN_ONLY.test(pathname)) {
      forbidden.push(`${method} ${pathname}`)
      return route.fulfill({ status: 403, json: { error: 'admins only' } })
    }
    if (pathname === '/api/auth/me') return route.fulfill({ json: me })
    if (pathname === '/api/rooms') return route.fulfill({ json: rooms })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/groups') return route.fulfill({ json: [] })
    if (pathname === '/api/history') return route.fulfill({ json: history() })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/valves') return route.fulfill({ json: [{ name: 'Spare TRV', room: null, online: true }].filter((v) => !ignored.has(v.name)) })
    if (pathname === '/api/sensors') {
      const sensors = [{ name: 'Hall Smoke', room: null, online: true, temperature: 20, battery: 90, lowBattery: false }]
      return route.fulfill({ json: sensors.filter((s) => !ignored.has(s.name)) })
    }
    if (pathname === '/api/devices/ignored') return route.fulfill({ json: [...ignored].sort() })
    const ignore = pathname.match(/^\/api\/devices\/([^/]+)\/ignored$/)
    if (ignore && method === 'PUT') {
      puts.push({ path: decodeURIComponent(pathname), body: null })
      ignored.add(decodeURIComponent(ignore[1]))
      return route.fulfill({ status: 204 })
    }
    if (ignore && method === 'DELETE') {
      puts.push({ path: `DELETE ${decodeURIComponent(pathname)}`, body: null })
      ignored.delete(decodeURIComponent(ignore[1]))
      return route.fulfill({ status: 204 })
    }
    if (pathname === '/api/settings/energy' && method === 'GET') return route.fulfill({ json: settings })
    if (pathname === '/api/settings/energy' && method === 'PUT') {
      const body = request.postDataJSON() as Partial<typeof settings>
      puts.push({ path: pathname, body })
      Object.assign(settings, body)
      return route.fulfill({ json: settings })
    }
    if (pathname === '/api/meters') {
      const all = meters
        .filter((m) => !ignored.has((m as { name: string }).name))
        .map((m) => ({ ...m, inUse: (m as { name: string }).name === settings.electricityMeter, monitored: monitored.has((m as { name: string }).name), room: meterRooms.get((m as { name: string }).name) ?? null }))
      return route.fulfill({ json: url.searchParams.get('unassigned') === 'true' ? all.filter((m) => !m.inUse && !m.monitored) : all })
    }
    const monitor = pathname.match(/^\/api\/meters\/([^/]+)\/monitored$/)
    if (monitor && method === 'DELETE') {
      puts.push({ path: `DELETE ${decodeURIComponent(pathname)}`, body: null })
      monitored.delete(decodeURIComponent(monitor[1]))
      meterRooms.delete(decodeURIComponent(monitor[1]))
      return route.fulfill({ status: 204 })
    }
    if (monitor && method === 'PUT') {
      puts.push({ path: decodeURIComponent(pathname), body: null })
      monitored.add(decodeURIComponent(monitor[1]))
      return route.fulfill({ json: { ...meters.find((m) => (m as { name: string }).name === decodeURIComponent(monitor[1])), inUse: false, monitored: true } })
    }
    const inRoom = pathname.match(/^\/api\/rooms\/([^/]+)\/meters\/([^/]+)$/)
    if (inRoom && method === 'PUT') {
      puts.push({ path: decodeURIComponent(pathname), body: null })
      const name = decodeURIComponent(inRoom[2])
      if (unplaceable.has(name)) return route.fulfill({ status: 500, json: { error: 'The store is not answering' } })
      if (!monitored.has(name)) return route.fulfill({ status: 409, json: { error: 'only a monitored device can be put in a room' } })
      meterRooms.set(name, inRoom[1])
      return route.fulfill({ json: { ...meters.find((m) => (m as { name: string }).name === name), inUse: false, monitored: true, room: inRoom[1] } })
    }
    if (inRoom && method === 'DELETE') {
      puts.push({ path: `DELETE ${decodeURIComponent(pathname)}`, body: null })
      meterRooms.delete(decodeURIComponent(inRoom[2]))
      return route.fulfill({ status: 204 })
    }
    const house = pathname.match(/^\/api\/meters\/([^/]+)\/house$/)
    if (house && method === 'PUT') {
      puts.push({ path: decodeURIComponent(pathname), body: null })
      settings.electricityMeter = decodeURIComponent(house[1])
      return route.fulfill({ json: settings })
    }
    if (pathname === '/api/dashboard') {
      const period = url.searchParams.get('period') ?? 'today'
      dashboards.push(period)
      return route.fulfill({ json: dashboard(period, settings.electricityMeter, settings.currency, [...monitored], meterRooms, rooms as { id: string; name: string }[]) })
    }
    return route.fulfill({ status: 404, json: { error: `not mocked: ${method} ${pathname}` } })
  })
  return { forbidden, puts, dashboards, settings, monitored, meterRooms, unplaceable }
}

// BDD: An admin edits prices in the app
test('an admin edits energy prices on the account page', async ({ page }) => {
  const { puts } = await serve(page, { ...jane })
  await page.goto('/account')

  const card = page.getByTestId('energy-prices')
  await expect(card.getByRole('heading', { name: 'Energy prices' })).toBeVisible()
  await expect(card).toContainText('11.0p per kWh')
  await expect(card).toContainText('30.0p per day')
  await expect(card).toContainText('27.0p per kWh')
  await expect(card).toContainText('50.0p per day')
  await expect(card).toContainText('12.0 kW')

  await card.getByRole('button', { name: 'Edit prices' }).click()
  await card.getByLabel('Electricity', { exact: true }).fill('24.5')
  await card.getByRole('button', { name: 'Save' }).click()

  await expect.poll(() => puts.at(-1)).toEqual({
    path: '/api/settings/energy',
    body: { gasPencePerKwh: 11, gasStandingPencePerDay: 30, electricityPencePerKwh: 24.5, electricityStandingPencePerDay: 50, boilerAverageKw: 12 },
  })
  await expect(card).toContainText('24.5p per kWh')
  await expect(card.getByRole('button', { name: 'Edit prices' })).toBeVisible()
})

// BDD: An admin chooses the household's currency
test("an admin chooses the household's currency", async ({ page }) => {
  const { puts, settings } = await serve(page, { ...jane })
  await page.goto('/account')

  const card = page.getByTestId('energy-prices')
  await expect(card).toContainText('Currency: £ Pound (GBP)')
  await card.getByRole('button', { name: 'Edit prices' }).click()
  const currency = card.getByLabel('Currency')
  await expect(currency.getByRole('option')).toHaveText(['£ Pound (GBP)', '€ Euro (EUR)', '$ US dollar (USD)'])
  const warning = 'Prices are not converted. Check them for the new currency.'
  await expect(card).not.toContainText(warning)
  await currency.selectOption({ label: '€ Euro (EUR)' })
  await expect(card).toContainText(warning)
  await expect(card).toContainText('c per kWh')
  await card.getByRole('button', { name: 'Save' }).click()

  await expect.poll(() => (puts.at(-1)?.body as { currency?: string } | undefined)?.currency).toBe('EUR')
  await expect(card).toContainText('27.0c per kWh')
  await expect(card).toContainText('Currency: € Euro (EUR)')

  // Another currency set elsewhere is offered too, by its code.
  settings.currency = 'SEK'
  await page.reload()
  await card.getByRole('button', { name: 'Edit prices' }).click()
  await expect(card.getByLabel('Currency').getByRole('option')).toHaveText(['£ Pound (GBP)', '€ Euro (EUR)', '$ US dollar (USD)', 'SEK'])
})

test('members do not see energy prices', async ({ page }) => {
  const { forbidden } = await serve(page, { ...sam })
  await page.goto('/account')
  await expect(page.getByRole('heading', { name: 'sam' })).toBeVisible()
  await expect(page.getByTestId('energy-prices')).toHaveCount(0)
  expect(forbidden).toEqual([])
})

const CLAMP = { name: 'Main Clamp', online: true, energy: 1523.4, power: 412, stale: false, lastReading: '2026-10-12T10:00:00+01:00' }

// BDD: New meters are offered in the app like new valves and sensors
test('new meters are offered with the new valves and sensors', async ({ page }) => {
  const { puts } = await serve(page, { ...jane }, [CLAMP])
  await page.goto('/')

  const waiting = page.getByTestId('waiting-devices')
  await expect(waiting.getByRole('heading', { name: 'Waiting to be set up' })).toBeVisible()
  await expect(waiting).toContainText('Spare TRV')
  await expect(waiting).toContainText('Hall Smoke')
  const clamp = waiting.getByTestId('meter-Main Clamp')
  await expect(clamp).toContainText('Main Clamp')
  await expect(clamp).toContainText('412 W')

  await clamp.getByRole('button', { name: 'Main meter (whole house)' }).click()
  await expect.poll(() => puts.at(-1)?.path).toBe('/api/meters/Main Clamp/house')
  await expect(waiting.getByTestId('meter-Main Clamp')).toHaveCount(0)
})

// BDD: An admin monitors a plug
test('an admin monitors a plug from the waiting devices', async ({ page }) => {
  const FRIDGE = { ...CLAMP, name: 'Fridge Plug', energy: 12.3, power: 85 }
  const { puts } = await serve(page, { ...jane }, [CLAMP, FRIDGE])
  await page.goto('/')

  const waiting = page.getByTestId('waiting-devices')
  const fridge = waiting.getByTestId('meter-Fridge Plug')
  await fridge.getByRole('button', { name: 'Monitor this device' }).click()
  await expect.poll(() => puts.at(-1)?.path).toBe('/api/meters/Fridge Plug/monitored')
  await expect(waiting.getByTestId('meter-Fridge Plug')).toHaveCount(0)
  await expect(waiting.getByTestId('meter-Main Clamp')).toBeVisible()
})

// BDD: A plug is put in a room straight after monitoring it
test('a plug is put in a room straight after monitoring it', async ({ page }) => {
  const KITCHEN = { ...LOUNGE, id: 'kitchen', name: 'Kitchen' }
  const plug = (name: string) => ({ ...CLAMP, name, energy: 3.1, power: 0 })
  const { puts, monitored, meterRooms, unplaceable } = await serve(
    page,
    { ...jane },
    [plug('Kettle Plug'), plug('Fridge Plug'), plug('Lamp Plug')],
    [LOUNGE, KITCHEN],
  )
  await page.goto('/')
  const waiting = page.getByTestId('waiting-devices')
  const picker = page.getByRole('dialog', { name: 'Which room is Kettle Plug in?' })

  await waiting.getByTestId('meter-Kettle Plug').getByRole('button', { name: 'Monitor this device' }).click()
  await expect(picker.getByRole('button', { name: 'Lounge' })).toBeVisible()
  await expect(picker.getByRole('button', { name: 'Kitchen' })).toBeVisible()
  await expect(picker.getByRole('button', { name: 'Not now' })).toBeVisible()
  await picker.getByRole('button', { name: 'Kitchen' }).click()
  await expect.poll(() => puts.slice(-2).map((p) => p.path)).toEqual(['/api/meters/Kettle Plug/monitored', '/api/rooms/kitchen/meters/Kettle Plug'])
  await expect(picker).toHaveCount(0)
  // In a room, so it lives in the room's settings, not on the Rooms screen.
  await expect(page.getByTestId('monitored-Kettle Plug')).toHaveCount(0)

  // Not now: monitored, in no room.
  await waiting.getByTestId('meter-Fridge Plug').getByRole('button', { name: 'Monitor this device' }).click()
  await page.getByRole('dialog', { name: 'Which room is Fridge Plug in?' }).getByRole('button', { name: 'Not now' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  expect(puts.at(-1)?.path).toBe('/api/meters/Fridge Plug/monitored')
  expect(monitored.has('Fridge Plug')).toBe(true)
  expect(meterRooms.has('Fridge Plug')).toBe(false)
  await expect(page.getByTestId('monitored-devices').getByLabel('Room for Fridge Plug')).toHaveValue('')

  // Putting it in the room fails: still monitored, the error is shown.
  unplaceable.add('Lamp Plug')
  await waiting.getByTestId('meter-Lamp Plug').getByRole('button', { name: 'Monitor this device' }).click()
  await page.getByRole('dialog', { name: 'Which room is Lamp Plug in?' }).getByRole('button', { name: 'Lounge' }).click()
  await expect(page.getByText('The store is not answering')).toBeVisible()
  expect(puts.at(-1)?.path).toBe('/api/rooms/lounge/meters/Lamp Plug')
  expect(monitored.has('Lamp Plug')).toBe(true)
  await expect(page.getByTestId('monitored-devices').getByLabel('Room for Lamp Plug')).toHaveValue('')
})

// BDD: An admin puts a monitored plug in a room
test('an admin puts a monitored plug in a room', async ({ page }) => {
  const KITCHEN = { ...LOUNGE, id: 'kitchen', name: 'Kitchen' }
  const FRIDGE = { ...CLAMP, name: 'Fridge Plug', energy: 12.3, power: 85 }
  const KETTLE = { ...CLAMP, name: 'Kettle Plug', energy: 3.1, power: 0 }
  const { puts, monitored, meterRooms } = await serve(page, { ...jane }, [CLAMP, FRIDGE, KETTLE], [LOUNGE, KITCHEN])
  monitored.add('Fridge Plug')
  monitored.add('Kettle Plug')
  await page.goto('/')

  const card = page.getByTestId('monitored-devices')
  await expect(card.getByLabel('Room for Fridge Plug')).toHaveValue('')
  await card.getByLabel('Room for Fridge Plug').selectOption({ label: 'Kitchen' })
  await expect.poll(() => puts.at(-1)?.path).toBe('/api/rooms/kitchen/meters/Fridge Plug')

  // A room holds any number of plugs.
  await card.getByLabel('Room for Kettle Plug').selectOption({ label: 'Kitchen' })
  await expect.poll(() => puts.at(-1)?.path).toBe('/api/rooms/kitchen/meters/Kettle Plug')
  expect([meterRooms.get('Fridge Plug'), meterRooms.get('Kettle Plug')]).toEqual(['kitchen', 'kitchen'])
})

// BDD: Only plugs in no room are listed on the Rooms screen
test('only plugs in no room are listed on the rooms screen', async ({ page }) => {
  const KITCHEN = { ...LOUNGE, id: 'kitchen', name: 'Kitchen' }
  const FRIDGE = { ...CLAMP, name: 'Fridge Plug', energy: 12.3, power: 85 }
  const GARAGE = { ...CLAMP, name: 'Garage Plug', energy: 3.1, power: 0 }
  const { monitored, meterRooms } = await serve(page, { ...jane }, [CLAMP, FRIDGE, GARAGE], [LOUNGE, KITCHEN])
  monitored.add('Fridge Plug').add('Garage Plug')
  meterRooms.set('Fridge Plug', 'kitchen')
  await page.goto('/')

  const card = page.getByTestId('monitored-devices')
  await expect(card.getByTestId('monitored-Garage Plug').getByLabel('Room for Garage Plug')).toHaveValue('')
  await expect(card.getByTestId('monitored-Fridge Plug')).toHaveCount(0)

  // Choosing a room takes it off the card; with every plug in a room the card is not shown.
  await card.getByLabel('Room for Garage Plug').selectOption({ label: 'Lounge' })
  await expect(page.getByTestId('monitored-devices')).toHaveCount(0)

  // Taken out of its room in the room's settings: listed again.
  meterRooms.delete('Fridge Plug')
  await page.reload()
  await expect(page.getByTestId('monitored-devices').getByLabel('Room for Fridge Plug')).toHaveValue('')
})

// BDD: An admin stops monitoring a plug from the Rooms screen
test('an admin stops monitoring a plug from the rooms screen', async ({ page }) => {
  const GARAGE = { ...CLAMP, name: 'Garage Plug', energy: 3.1, power: 0 }
  const { puts, monitored } = await serve(page, { ...jane }, [CLAMP, GARAGE], [LOUNGE])
  monitored.add('Garage Plug')
  await page.goto('/')

  // A visible button on the row, not a choice in the room menu.
  await expect(page.getByTestId('monitored-devices').getByLabel('Room for Garage Plug').locator('option', { hasText: 'Stop monitoring' })).toHaveCount(0)
  await page.getByTestId('monitored-Garage Plug').getByRole('button', { name: 'Disconnect' }).click()
  await expect.poll(() => puts.slice(-2).map((p) => p.path)).toEqual(['DELETE /api/meters/Garage Plug/monitored', '/api/devices/Garage Plug/ignored'])
  // Off the Rooms screen, and not waiting to be set up.
  await expect(page.getByTestId('monitored-devices')).toHaveCount(0)
  await expect(page.getByTestId('meter-Garage Plug')).toHaveCount(0)
  // It can be brought back from the ignored devices.
  await page.goto('/account')
  await expect(page.getByTestId('ignored-devices')).toContainText('Garage Plug')
})

// BDD: The main meter is labelled as measuring the whole house
test('the main meter is labelled as measuring the whole house', async ({ page }) => {
  await serve(page, { ...jane }, [CLAMP])
  await page.goto('/')

  const clamp = page.getByTestId('waiting-devices').getByTestId('meter-Main Clamp')
  await expect(clamp.getByRole('button', { name: 'Main meter (whole house)' })).toBeVisible()
  await expect(clamp).toContainText('Measures all electricity coming in')
  await expect(clamp.getByRole('button', { name: 'Use for the house' })).toHaveCount(0)
})

// BDD: An admin ignores a device that needs no setting up
test('an admin ignores a device that needs no setting up', async ({ page }) => {
  const { puts } = await serve(page, { ...jane }, [CLAMP, { ...CLAMP, name: 'Garage Plug' }])
  await page.goto('/')

  const waiting = page.getByTestId('waiting-devices')
  await expect(waiting.getByTestId('meter-Garage Plug')).toBeVisible()
  await waiting.getByRole('button', { name: 'Ignore Garage Plug' }).click()
  await expect.poll(() => puts.at(-1)?.path).toBe('/api/devices/Garage Plug/ignored')
  await expect(waiting.getByTestId('meter-Garage Plug')).toHaveCount(0)
  await expect(waiting.getByTestId('meter-Main Clamp')).toBeVisible()

  await waiting.getByRole('button', { name: 'Ignore Spare TRV' }).click()
  await expect(waiting).not.toContainText('Spare TRV')

  // Ignored devices stay off the list when the page is reloaded.
  await page.reload()
  await expect(page.getByTestId('waiting-devices')).not.toContainText('Garage Plug')
})

// BDD: Ignored devices can be brought back
test('ignored devices can be brought back', async ({ page }) => {
  const { puts } = await serve(page, { ...jane }, [CLAMP, { ...CLAMP, name: 'Garage Plug' }])
  await page.goto('/')
  await page.getByTestId('waiting-devices').getByRole('button', { name: 'Ignore Garage Plug' }).click()
  await expect(page.getByTestId('meter-Garage Plug')).toHaveCount(0)

  await page.goto('/account')
  const ignored = page.getByTestId('ignored-devices')
  await expect(ignored.getByRole('heading', { name: 'Ignored devices' }).or(ignored)).toContainText('Garage Plug')
  await ignored.getByRole('button', { name: 'Show Garage Plug again' }).click()
  await expect.poll(() => puts.at(-1)?.path).toBe('DELETE /api/devices/Garage Plug/ignored')
  await expect(page.getByTestId('ignored-devices')).toHaveCount(0)

  await page.goto('/')
  await expect(page.getByTestId('meter-Garage Plug')).toBeVisible()
})

// BDD: The Energy tab shows energy use and cost
test('the energy tab shows use and cost under the live dial', async ({ page }) => {
  const { dashboards, settings } = await serve(page, { ...jane }, [CLAMP])
  settings.electricityMeter = 'Main Clamp'
  await page.goto('/energy')

  const energy = page.getByTestId('energy')
  await expect(energy.getByRole('heading', { name: 'Use and cost' })).toBeVisible()
  // Below the live dial.
  const dial = await page.getByTestId('live-power').boundingBox()
  const section = await energy.boundingBox()
  expect(section!.y).toBeGreaterThan(dial!.y)

  // Today: gas, electricity, standing charges and the total.
  await expect(energy.getByTestId('energy-gas')).toContainText('£3.96')
  await expect(energy.getByTestId('energy-gas')).toContainText('36.0 kWh')
  await expect(energy.getByTestId('energy-electricity')).toContainText('£2.16')
  await expect(energy.getByTestId('energy-electricity')).toContainText('8.0 kWh')
  await expect(energy.getByTestId('energy-standing')).toContainText('£0.80')
  await expect(energy.getByTestId('energy-total')).toContainText('£6.92')
  await expect(energy.getByTestId('energy-day')).toHaveCount(0)

  // Week and month: a bar per day, incomplete days marked.
  await energy.getByRole('button', { name: 'Week' }).click()
  await expect(energy.getByTestId('energy-day')).toHaveCount(7)
  await expect(energy.getByTestId('energy-total')).toContainText('£48.44')
  await expect(energy.locator('[data-testid=energy-day][data-incomplete=true]')).toHaveCount(1)
  await expect(energy).toContainText('Incomplete')
  await energy.getByRole('button', { name: 'Month' }).click()
  await expect(energy.getByTestId('energy-day')).toHaveCount(30)
  // The live power dial asks for today's figures too.
  expect(dashboards.filter((p) => p !== 'today')).toEqual(['week', 'month'])
  expect(dashboards).toContain('today')
})

// BDD: The Energy tab shows each monitored device
test('the dashboard shows each monitored device under electricity', async ({ page }) => {
  const { settings, monitored } = await serve(page, { ...jane }, [CLAMP])
  settings.electricityMeter = 'Main Clamp'
  monitored.add('Fridge Plug').add('Garage Plug')
  await page.goto('/energy')

  const energy = page.getByTestId('energy')
  const devices = energy.getByTestId('energy-devices')
  await expect(devices).toContainText('of which')
  const fridge = devices.getByTestId('device-Fridge Plug')
  await expect(fridge).toContainText('1.2 kWh')
  await expect(fridge).toContainText('£0.32')
  await expect(fridge.getByTestId('incomplete-warning')).toHaveCount(0)
  // Stale for a while: the cost so far, with the warning triangle.
  const garage = devices.getByTestId('device-Garage Plug')
  await expect(garage).toContainText('£0.16')
  await expect(garage.getByTestId('incomplete-warning')).toBeVisible()
  await expect(garage.getByTestId('incomplete-warning')).toHaveAttribute('title', 'Incomplete: 10 min with no reading, so this is the cost so far')
  // Plugs are part of the house meter, never added to the total.
  await expect(energy.getByTestId('energy-electricity')).toContainText('£2.16')
  await expect(energy.getByTestId('energy-total')).toContainText('£6.92')
})

// BDD: A plug that is offline now says so
test('a plug that is offline now says so on the dashboard', async ({ page }) => {
  const { settings, monitored } = await serve(page, { ...jane }, [CLAMP])
  settings.electricityMeter = 'Main Clamp'
  monitored.add('Fridge Plug').add('TV Plug')
  await page.goto('/energy')

  const devices = page.getByTestId('energy-devices')
  const tv = devices.getByTestId('device-TV Plug')
  await expect(tv).toContainText('Offline')
  await expect(tv).toContainText('£0.14')
  const warning = tv.getByRole('button', { name: 'Cost incomplete' })
  await expect(warning).toHaveAccessibleDescription("Offline since 14:20. Cost will update when it's back.")
  await warning.click()
  await expect(page.locator('[data-slot=popover-content]')).toContainText("Offline since 14:20. Cost will update when it's back.")
  await expect(devices.getByTestId('device-Fridge Plug')).not.toContainText('Offline')
})

// BDD: A plug whose energy total stops going up is flagged
test('a plug whose energy total stops going up is flagged on the dashboard', async ({ page }) => {
  const { settings, monitored } = await serve(page, { ...jane }, [CLAMP])
  settings.electricityMeter = 'Main Clamp'
  monitored.add('Fridge Plug').add('Heater Plug')
  await page.goto('/energy')

  const stuck = "Using power but its energy total isn't going up. Cost may be too low."
  const devices = page.getByTestId('energy-devices')
  const heater = devices.getByTestId('device-Heater Plug')
  await expect(heater).toContainText('£0.05')
  await expect(heater).not.toContainText('Offline')
  const warning = heater.getByRole('button', { name: 'Cost incomplete' })
  await expect(warning).toHaveAccessibleDescription(stuck)
  await warning.click()
  await expect(page.locator('[data-slot=popover-content]')).toContainText(stuck)
  await expect(devices.getByTestId('device-Fridge Plug').getByTestId('incomplete-warning')).toHaveCount(0)
})

// BDD: The Energy tab shows each room's electricity
test("the dashboard shows each room's electricity under electricity", async ({ page }) => {
  const { settings, monitored, meterRooms } = await serve(page, { ...jane }, [CLAMP], [LOUNGE, KITCHEN])
  settings.electricityMeter = 'Main Clamp'
  monitored.add('Fridge Plug').add('Lamp Plug').add('Garage Plug').add('Charger Plug')
  meterRooms.set('Fridge Plug', 'kitchen').set('Lamp Plug', 'lounge')
  await page.goto('/energy')

  const energy = page.getByTestId('energy')
  const rooms = energy.getByTestId('energy-rooms')
  await expect(rooms).toContainText('of which')
  // Rooms by name, then plugs in no room, then everything else last.
  await expect(rooms.getByTestId(/^energy-room-/)).toHaveText([/^Kitchen/, /^Lounge/, /^Not in a room/, /^Everything else/])
  const kitchen = rooms.getByTestId('energy-room-kitchen')
  await expect(kitchen).toContainText('1.2 kWh')
  await expect(kitchen).toContainText('£0.32')
  await expect(kitchen.getByTestId('incomplete-warning')).toHaveCount(0)
  // A stale plug: the room's cost so far, with the same warning triangle as a plug.
  const lounge = rooms.getByTestId('energy-room-lounge')
  await expect(lounge).toContainText('£0.08')
  await expect(lounge.getByTestId('incomplete-warning')).toHaveAttribute('title', 'Incomplete: 15 min with no reading, so this is the cost so far')
  // The Garage Plug and Charger Plug are in no room: grouped together, and named.
  const none = rooms.getByTestId('energy-room-none')
  await expect(none).toContainText('Charger Plug, Garage Plug')
  await expect(none).toContainText('2.6 kWh')
  await expect(none).toContainText('£0.70')
  await expect(none.getByTestId('incomplete-warning')).toBeVisible()
  // Everything else: the main meter's 8.0 kWh minus all four plugs, incomplete while any plug is.
  const rest = rooms.getByTestId('energy-room-rest')
  await expect(rest).toContainText('3.9 kWh')
  await expect(rest).toContainText('£1.05')
  await expect(rest.getByTestId('incomplete-warning')).toHaveAttribute('title', 'Incomplete: 15 min with no reading, so this is the cost so far')
  // Rooms are "of which", never added to the house figures.
  await expect(energy.getByTestId('energy-electricity')).toContainText('£2.16')
  await expect(energy.getByTestId('energy-total')).toContainText('£6.92')
  await expect(energy.getByTestId('energy-devices')).toBeVisible()

  // In money of the household's currency.
  settings.currency = 'EUR'
  await page.goto('/energy')
  await expect(page.getByTestId('energy-room-kitchen')).toContainText('€0.32')
})

test('without plugs in rooms there is no rooms card', async ({ page }) => {
  const { settings, monitored } = await serve(page, { ...jane }, [CLAMP])
  settings.electricityMeter = 'Main Clamp'
  monitored.add('Fridge Plug')
  await page.goto('/energy')
  await expect(page.getByTestId('energy-devices')).toBeVisible()
  await expect(page.getByTestId('energy-rooms')).toHaveCount(0)
})

test('members do not see rooms, devices or energy', async ({ page }) => {
  const { forbidden } = await serve(page, { ...sam }, [CLAMP])
  await page.goto('/energy')
  await expect(page.getByTestId('room-tile')).toHaveCount(1)
  await expect(page.getByTestId('energy')).toHaveCount(0)
  expect(forbidden).toEqual([])
})

test('without monitored devices there is no "of which"', async ({ page }) => {
  const { settings } = await serve(page, { ...jane }, [CLAMP])
  settings.electricityMeter = 'Main Clamp'
  await page.goto('/energy')
  await expect(page.getByTestId('energy-total')).toContainText('£6.92')
  await expect(page.getByTestId('energy-devices')).toHaveCount(0)
})

test('without a house meter the dashboard asks for one', async ({ page }) => {
  await serve(page, { ...jane })
  await page.goto('/energy')
  const energy = page.getByTestId('energy')
  await expect(energy).toContainText('Choose an electricity meter to see electricity use')
  await expect(energy.getByTestId('energy-gas')).toContainText('£3.96')
  await expect(energy.getByTestId('energy-total')).toContainText('£4.26')
})

// BDD: Money is shown in the household's currency
test("money is shown in the household's currency", async ({ page }) => {
  const { settings } = await serve(page, { ...jane }, [CLAMP])
  settings.electricityMeter = 'Main Clamp'
  await page.goto('/energy')
  const energy = page.getByTestId('energy')
  // Under a pound is still pounds and two decimals, never "80p".
  await expect(energy.getByTestId('energy-standing')).toContainText('£0.80')
  await expect(energy.getByTestId('energy-total')).toContainText('£6.92')

  settings.currency = 'EUR'
  await page.goto('/energy')
  await expect(energy.getByTestId('energy-gas')).toContainText('€3.96')
  await expect(energy.getByTestId('energy-standing')).toContainText('€0.80')

  // Prices are still entered in the minor unit: cents for euros.
  await page.goto('/account')
  await expect(page.getByTestId('energy-prices')).toContainText('27.0c per kWh')
  await expect(page.getByTestId('energy-prices')).toContainText('12.0 kW')
})

test('members are not offered new meters', async ({ page }) => {
  const { forbidden } = await serve(page, { ...sam }, [CLAMP])
  await page.goto('/')
  await expect(page.getByTestId('room-tile')).toHaveCount(1)
  await expect(page.getByTestId('waiting-devices')).toHaveCount(0)
  expect(forbidden).toEqual([])
})
