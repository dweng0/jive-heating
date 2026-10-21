import { expect, test, type Page } from '@playwright/test'

// The installed web app, against a mocked API: every /api call is answered here at the context
// level (so requests that pass through the service worker are answered too), and nobody is
// signed in, so the app shows the sign-in page.

test.beforeEach(async ({ context }) => {
  await context.routeWebSocket('**/ws', () => {})
  await context.route('**/api/**', (route) => {
    const { pathname } = new URL(route.request().url())
    if (pathname === '/api/auth/status') return route.fulfill({ json: { setupRequired: false } })
    return route.fulfill({ status: 401, json: { error: 'sign in first' } })
  })
})

/** Waits until the service worker controls the page, as it does for an app opened before. */
async function installed(page: Page) {
  await page.goto('/')
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
  const scope = await page.evaluate(async () => (await navigator.serviceWorker.ready).scope)
  if (!(await page.evaluate(() => navigator.serviceWorker.controller !== null))) await page.reload()
  await expect.poll(() => page.evaluate(() => navigator.serviceWorker.controller !== null)).toBe(true)
  return scope
}

// BDD: The service worker is served from the root and never cached stale
test('the service worker is registered for the whole site and keeps /api out of its cache', async ({ page, baseURL }) => {
  const scope = await installed(page)
  expect(scope).toBe(new URL('/', baseURL).href)
  // Signing in calls the API through the worker; nothing from /api may end up cached.
  await page.evaluate(() => fetch('/api/auth/me').catch(() => undefined))
  const cached = await page.evaluate(async () => {
    const urls: string[] = []
    for (const name of await caches.keys()) {
      for (const request of await (await caches.open(name)).keys()) urls.push(new URL(request.url).pathname)
    }
    return urls
  })
  expect(cached).toContain('/offline.html')
  expect(cached.filter((path) => path.startsWith('/api'))).toEqual([])
})

// BDD: An installed app with no connection says so
test('an installed app with no connection shows a friendly page and recovers', async ({ page, context }) => {
  await installed(page)
  await context.setOffline(true)
  await page.goto('/rooms/lounge')
  await expect(page.getByRole('heading', { name: 'Can’t reach the heating' })).toBeVisible()
  await context.setOffline(false)
  await page.getByRole('button', { name: 'Try again' }).click()
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
})
