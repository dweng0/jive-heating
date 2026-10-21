import { expect, test, type BrowserContext, type Page } from '@playwright/test'
import { needsHomeScreen } from '../src/lib/webPush'

// Notifications on the Account page, against a mocked API. The browser's own Notification and
// PushManager are replaced with fakes before the app loads, so nothing ever reaches a real push
// service; the fakes record what the app asked of them.

const PUBLIC_KEY = 'BPkVhC0ZbQ6rvmb5hTq4tHvAyw9Uf8i2gUpwiEhNdG8-k8bUUyXq3HDdFVYYDd7tq0dScA2ChNzt-7b1czk0-8c'
const ENDPOINT = 'https://web.push.apple.com/QGuQyavXutnMHy-fake'

const IPHONE_SAFARI =
  'Mozilla/5.0 (iPhone; CPU iPhone OS 18_5 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Mobile/15E148 Safari/604.1'
const IPAD_DESKTOP_MODE =
  'Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/18.5 Safari/605.1.15'
const MAC_SAFARI = IPAD_DESKTOP_MODE
const ANDROID_CHROME =
  'Mozilla/5.0 (Linux; Android 15; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Mobile Safari/537.36'
const WINDOWS_EDGE =
  'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.0.0 Safari/537.36 Edg/141.0.0.0'

interface Calls {
  webPushPuts: { endpoint: string; keys: { p256dh: string; auth: string } }[]
  webPushDeletes: { endpoint: string }[]
  prefsPuts: { heatingOn: boolean; heatingFinished: boolean }[]
}

async function serve(context: BrowserContext, webPush: boolean) {
  const calls: Calls = { webPushPuts: [], webPushDeletes: [], prefsPuts: [] }
  let prefs = { heatingOn: true, heatingFinished: false }
  await context.routeWebSocket('**/ws', () => {})
  await context.route('**/api/**', (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    const method = request.method()
    if (pathname === '/api/auth/me') return route.fulfill({ json: { id: 'u-john', username: 'john', admin: false, rooms: [] } })
    if (pathname === '/api/rooms' || pathname === '/api/groups') return route.fulfill({ json: [] })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/me/webpush' && method === 'GET') return route.fulfill({ json: webPush ? { webPush: true, publicKey: PUBLIC_KEY } : { webPush: false } })
    if (pathname === '/api/me/webpush' && method === 'PUT') {
      calls.webPushPuts.push(request.postDataJSON())
      return route.fulfill({ status: 204 })
    }
    if (pathname === '/api/me/webpush' && method === 'DELETE') {
      calls.webPushDeletes.push(request.postDataJSON())
      return route.fulfill({ status: 204 })
    }
    if (pathname === '/api/me/notifications' && method === 'GET') return route.fulfill({ json: prefs })
    if (pathname === '/api/me/notifications' && method === 'PUT') {
      prefs = request.postDataJSON()
      calls.prefsPuts.push(prefs)
      return route.fulfill({ json: prefs })
    }
    return route.fulfill({ status: 404, json: { error: `not mocked: ${method} ${pathname}` } })
  })
  return calls
}

/** Fakes for the browser's notification permission and push subscription; [answer] is what the permission prompt answers. */
async function fakePush(page: Page, permission: NotificationPermission, answer: NotificationPermission, standalone = false) {
  await page.addInitScript(
    ({ permission, answer, endpoint, standalone }) => {
      const state = { permission, answer, permissionRequests: 0, subscribed: null as null | { userVisibleOnly: boolean; key: string }, subscription: null as unknown }
      ;(window as unknown as { fakePush: typeof state }).fakePush = state
      Object.defineProperty(Notification, 'permission', { configurable: true, get: () => state.permission })
      Notification.requestPermission = async () => {
        state.permissionRequests++
        state.permission = answer
        return answer
      }
      const subscription = {
        endpoint,
        toJSON: () => ({ endpoint, expirationTime: null, keys: { p256dh: 'BFakeP256dh', auth: 'fake-auth' } }),
        unsubscribe: async () => {
          state.subscription = null
          return true
        },
      }
      PushManager.prototype.getSubscription = async () => state.subscription as PushSubscription | null
      PushManager.prototype.subscribe = async (options?: PushSubscriptionOptionsInit) => {
        const key = new Uint8Array(options!.applicationServerKey as ArrayBuffer)
        state.subscribed = { userVisibleOnly: options!.userVisibleOnly === true, key: btoa(String.fromCharCode(...key)) }
        state.subscription = subscription
        return subscription as unknown as PushSubscription
      }
      if (standalone) Object.defineProperty(navigator, 'standalone', { configurable: true, get: () => true })
    },
    { permission, answer, endpoint: ENDPOINT, standalone },
  )
}

const fake = (page: Page) =>
  page.evaluate(() => {
    const s = (window as unknown as { fakePush: { permissionRequests: number; subscribed: unknown; subscription: unknown } }).fakePush
    return { permissionRequests: s.permissionRequests, subscribed: s.subscribed, hasSubscription: s.subscription !== null }
  })

/** The public key as standard base64, as the fake records the applicationServerKey bytes. */
const standardBase64 = (key: string) => Buffer.from(key, 'base64url').toString('base64')

// BDD: Account turns notifications on for this browser
test('account turns notifications on for this browser', async ({ page, context }) => {
  const calls = await serve(context, true)
  await fakePush(page, 'default', 'granted')
  await page.goto('/account')

  const device = page.getByRole('switch', { name: 'Notifications on this device' })
  await expect(device).toBeVisible()
  await expect(device).not.toBeChecked()
  await expect(page.getByRole('switch', { name: 'Someone turns the heating on' })).toBeChecked()
  await expect(page.getByRole('switch', { name: 'Heating finished' })).not.toBeChecked()
  // Opening Account never asks for permission by itself.
  expect((await fake(page)).permissionRequests).toBe(0)

  await device.click()
  await expect(device).toBeChecked()
  expect(await fake(page)).toEqual({ permissionRequests: 1, subscribed: { userVisibleOnly: true, key: standardBase64(PUBLIC_KEY) }, hasSubscription: true })
  expect(calls.webPushPuts).toEqual([{ endpoint: ENDPOINT, expirationTime: null, keys: { p256dh: 'BFakeP256dh', auth: 'fake-auth' } }])

  await page.getByRole('switch', { name: 'Heating finished' }).click()
  await expect(page.getByRole('switch', { name: 'Heating finished' })).toBeChecked()
  expect(calls.prefsPuts).toEqual([{ heatingOn: true, heatingFinished: true }])

  await device.click()
  await expect(device).not.toBeChecked()
  expect(calls.webPushDeletes).toEqual([{ endpoint: ENDPOINT }])
  expect((await fake(page)).hasSubscription).toBe(false)
})

test('refused permission says to allow notifications in the phone settings', async ({ page, context }) => {
  const calls = await serve(context, true)
  await fakePush(page, 'default', 'denied')
  await page.goto('/account')
  await page.getByRole('switch', { name: 'Notifications on this device' }).click()
  await expect(page.getByText(/allow notifications for Heating in your phone.s settings/)).toBeVisible()
  await expect(page.getByRole('switch', { name: 'Notifications on this device' })).not.toBeChecked()
  expect(calls.webPushPuts).toEqual([])
  expect((await fake(page)).subscribed).toBeNull()

  // Opening Account again later says the same straight away.
  await fakePush(page, 'denied', 'denied')
  await page.reload()
  await expect(page.getByText(/allow notifications for Heating in your phone.s settings/)).toBeVisible()
  expect((await fake(page)).permissionRequests).toBe(0)
})

test('without web push the web app shows no notification settings', async ({ page, context }) => {
  await serve(context, false)
  await fakePush(page, 'default', 'granted')
  await page.goto('/account')
  await expect(page.getByRole('heading', { name: 'john' })).toBeVisible()
  await expect(page.getByText('Notifications on this device')).toHaveCount(0)
})

// BDD: iPhone Safari explains how to add the app to the home screen
test.describe('iPhone Safari explains how to add the app to the home screen', () => {
  test('only an iPhone or iPad browser outside the home screen app needs it', () => {
    expect(needsHomeScreen({ userAgent: IPHONE_SAFARI, maxTouchPoints: 5, standalone: false })).toBe(true)
    // iPadOS asks for desktop pages and says it is a Mac; only its touch screen gives it away.
    expect(needsHomeScreen({ userAgent: IPAD_DESKTOP_MODE, maxTouchPoints: 5, standalone: false })).toBe(true)
    expect(needsHomeScreen({ userAgent: IPHONE_SAFARI, maxTouchPoints: 5, standalone: true })).toBe(false)
    expect(needsHomeScreen({ userAgent: MAC_SAFARI, maxTouchPoints: 0, standalone: false })).toBe(false)
    expect(needsHomeScreen({ userAgent: ANDROID_CHROME, maxTouchPoints: 5, standalone: false })).toBe(false)
    expect(needsHomeScreen({ userAgent: WINDOWS_EDGE, maxTouchPoints: 0, standalone: false })).toBe(false)
  })

  test.describe('on an iPhone', () => {
    test.use({ userAgent: IPHONE_SAFARI, hasTouch: true, isMobile: true, viewport: { width: 390, height: 844 } })

    test('Safari shows the home screen hint instead of the switch', async ({ page, context }) => {
      await serve(context, true)
      await fakePush(page, 'default', 'granted')
      await page.goto('/account')
      await expect(page.getByText(/tap Share, then “Add to Home Screen”/)).toBeVisible()
      await expect(page.getByRole('switch', { name: 'Notifications on this device' })).toHaveCount(0)
      // Who gets which notifications is still theirs to choose.
      await expect(page.getByRole('switch', { name: 'Someone turns the heating on' })).toBeVisible()
    })

    test('the home screen app shows the switch and no hint', async ({ page, context }) => {
      await serve(context, true)
      await fakePush(page, 'default', 'granted', true)
      await page.goto('/account')
      await expect(page.getByRole('switch', { name: 'Notifications on this device' })).toBeVisible()
      await expect(page.getByText(/Add to Home Screen/)).toHaveCount(0)
    })
  })

  test('other devices show no hint', async ({ page, context }) => {
    await serve(context, true)
    await fakePush(page, 'default', 'granted')
    await page.goto('/account')
    await expect(page.getByRole('switch', { name: 'Notifications on this device' })).toBeVisible()
    await expect(page.getByText(/Add to Home Screen/)).toHaveCount(0)
  })
})
