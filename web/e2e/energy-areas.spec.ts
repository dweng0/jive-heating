import { expect, test, type Page } from '@playwright/test'

// Energy areas on the web app, against a mocked service that keeps the areas, meters and choices it is given:
// every /api call, /ws and /ws/power are answered here.

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[] }
const sam = { id: 'u-sam', username: 'sam', admin: false, rooms: ['kitchen'] }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

type Area = { id: string; name: string; wholeMeter: string | null; overlaps: string[] }
type Meter = {
  name: string
  online: boolean
  energy: number | null
  power: number | null
  stale: boolean
  lastReading: string | null
  inUse: boolean
  monitored: boolean
  room: string | null
  area: string | null
  measures: string | null
  reportsEnergy: boolean
}

function meter(name: string, more: Partial<Meter> = {}): Meter {
  return { name, online: true, energy: 10, power: 50, stale: false, lastReading: new Date().toISOString(), inUse: false, monitored: false, room: null, area: null, measures: null, reportsEnergy: true, ...more }
}

const slug = (name: string) => name.toLowerCase().replace(/[^a-z0-9]+/g, '-')

type AreaDay = { kwh: number; cost: number; plugs?: { meter: string; kwh: number; cost: number }[]; rest?: number | null; restCost?: number | null }

async function serve(page: Page, opts: { me?: typeof jane; areas?: Partial<Area>[]; meters?: Meter[]; watts?: Record<string, number | null>; days?: Record<string, AreaDay> } = {}) {
  const areas: Area[] = (opts.areas ?? []).map((a) => ({ id: slug(a.name!), wholeMeter: null, overlaps: [], ...a }) as Area)
  const meters = opts.meters ?? []
  const sent: string[] = []
  const taken = (name: string, except?: string) => areas.some((a) => a.id !== except && a.name.toLowerCase() === name.toLowerCase())
  const sharedCircuits = () =>
    areas.flatMap((a) => a.overlaps.filter((o) => a.id < o).map((o) => [a.name, areas.find((b) => b.id === o)!.name]))
  const dashboard = () => ({
    period: 'today', from: new Date().toISOString(), to: new Date().toISOString(), boilerHours: 0, gasKwh: 0, gasCost: 0,
    electricityMeter: 'Main Clamp', electricityKwh: 8, electricityCost: 216, gasStandingCost: 30, electricityStandingCost: 50, standingCost: 80,
    totalCost: 296, missingMinutes: 0, incomplete: false, days: [], currency: 'GBP', devices: [],
    rooms: [{ room: 'kitchen', name: 'Kitchen', kwh: 1.2, cost: 30, missingMinutes: 0, incomplete: false }],
    everythingElse: { kwh: 5.3, cost: 143, missingMinutes: 0, incomplete: false, sharedCircuits: sharedCircuits() },
    areas: areas.map((a) => {
      const day = opts.days?.[a.id] ?? { kwh: 0, cost: 0 }
      return {
        area: a.id, name: a.name, kwh: day.kwh, cost: day.cost, missingMinutes: 0, incomplete: false, wholeMeter: a.wholeMeter,
        plugs: day.plugs ?? [], rest: day.rest ?? null, restCost: day.restCost ?? null,
        overlaps: a.overlaps.map((o) => areas.find((b) => b.id === o)!.name),
      }
    }),
  })
  const live = () => ({
    watts: 855, at: new Date().toISOString(),
    rooms: [{ room: 'kitchen', name: 'Kitchen', watts: 42 }], devices: [],
    areas: areas.map((a) => ({ area: a.id, name: a.name, watts: opts.watts?.[a.id] ?? null })),
    elseWatts: 600,
  })
  const usage = (url: URL, plugs: string[]) => {
    const to = Date.now()
    const step = 5 * 60_000
    const points = (watts: number) =>
      Array.from({ length: 12 }, (_, i) => ({ at: new Date(to - (11 - i) * step).toISOString(), watts, kwh: (watts * step) / 3.6e9, cost: (watts * step * 25) / 3.6e9, incomplete: false }))
    void url
    return { currency: 'GBP', from: new Date(to - 86_400_000).toISOString(), to: new Date(to).toISOString(), earliest: new Date(to - 3_600_000).toISOString(), room: points(100), plugs: plugs.map((meter) => ({ meter, points: points(40) })) }
  }

  await page.routeWebSocket('**/ws', () => {})
  await page.routeWebSocket('**/ws/power', (ws) => ws.send(JSON.stringify(live())))
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const method = request.method()
    const url = new URL(request.url())
    const pathname = decodeURIComponent(url.pathname)
    if (pathname === '/api/auth/me') return route.fulfill({ json: opts.me ?? jane })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/rooms') return route.fulfill({ json: [] })
    if (pathname === '/api/groups') return route.fulfill({ json: [] })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/power') return route.fulfill({ json: { bands: null, recent: [] } })
    if (pathname === '/api/dashboard') return route.fulfill({ json: dashboard() })
    if (pathname === '/api/energy/else/usage') return route.fulfill({ json: { ...usage(url, []), plugs: undefined, sharedCircuits: sharedCircuits() } })
    if (pathname === '/api/meters') {
      const unassigned = url.searchParams.get('unassigned') === 'true'
      return route.fulfill({ json: meters.filter((m) => !unassigned || (!m.inUse && !m.monitored && !m.measures)) })
    }
    if (pathname === '/api/energy/areas' && method === 'GET') return route.fulfill({ json: areas })
    if (pathname === '/api/energy/areas' && method === 'POST') {
      sent.push(`POST ${pathname}`)
      const { name } = request.postDataJSON()
      if (taken(name)) return route.fulfill({ status: 409, json: { error: `an area called ${name} already exists` } })
      const area = { id: slug(name), name, wholeMeter: null, overlaps: [] }
      areas.push(area)
      return route.fulfill({ status: 201, json: area })
    }
    const match = pathname.match(/^\/api\/energy\/areas\/([^/]+)(?:\/(whole-meter|meters|overlaps|usage)(?:\/(.+))?)?$/)
    const area = match && areas.find((a) => a.id === match[1])
    if (match && !area) return route.fulfill({ status: 404, json: { error: 'no such area' } })
    if (area && match) {
      const [, , part, rest] = match
      if (method !== 'GET') sent.push(`${method} ${pathname}`)
      if (!part && method === 'PATCH') {
        const { name } = request.postDataJSON()
        if (taken(name, area.id)) return route.fulfill({ status: 409, json: { error: `an area called ${name} already exists` } })
        area.name = name
        return route.fulfill({ json: area })
      }
      if (!part && method === 'DELETE') {
        areas.splice(areas.indexOf(area), 1)
        areas.forEach((a) => (a.overlaps = a.overlaps.filter((o) => o !== area.id)))
        meters.forEach((m) => {
          if (m.measures === area.id) m.measures = null
          if (m.area === area.id) m.area = null
        })
        return route.fulfill({ status: 204 })
      }
      if (part === 'whole-meter' && method === 'PUT') {
        const m = meters.find((x) => x.name === rest)!
        meters.forEach((x) => x.measures === area.id && (x.measures = null))
        m.measures = area.id
        area.wholeMeter = m.name
        return route.fulfill({ json: area })
      }
      if (part === 'meters' && method === 'PUT') {
        const m = meters.find((x) => x.name === rest)!
        m.area = area.id
        m.room = null
        return route.fulfill({ json: m })
      }
      if (part === 'overlaps') {
        const other = areas.find((a) => a.id === rest)!
        if (method === 'PUT') {
          area.overlaps.push(other.id)
          other.overlaps.push(area.id)
        } else {
          area.overlaps = area.overlaps.filter((o) => o !== other.id)
          other.overlaps = other.overlaps.filter((o) => o !== area.id)
        }
        return route.fulfill({ json: area })
      }
      if (part === 'usage') {
        const plugs = meters.filter((m) => m.area === area.id).map((m) => m.name)
        return route.fulfill({ json: { ...usage(url, plugs), area: area.id, name: area.name, wholeMeter: area.wholeMeter, overlaps: area.overlaps.map((o) => areas.find((b) => b.id === o)!.name) } })
      }
    }
    return route.fulfill({ status: 404, json: { error: 'not mocked' } })
  })
  return { sent, areas, meters }
}

async function addArea(page: Page, name: string) {
  await page.getByRole('button', { name: 'Add area' }).first().click()
  await page.getByLabel('Area name').fill(name)
  await page.getByRole('dialog').getByRole('button', { name: 'Add area' }).click()
}

// BDD: An admin creates an energy area
test('an admin adds an area, renames it and deletes it', async ({ page }) => {
  const { sent } = await serve(page)
  await page.goto('/energy')
  await addArea(page, 'Garage/Office')
  const areas = page.getByTestId('energy-areas')
  await expect(areas.getByText('Garage/Office')).toBeVisible()
  expect(sent).toEqual(['POST /api/energy/areas'])

  await areas.getByRole('link', { name: 'Garage/Office energy' }).click()
  await page.getByRole('button', { name: 'Rename area' }).click()
  await page.getByLabel('Area name').fill('Workshop')
  await page.getByRole('dialog').getByRole('button', { name: 'Rename' }).click()
  await expect(page.getByRole('heading', { name: 'Workshop' })).toBeVisible()
  expect(sent).toContain('PATCH /api/energy/areas/garage-office')

  await page.getByRole('button', { name: 'Delete area' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Delete' }).click()
  await expect(page).toHaveURL(/\/energy$/)
  await expect(page.getByTestId('energy-areas').getByText('Workshop')).toHaveCount(0)
  expect(sent).toContain('DELETE /api/energy/areas/garage-office')
})

// BDD: An admin creates an energy area
test('a name another area uses is refused and the dialog stays open', async ({ page }) => {
  await serve(page)
  await page.goto('/energy')
  await addArea(page, 'Lights')
  await expect(page.getByTestId('energy-areas').getByText('Lights')).toBeVisible()
  await addArea(page, 'lights')
  await expect(page.getByText('an area called lights already exists').first()).toBeVisible()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByLabel('Area name')).toHaveValue('lights')
})

// BDD: A clamp measures a whole area
test('a waiting clamp is chosen to measure an area and leaves the waiting list', async ({ page }) => {
  const { sent } = await serve(page, {
    areas: [{ name: 'Garage/Office' }],
    meters: [meter('Garage Clamp'), meter('Lights CT', { energy: null, reportsEnergy: false })],
  })
  await page.goto('/')
  const clamp = page.getByTestId('meter-Garage Clamp')
  await clamp.getByRole('button', { name: 'Measure an energy area' }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Garage/Office' }).click()
  await expect(clamp).toHaveCount(0)
  expect(sent).toEqual(['PUT /api/energy/areas/garage-office/whole-meter/Garage Clamp'])

  // Power but no energy total: it can only measure an area.
  const ct = page.getByTestId('meter-Lights CT')
  await expect(ct.getByRole('button', { name: 'Measure an energy area' })).toBeVisible()
  await expect(ct.getByRole('button', { name: 'Main meter (whole house)' })).toHaveCount(0)
  await expect(ct.getByRole('button', { name: 'Monitor this device' })).toHaveCount(0)
})

// BDD: A plug is put in an energy area
test('a monitored plug is put in an area and leaves the plugs in no room', async ({ page }) => {
  const { sent } = await serve(page, {
    areas: [{ name: 'Garage/Office' }, { name: 'Lights' }],
    meters: [meter('Desk Plug', { monitored: true }), meter('Kettle Plug', { monitored: true }), meter('Lamp Plug', { monitored: true, area: 'lights' })],
  })
  await page.goto('/')
  const card = page.getByTestId('monitored-devices')
  // Already in an area: placed, so not listed.
  await expect(card.getByTestId('monitored-Lamp Plug')).toHaveCount(0)
  await card.getByLabel('Room for Desk Plug').selectOption({ label: 'Garage/Office' })
  await expect(card.getByTestId('monitored-Desk Plug')).toHaveCount(0)
  await expect(card.getByTestId('monitored-Kettle Plug')).toBeVisible()
  expect(sent).toEqual(['PUT /api/energy/areas/garage-office/meters/Desk Plug'])
})

// BDD: The Energy tab shows areas below the rooms
test('an Other areas section sits between the room cards and everything else, with live cards', async ({ page }) => {
  await serve(page, { areas: [{ name: 'Lights' }], watts: { lights: 60 }, days: { lights: { kwh: 0.5, cost: 13.5 } } })
  await page.goto('/energy')
  const card = page.getByTestId('live-area-lights')
  await expect(page.getByRole('heading', { name: 'Other areas' })).toBeVisible()
  await expect(card.getByTestId('live-watts')).toHaveText('60 W')
  await expect(card.getByTestId('live-day')).toContainText('0.5 kWh')
  await expect(card.getByTestId('live-day')).toContainText('£0.14')

  const room = await page.getByTestId('live-room-kitchen').boundingBox()
  const area = await card.boundingBox()
  const rest = await page.getByTestId('live-rest').boundingBox()
  expect(room!.y).toBeLessThan(area!.y)
  expect(area!.y).toBeLessThan(rest!.y)
})

// BDD: The Energy tab shows areas below the rooms
test('with no areas there is only Add area, and a member sees no areas', async ({ page }) => {
  await serve(page)
  await page.goto('/energy')
  await expect(page.getByTestId('energy-areas').getByRole('button', { name: 'Add area' })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Other areas' })).toHaveCount(0)

  await page.unrouteAll({ behavior: 'ignoreErrors' })
  await serve(page, { me: sam, areas: [{ name: 'Lights' }] })
  await page.goto('/energy')
  await expect(page).not.toHaveURL(/energy/)
  await expect(page.getByText('Other areas')).toHaveCount(0)
  await expect(page.getByText('Lights')).toHaveCount(0)
})

const garage = {
  areas: [{ name: 'Garage/Office', wholeMeter: 'Garage Clamp' }, { name: 'Lights', wholeMeter: 'Lights Clamp' }],
  meters: [meter('Garage Clamp', { measures: 'garage-office' }), meter('Lights Clamp', { measures: 'lights' }), meter('Desk Plug', { monitored: true, area: 'garage-office', power: 30 })],
  watts: { 'garage-office': 1200, lights: 60 },
  days: {
    'garage-office': { kwh: 1.0, cost: 27, plugs: [{ meter: 'Desk Plug', kwh: 0.2, cost: 5.4 }], rest: 0.8, restCost: 21.6 },
    lights: { kwh: 0.5, cost: 13.5 },
  },
}

// BDD: An area card opens its energy dashboard
test('an area card opens a dashboard with the whole area first, then its plugs, and a rest row', async ({ page }) => {
  await serve(page, garage)
  await page.goto('/energy')
  await page.getByRole('link', { name: 'Garage/Office energy' }).click()
  await expect(page).toHaveURL(/\/energy\/areas\/garage-office$/)
  await expect(page.getByRole('heading', { name: 'Garage/Office' })).toBeVisible()
  await expect(page.getByTestId('usage-now')).toContainText('1.2 kW')
  await expect(page.getByTestId('usage-today')).toContainText('1.0 kWh')

  await expect(page.getByTestId('chart-title')).toHaveText('Garage/Office')
  await expect(page.getByRole('button', { name: 'Show Desk Plug' })).toBeVisible()
  await page.getByRole('button', { name: 'Next chart' }).click()
  await expect(page.getByTestId('chart-title')).toHaveText('Desk Plug')

  const table = page.getByTestId('plug-table')
  await expect(table.getByTestId('plug-row')).toHaveCount(1)
  const rest = table.getByTestId('plug-rest')
  await expect(rest).toContainText('Rest of Garage/Office')
  await expect(rest).toContainText('0.8 kWh')
  // The rest has no chart to show.
  await expect(rest.getByRole('button')).toHaveCount(0)
})

// BDD: An area card opens its energy dashboard
test('an area measured by a clamp alone has one chart and no table', async ({ page }) => {
  await serve(page, garage)
  await page.goto('/energy/areas/lights')
  await expect(page.getByTestId('chart-title')).toHaveText('Lights')
  await expect(page.getByTestId('chart-dots')).toHaveCount(0)
  await expect(page.getByTestId('plug-table')).toHaveCount(0)
})

// BDD: An area card opens its energy dashboard
test('a member sent to an area dashboard goes to the Rooms screen, and an unknown area says so', async ({ page }) => {
  await serve(page, { ...garage, me: sam })
  await page.goto('/energy/areas/garage-office')
  await expect(page).toHaveURL(/\/$/)

  await page.unrouteAll({ behavior: 'ignoreErrors' })
  await serve(page, garage)
  await page.goto('/energy/areas/nowhere')
  await expect(page.getByTestId('area-not-found')).toHaveText('Area not found')
})

// BDD: Two areas that share a circuit say so
test('choosing Shares a circuit with marks both dashboards and everything else', async ({ page }) => {
  const { sent } = await serve(page, garage)
  await page.goto('/energy/areas/lights')
  await page.getByRole('checkbox', { name: 'Garage/Office' }).click()
  await expect(page.getByTestId('area-overlaps')).toHaveText('Some use is also counted in Garage/Office')
  expect(sent).toEqual(['PUT /api/energy/areas/lights/overlaps/garage-office'])

  await page.goto('/energy/areas/garage-office')
  await expect(page.getByTestId('area-overlaps')).toHaveText('Some use is also counted in Lights')

  await page.goto('/energy/else')
  await expect(page.getByTestId('shared-circuits')).toContainText('Reads low by whatever Garage/Office and Lights both measure')

  await page.goto('/energy/areas/lights')
  await page.getByRole('checkbox', { name: 'Garage/Office' }).click()
  await expect(page.getByTestId('area-overlaps')).toHaveCount(0)
  expect(sent).toContain('DELETE /api/energy/areas/lights/overlaps/garage-office')
})

// BDD: Deleting an area keeps what was recorded
test('deleting an area asks first, says what is kept, and Cancel keeps it', async ({ page }) => {
  const { sent, meters } = await serve(page, garage)
  await page.goto('/energy/areas/garage-office')
  await page.getByRole('button', { name: 'Delete area' }).click()
  const confirm = page.getByRole('alertdialog')
  await expect(confirm).toContainText('What it recorded is kept')
  await confirm.getByRole('button', { name: 'Cancel' }).click()
  expect(sent).toEqual([])

  await page.getByRole('button', { name: 'Delete area' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Delete' }).click()
  await expect(page).toHaveURL(/\/energy$/)
  await expect(page.getByTestId('live-area-garage-office')).toHaveCount(0)
  expect(sent).toEqual(['DELETE /api/energy/areas/garage-office'])
  expect(meters.find((m) => m.name === 'Garage Clamp')!.measures).toBeNull()
})
