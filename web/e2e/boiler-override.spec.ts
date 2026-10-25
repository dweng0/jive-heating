import { expect, test, type Page, type WebSocketRoute } from '@playwright/test'

// The boiler override on the web app, against a mocked service (like activity.spec.ts).

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[] }
const john = { id: 'u-john', username: 'john', admin: false, rooms: ['r1'] }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }
const OFF = { by: 'jane', since: '2026-10-25T09:00:00+00:00' }

async function serve(page: Page, me: typeof jane, initial: { by: string; since: string } | null) {
  let override = initial
  const sockets: WebSocketRoute[] = []
  const posts: string[] = []
  await page.routeWebSocket('**/ws', (ws) => {
    sockets.push(ws)
  })
  await page.route('**/api/**', async (route) => {
    const { pathname } = new URL(route.request().url())
    if (pathname === '/api/auth/me') return route.fulfill({ json: me })
    if (pathname === '/api/rooms' || pathname === '/api/groups' || pathname === '/api/valves' || pathname === '/api/sensors') return route.fulfill({ json: [] })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/activity') return route.fulfill({ json: { events: [] } })
    if (pathname === '/api/settings/boost') return route.fulfill({ json: { defaultTarget: 22 } })
    if (pathname === '/api/boiler') return route.fulfill({ json: { override } })
    if (pathname === '/api/boiler/off' || pathname === '/api/boiler/on') {
      posts.push(pathname)
      override = pathname.endsWith('off') ? OFF : null
      return route.fulfill({ json: { override } })
    }
    return route.fulfill({ status: 404, json: { error: `not mocked: ${pathname}` } })
  })
  return { posts, live: (e: object) => sockets.forEach((ws) => ws.send(JSON.stringify(e))) }
}

// BDD: The apps show that the boiler is forced off
test('an admin turns the boiler off after confirming, and sees the notice', async ({ page }) => {
  const { posts } = await serve(page, jane, null)
  await page.goto('/')
  await page.getByTestId('boiler-chip').click()
  await page.getByTestId('boiler-off').click()
  await expect(page.getByText('Are you sure?')).toBeVisible()
  expect(posts).toEqual([])
  await page.getByTestId('boiler-off-confirm').click()
  await expect.poll(() => posts).toEqual(['/api/boiler/off'])
  await expect(page.getByTestId('boiler-override-notice')).toContainText('will not heat while the admin override is set')
  await page.getByTestId('boiler-override-got-it').click()
  await page.keyboard.press('Escape')
  await expect(page.getByTestId('boiler-chip')).toHaveAttribute('data-state-name', 'override')
  await expect(page.getByTestId('boiler-label')).toHaveText('Off (admin)')
})

// BDD: The apps show that the boiler is forced off
test('an admin turns the boiler back on from the chip', async ({ page }) => {
  const { posts } = await serve(page, jane, OFF)
  await page.goto('/')
  await expect(page.getByTestId('boiler-override-notice')).toBeVisible()
  await page.getByTestId('boiler-override-got-it').click()
  await page.getByTestId('boiler-chip').click()
  await page.getByTestId('boiler-on').click()
  await expect.poll(() => posts).toEqual(['/api/boiler/on'])
  await expect(page.getByTestId('boiler-chip')).toHaveAttribute('data-state-name', 'idle')
})

// BDD: The apps show that the boiler is forced off
test('a member is told, live, and has no boiler controls', async ({ page }) => {
  const { live } = await serve(page, john, null)
  await page.goto('/')
  await expect(page.getByTestId('boiler-override-notice')).toHaveCount(0)
  live({ type: 'boiler.override', boilerOverride: { override: OFF } })
  await expect(page.getByTestId('boiler-override-notice')).toContainText('Jane'.toLowerCase())
  await expect(page.getByTestId('boiler-on')).toHaveCount(0)
  await expect(page.getByTestId('boiler-chip')).toHaveCount(0)
})
