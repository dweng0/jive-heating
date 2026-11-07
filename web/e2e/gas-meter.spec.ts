import { expect, test, type Page } from '@playwright/test'

// Gas from the smart meter on the Energy tab, against a mocked service.

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[], energy: true }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

/** A day of the week: 5 November and before from the meter, 6 November estimated from 5.5 boiler hours, 7 November part of each. */
function day(date: string, gasKwh: number, gasEstimatedKwh: number) {
  return {
    date, boilerHours: 5.5, gasKwh, gasCost: gasKwh * 6.906, gasEstimatedKwh,
    electricityKwh: 9, electricityCost: 243, standingCost: 77.3, totalCost: gasKwh * 6.906 + 243 + 77.3, missingMinutes: 0, incomplete: false,
  }
}

const WEEK_DAYS = [
  day('2026-11-01', 59.9, 0), day('2026-11-02', 52.4, 0), day('2026-11-03', 44.0, 0), day('2026-11-04', 63.8, 0),
  day('2026-11-05', 68.0, 0), day('2026-11-06', 66.0, 66.0), day('2026-11-07', 24.0, 24.0),
]

function dashboard(period: string, metered: boolean) {
  const days = period === 'week' ? WEEK_DAYS : [day('2026-11-07', 24, 24)]
  const gasKwh = days.reduce((a, d) => a + d.gasKwh, 0)
  const gasEstimatedKwh = metered ? days.reduce((a, d) => a + d.gasEstimatedKwh, 0) : gasKwh
  return {
    period, from: '2026-11-01T00:00:00Z', to: '2026-11-07T07:00:00Z', boilerHours: 5.5 * days.length,
    gasKwh, gasCost: gasKwh * 6.906, gasEstimatedKwh, gasMeteredTo: metered ? '2026-11-06T00:00:00Z' : null,
    electricityMeter: 'Main Clamp', electricityKwh: 9 * days.length, electricityCost: 243 * days.length,
    gasStandingCost: 27.3 * days.length, electricityStandingCost: 50 * days.length, standingCost: 77.3 * days.length,
    totalCost: 1000, missingMinutes: 0, incomplete: false, days: metered ? days : days.map((d) => ({ ...d, gasEstimatedKwh: d.gasKwh })),
    currency: 'GBP', devices: [], rooms: [], everythingElse: null, target: null,
  }
}

const TOTALS = {
  currency: 'GBP', pencePerKwh: 27,
  today: { kwh: 8.2, cost: 221.4 }, week: { kwh: 51, cost: 1377 }, month: { kwh: 190, cost: 5130 }, year: { kwh: 2100, cost: 56700 },
  yesterdaySoFar: { kwh: 7, cost: 189 },
}

/** Gas days 3 to 5 November: a kWh a half-hour, 6.5 at 18:30. */
function gasDay(date: string, measured: number | null = 12.1) {
  const halfHours = Array.from({ length: 48 }, (_, i) => ({ at: new Date(Date.parse(`${date}T00:00:00Z`) + i * 1_800_000).toISOString(), kwh: i === 37 ? 6.5 : 1 }))
  const days = ['2026-11-03', '2026-11-04', '2026-11-05']
  const i = days.indexOf(date)
  return {
    date, kwh: 53.5, cost: 369.47, currency: 'GBP', halfHours,
    previous: days[i - 1] ?? null, next: days[i + 1] ?? null, measuredBoilerKw: measured, boilerAverageKw: 12.0,
  }
}

async function serve(page: Page, opts: { metered?: boolean; gas?: object | null; me?: typeof jane; measured?: number | null } = {}) {
  const puts: unknown[] = []
  await page.routeWebSocket('**/ws', () => {})
  await page.routeWebSocket('**/ws/power', (ws) => {
    ws.send(JSON.stringify({ watts: 0, at: new Date().toISOString(), rooms: [], devices: [], areas: [], elseWatts: 0 }))
  })
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const { pathname } = url
    if (pathname === '/api/auth/me') return route.fulfill({ json: opts.me ?? jane })
    if (pathname === '/api/energy/gas') return route.fulfill({ json: gasDay(url.searchParams.get('date') ?? '2026-11-05', opts.measured === undefined ? 12.1 : opts.measured) })
    if (pathname === '/api/settings/energy' && route.request().method() === 'PUT') {
      puts.push(route.request().postDataJSON())
      return route.fulfill({ json: {} })
    }
    if (pathname === '/api/rooms') return route.fulfill({ json: [] })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/groups') return route.fulfill({ json: [] })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/energy/areas') return route.fulfill({ json: [] })
    if (pathname === '/api/dashboard') return route.fulfill({ json: dashboard(url.searchParams.get('period') ?? 'today', opts.metered ?? true) })
    if (pathname === '/api/power') return route.fulfill({ json: { bands: { green: 350, red: 4000 }, recent: [], todayPeak: null } })
    if (pathname === '/api/energy/totals') return route.fulfill({ json: { ...TOTALS, gas: opts.gas === undefined ? null : opts.gas } })
    if (pathname === '/api/energy/history') return route.fulfill({ json: { currency: 'GBP', range: 'day', from: new Date().toISOString(), to: new Date().toISOString(), earliest: null, points: [] } })
    return route.fulfill({ status: 404, json: { error: 'not mocked' } })
  })
  return { puts }
}

// BDD: The Energy tab says where gas comes from
test('the energy tab says where gas comes from', async ({ page }) => {
  await serve(page)
  await page.goto('/energy')
  const energy = page.getByTestId('energy')
  await expect(energy).toContainText("Gas is from the smart meter up to 6 Nov 00:00, estimated from the boiler's hours after.")
  // Today: all of it estimated.
  await expect(energy.getByTestId('energy-gas')).toContainText('estimated')
  await expect(energy.getByTestId('energy-gas')).not.toContainText('part estimated')
  await energy.getByRole('button', { name: 'Week' }).click()
  await expect(energy.getByTestId('gas-estimate-chip')).toHaveText('part estimated')
  // Each day of the week named, with its total.
  await expect(energy.getByTestId('day-label').first()).toContainText('Sun 1')
  await expect(energy.getByTestId('day-label').nth(5)).toContainText('estimate')
  await expect(energy.getByTestId('day-total')).toHaveCount(7)
  // The estimated part of each day's gas is hatched.
  await expect(energy.locator('[data-testid=energy-day] [data-testid=gas-estimated]')).toHaveCount(2)
  await expect(energy).toContainText('Gas, estimated from boiler hours')
})

test('with no meter readings gas is estimated from boiler hours', async ({ page }) => {
  await serve(page, { metered: false })
  await page.goto('/energy')
  await expect(page.getByTestId('energy')).toContainText("Gas is estimated from the boiler's hours.")
})

// BDD: The car dashboard shows the gas meter's latest day
test("the car dashboard shows the gas meter's latest day", async ({ page }) => {
  await serve(page, { gas: { date: '2026-11-05', kwh: 67.95, cost: 469.26, previousKwh: 63.8 } })
  await page.goto('/energy')
  const gas = page.getByTestId('gas-day')
  await expect(gas).toContainText('GAS · THU 5 NOV')
  await expect(gas.getByTestId('gas-kwh')).toHaveAttribute('data-value', '68.0')
  await expect(gas).toContainText('£4.69')
  await expect(gas.getByTestId('gas-delta')).toHaveText('▲ 4.2 kWh vs Wed')
})

test('the gas counter shows less with ▼ and is not shown before a whole day', async ({ page }) => {
  await serve(page, { gas: { date: '2026-11-05', kwh: 60, cost: 414.4, previousKwh: 63.8 } })
  await page.goto('/energy')
  await expect(page.getByTestId('gas-delta')).toHaveText('▼ 3.8 kWh vs Wed')
  await page.unrouteAll({ behavior: 'ignoreErrors' })
  await serve(page, { gas: null })
  await page.reload()
  await expect(page.getByTestId('odometer-kwh')).toBeVisible()
  await expect(page.getByTestId('gas-day')).toHaveCount(0)
})

// BDD: A day of gas, half-hour by half-hour
test('a day of gas, half-hour by half-hour', async ({ page }) => {
  await serve(page)
  await page.goto('/energy')
  const card = page.getByTestId('gas-card')
  await expect(card.getByTestId('gas-card-date')).toHaveText('Thu 5 Nov')
  // Right under the dials, above the history chart.
  const dials = await page.getByTestId('energy-dials').boundingBox()
  const gasBox = await card.boundingBox()
  expect(gasBox!.y).toBeGreaterThan(dials!.y + dials!.height - 1)
  expect(gasBox!.y).toBeLessThan((await page.getByTestId('energy').boundingBox())!.y)
  await expect(card.getByTestId('gas-half-hour')).toHaveCount(48)
  await expect(card.getByTestId('gas-card-figures')).toContainText('53.5 kWh · £3.69')
  await expect(card.getByTestId('gas-card-figures')).toContainText('busiest 18:30, 6.5 kWh')
  await expect(card.getByRole('button', { name: 'Day after' })).toBeDisabled()
  await card.getByRole('button', { name: 'Day before' }).click()
  await expect(card.getByTestId('gas-card-date')).toHaveText('Wed 4 Nov')
  await card.getByRole('button', { name: 'Day before' }).click()
  await expect(card.getByTestId('gas-card-date')).toHaveText('Tue 3 Nov')
  await expect(card.getByRole('button', { name: 'Day before' })).toBeDisabled()
  await card.getByRole('button', { name: 'Day after' }).click()
  await expect(card.getByTestId('gas-card-date')).toHaveText('Wed 4 Nov')
})

// BDD: The boiler's average burn rate from the meter
test("the boiler's average burn rate from the meter", async ({ page }) => {
  const { puts } = await serve(page)
  await page.goto('/energy')
  const measured = page.getByTestId('boiler-measured')
  await expect(measured).toContainText('Boiler average from the meter: 12.1 kW')
  await measured.getByRole('button', { name: 'Use 12.1 kW' }).click()
  await expect.poll(() => puts).toEqual([{ boilerAverageKw: 12.1 }])
  await expect(measured.getByRole('button', { name: 'Use 12.1 kW' })).toHaveCount(0)
})

test('members do not see the burn rate, and no button when it matches', async ({ page }) => {
  await serve(page, { me: { ...jane, admin: false }, measured: 12.1 })
  await page.goto('/energy')
  await expect(page.getByTestId('gas-card')).toBeVisible()
  await expect(page.getByTestId('boiler-measured')).toHaveCount(0)
  await page.unrouteAll({ behavior: 'ignoreErrors' })
  await serve(page, { measured: 12.05 })
  await page.reload()
  await expect(page.getByTestId('boiler-measured')).toContainText('12.1 kW')
  await expect(page.getByTestId('boiler-measured').getByRole('button')).toHaveCount(0)
})

test('screenshot for review', async ({ page }) => {
  test.skip(!process.env.SHOT, 'manual')
  await page.setViewportSize({ width: 1280, height: 900 })
  await serve(page, { gas: { date: '2026-11-05', kwh: 67.95, cost: 469.26, previousKwh: 63.8 } })
  await page.goto('/energy')
  await page.getByTestId('energy').getByRole('button', { name: 'Week' }).click()
  await page.getByTestId('gas-card').waitFor()
  await page.screenshot({ path: process.env.SHOT!, fullPage: true })
})
