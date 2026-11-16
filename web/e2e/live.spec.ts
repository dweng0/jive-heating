import { expect, test, type Page, type WebSocketRoute } from '@playwright/test'

// The Live tab, against a mocked service: /api calls, /ws and /ws/power are answered here. The 3D view renders in
// software in a headless browser, a few frames a second, so waits are longer than elsewhere.

test.describe.configure({ timeout: 240_000 })
const slowExpect = expect.configure({ timeout: 90_000 })

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

// BDD: The 3D house sits on the app's background in a rounded frame
for (const [scheme, bg] of [['light', 'rgb(250, 247, 242)'], ['dark', 'rgb(23, 19, 15)']] as const) {
  test(`the house sits on the ${scheme} background in a rounded frame`, async ({ page }) => {
    await page.emulateMedia({ colorScheme: scheme })
    await serve(page)
    await page.goto('/live')
    await slowExpect(page.locator('#gl')).toBeVisible()
    const look = await page.evaluate(() => {
      const css = (sel: string) => getComputedStyle(document.querySelector(sel)!)
      const gl = css('#gl')
      return {
        body: css('body').backgroundColor,
        page: css('.live-page').backgroundColor,
        stage: css('#stage').backgroundColor,
        stageBorder: css('#stage').borderTopWidth,
        mask: gl.maskImage || gl.getPropertyValue('-webkit-mask-image'),
        radius: css('#paneHouse').borderTopLeftRadius,
        diagramRadius: css('#paneDiagram').borderTopLeftRadius,
        clips: css('#paneHouse').overflow,
        panel: css('.live-page .panel').backgroundColor,
      }
    })
    expect(look.body).toBe(bg)
    expect(look.page).toBe(bg)
    expect(look.stage).toBe('rgba(0, 0, 0, 0)')
    expect(look.stageBorder).toBe('0px')
    // A rounded frame like the diagram's, and no fading at the edges.
    expect(look.radius).toBe('20px')
    expect(look.radius).toBe(look.diagramRadius)
    expect(look.clips).toBe('hidden')
    expect(look.mask).toBe('none')
    // The panels stay dark in both modes.
    expect(look.panel).toBe('rgb(17, 23, 31)')
  })
}

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
test('the dial shows the home, the grid and the battery', async ({ page }) => {
  const { push } = await serve(page, { first: power(1000, [{ from: 'solar', to: 'home', watts: 600 }, { from: 'battery', to: 'home', watts: 300 }, { from: 'grid', to: 'home', watts: 100 }], 50) })
  await page.goto('/live')
  await expect(page.getByText('Home powered by')).toHaveCount(0)
  const dial = page.getByTestId('live-dial')
  await slowExpect(dial.getByTestId('dial-home')).toHaveText('1.0 kW')
  await expect(dial).toContainText('Using now')
  await expect(dial.getByTestId('dial-grid')).toHaveText('100 W')
  await expect(dial.getByTestId('dial-grid-state')).toHaveText('Importing')
  await expect(dial.getByTestId('dial-battery')).toHaveText('50%')
  await expect(dial.getByTestId('dial-own')).toHaveText('90% your own')
  // Under the league table, full width.
  const [table, box] = await Promise.all([page.getByTestId('league-table').boundingBox(), dial.boundingBox()])
  expect(box!.y).toBeGreaterThanOrEqual(table!.y + table!.height)

  // Exporting, and no inverter yet.
  push(power(400, [{ from: 'grid', to: 'home', watts: 400 }]))
  await slowExpect(dial.getByTestId('dial-home')).toHaveText('400 W')
  await expect(dial.getByTestId('dial-battery')).toHaveText('Not connected')
  push(power(300, [{ from: 'solar', to: 'home', watts: 300 }, { from: 'solar', to: 'grid', watts: 1200 }], 80))
  await slowExpect(dial.getByTestId('dial-grid-state')).toHaveText('Exporting')
  await expect(dial.getByTestId('dial-grid')).toHaveText('1.2 kW')
})

// BDD: Live shows the league table full width under the picture
test('the league table runs full width under the picture', async ({ page }) => {
  const reading = {
    ...power(2400, [{ from: 'grid', to: 'home', watts: 2400 }]),
    rooms: [{ room: 'kitchen', name: 'Kitchen', watts: 1560 }],
    areas: [{ area: 'lights', name: 'Lights', watts: 110 }],
    items: [
      { meter: 'Dishwasher', name: 'Dishwasher', watts: 1560, room: 'kitchen', area: null, job: 'monitored' },
      { meter: 'Mains B', name: 'Lights', watts: 110, room: null, area: 'lights', job: 'area' },
    ],
  }
  const { push } = await serve(page, { first: reading })
  await page.goto('/live')
  await expect(page.getByText("Where it's going")).toHaveCount(0)
  const table = page.getByTestId('league-table')
  await slowExpect(table).toBeVisible()
  const [stage, box] = await Promise.all([page.locator('#stage').boundingBox(), table.boundingBox()])
  expect(box!.y).toBeGreaterThanOrEqual(stage!.y + stage!.height)
  expect(box!.width).toBeGreaterThanOrEqual(stage!.width - 40)

  const rows = table.getByTestId('league-row')
  await expect(rows.nth(0).getByTestId('league-place')).toHaveText('1')
  await expect(rows.nth(0).getByTestId('league-name')).toHaveText('Dishwasher')
  await expect(rows.nth(0).getByTestId('league-where')).toHaveText('Kitchen')
  await expect(rows.nth(0).getByTestId('league-share')).toHaveAttribute('aria-label', '65%')
  await expect(rows.nth(0).getByTestId('league-running')).toHaveText('65%')
  await expect(rows.nth(1).getByTestId('league-name')).toHaveText('Lights')
  await expect(rows.nth(1).getByTestId('league-where')).toHaveText('Lights')
  await expect(rows.nth(1).getByTestId('league-running')).toHaveText('70%')
  // Like order-book depth: Lights' bar is filled lightly up to where Dishwasher's ends, then solidly for its own 5%.
  const depth = await rows.nth(1).getByTestId('league-depth').evaluate((el) => parseFloat((el as HTMLElement).style.width))
  expect(depth).toBeCloseTo(65, 0)
  const own = await rows.nth(1).getByTestId('league-bar').evaluate((el) => [parseFloat((el as HTMLElement).style.left), parseFloat((el as HTMLElement).style.width)])
  expect(own[0]).toBeCloseTo(65, 0)
  expect(own[1]).toBeCloseTo(4.6, 0)
  await expect(rows.nth(0).getByTestId('league-depth')).toHaveCount(0)

  // Rows move to their new places as readings arrive.
  push({ ...reading, items: [{ ...reading.items[0], watts: 50 }, { ...reading.items[1], watts: 2000 }] })
  await slowExpect(rows.nth(0).getByTestId('league-name')).toHaveText('Lights')
})

// BDD: The diagram draws the flows as glowing lines
test('the diagram draws the flows as glowing lines', async ({ page }) => {
  await serve(page, { first: power(2400, [{ from: 'solar', to: 'home', watts: 700 }, { from: 'grid', to: 'home', watts: 1700 }], 64) })
  await page.goto('/live')
  await page.getByRole('button', { name: 'Diagram' }).click()
  const svg = page.locator('#svgwrap svg')
  await slowExpect(svg.locator('#nv-solar')).toHaveText('700 W')
  await expect(svg.locator('#ns-solar')).toHaveText('Generating')
  await slowExpect(svg.locator('#nv-grid')).toHaveText('1.7 kW')
  await expect(svg.locator('#ns-grid')).toHaveText('Importing')
  await slowExpect(svg.locator('#nv-home')).toHaveText('2.4 kW')
  await expect(svg.locator('#ns-home')).toHaveText('Using now')
  await expect(svg.locator('#batt-pct')).toHaveText('64%')
  // Line drawings of the panels, the house with appliances, the battery and the pylon.
  for (const id of ['art-solar', 'art-home', 'art-appliances', 'art-battery', 'art-grid']) await expect(svg.locator(`#${id}`)).toHaveCount(1)
  // Lit in the source's colour while power flows, dim while it does not.
  await slowExpect(svg.locator('#p-solar-home')).toHaveAttribute('data-lit', 'true')
  await expect(svg.locator('#p-solar-home')).toHaveAttribute('stroke', '#3ddc84')
  await expect(svg.locator('#p-grid-home')).toHaveAttribute('stroke', '#f59f5a')
  await expect(svg.locator('#p-solar-battery')).toHaveAttribute('data-lit', 'false')
})

// BDD: The league table's counters tick on like odometers
test('the league table counters tick on like odometers', async ({ page }) => {
  await page.clock.install()
  const reading = {
    ...power(1000, [{ from: 'grid', to: 'home', watts: 1000 }]),
    houseTodayKwh: 5.0,
    pencePerKwh: 25,
    currency: 'GBP',
    items: [{ meter: 'Fridge Plug', name: 'Fridge', watts: 100, room: 'kitchen', area: null, job: 'monitored', todayKwh: 0.2 }],
  }
  const { push } = await serve(page, { first: reading })
  await page.goto('/live')
  const table = page.getByTestId('league-table')
  await expect(table.getByText('Today kWh')).toBeVisible({ timeout: 90_000 })
  await expect(table.getByText('Cost', { exact: true })).toBeVisible()
  const fridge = table.getByTestId('league-row').filter({ hasText: 'Fridge' })
  const today = fridge.getByTestId('league-today')
  const cost = fridge.getByTestId('league-cost')
  // A fresh reading starts each counter at its figure.
  await page.clock.fastForward(1000)
  push(reading)
  await expect(today).toHaveAttribute('data-value', '0.2000')
  await expect(cost).toHaveAttribute('data-value', '0.0500')
  await expect(cost).toHaveAttribute('aria-label', '£0.0500')
  // 36 s at 100 W is 0.001 kWh more.
  // (A jump: running every frame of the 3D picture through 36 s is too slow in a headless browser.)
  await page.clock.fastForward(36_000)
  await expect(today).toHaveAttribute('data-value', '0.2010')
  await expect(cost).toHaveAttribute('data-value', '0.0503')
  // Everything else: the house's 5.0 kWh less the fridge's, rolling on at its 900 W.
  const rest = table.getByTestId('league-row').filter({ hasText: 'Everything else' }).getByTestId('league-today')
  expect(Number(await rest.getAttribute('data-value'))).toBeCloseTo(4.8 + (0.9 * 36) / 3600, 3)
  // The share rolls like an odometer, lit green as it rises.
  push({ ...reading, items: [{ ...reading.items[0], watts: 300 }] })
  const share = fridge.getByTestId('league-share')
  await expect(share).toHaveAttribute('aria-label', '30%')
  await expect(share.getByTestId('league-share-wheels')).toHaveAttribute('data-value', '30')
  await expect(share).toHaveAttribute('data-flash', 'up')
})

// BDD: House and Diagram are switched at the top
test('house and diagram are switched at the top', async ({ page }) => {
  await serve(page)
  await page.goto('/live')
  const house = page.getByRole('button', { name: 'House' })
  await slowExpect(house).toBeVisible()
  const [title, stage, btn] = await Promise.all([
    page.getByRole('heading', { name: 'Live' }).boundingBox(),
    page.locator('#stage').boundingBox(),
    house.boundingBox(),
  ])
  // Beside the title and above the picture, not under it.
  expect(btn!.y + btn!.height).toBeLessThanOrEqual(stage!.y)
  expect(Math.abs(btn!.y + btn!.height / 2 - (title!.y + title!.height / 2))).toBeLessThan(30)
  expect(btn!.height).toBeGreaterThanOrEqual(36)
  // The chosen view is filled in the app's orange.
  await expect(house).toHaveCSS('background-color', 'rgb(242, 140, 40)')
  await page.getByRole('button', { name: 'Diagram' }).click()
  await expect(page.getByRole('button', { name: 'Diagram' })).toHaveCSS('background-color', 'rgb(242, 140, 40)')
})

// BDD: Effects can be turned down on a slow phone
test('effects can be switched off one by one', async ({ page }) => {
  await serve(page)
  // Only with debug=true in the address.
  await page.goto('/live')
  await slowExpect(page.locator('#gl')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Effects' })).toHaveCount(0)
  await page.goto('/live?debug=true')
  await page.getByRole('button', { name: 'Effects' }).click()
  for (const name of ['Shadows', 'Glow', 'Cloud shadows', 'Moss and weathering', 'Film grain', '3D grass']) {
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

// BDD: The Live picture shows the weather
test('the Live picture follows the weather in live power', async ({ page }) => {
  const at = new Date().toISOString()
  const { push } = await serve(page, { first: { ...SUNNY, weather: { look: 'snow', temperatureC: -1, at } } })
  await page.goto('/live')
  await slowExpect(page.locator('#stage')).toHaveAttribute('data-weather', 'snow')
  push({ ...SUNNY, weather: { look: 'rain', temperatureC: 6, at } })
  await slowExpect(page.locator('#stage')).toHaveAttribute('data-weather', 'rain')
  // No weather source, or nothing recent: a clear sky.
  push({ ...SUNNY, weather: null })
  await slowExpect(page.locator('#stage')).toHaveAttribute('data-weather', 'clear')
})
