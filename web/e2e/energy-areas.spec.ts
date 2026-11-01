import { expect, test, type Page } from '@playwright/test'

// Energy areas on the Energy tab, against a mocked service that keeps the areas it is given.

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[] }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

async function serve(page: Page) {
  const areas: { id: string; name: string }[] = []
  const sent: string[] = []
  const taken = (name: string, except?: string) => areas.some((a) => a.id !== except && a.name.toLowerCase() === name.toLowerCase())
  await page.routeWebSocket('**/ws', () => {})
  await page.routeWebSocket('**/ws/power', () => {})
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const { pathname } = new URL(request.url())
    if (pathname === '/api/auth/me') return route.fulfill({ json: jane })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/rooms' || pathname === '/api/groups') return route.fulfill({ json: [] })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/energy/areas' && request.method() === 'GET') return route.fulfill({ json: areas })
    if (pathname === '/api/energy/areas' && request.method() === 'POST') {
      sent.push(`POST ${pathname}`)
      const { name } = request.postDataJSON()
      if (taken(name)) return route.fulfill({ status: 409, json: { error: `an area called ${name} already exists` } })
      const area = { id: name.toLowerCase().replace(/[^a-z0-9]+/g, '-'), name }
      areas.push(area)
      return route.fulfill({ status: 201, json: area })
    }
    const area = areas.find((a) => pathname === `/api/energy/areas/${a.id}`)
    if (area && request.method() === 'PATCH') {
      sent.push(`PATCH ${pathname}`)
      const { name } = request.postDataJSON()
      if (taken(name, area.id)) return route.fulfill({ status: 409, json: { error: `an area called ${name} already exists` } })
      area.name = name
      return route.fulfill({ json: area })
    }
    if (area && request.method() === 'DELETE') {
      sent.push(`DELETE ${pathname}`)
      areas.splice(areas.indexOf(area), 1)
      return route.fulfill({ status: 204 })
    }
    return route.fulfill({ status: 404, json: { error: 'not mocked' } })
  })
  return { sent }
}

async function addArea(page: Page, name: string) {
  await page.getByRole('button', { name: 'Add area' }).click()
  await page.getByLabel('Area name').fill(name)
  await page.getByRole('dialog').getByRole('button', { name: 'Add area' }).click()
}

// BDD: An admin creates an energy area
test('an admin adds an area, renames it and deletes it', async ({ page }) => {
  const { sent } = await serve(page)
  await page.goto('/energy')
  await addArea(page, 'Garage/Office')
  const areas = page.getByTestId('energy-areas')
  await expect(areas.getByText('Garage/Office')).toBeVisible()
  expect(sent).toEqual(['POST /api/energy/areas'])

  await areas.getByRole('button', { name: 'Rename Garage/Office' }).click()
  await page.getByLabel('Area name').fill('Workshop')
  await page.getByRole('dialog').getByRole('button', { name: 'Rename' }).click()
  await expect(areas.getByText('Workshop')).toBeVisible()
  expect(sent).toContain('PATCH /api/energy/areas/garage-office')

  await areas.getByRole('button', { name: 'Delete Workshop' }).click()
  await page.getByRole('alertdialog').getByRole('button', { name: 'Delete' }).click()
  await expect(areas.getByText('Workshop')).toHaveCount(0)
  expect(sent).toContain('DELETE /api/energy/areas/garage-office')
})

// BDD: An admin creates an energy area
test('a name another area uses is refused and the dialog stays open', async ({ page }) => {
  await serve(page)
  await page.goto('/energy')
  await addArea(page, 'Lights')
  await expect(page.getByTestId('energy-areas').getByText('Lights')).toBeVisible()
  await addArea(page, 'lights')
  await expect(page.getByText('an area called lights already exists').first()).toBeVisible()
  await expect(page.getByRole('dialog')).toBeVisible()
  await expect(page.getByLabel('Area name')).toHaveValue('lights')
})
