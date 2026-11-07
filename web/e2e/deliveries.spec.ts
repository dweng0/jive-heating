import { expect, test, type Page } from '@playwright/test'

// Failed notification deliveries and the Deliveries table on the web app, against a mocked service.

const jane = { id: 'u-jane', username: 'jane', admin: true, rooms: [] as string[] }
const sam = { id: 'u-sam', username: 'sam', admin: false, rooms: [] as string[] }
const STATUS = { heatDemand: false, boiler: { state: 'idle', until: null }, holiday: null, serverTime: new Date().toISOString(), timezone: 'Europe/London' }

interface D {
  id: string
  at: string
  source: string
  userId: string
  person: string
  title: string
  body: string
  status: string
  tries: number
  sentAt?: string | null
  sentTo: string[]
  attempts: { at: string; error: string }[]
  event?: unknown
}

const failedOne = (id: string, person: string): D => ({
  id, at: '2026-11-07T08:00:00Z', source: 'Front door opened', userId: `u-${person}`, person, title: 'Front door opened', body: 'The front door was opened',
  status: 'failed', tries: 4, sentTo: [],
  attempts: [1, 2, 3, 4].map((n) => ({ at: `2026-11-07T08:0${n}:00Z`, error: 'phone …-phone: push service unreachable or refusing' })),
})

async function serve(page: Page, me: typeof jane, all: D[]) {
  const calls: string[] = []
  await page.routeWebSocket('**/ws', () => {})
  await page.route('**/api/**', async (route) => {
    const request = route.request()
    const url = new URL(request.url())
    const { pathname } = url
    const method = request.method()
    if (pathname === '/api/auth/me') return route.fulfill({ json: me })
    if (pathname === '/api/status') return route.fulfill({ json: STATUS })
    if (pathname === '/api/rooms' || pathname === '/api/groups' || pathname === '/api/meters') return route.fulfill({ json: [] })
    if (pathname === '/api/app') return route.fulfill({ json: { android: null } })
    if (pathname === '/api/me/webpush') return route.fulfill({ json: { webPush: false } })
    if (pathname === '/api/deliveries' && method === 'GET') {
      if (!me.admin) return route.fulfill({ status: 403, json: { error: 'admins only' } })
      calls.push(`GET ${url.search}`)
      let rows = all.filter((d) => !url.searchParams.get('status') || d.status === url.searchParams.get('status'))
      const rule = url.searchParams.get('rule')
      const person = url.searchParams.get('person')
      if (rule) rows = rows.filter((d) => d.source === rule)
      if (person) rows = rows.filter((d) => d.person === person)
      const sort = url.searchParams.get('sort')
      if (sort === 'person') rows = [...rows].sort((a, b) => a.person.localeCompare(b.person) * (url.searchParams.get('dir') === 'asc' ? 1 : -1))
      return route.fulfill({
        json: {
          deliveries: rows,
          failed: all.filter((d) => d.status === 'failed').length,
          sources: [...new Set(all.map((d) => d.source))],
          people: [{ id: 'u-jane', name: 'jane' }, { id: 'u-sam', name: 'sam' }],
        },
      })
    }
    if (pathname === '/api/deliveries/retry-all') {
      calls.push('retry-all')
      all.filter((d) => d.status === 'failed').forEach((d) => (d.status = 'queued'))
      return route.fulfill({ json: { retried: 1 } })
    }
    const one = pathname.match(/^\/api\/deliveries\/([^/]+)(\/retry)?$/)
    if (one) {
      const d = all.find((x) => x.id === one[1])!
      if (method === 'DELETE') {
        calls.push(`clear ${d.id}`)
        all.splice(all.indexOf(d), 1)
        return route.fulfill({ status: 204 })
      }
      if (one[2]) {
        calls.push(`retry ${d.id}`)
        d.status = 'queued'
      }
      return route.fulfill({ json: d })
    }
    return route.fulfill({ status: 404, json: { error: 'not mocked' } })
  })
  return { calls }
}

// BDD: A delivery that keeps failing goes to failed deliveries
test('admins see failed notifications on Settings and can retry or clear them', async ({ page }) => {
  const { calls } = await serve(page, jane, [failedOne('d1', 'jane'), failedOne('d2', 'sam')])
  await page.goto('/account')
  await expect(page.getByTestId('failed-count')).toHaveText('2 failed notifications')
  const first = page.getByTestId('failed-delivery').first()
  await expect(first).toContainText('Front door opened')
  await expect(first).toContainText('For jane')
  await expect(first).toContainText('push service unreachable or refusing')

  await first.getByRole('button', { name: 'Retry' }).click()
  await expect.poll(() => calls).toContain('retry d1')
  await expect(page.getByTestId('failed-count')).toHaveText('1 failed notification')

  await page.getByTestId('failed-delivery').first().getByRole('button', { name: 'Clear' }).click()
  await expect.poll(() => calls).toContain('clear d2')
  await expect(page.getByTestId('failed-count')).toHaveCount(0)
  await expect(page.getByTestId('deliveries-card')).toContainText('Every notification has been delivered.')
})

// BDD: A delivery that keeps failing goes to failed deliveries
test('Retry all queues every failed notification', async ({ page }) => {
  const { calls } = await serve(page, jane, [failedOne('d1', 'jane'), failedOne('d2', 'sam')])
  await page.goto('/account')
  await page.getByRole('button', { name: 'Retry all' }).click()
  await expect.poll(() => calls).toContain('retry-all')
  await expect(page.getByTestId('deliveries-card')).toContainText('Every notification has been delivered.')
})

test('members see no deliveries', async ({ page }) => {
  await serve(page, sam, [failedOne('d1', 'jane')])
  await page.goto('/account')
  await expect(page.getByRole('heading', { name: 'Account' }).or(page.getByText('sam')).first()).toBeVisible()
  await expect(page.getByTestId('deliveries-card')).toHaveCount(0)
})
