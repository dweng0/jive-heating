import { expect, test, type Page } from '@playwright/test'

// Energy management, the daily target and who is signed in, on the web app, against a mocked service.

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[] }
const sam = { id: 'u-sam', username: 'sam', admin: false, rooms: ['kitchen'] }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

type Meter = Record<string, unknown> & { name: string; inUse: boolean; monitored: boolean; room: string | null; area: string | null; measures: string | null }

function meter(name: string, more: Partial<Meter> = {}): Meter {
  return {
    name, label: name, device: name, clamp: null, reversed: false, online: true, energy: 10, power: 50, stale: false,
    lastReading: new Date().toISOString(), inUse: false, monitored: false, room: null, area: null, measures: null, reportsEnergy: true, ...more,
  }
}

type Target = { kwh: number; usedKwh: number; expectedKwh: number; deltaKwh: number; deltaCost: number; onTarget: boolean; projectedKwh: number | null; incomplete: boolean }

async function serve(page: Page, opts: { me?: typeof jane; meters?: Meter[]; target?: Target | null; days?: unknown[]; dailyKwhTarget?: number | null } = {}) {
  const meters = opts.meters ?? []
  const areas: { id: string; name: string; wholeMeter: string | null; overlaps: string[] }[] = []
  const settings = {
    gasPencePerKwh: 11, gasStandingPencePerDay: 30, boilerAverageKw: 12, electricityPencePerKwh: 27, electricityStandingPencePerDay: 50,
    electricityMeter: meters.find((m) => m.inUse)?.name ?? null, currency: 'GBP', notices: [] as string[], dailyKwhTarget: opts.dailyKwhTarget ?? null,
  }
  const sent: string[] = []
  const sessions = [
    { id: 's-phone', device: 'Chrome on Android', signedInAt: new Date(Date.now() - 86_400_000).toISOString(), lastSeen: new Date(Date.now() - 3 * 3_600_000).toISOString(), signedOutAt: null as string | null, current: false },
    { id: 's-here', device: 'Firefox on Windows', signedInAt: new Date().toISOString(), lastSeen: new Date().toISOString(), signedOutAt: null as string | null, current: true },
  ]
  const dashboard = (period: string) => ({
    period, from: new Date().toISOString(), to: new Date().toISOString(), boilerHours: 0, gasKwh: 0, gasCost: 0,
    electricityMeter: settings.electricityMeter, electricityKwh: 5, electricityCost: 135, gasStandingCost: 30, electricityStandingCost: 50, standingCost: 80,
    totalCost: 215, missingMinutes: 0, incomplete: false, days: period === 'today' ? [] : (opts.days ?? []), currency: 'GBP', devices: [], rooms: [],
    everythingElse: null, areas: [], target: opts.target ?? null,
  })

  await page.routeWebSocket('**/ws', () => {})
  await page.routeWebSocket('**/ws/power', () => {})
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const method = request.method()
    const url = new URL(request.url())
    const path = decodeURIComponent(url.pathname)
    if (method !== 'GET') sent.push(`${method} ${path}${request.postData() ? ` ${request.postData()}` : ''}`)
    if (path === '/api/auth/me') return route.fulfill({ json: opts.me ?? jane })
    if (path === '/api/status') return route.fulfill({ json: STATUS })
    if (path === '/api/rooms') return route.fulfill({ json: [] })
    if (path === '/api/groups') return route.fulfill({ json: [] })
    if (path === '/api/app') return route.fulfill({ json: { android: null } })
    if (path === '/api/power') return route.fulfill({ json: { bands: null, recent: [] } })
    if (path === '/api/valves' || path === '/api/sensors') return route.fulfill({ json: [] })
    if (path === '/api/dashboard') return route.fulfill({ json: dashboard(url.searchParams.get('period') ?? 'today') })
    if (path === '/api/settings/energy' && method === 'GET') return route.fulfill({ json: settings })
    if (path === '/api/settings/energy' && method === 'PUT') {
      Object.assign(settings, request.postDataJSON())
      return route.fulfill({ json: settings })
    }
    if (path === '/api/meters') {
      const unassigned = url.searchParams.get('unassigned') === 'true'
      return route.fulfill({ json: meters.filter((m) => !unassigned || (!m.inUse && !m.monitored && !m.measures)) })
    }
    const house = path.match(/^\/api\/meters\/(.+)\/house$/)
    if (house) {
      meters.forEach((m) => (m.inUse = method === 'PUT' && m.name === house[1]))
      settings.electricityMeter = method === 'PUT' ? house[1] : null
      return route.fulfill({ json: settings })
    }
    const label = path.match(/^\/api\/meters\/(.+)\/label$/)
    if (label) {
      const m = meters.find((x) => x.name === label[1])!
      m.label = request.postDataJSON().label.trim() || m.name
      return route.fulfill({ json: m })
    }
    const job = path.match(/^\/api\/meters\/(.+)\/job$/)
    if (job) {
      const m = meters.find((x) => x.name === job[1])!
      const body = request.postDataJSON()
      m.inUse = body.job === 'house'
      if (m.inUse) {
        meters.forEach((x) => x !== m && (x.inUse = false))
        settings.electricityMeter = m.name
      } else if (settings.electricityMeter === m.name) settings.electricityMeter = null
      m.measures = body.job === 'area' ? body.area : null
      m.monitored = body.job === 'monitored'
      m.room = body.job === 'monitored' ? (body.room ?? null) : null
      m.area = body.job === 'monitored' ? (body.area ?? null) : null
      areas.forEach((a) => (a.wholeMeter = meters.find((x) => x.measures === a.id)?.name ?? null))
      return route.fulfill({ json: m })
    }
    if (path === '/api/energy/areas' && method === 'GET') return route.fulfill({ json: areas })
    if (path === '/api/energy/areas' && method === 'POST') {
      const { name } = request.postDataJSON()
      const area = { id: name.toLowerCase().replace(/[^a-z0-9]+/g, '-'), name, wholeMeter: null, overlaps: [] }
      areas.push(area)
      return route.fulfill({ status: 201, json: area })
    }
    if (path === '/api/admin/users') {
      return route.fulfill({
        json: [
          { ...jane, activeNow: true, lastSeen: new Date().toISOString(), sessions: 1 },
          { ...sam, activeNow: false, lastSeen: new Date(Date.now() - 3 * 3_600_000).toISOString(), sessions: sessions.filter((s) => !s.signedOutAt).length },
        ],
      })
    }
    if (path === '/api/admin/users/u-sam/sessions' && method === 'GET') return route.fulfill({ json: sessions })
    const one = path.match(/^\/api\/admin\/users\/u-sam\/sessions\/(.+)$/)
    if (one && method === 'DELETE') {
      sessions.find((s) => s.id === one[1])!.signedOutAt = new Date().toISOString()
      return route.fulfill({ status: 204 })
    }
    return route.fulfill({ status: 404, json: { error: 'not mocked' } })
  })
  return { sent, meters, settings }
}

// BDD: Admins have an energy management page
test('the Energy tab opens the management page with every section, and members cannot reach it', async ({ page }) => {
  await serve(page, { meters: [meter('CCU Clamp', { inUse: true, power: 757 }), meter('Mains and lights B', { reversed: true, power: 25, online: false })] })
  await page.goto('/energy')
  await page.getByRole('link', { name: 'Manage' }).click()
  await expect(page).toHaveURL(/\/energy\/manage$/)
  for (const title of ['Main meter', 'Daily target', 'Areas', 'Monitored devices', 'Waiting to be set up']) {
    await expect(page.getByText(title, { exact: true }).first()).toBeVisible()
  }
  await expect(page.getByTestId('manage-main').getByTestId('meter-now')).toHaveText('757 W now')
})

// BDD: Admins have an energy management page
test('a member sent to the management page goes to the Rooms screen', async ({ page }) => {
  await serve(page, { me: sam })
  await page.goto('/energy/manage')
  await expect(page).toHaveURL(/\/$/)
  await expect(page.getByRole('link', { name: 'Manage' })).toHaveCount(0)
})

// BDD: A clamp fitted the wrong way round is still counted as use
test('a clamp that reads backwards says its use is still counted', async ({ page }) => {
  await serve(page, { meters: [meter('Mains and lights B', { measures: null, monitored: true, reversed: true, clamp: 'B', device: 'Mains and lights' })] })
  await page.goto('/energy/manage')
  await expect(page.getByTestId('meter-reversed')).toHaveText('This clamp reads backwards. Its use is still counted.')
})

// BDD: An admin names a meter
test('an admin renames a clamp and the label is shown', async ({ page }) => {
  const { sent } = await serve(page, { meters: [meter('Mains and lights A', { inUse: true, clamp: 'A', device: 'Mains and lights' })] })
  await page.goto('/energy/manage')
  await page.getByRole('button', { name: 'Rename Mains and lights A' }).click()
  await page.getByLabel('Name', { exact: true }).fill('Mains')
  await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click()
  await expect(page.getByTestId('manage-main').getByText('Mains', { exact: true })).toBeVisible()
  expect(sent).toContain('PUT /api/meters/Mains and lights A/label {"label":"Mains"}')
})

// BDD: An admin changes or removes the main meter from the page
test('the main meter is changed, then stopped after a confirmation', async ({ page }) => {
  const { sent } = await serve(page, { meters: [meter('CCU Clamp', { inUse: true }), meter('Mains and lights A'), meter('Lights CT', { reportsEnergy: false })] })
  await page.goto('/energy/manage')
  await page.getByTestId('manage-main').getByRole('button', { name: 'Change', exact: true }).click()
  const dialog = page.getByRole('dialog')
  // Only meters that report energy and do nothing else are offered.
  await expect(dialog.getByRole('button', { name: /Lights CT/ })).toHaveCount(0)
  await dialog.getByRole('button', { name: /Mains and lights A/ }).click()
  await expect(page.getByTestId('manage-main').getByText('Mains and lights A')).toBeVisible()
  expect(sent).toContain('PUT /api/meters/Mains and lights A/house')

  await page.getByRole('button', { name: 'Stop using' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Stop using' }).click()
  await expect(page.getByText('No main meter chosen.')).toBeVisible()
  expect(sent).toContain('DELETE /api/meters/Mains and lights A/house')
})

// BDD: An admin sets up areas from the page
test('an area is added and given a whole-area clamp, which shows its power', async ({ page }) => {
  const { sent } = await serve(page, { meters: [meter('Mains and lights B', { power: 25.5 })] })
  await page.goto('/energy/manage')
  await page.getByTestId('manage-areas').getByRole('button', { name: 'Add area' }).click()
  await page.getByLabel('Area name').fill('Lights')
  await page.getByRole('dialog').getByRole('button', { name: 'Add area' }).click()
  const area = page.getByTestId('manage-area-lights')
  await expect(area.getByTestId('area-now')).toHaveText('Sum of its plugs')
  await area.getByLabel('Whole-area meter for Lights').selectOption('Mains and lights B')
  await expect(area.getByTestId('area-now')).toHaveText('26 W now on Mains and lights B')
  expect(sent).toContain('PUT /api/meters/Mains and lights B/job {"job":"area","area":"lights"}')
})

// BDD: A meter is given a new job in one step
test('change job moves a clamp from the main meter to monitoring one device', async ({ page }) => {
  const { sent } = await serve(page, { meters: [meter('CCU Clamp', { inUse: true })] })
  await page.goto('/energy/manage')
  await page.getByRole('button', { name: 'Change job of CCU Clamp' }).click()
  await page.getByRole('radio', { name: /Monitor one device/ }).check()
  await page.getByRole('dialog').getByRole('button', { name: 'Save' }).click()
  await expect(page.getByTestId('manage-monitored').getByText('CCU Clamp')).toBeVisible()
  expect(sent).toContain('PUT /api/meters/CCU Clamp/job {"job":"monitored"}')
})

// BDD: An admin stops monitoring a device
test('stop monitoring asks first', async ({ page }) => {
  const { sent } = await serve(page, { meters: [meter('Desk Plug', { monitored: true })] })
  await page.goto('/energy/manage')
  await page.getByRole('button', { name: 'Stop monitoring' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Stop monitoring' }).click()
  expect(sent).toContain('DELETE /api/meters/Desk Plug/monitored')
})

// BDD: An admin sets a daily target
test('the daily target is set on the management page with its cost', async ({ page }) => {
  const { sent } = await serve(page, { meters: [meter('Main Clamp', { inUse: true })] })
  await page.goto('/energy/manage')
  await page.getByRole('button', { name: 'Set a target' }).click()
  await page.getByLabel('kWh a day').fill('12')
  await page.getByRole('button', { name: 'Save' }).click()
  await expect(page.getByTestId('target-value')).toHaveText('12.0 kWh a day')
  await expect(page.getByTestId('target-cost')).toContainText('£3.24')
  expect(sent).toContain('PUT /api/settings/energy {"dailyKwhTarget":12}')
})

// BDD: The Energy tab shows the delta for today
test('today shows how far under or over target the house is', async ({ page }) => {
  await serve(page, {
    meters: [meter('Main Clamp', { inUse: true })],
    target: { kwh: 12, usedKwh: 5, expectedKwh: 6, deltaKwh: -1, deltaCost: -27, onTarget: true, projectedKwh: 10, incomplete: false },
  })
  await page.goto('/energy')
  await expect(page.getByTestId('target-delta')).toHaveText('1.0 kWh under pace')
  await expect(page.getByTestId('target-delta')).toHaveAttribute('data-on-target', 'true')
  await expect(page.getByTestId('target-progress')).toHaveText('5.0 of 12.0 kWh, 7.0 kWh left today, on track for 10.0 kWh')
  await expect(page.getByTestId('target-expected')).toBeAttached()
})

// BDD: The Energy tab shows the delta for today
test('over target says so, with missing readings; no target offers to set one', async ({ page }) => {
  await serve(page, {
    meters: [meter('Main Clamp', { inUse: true })],
    target: { kwh: 12, usedKwh: 7, expectedKwh: 6, deltaKwh: 1, deltaCost: 27, onTarget: false, projectedKwh: 14, incomplete: true },
  })
  await page.goto('/energy')
  await expect(page.getByTestId('target-delta')).toHaveText('1.0 kWh over pace')
  await expect(page.getByTestId('energy-target')).toContainText('Some readings are missing')
})

// BDD: The Energy tab shows the delta for today
test('with no target the Energy tab offers to set one, and week bars mark days over target', async ({ page }) => {
  await serve(page, { meters: [meter('Main Clamp', { inUse: true })] })
  await page.goto('/energy')
  await page.getByTestId('energy').getByRole('link', { name: 'Set a daily target' }).click()
  await expect(page).toHaveURL(/\/energy\/manage$/)

  const day = (date: string, kwh: number, delta: number) => ({
    date, boilerHours: 0, gasKwh: 0, gasCost: 0, electricityKwh: kwh, electricityCost: kwh * 27, standingCost: 80, totalCost: 80 + kwh * 27, missingMinutes: 0, incomplete: false, targetDeltaKwh: delta,
  })
  await serve(page, {
    meters: [meter('Main Clamp', { inUse: true })],
    target: { kwh: 24, usedKwh: 24, expectedKwh: 24, deltaKwh: 0, deltaCost: 0, onTarget: true, projectedKwh: 24, incomplete: false },
    days: [day('2026-11-09', 10, -2), day('2026-11-10', 14, 2)],
  })
  await page.goto('/energy')
  await page.getByRole('group', { name: 'Energy period' }).getByRole('button', { name: 'Week' }).click()
  await expect(page.getByTestId('day-target')).toHaveCount(2)
  await expect(page.locator('[data-testid="energy-day"][data-over-target="true"]')).toHaveCount(1)
})

// BDD: The People page shows who is signed in
test('people show whether they are active, and a device can be signed out', async ({ page }) => {
  const { sent } = await serve(page)
  await page.goto('/people')
  await expect(page.getByTestId('person-u-jane').getByTestId('presence')).toHaveText('Active now')
  await expect(page.getByTestId('person-u-sam').getByTestId('presence')).toHaveText('Last seen 3 hours ago')
  await page.getByRole('button', { name: 'Options for sam' }).click()
  await page.getByRole('menuitem', { name: 'Devices' }).click()
  const phone = page.getByTestId('session-s-phone')
  await expect(phone).toContainText('Chrome on Android')
  await expect(page.getByTestId('session-s-here')).toContainText('This device')
  await expect(page.getByTestId('session-s-here').getByRole('button', { name: 'Sign out' })).toHaveCount(0)
  await phone.getByRole('button', { name: 'Sign out' }).click()
  await expect(page.getByTestId('session-s-phone')).toHaveCount(0)
  expect(sent).toContain('DELETE /api/admin/users/u-sam/sessions/s-phone')
})
