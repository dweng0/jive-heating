import { expect, test, type Page } from '@playwright/test'

// "Where it went": the Energy tab's Sankey diagram of the house's energy, against a mocked service.

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[], energy: true }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

const node = (id: string, name: string, kind: string, kwh: number, price: number, estimated = false) => ({ id, name, kind, kwh, cost: kwh * price, estimated })

function flow(period: string, electricity = true) {
  const e = electricity
    ? {
        nodes: [
          node('electricity', 'Electricity', 'source', 11.8, 24),
          node('room:kitchen', 'Kitchen', 'place', 3.0, 24),
          node('device:Kettle', 'Kettle', 'device', 1.2, 24),
          node('device:Oven', 'Oven', 'device', 1.8, 24),
          node('area:lights', 'Lights', 'place', 0.9, 24),
          node('else', 'Everything else', 'place', 7.9, 24),
        ],
        links: [
          { from: 'electricity', to: 'room:kitchen', kwh: 3.0 },
          { from: 'electricity', to: 'area:lights', kwh: 0.9 },
          { from: 'electricity', to: 'else', kwh: 7.9 },
          { from: 'room:kitchen', to: 'device:Kettle', kwh: 1.2 },
          { from: 'room:kitchen', to: 'device:Oven', kwh: 1.8 },
        ],
      }
    : { nodes: [], links: [] }
  return {
    currency: 'GBP', from: '2026-11-11T00:00:00Z', to: '2026-11-11T12:00:00Z', period,
    nodes: [...e.nodes, node('gas', 'Gas', 'source', 20, 6.9, true), node('heating', 'Heating', 'place', 20, 6.9, true), node('heat:lounge', 'Lounge', 'place', 15, 6.9, true), node('heat:bedroom', 'Bedroom', 'place', 5, 6.9, true)],
    links: [...e.links, { from: 'gas', to: 'heating', kwh: 20 }, { from: 'heating', to: 'heat:lounge', kwh: 15, estimated: true }, { from: 'heating', to: 'heat:bedroom', kwh: 5, estimated: true }],
  }
}

async function serve(page: Page, opts: { electricity?: boolean } = {}) {
  const asked: string[] = []
  await page.routeWebSocket('**/ws', () => {})
  await page.routeWebSocket('**/ws/power', (ws) => ws.send(JSON.stringify({ watts: 0, at: new Date().toISOString(), rooms: [], devices: [], areas: [], elseWatts: 0 })))
  await page.route('**/api/**', async (route) => {
    const url = new URL(route.request().url())
    const { pathname } = url
    if (pathname === '/api/auth/me') return route.fulfill({ json: jane })
    if (pathname === '/api/energy/flow') {
      const period = url.searchParams.get('period') ?? 'today'
      asked.push(period)
      return route.fulfill({ json: flow(period, opts.electricity ?? true) })
    }
    if (pathname === '/api/rooms' || pathname === '/api/groups' || pathname === '/api/energy/areas') return route.fulfill({ json: [] })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    return route.fulfill({ status: 404, json: { error: 'not mocked' } })
  })
  return { asked }
}

// BDD: The Energy tab shows where the energy went
test('the energy tab shows where the energy went', async ({ page }) => {
  const { asked } = await serve(page)
  await page.goto('/energy')
  const card = page.getByTestId('energy-flow')
  await expect(card.getByRole('heading', { name: 'Where it went' })).toBeVisible()
  // Sources, places and devices, each labelled with its name and kWh.
  for (const name of ['Electricity', 'Gas', 'Kitchen', 'Lights', 'Everything else', 'Heating', 'Kettle', 'Oven', 'Lounge', 'Bedroom']) {
    await expect(card.locator(`[data-testid="flow-node"][data-name="${name}"]`)).toBeVisible()
  }
  await expect(card.locator('[data-testid="flow-node"][data-name="Kitchen"]')).toContainText('3.0 kWh')
  // Sources on the left, devices on the right.
  const x = async (name: string) => (await card.locator(`[data-testid="flow-node"][data-name="${name}"] rect`).boundingBox())!.x
  expect(await x('Electricity')).toBeLessThan(await x('Kitchen'))
  expect(await x('Kitchen')).toBeLessThan(await x('Kettle'))
  // Bands as wide as their kWh: Everything else's is wider than Lights'.
  const h = async (name: string) => (await card.locator(`[data-testid="flow-node"][data-name="${name}"] rect`).boundingBox())!.height
  expect(await h('Everything else')).toBeGreaterThan(await h('Lights'))
  // Hovering says kWh, cost and share of its source; estimated bands are hatched.
  await card.locator('[data-testid="flow-node"][data-name="Kitchen"] rect').hover()
  await expect(card.getByTestId('flow-readout')).toHaveText('Kitchen · 3.0 kWh · £0.72 · 25% of electricity')
  const lounge = card.locator('[data-testid="flow-link"][data-to="heat:lounge"]')
  await expect(lounge).toHaveAttribute('fill', 'url(#hatch-gas)')
  await lounge.click()
  await expect(card.getByTestId('flow-readout')).toContainText('Heating → Lounge · 15.0 kWh')
  await expect(card.getByTestId('flow-readout')).toContainText('estimated')
  // The period is chosen, and remembered.
  await card.getByRole('group', { name: 'Flow period' }).getByRole('button', { name: 'Week' }).click()
  await expect.poll(() => asked.at(-1)).toBe('week')
  await page.reload()
  await expect(page.getByTestId('energy-flow').getByRole('button', { name: 'Week' })).toHaveAttribute('aria-pressed', 'true')
  await expect.poll(() => asked.at(-1)).toBe('week')
})

test('with no house meter the flow says so and still shows gas', async ({ page }) => {
  await serve(page, { electricity: false })
  await page.goto('/energy')
  const card = page.getByTestId('energy-flow')
  await expect(card).toContainText('Choose a house meter to see where electricity goes')
  await expect(card.locator('[data-testid="flow-node"][data-name="Gas"]')).toBeVisible()
})
