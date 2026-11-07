import { expect, test, type Page } from '@playwright/test'

// The connection setup steps and the lost-broker banner, against a mocked service.

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[], energy: true }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

interface Mock {
  setup: Record<string, unknown>
  failTest?: (body: Record<string, unknown>) => { reason: string; message: string } | null
  found?: { baseTopic: string; version: string | null; devices: number }[]
  registered?: boolean
}

async function serve(page: Page, mock: Mock) {
  const sent: { method: string; path: string; body: unknown }[] = []
  await page.routeWebSocket('**/ws', () => {})
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const method = request.method()
    const { pathname } = new URL(request.url())
    const body = method === 'GET' ? null : request.postDataJSON()
    if (pathname.startsWith('/api/setup/') && method !== 'GET') sent.push({ method, path: pathname, body })
    if (pathname === '/api/auth/me') return route.fulfill({ json: jane })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/rooms' || pathname === '/api/groups') return route.fulfill({ json: [] })
    if (pathname === '/api/setup/status') return route.fulfill({ json: mock.setup })
    if (pathname === '/api/setup/broker' && method === 'GET') return route.fulfill({ status: 404, json: { error: 'none' } })
    if (pathname === '/api/setup/broker/test') {
      const failure = mock.failTest?.(body as Record<string, unknown>)
      return route.fulfill({ json: failure ? { ok: false, ...failure } : { ok: true } })
    }
    if (pathname === '/api/setup/broker') return route.fulfill({ json: { host: 'mosquitto', port: 1883, tls: false, username: 'heating', passwordSet: true } })
    if (pathname === '/api/setup/zigbee2mqtt/discover') return route.fulfill({ json: mock.found ?? [] })
    if (pathname === '/api/setup/zigbee2mqtt') return route.fulfill({ json: { baseTopic: (body as { baseTopic: string }).baseTopic } })
    if (pathname === '/api/setup/test-notification') return route.fulfill({ json: { sent: mock.registered ?? false, registered: mock.registered ?? false } })
    if (pathname === '/api/setup/complete') {
      mock.setup = { ...mock.setup, complete: true }
      return route.fulfill({ status: 204 })
    }
    return route.fulfill({ status: 404, json: { error: 'not mocked' } })
  })
  return { sent }
}

// BDD: A new install with no broker asks for connection setup
test('a new install opens the setup steps instead of the Rooms screen', async ({ page }) => {
  await serve(page, { setup: { broker: 'missing', zigbee2mqtt: 'missing', complete: false } })
  await page.goto('/')
  const setup = page.getByTestId('connection-setup')
  await expect(setup).toBeVisible()
  for (const name of ['1. Broker', '2. Zigbee2MQTT', '3. Test notification']) await expect(setup.getByRole('list', { name: 'Setup steps' })).toContainText(name)
  await expect(page.getByText('The MQTT broker Zigbee2MQTT publishes to')).toBeVisible()
})

// BDD: An existing install is filled in from the environment
test('a complete install shows no setup steps', async ({ page }) => {
  await serve(page, { setup: { broker: 'connected', zigbee2mqtt: 'configured', complete: true } })
  await page.goto('/')
  await expect(page.getByTestId('connection-setup')).toHaveCount(0)
  await expect(page.getByTestId('broker-banner')).toHaveCount(0)
})

// BDD: An admin tests a broker before saving it
// BDD: A broker test explains why it failed
test('a failed broker test is shown beside its field and keeps what was typed', async ({ page }) => {
  const { sent } = await serve(page, {
    setup: { broker: 'missing', zigbee2mqtt: 'missing', complete: false },
    failTest: (body) => (body.host === 'mosquito' ? { reason: 'unknown_host', message: 'Can\'t find "mosquito". Check the host name.' } : null),
  })
  await page.goto('/')
  await page.getByLabel('Host').fill('mosquito')
  await page.getByLabel('Username').fill('heating')
  await page.getByLabel('Password', { exact: true }).fill('secret')
  await page.getByRole('button', { name: 'Test connection' }).click()
  await expect(page.getByTestId('error-broker-host')).toHaveText('Can\'t find "mosquito". Check the host name.')
  await expect(page.getByLabel('Host')).toHaveValue('mosquito')
  await expect(page.getByLabel('Username')).toHaveValue('heating')
  expect(sent.filter((s) => s.method === 'PUT')).toEqual([])

  // A port outside 1 to 65535 is not even tried.
  await page.getByLabel('Host').fill('mosquitto')
  await page.getByLabel('Port').fill('70000')
  await page.getByRole('button', { name: 'Test connection' }).click()
  await expect(page.getByTestId('error-broker-port')).toHaveText('Port must be from 1 to 65535.')
  expect(sent.filter((s) => s.path === '/api/setup/broker/test')).toHaveLength(1)

  await page.getByLabel('Port').fill('1883')
  await page.getByRole('button', { name: 'Test connection' }).click()
  await expect(page.getByTestId('broker-ok')).toBeVisible()
  expect(sent.at(-1)).toEqual({ method: 'POST', path: '/api/setup/broker/test', body: { host: 'mosquitto', port: 1883, tls: false, username: 'heating', password: 'secret' } })
})

// BDD: Zigbee2MQTT is found on the broker
// BDD: Zigbee2MQTT is not found on the broker
// BDD: Setup ends with a test notification
test('the steps run through to Finish', async ({ page }) => {
  const mock: Mock = {
    setup: { broker: 'missing', zigbee2mqtt: 'missing', complete: false },
    found: [
      { baseTopic: 'z2m', version: '2.1.0', devices: 14 },
      { baseTopic: 'attic', version: '1.9.0', devices: 3 },
    ],
  }
  const { sent } = await serve(page, mock)
  await page.goto('/')
  await page.getByLabel('Host').fill('mosquitto')
  await page.getByRole('button', { name: 'Save and continue' }).click()

  // Both bridges are offered and none is chosen.
  await expect(page.getByText('z2m (version 2.1.0, 14 devices)')).toBeVisible()
  await expect(page.getByText('attic (version 1.9.0, 3 devices)')).toBeVisible()
  await expect(page.getByRole('button', { name: 'Save and continue' })).toBeDisabled()
  await page.getByLabel('z2m (version 2.1.0, 14 devices)').check()
  await page.getByRole('button', { name: 'Save and continue' }).click()
  expect(sent.at(-1)).toEqual({ method: 'PUT', path: '/api/setup/zigbee2mqtt', body: { baseTopic: 'z2m' } })

  // No phone or browser registered.
  await page.getByRole('button', { name: 'Send test notification' }).click()
  await expect(page.getByTestId('no-device')).toBeVisible()
  await page.getByRole('button', { name: 'Finish' }).click()
  expect(sent.at(-1)?.path).toBe('/api/setup/complete')
  await expect(page.getByTestId('connection-setup')).toHaveCount(0)
})

test('nothing found on the broker says so and a base topic can be typed', async ({ page }) => {
  await serve(page, { setup: { broker: 'connected', zigbee2mqtt: 'missing', complete: false }, found: [] })
  await page.goto('/')
  await page.getByLabel('Host').fill('mosquitto')
  await page.getByRole('button', { name: 'Save and continue' }).click()
  await expect(page.getByTestId('no-bridge')).toHaveText(
    "No Zigbee2MQTT found on this broker. Check it's running and connected to the same broker, or type its base topic.",
  )
})

// BDD: The broker connection drops after setup
test('a lost broker shows a banner linking to Settings > Connection', async ({ page }) => {
  const since = '2026-11-07T18:04:00Z'
  await serve(page, { setup: { broker: 'disconnected', zigbee2mqtt: 'configured', complete: true, since, reason: 'connection reset' } })
  await page.goto('/')
  const banner = page.getByTestId('broker-banner')
  const time = new Date(since).toLocaleTimeString('en-GB', { hour: '2-digit', minute: '2-digit' })
  await expect(banner).toContainText(`Lost connection to your broker since ${time}`)
  await banner.getByRole('link', { name: /Connection/ }).click()
  await expect(page).toHaveURL(/\/settings\/connection$/)
  await expect(page.getByTestId('connection-setup')).toBeVisible()
})
