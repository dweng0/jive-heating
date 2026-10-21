import { expect, test, type Page } from '@playwright/test'

// Web Push in the service worker, against a mocked API (nobody signed in). A push is delivered
// to the worker through the Chrome DevTools Protocol, as a push service would; the tap itself
// cannot be simulated, so where it leads is checked through the same code the worker runs.

// Full Chromium in its new headless mode: the old headless shell refuses notification permission.
test.use({ channel: 'chromium' })

test.beforeEach(async ({ context }) => {
  await context.routeWebSocket('**/ws', () => {})
  await context.route('**/api/**', (route) => {
    const { pathname } = new URL(route.request().url())
    if (pathname === '/api/auth/status') return route.fulfill({ json: { setupRequired: false } })
    return route.fulfill({ status: 401, json: { error: 'sign in first' } })
  })
})

async function workerReady(page: Page) {
  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
  await page.evaluate(async () => (await navigator.serviceWorker.ready).scope)
}

// BDD: Tapping a notification opens the room
test('a push shows the notification and a tap opens the room it names', async ({ page, context, baseURL }) => {
  await context.grantPermissions(['notifications'])
  const cdp = await context.newCDPSession(page)
  const registrations: string[] = []
  cdp.on('ServiceWorker.workerRegistrationUpdated', (e) => {
    for (const r of e.registrations) if (r.scopeURL === new URL('/', baseURL).href && !r.isDeleted) registrations.push(r.registrationId)
  })
  await cdp.send('ServiceWorker.enable')
  await workerReady(page)
  await expect.poll(() => registrations.length).toBeGreaterThan(0)

  const push = (data: object) =>
    cdp.send('ServiceWorker.deliverPushMessage', { origin: new URL(baseURL!).origin, registrationId: registrations[0], data: JSON.stringify(data) })
  const shown = () =>
    page.evaluate(async () =>
      (await (await navigator.serviceWorker.ready).getNotifications()).map((n) => ({ title: n.title, body: n.body, url: n.data?.url })),
    )

  await push({ title: 'Jane turned the heating on', body: 'Utility Room, heating to 22°', roomId: 'utility-room' })
  await expect.poll(shown).toContainEqual({ title: 'Jane turned the heating on', body: 'Utility Room, heating to 22°', url: '/rooms/utility-room' })

  // A notification about several rooms opens the Rooms screen.
  await push({ title: 'Jane turned the heating on', body: '3 rooms, heating to 21°', roomId: null })
  await expect.poll(shown).toContainEqual({ title: 'Jane turned the heating on', body: '3 rooms, heating to 21°', url: '/' })

  // The tap: the worker opens the notification's url in the app, taking an open window if there is one.
  await page.addScriptTag({ url: '/notifications.js' })
  const choices = await page.evaluate(() => {
    const n = (self as unknown as { heatingNotification: { url: (d: unknown) => string } }).heatingNotification
    return [n.url({ roomId: 'lounge' }), n.url({ roomId: 'a room/with?odd#chars' }), n.url({ roomId: null }), n.url({}), n.url(null)]
  })
  expect(choices).toEqual(['/rooms/lounge', '/rooms/a%20room%2Fwith%3Fodd%23chars', '/', '/', '/'])
})
