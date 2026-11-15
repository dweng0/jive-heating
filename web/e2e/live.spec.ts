import { expect, test, type Page, type WebSocketRoute } from '@playwright/test'

// The Live tab, against a mocked service: /api calls, /ws and /ws/power are answered here. The 3D view renders in
// software in a headless browser, a few frames a second, so waits are longer than elsewhere.

test.describe.configure({ timeout: 120_000 })
const slowExpect = expect.configure({ timeout: 30_000 })

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[] }
const sam = { id: 'u-sam', username: 'sam', admin: false, energy: false, rooms: [] as string[] }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

type Flow = { from: string; to: string; watts: number }
function power(watts: number | null, flows: Flow[] = [], batteryPct: number | null = null) {
  return {
    watts,
    at: new Date().toISOString(),
    rooms: [{ room: 'kitchen', name: 'Kitchen', watts: watts == null ? null : 300 }],
    devices: [{ meter: 'Fridge Plug', watts: 90 }],
    elseWatts: watts == null ? null : Math.max(0, watts - 390),
    flows: watts == null ? [] : flows,
    batteryPct,
  }
}
const SUNNY = power(900, [{ from: 'solar', to: 'home', watts: 900 }, { from: 'solar', to: 'battery', watts: 2600 }, { from: 'solar', to: 'grid', watts: 1700 }], 64)

async function serve(page: Page, opts: { first?: object; me?: object } = {}) {
  const sockets: WebSocketRoute[] = []
  await page.routeWebSocket('**/ws', () => {})
  await page.routeWebSocket('**/ws/power', (ws) => {
    sockets.push(ws)
    ws.send(JSON.stringify(opts.first ?? SUNNY))
  })
  await page.route('**/api/**', async (route) => {
    const { pathname } = new URL(route.request().url())
    if (pathname === '/api/auth/me') return route.fulfill({ json: opts.me ?? jane })
    if (pathname === '/api/rooms' || pathname === '/api/groups') return route.fulfill({ json: [] })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/setup') return route.fulfill({ json: { needsSetup: false, broker: 'connected' } })
    return route.fulfill({ status: 404, json: { error: 'not mocked' } })
  })
  return { push: (msg: object) => sockets.at(-1)!.send(JSON.stringify(msg)) }
}

const label = (page: Page, k: string) => page.getByTestId(`label-${k}`)

// BDD: The Live tab shows the house with its flows
test('the Live tab shows the house with its flows, full width', async ({ page }) => {
  await serve(page)
  await page.goto('/')
  await page.getByRole('link', { name: 'Live' }).first().click()
  await slowExpect(page).toHaveURL(/\/live$/)
  await slowExpect(page.locator('#gl')).toBeVisible()
  await slowExpect(page.getByText('Drag to turn · pinch or scroll to zoom')).toBeVisible()

  // Labels: solar, battery (with % and kWh left), grid and home.
  await slowExpect(label(page, 'solar').locator('.v')).toHaveText('5.2 kW')
  await slowExpect(label(page, 'home').locator('.v')).toHaveText('900 W')
  await slowExpect(label(page, 'grid').locator('.s')).toHaveText('Exporting')
  await slowExpect(label(page, 'battery').locator('.s')).toContainText('64% · 10.2 kWh · Charging')

  // The picture takes the full width of the page's content.
  const stage = await page.locator('#stage').boundingBox()
  const main = await page.locator('main').boundingBox()
  expect(stage!.width).toBeGreaterThan(main!.width - 60)

  // Diagram switches view, and the choice is remembered.
  await page.getByRole('button', { name: 'Diagram' }).click()
  await slowExpect(page.getByRole('button', { name: 'Diagram' })).toHaveAttribute('aria-pressed', 'true')
  await page.reload()
  await slowExpect(page.getByRole('button', { name: 'Diagram' })).toHaveAttribute('aria-pressed', 'true')
  await page.getByRole('button', { name: 'House' }).click()
})

// BDD: The Live tab shows the house with its flows
test('members without energy access have no Live tab', async ({ page }) => {
  await serve(page, { me: sam })
  await page.goto('/')
  await slowExpect(page.getByRole('link', { name: 'Rooms' }).first()).toBeVisible()
  await slowExpect(page.getByRole('link', { name: 'Live' })).toHaveCount(0)
  await page.goto('/live')
  await slowExpect(page).not.toHaveURL(/\/live$/)
})

test.describe('on a phone', () => {
  test.use({ viewport: { width: 390, height: 844 } })
  // BDD: The Live tab shows the house with its flows
  test('the picture runs edge to edge', async ({ page }) => {
    await serve(page)
    await page.goto('/live')
    const stage = await page.locator('#stage').boundingBox()
    expect(stage!.x).toBeLessThanOrEqual(1)
    expect(stage!.width).toBeGreaterThanOrEqual(389)
  })
})

// BDD: Before the inverter is connected only the grid shows
test('before the inverter is connected solar and battery say Not connected', async ({ page }) => {
  await serve(page, { first: power(1200, [{ from: 'grid', to: 'home', watts: 1200 }]) })
  await page.goto('/live')
  await slowExpect(label(page, 'home').locator('.v')).toHaveText('1.2 kW')
  await slowExpect(label(page, 'solar').locator('.v')).toHaveText('Not connected')
  await slowExpect(label(page, 'battery').locator('.v')).toHaveText('Not connected')
  await slowExpect(label(page, 'grid').locator('.s')).toHaveText('Importing')
})

// BDD: Stopped readings fade the flows
test('stopped readings fade the flows and come back', async ({ page }) => {
  const { push } = await serve(page)
  await page.goto('/live')
  await slowExpect(page.locator('#statusText')).toHaveText(/Updated/)
  push(power(null))
  await slowExpect(page.locator('#statusText')).toHaveText('Readings stopped')
  await slowExpect(page.locator('#stage')).toHaveClass(/stale/)
  await slowExpect(page.locator('#staleNote')).toContainText('Last reading at')
  push(SUNNY)
  await slowExpect(page.locator('#statusText')).toHaveText(/Updated/)
  await slowExpect(page.locator('#stage')).not.toHaveClass(/stale/)
})

// BDD: Readings ease instead of jumping
test('a new reading eases in over about a second and a half', async ({ page }) => {
  const { push } = await serve(page, { first: power(500, [{ from: 'grid', to: 'home', watts: 500 }]) })
  await page.goto('/live')
  await slowExpect(label(page, 'home').locator('.v')).toHaveText('500 W')
  const watched = page.evaluate(
    () =>
      new Promise<string[]>((resolve) => {
        const v = document.querySelector('[data-testid=label-home] .v')!
        const seen: string[] = []
        const t0 = performance.now()
        const tick = () => { seen.push(v.textContent ?? ''); if (performance.now() - t0 < 12000) requestAnimationFrame(tick); else resolve(seen) }
        requestAnimationFrame(tick)
      }),
  )
  push(power(3000, [{ from: 'grid', to: 'home', watts: 3000 }]))
  const seen = await watched
  const between = new Set(seen.filter((s) => s !== '500 W' && s !== '3.0 kW'))
  expect(between.size).toBeGreaterThanOrEqual(2)
  expect(seen.at(-1)).toBe('3.0 kW')
})

// BDD: The Live tab says where the home's power came from
test('the ring shows the share from each source and where it is going', async ({ page }) => {
  await serve(page, { first: power(1000, [{ from: 'solar', to: 'home', watts: 600 }, { from: 'battery', to: 'home', watts: 300 }, { from: 'grid', to: 'home', watts: 100 }], 50) })
  await page.goto('/live')
  await slowExpect(page.locator('#rPct')).toHaveText('90%', { timeout: 60_000 })
  await slowExpect(page.locator('#ringKey')).toContainText('Solar600 W')
  await slowExpect(page.locator('#ringKey')).toContainText('Battery300 W')
  await slowExpect(page.locator('#ringKey')).toContainText('Grid100 W')
  await slowExpect(page.locator('#circuits')).toContainText('Kitchen')
  await slowExpect(page.locator('#circuits')).toContainText('Fridge Plug')
  await slowExpect(page.locator('#circuits')).toContainText('Everything else')
})

// BDD: Effects can be turned down on a slow phone
test('effects can be switched off one by one', async ({ page }) => {
  await serve(page)
  await page.goto('/live')
  await page.getByRole('button', { name: 'Effects' }).click()
  for (const name of ['Shadows', 'Glow', 'Cloud shadows', 'Moss and weathering', 'Vignette and grain', '3D grass']) {
    // Each switch takes effect at once. (A slow device, like this headless browser, starts with some already off.)
    const box = page.getByLabel(name, { exact: true })
    const was = await box.isChecked()
    await box.click()
    await slowExpect(box).toBeChecked({ checked: !was })
  }
})

// BDD: Before the inverter is connected only the grid shows
test('a service that sends no flows yet shows the house meter as grid to home', async ({ page }) => {
  const old = { watts: 700, at: new Date().toISOString(), rooms: [], devices: [], elseWatts: 700 }
  await serve(page, { first: old })
  await page.goto('/live')
  await slowExpect(label(page, 'home').locator('.v')).toHaveText('700 W', { timeout: 90_000 })
  await slowExpect(label(page, 'grid').locator('.s')).toHaveText('Importing')
  await slowExpect(label(page, 'solar').locator('.v')).toHaveText('Not connected')
})
