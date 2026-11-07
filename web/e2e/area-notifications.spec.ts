import { expect, test, type Page } from '@playwright/test'

// Setting up an energy area's "still using power" notification on the area page, against a mocked service.

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[], energy: true }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }
const LIGHTS = { id: 'lights', name: 'Lights', wholeMeter: null, overlaps: [] }

async function serve(page: Page, initial: { hours: number | null; notify: string | null; by: string | null } = { hours: null, notify: null, by: null }) {
  let alert = { ...initial }
  const sent: { method: string; body: unknown }[] = []
  await page.routeWebSocket('**/ws', () => {})
  await page.routeWebSocket('**/ws/power', (ws) =>
    ws.send(JSON.stringify({ watts: 500, at: new Date().toISOString(), rooms: [], devices: [], areas: [{ area: 'lights', name: 'Lights', watts: 85 }], elseWatts: 415 })),
  )
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const method = request.method()
    const { pathname } = new URL(request.url())
    if (pathname === '/api/auth/me') return route.fulfill({ json: jane })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/rooms' || pathname === '/api/groups' || pathname === '/api/meters') return route.fulfill({ json: [] })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/power') return route.fulfill({ json: { bands: null, recent: [] } })
    if (pathname === '/api/energy/areas') return route.fulfill({ json: [LIGHTS] })
    if (pathname === '/api/energy/areas/lights/alert') {
      if (method === 'PUT') {
        const body = request.postDataJSON()
        sent.push({ method, body })
        alert = { ...body, by: 'jane' }
        return route.fulfill({ json: alert })
      }
      if (method === 'DELETE') {
        sent.push({ method, body: null })
        alert = { hours: null, notify: null, by: null }
        return route.fulfill({ status: 204 })
      }
      return route.fulfill({ json: alert })
    }
    return route.fulfill({ status: 404, json: { error: 'not mocked' } })
  })
  return { sent }
}

// BDD: An admin sets up an area notification in the app
test('an admin sets up an area notification in the app', async ({ page }) => {
  const { sent } = await serve(page)
  await page.goto('/energy/areas/lights')
  const controls = page.getByTestId('area-alert')
  await controls.getByRole('button', { name: 'Set up notification' }).click()
  const dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('if Lights is still using power')
  await dialog.getByLabel('Notify after').fill('2')
  await dialog.getByRole('button', { name: 'Save' }).click()
  await expect.poll(() => sent).toEqual([{ method: 'PUT', body: { hours: 2, notify: 'me' } }])
  await expect(controls.getByTestId('area-alert-line')).toHaveText('Notifies you after 2 h')

  // Everyone, in half hours.
  await controls.getByRole('button', { name: 'Edit notification' }).click()
  await dialog.getByLabel('Notify after').fill('1.5')
  await dialog.getByLabel('Everyone with energy access').check()
  await dialog.getByRole('button', { name: 'Save' }).click()
  await expect(controls.getByTestId('area-alert-line')).toHaveText('Notifies everyone after 1 h 30 min')
  expect(sent.at(-1)).toEqual({ method: 'PUT', body: { hours: 1.5, notify: 'everyone' } })

  // Not a half hour: refused before it is sent.
  await controls.getByRole('button', { name: 'Edit notification' }).click()
  await dialog.getByLabel('Notify after').fill('1.2')
  await expect(dialog.getByRole('button', { name: 'Save' })).toBeDisabled()
  await dialog.getByRole('button', { name: 'Cancel' }).click()

  await controls.getByRole('button', { name: 'Turn off' }).click()
  await expect.poll(() => sent.at(-1)?.method).toBe('DELETE')
  await expect(controls.getByTestId('area-alert-line')).toHaveCount(0)
  await expect(controls.getByRole('button', { name: 'Set up notification' })).toBeVisible()
})

test("another admin's notification names them", async ({ page }) => {
  await serve(page, { hours: 2, notify: 'me', by: 'john' })
  await page.goto('/energy/areas/lights')
  await expect(page.getByTestId('area-alert-line')).toHaveText('Notifies john after 2 h')
})
