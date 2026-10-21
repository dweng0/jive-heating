import { execFileSync } from 'node:child_process'
import { expect, test, type Page } from '@playwright/test'

// One fresh service per run (playwright.config.ts). These run in order and build on each other:
// setup makes the admin, later tests add rooms and people.
test.describe.configure({ mode: 'serial' })

const SERVICE_TOKEN = 'e2e-service-token'
const ADMIN = { username: 'jane', password: 'correct horse battery' }
const MEMBER = { username: 'sam', password: 'battery staple horse' }

async function signIn(page: Page, user: { username: string; password: string }) {
  await page.goto('/')
  await page.getByLabel('Username').fill(user.username)
  await page.getByLabel('Password', { exact: true }).fill(user.password)
  await page.getByRole('button', { name: 'Sign in' }).click()
}

/** Publishes to the test Mosquitto (see start-service.sh), as Zigbee2MQTT would. */
function publish(topic: string, payload: string) {
  execFileSync('docker', ['exec', process.env.E2E_MQTT_CONTAINER ?? 'heating-e2e-mosquitto', 'mosquitto_pub', '-t', topic, '-m', payload])
}

/** A room's tile on the rooms page; its name is the link that opens the room. */
const roomTile = (page: Page, name: string) =>
  page.getByTestId('room-tile').filter({ has: page.getByRole('link', { name, exact: true }) })

async function signOut(page: Page) {
  await page.getByRole('button', { name: 'Account menu' }).click()
  await page.getByRole('menuitem', { name: 'Sign out' }).click()
  await expect(page.getByRole('button', { name: 'Sign in' })).toBeVisible()
}

test('first run asks for the service token and creates the admin', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByText('Set up your heating')).toBeVisible()
  const create = page.getByRole('button', { name: 'Create admin account' })

  await page.getByLabel('Service token').fill('wrong-token')
  await page.getByLabel('Username').fill(ADMIN.username)
  await page.getByLabel('Password', { exact: true }).fill(ADMIN.password)
  await page.getByLabel('Confirm password').fill('something else entirely')
  await expect(create).toBeDisabled() // passwords differ

  await page.getByLabel('Confirm password').fill(ADMIN.password)
  await create.click()
  await expect(page.getByRole('alert')).toContainText('wrong service token')

  await page.getByLabel('Service token').fill(SERVICE_TOKEN)
  await page.getByLabel('Password', { exact: true }).fill(ADMIN.password)
  await page.getByLabel('Confirm password').fill(ADMIN.password)
  await create.click()
  await expect(page.getByText(`Good`)).toContainText(ADMIN.username)
  await expect(page.getByText('No rooms yet')).toBeVisible()
})

test('the session survives a reload and is an HttpOnly cookie the page cannot read', async ({ page, context }) => {
  await signIn(page, ADMIN)
  await expect(page.getByRole('heading', { level: 1 })).toBeVisible()
  await page.reload()
  await expect(page.getByRole('button', { name: 'Add room' })).toBeVisible()

  const cookie = (await context.cookies()).find((c) => c.name === '__Host-heat')
  expect(cookie?.httpOnly).toBe(true)
  expect(cookie?.sameSite).toBe('Strict')
  expect(await page.evaluate(() => document.cookie)).not.toContain('__Host-heat')
})

test('a wrong password is refused, and repeated failures lock the name', async ({ page }) => {
  // Unknown names get the same answer and the same lock, so this does not lock out the admin.
  await page.goto('/')
  await page.getByLabel('Username').fill('mallory')
  for (let i = 0; i < 5; i++) {
    await page.getByLabel('Password', { exact: true }).fill(`wrong password ${i}`)
    await page.getByRole('button', { name: 'Sign in' }).click()
    await expect(page.getByRole('alert')).toContainText('wrong username or password')
  }
  await page.getByLabel('Password', { exact: true }).fill('one more guess')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('alert')).toContainText('Too many attempts')
})

test('add a room, give it a valve and turn it up', async ({ page }) => {
  await signIn(page, ADMIN)

  await page.getByRole('button', { name: 'Add your first room' }).click()
  await page.getByPlaceholder('e.g. Living room').fill('Lounge')
  await page.getByRole('button', { name: 'Add room' }).last().click()
  await expect(page.getByRole('heading', { name: 'Lounge' })).toBeVisible()

  await page.getByRole('button', { name: 'Add valve' }).click()
  await page.getByRole('button', { name: /Lounge TRV/ }).click()
  await expect(page.getByText('Lounge TRV', { exact: true })).toBeVisible()
  await expect(page.getByText('now 19.0°')).toBeVisible()

  await page.getByRole('tab', { name: 'Manual' }).click()
  const target = page.getByRole('img', { name: /target/ })
  const before = await target.getAttribute('aria-label')
  await page.getByRole('button', { name: 'Warmer' }).first().click()
  await page.getByRole('button', { name: 'Warmer' }).first().click()
  await expect(page.getByText(/Manual at/)).toBeVisible({ timeout: 5_000 })
  await expect(target).not.toHaveAttribute('aria-label', before!)

  await page.getByRole('link', { name: 'Back to rooms' }).click()
  await expect(roomTile(page, 'Lounge')).toContainText('19.0°')
})

test('a room sensor is added like a valve, measures the room, and the valves take over while it is offline', async ({ page }) => {
  await signIn(page, ADMIN)
  await page.getByRole('link', { name: /Lounge/ }).click()
  const sensorCard = page.locator('[data-slot=card]').filter({ has: page.getByText('Temperature sensor', { exact: true }) })
  await expect(sensorCard).toContainText('No sensor')
  await expect(page.getByRole('button', { name: 'About this temperature' })).toHaveCount(0)

  // The picker lists unassigned sensors with their reading and battery, like the valve picker.
  await page.getByRole('button', { name: 'Add sensor' }).click()
  const picker = page.getByRole('dialog')
  await expect(picker.getByRole('button', { name: /Hall Smoke/ })).toContainText('21.3°')
  await expect(picker.getByRole('button', { name: /Hall Smoke/ })).toContainText('battery 95%')
  await expect(picker.getByRole('button', { name: /Landing Smoke/ })).toContainText('low battery')
  await picker.getByRole('button', { name: /Hall Smoke/ }).click()
  await expect(sensorCard.getByText('Hall Smoke', { exact: true })).toBeVisible()
  await expect(page.getByText('now 21.3°')).toBeVisible() // the dial follows the sensor

  // The info button opens a note, from the keyboard as well as by click or tap.
  const info = page.getByRole('button', { name: 'About this temperature' })
  await info.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('dialog')).toContainText('separate thermometer (Hall Smoke)')
  await expect(page.getByRole('dialog')).toContainText('goes back to the valves')
  await page.keyboard.press('Escape')
  await expect(page.getByText('separate thermometer (Hall Smoke)')).toBeHidden()

  // Picking another sensor replaces the first, and says so first.
  await page.getByRole('button', { name: 'Change sensor' }).click()
  await expect(picker).toContainText('replaces Hall Smoke')
  await picker.getByRole('button', { name: /Landing Smoke/ }).click()
  await expect(sensorCard.getByText('Landing Smoke', { exact: true })).toBeVisible()
  await expect(sensorCard.getByText('Low battery')).toBeVisible()
  await expect(page.getByText('now 17.2°')).toBeVisible()

  // Removing it hands the room back to its valves.
  await page.getByRole('button', { name: 'Remove Landing Smoke' }).click()
  await expect(sensorCard).toContainText('No sensor')
  await expect(page.getByText('now 19.0°')).toBeVisible()

  await page.getByRole('button', { name: 'Add sensor' }).click()
  await picker.getByRole('button', { name: /Hall Smoke/ }).click()
  await expect(page.getByText('now 21.3°')).toBeVisible()

  // Offline: the room falls back to the valve reading and says so, here and on its tile.
  publish('zigbee2mqtt/Hall Smoke/availability', '{"state":"offline"}')
  await expect(page.getByText('Sensor offline — using valve readings')).toHaveCount(2) // badge and sensor section
  await expect(page.getByText('now 19.0°')).toBeVisible()
  await expect(sensorCard.getByText('Offline', { exact: true })).toBeVisible()
  await expect(page.getByRole('button', { name: 'About this temperature' })).toHaveCount(0)
  await page.getByRole('link', { name: 'Back to rooms' }).click()
  const tile = roomTile(page, 'Lounge')
  await expect(tile).toContainText('Sensor offline — using valve readings')
  await expect(tile).toContainText('19.0°')

  // Back online: the badge goes, and the tile's info button opens the note by click.
  publish('zigbee2mqtt/Hall Smoke/availability', '{"state":"online"}')
  await expect(tile).not.toContainText('Sensor offline')
  await expect(tile).toContainText('21.3°')
  await tile.getByRole('button', { name: 'About this temperature' }).click()
  await expect(page.getByRole('dialog')).toContainText('separate thermometer (Hall Smoke)')
  await expect(page).toHaveURL(/\/$/) // the button sits on the tile but does not open the room
  await page.keyboard.press('Escape')
  await tile.getByRole('link', { name: 'Lounge' }).click()
  await expect(page.getByRole('heading', { name: 'Lounge' })).toBeVisible()
})

test('a boost can be started and cancelled', async ({ page }) => {
  await signIn(page, ADMIN)
  await page.getByRole('link', { name: /Lounge/ }).click()
  await page.getByRole('button', { name: '30 min' }).click()
  await expect(page.getByText(/Boosting to/)).toBeVisible()
  await page.getByRole('button', { name: 'Cancel' }).click()
  await expect(page.getByText(/Boosting to/)).toBeHidden()
})

test('a schedule day can be edited and saved', async ({ page }) => {
  await signIn(page, ADMIN)
  await page.getByRole('link', { name: /Lounge/ }).click()
  await page.getByRole('button', { name: /^mon$/i }).click()
  const save = page.getByRole('button', { name: /Save monday/i })
  await expect(save).toBeDisabled()
  await page.getByRole('button', { name: 'Warmer' }).nth(1).click() // first block's temperature
  await expect(save).toBeEnabled()
  await save.click()
  await expect(page.getByText('Monday saved')).toBeVisible()
})

test('holiday can be started and ended from the header', async ({ page }) => {
  await signIn(page, ADMIN)
  await page.getByRole('button', { name: 'Holiday' }).click()
  await page.getByRole('button', { name: 'Start holiday' }).click()
  await expect(page.getByText(/every room at 12.0°/)).toBeVisible()
  await page.getByRole('button', { name: 'Holiday' }).click()
  await page.getByRole('button', { name: 'End holiday now' }).click()
  await expect(page.getByText(/every room at/)).toBeHidden()
})

test('a group sets all its rooms at once, and other open pages see it live', async ({ page, browser }) => {
  await signIn(page, ADMIN)
  await page.getByRole('button', { name: 'Add room' }).click()
  await page.getByPlaceholder('e.g. Living room').fill('Bedroom')
  await page.getByRole('button', { name: 'Add room' }).last().click()
  await page.getByRole('button', { name: 'Add valve' }).click()
  await page.getByRole('button', { name: /Bedroom TRV/ }).click()
  await expect(page.getByRole('dialog')).toBeHidden() // the picker closes before the room lists the valve
  await expect(page.getByText('Bedroom TRV', { exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Back to rooms' }).click()

  const other = await browser.newPage()
  await signIn(other, ADMIN)
  await expect(other.getByRole('heading', { name: 'Groups' })).toBeVisible()

  await page.getByRole('button', { name: 'New group' }).click()
  const add = page.getByRole('button', { name: 'Add group' })
  await expect(add).toBeDisabled() // needs a name
  await page.getByLabel('Group name').fill('Upstairs')
  await page.getByRole('checkbox', { name: 'Lounge' }).click()
  await page.getByRole('checkbox', { name: 'Bedroom' }).click()
  await add.click()
  await expect(page.getByRole('heading', { name: 'Upstairs' })).toBeVisible()
  await expect(page.getByRole('link', { name: /Bedroom/ })).toBeVisible()

  // The other page gets the new group from the groups.updated live event, without a reload.
  const tile = other.getByRole('link', { name: 'Group Upstairs' })
  await expect(tile).toBeVisible()
  await expect(tile).toContainText('Lounge, Bedroom')
  await expect(tile).toContainText('Mixed') // Lounge is manual, Bedroom on its schedule

  await page.getByRole('tab', { name: 'Manual' }).click()
  await expect(tile).toContainText('Manual at 21.0°')
  await page.getByRole('button', { name: 'Warmer for the group' }).click()
  await page.getByRole('button', { name: 'Warmer for the group' }).click()
  await expect(tile).toContainText('Manual at 22.0°', { timeout: 5_000 })

  await page.getByRole('button', { name: '1.5 h' }).click()
  await page.getByRole('button', { name: 'Warmer boost' }).click()
  await page.getByRole('button', { name: 'Boost 1.5 h' }).click()
  await expect(page.getByText('2 rooms boosting')).toBeVisible()
  await expect(page.getByText(/Boost to 22.5°/)).toHaveCount(2) // each room in the list

  // The group page and the group's tile say when the boost ends.
  const boostEnd = page.getByText(/^Boosting until \d\d:\d\d$/)
  await expect(boostEnd).toBeVisible()
  const latest = (await boostEnd.textContent())!.replace('Boosting until ', '')
  await expect(tile).toContainText(`Boosting until ${latest}`)

  // When the rooms' boosts end at different times, they give the latest end and how many rooms boost.
  await roomTile(other, 'Lounge').getByRole('link', { name: 'Lounge', exact: true }).click()
  await other.getByRole('button', { name: 'Cancel' }).click() // a boosted room is re-boosted after cancelling
  await other.getByRole('button', { name: '30 min' }).click()
  await expect(other.getByText(/Boosting to/)).toBeVisible()
  await expect(page.getByText(`2 rooms boosting · until ${latest}`)).toBeVisible()
  await expect(boostEnd).toBeHidden()
  await other.getByRole('link', { name: 'Back to rooms' }).click()
  await expect(tile).toContainText(`2 rooms boosting · until ${latest}`)

  await page.getByRole('button', { name: 'Cancel boost' }).click()
  await expect(page.getByText(/rooms boosting/)).toBeHidden()
  await expect(tile).not.toContainText(/boosting/i)

  // The room list shows the sensor info button for a room its sensor measures, and the offline badge while that sensor is offline.
  const lounge = page.getByTestId('group-room').filter({ has: page.getByRole('link', { name: 'Lounge', exact: true }) })
  const bedroom = page.getByTestId('group-room').filter({ has: page.getByRole('link', { name: 'Bedroom', exact: true }) })
  await expect(bedroom.getByRole('button', { name: 'About this temperature' })).toHaveCount(0)
  await lounge.getByRole('button', { name: 'About this temperature' }).click()
  await expect(page.getByRole('dialog')).toContainText('separate thermometer (Hall Smoke)')
  await expect(page).toHaveURL(/\/groups\//) // the button sits on the row but does not open the room
  await page.keyboard.press('Escape')
  publish('zigbee2mqtt/Hall Smoke/availability', '{"state":"offline"}')
  await expect(lounge).toContainText('Sensor offline — using valve readings')
  await expect(lounge.getByRole('button', { name: 'About this temperature' })).toHaveCount(0)
  await expect(bedroom).not.toContainText('Sensor offline')
  publish('zigbee2mqtt/Hall Smoke/availability', '{"state":"online"}')
  await expect(lounge).not.toContainText('Sensor offline')
  await expect(lounge.getByRole('button', { name: 'About this temperature' })).toBeVisible()

  await page.getByRole('tab', { name: 'Off' }).click()
  await expect(tile).toContainText('Off')
  await page.getByRole('tab', { name: 'Schedule' }).click()
  await expect(tile).toContainText('Schedule')
  await other.close()
})

test('a group can be renamed, given different rooms and deleted, leaving its rooms', async ({ page }) => {
  await signIn(page, ADMIN)
  await page.getByRole('link', { name: 'Group Upstairs' }).click()
  await page.getByRole('button', { name: 'Edit group' }).click()
  await page.getByLabel('Group name').fill('Bedrooms')
  await page.getByRole('checkbox', { name: 'Lounge' }).click()
  await page.getByRole('button', { name: 'Save group' }).click()
  await expect(page.getByRole('heading', { name: 'Bedrooms' })).toBeVisible()
  await expect(page.getByRole('link', { name: /Bedroom/ })).toBeVisible()
  await expect(page.getByRole('link', { name: /Lounge/ })).toHaveCount(0)

  // A room can be in more than one group, but group names are unique.
  await page.getByRole('link', { name: 'Back to rooms' }).click()
  await page.getByRole('button', { name: 'New group' }).click()
  await page.getByLabel('Group name').fill('Bedrooms')
  await page.getByRole('checkbox', { name: 'Bedroom' }).click()
  await page.getByRole('button', { name: 'Add group' }).click()
  await expect(page.getByRole('dialog')).toBeVisible() // refused; the dialog stays open
  await page.getByLabel('Group name').fill('Quiet rooms')
  await page.getByRole('button', { name: 'Add group' }).click()
  await expect(page.getByRole('heading', { name: 'Quiet rooms' })).toBeVisible()

  for (const name of ['Quiet rooms', 'Bedrooms']) {
    await page.goto('/')
    await page.getByRole('link', { name: `Group ${name}` }).click()
    await page.getByRole('button', { name: 'Delete group' }).click()
    await page.getByRole('button', { name: 'Delete group' }).last().click()
    await expect(page).toHaveURL(/\/$/)
    await expect(page.getByRole('link', { name: `Group ${name}` })).toHaveCount(0)
  }
  await expect(page.getByRole('link', { name: /Bedroom/ })).toBeVisible()
  await expect(page.getByText('Group rooms, like Upstairs')).toBeVisible()
})

test('the admin adds a member, who can sign in but cannot manage people', async ({ page }) => {
  await signIn(page, ADMIN)
  await page.getByRole('link', { name: 'People' }).click()
  await page.getByRole('button', { name: 'Add person' }).click()
  await page.getByLabel('Username').fill(MEMBER.username)
  await page.getByLabel('Password', { exact: true }).fill('short')
  await expect(page.getByRole('button', { name: 'Add person' }).last()).toBeDisabled()
  await page.getByLabel('Password', { exact: true }).fill(MEMBER.password)
  await page.getByRole('button', { name: 'Add person' }).last().click()
  await expect(page.getByText(`${MEMBER.username} can now sign in`)).toBeVisible()
  // A new member sees no rooms until the admin gives them some.
  await expect(page.getByTestId(/^person-/).filter({ hasText: MEMBER.username })).toContainText('Sees no rooms')
  await page.getByRole('button', { name: `Rooms for ${MEMBER.username}` }).click()
  const rooms = page.getByRole('dialog')
  await rooms.getByRole('checkbox', { name: 'Lounge' }).click()
  await expect(rooms.getByRole('checkbox', { name: 'Lounge' })).toBeChecked()
  await page.keyboard.press('Escape')
  await expect(page.getByTestId(/^person-/).filter({ hasText: MEMBER.username })).toContainText('Sees 1 room')
  await signOut(page)

  await signIn(page, MEMBER)
  await expect(page.getByRole('link', { name: /Lounge/ })).toBeVisible()
  await expect(page.getByRole('link', { name: 'People' })).toHaveCount(0)
  await page.goto('/people')
  await expect(page).toHaveURL(/\/$/)
})

test('changing your password keeps you signed in and ends your other sessions', async ({ page, browser }) => {
  await signIn(page, MEMBER)
  await expect(page.getByRole('link', { name: /Lounge/ })).toBeVisible()

  const other = await browser.newPage()
  await signIn(other, MEMBER)
  await expect(other.getByRole('link', { name: /Lounge/ })).toBeVisible()

  await page.goto('/account')
  await page.getByLabel('Current password', { exact: true }).fill(MEMBER.password)
  await page.getByLabel('New password', { exact: true }).fill('a brand new passphrase')
  await page.getByLabel('Confirm new password').fill('a brand new passphrase')
  await page.getByRole('button', { name: 'Change password' }).click()
  await expect(page.getByText('Password changed')).toBeVisible()
  await page.goto('/')
  await expect(page.getByRole('link', { name: /Lounge/ })).toBeVisible()

  await other.reload()
  await expect(other.getByRole('button', { name: 'Sign in' })).toBeVisible()
  await other.close()
})

test('the admin removes a member, whose open session then ends', async ({ page, browser }) => {
  const member = await browser.newPage()
  await signIn(member, { username: MEMBER.username, password: 'a brand new passphrase' })
  await expect(member.getByRole('link', { name: /Lounge/ })).toBeVisible()

  await signIn(page, ADMIN)
  await page.getByRole('link', { name: 'People' }).click()
  await page.getByRole('button', { name: `Options for ${MEMBER.username}` }).click()
  await page.getByRole('menuitem', { name: 'Remove' }).click()
  await page.getByRole('button', { name: 'Remove' }).click()
  await expect(page.getByText(`${MEMBER.username} removed`)).toBeVisible()

  await member.reload()
  await expect(member.getByRole('button', { name: 'Sign in' })).toBeVisible()
  await member.close()
})

test('pages work on a phone-sized screen without sideways scrolling', async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 })
  await signIn(page, ADMIN)
  await page.getByRole('link', { name: /Lounge/ }).click()
  await expect(page.getByRole('heading', { name: 'Lounge' })).toBeVisible()
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(0)
})

test('the room sensor note opens with a tap on a touch screen', async ({ browser }) => {
  const phone = await browser.newContext({ viewport: { width: 390, height: 844 }, hasTouch: true, isMobile: true })
  const page = await phone.newPage()
  await signIn(page, ADMIN)
  const tile = roomTile(page, 'Lounge')
  await tile.getByRole('button', { name: 'About this temperature' }).tap()
  await expect(page.getByRole('dialog')).toContainText('separate thermometer (Hall Smoke)')
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - document.documentElement.clientWidth)
  expect(overflow).toBeLessThanOrEqual(0)
  await phone.close()
})

// BDD: A room's page shows its temperature and target over time
test("a room's page shows its temperature and target over time", async ({ page }) => {
  const now = Date.now()
  const iso = (minutesAgo: number) => new Date(now - minutesAgo * 60_000).toISOString()
  let empty = false
  const ranges: string[] = []
  await signIn(page, ADMIN)
  await expect(page.getByRole('button', { name: 'Add room' })).toBeVisible()
  const lounge = await roomByName(page, 'Lounge')
  await page.route('**/api/history?*', (route) => {
    ranges.push(new URL(route.request().url()).searchParams.get('range')!)
    const points = Array.from({ length: 24 }, (_, k) => ({ at: iso(115 - k * 5), current: 19.5 + (k % 4) * 0.1, target: k < 12 ? 21 : 18 }))
    return route.fulfill({
      json: {
        from: iso(24 * 60),
        to: iso(0),
        rooms: [{ id: lounge.id, name: 'Lounge', points: empty ? [] : points }],
        house: [],
        boiler: [{ state: 'heating', from: iso(90), to: iso(50) }],
      },
    })
  })

  await page.goto(`/rooms/${lounge.id}`)
  const history = page.locator('[data-slot="card"]', { has: page.getByRole('heading', { name: 'History' }) })
  await history.scrollIntoViewIfNeeded()
  await expect(history.locator('.temperature-line .recharts-line-curve')).toBeVisible()
  await expect(history.locator('.target-line .recharts-line-curve')).toBeVisible()
  await expect(history.getByText('Temperature')).toBeVisible()
  await expect(history.getByText('Target')).toBeVisible()

  await history.getByRole('button', { name: '7 days' }).click()
  await expect.poll(() => ranges.at(-1)).toBe('7d')
  await history.getByRole('button', { name: '30 days' }).click()
  await expect.poll(() => ranges.at(-1)).toBe('30d')

  empty = true
  await history.getByRole('button', { name: '24 hours' }).click()
  await expect(history.getByText('within five minutes')).toBeVisible()
  await expect(history.locator('.temperature-line')).toHaveCount(0)
})

/** Calls the service from the signed-in page (its cookie), as the web app does. */
async function call(page: Page, method: string, path: string, data?: unknown) {
  const result = await page.evaluate(
    async ({ method, path, body }) => {
      const headers: Record<string, string> = { 'X-Heat-Client': 'web' }
      if (body !== undefined) headers['Content-Type'] = 'application/json'
      const response = await fetch(path, { method, headers, body, credentials: 'same-origin' })
      return { ok: response.ok, status: response.status, text: await response.text() }
    },
    { method, path, body: data === undefined ? undefined : JSON.stringify(data) },
  )
  expect(result.ok, `${method} ${path}: ${result.status} ${result.text}`).toBe(true)
  return result.text ? JSON.parse(result.text) : undefined
}

async function roomByName(page: Page, name: string): Promise<{ id: string; zones: { id: string }[] }> {
  const rooms = (await call(page, 'GET', '/api/rooms')) as { id: string; name: string; zones: { id: string }[] }[]
  return rooms.find((r) => r.name === name)!
}

const setRoom = async (page: Page, name: string, mode: string, target?: number) =>
  call(page, 'PUT', `/api/zones/${encodeURIComponent((await roomByName(page, name)).zones[0].id)}/mode`, { mode, target })

// The service runs with a 1-minute minimum run and no minimum rest (start-service.sh).
test('the boiler chip says what the boiler is doing, with a countdown while it finishes its run', async ({ page }) => {
  test.setTimeout(240_000)
  await signIn(page, ADMIN)
  await expect(page.getByRole('button', { name: 'Add room' })).toBeVisible()
  const chip = page.getByTestId('boiler-chip')
  const chipLabel = page.getByTestId('boiler-label')
  const title = page.getByRole('heading', { level: 1 })

  await call(page, 'POST', '/api/house', { mode: 'off' })
  await expect(chipLabel).toHaveText('Idle', { timeout: 90_000 }) // after any run still finishing
  await expect(title).toHaveText('Heating is off')
  await expect(page.getByText('Live', { exact: true })).toBeVisible()
  await chip.focus()
  await page.keyboard.press('Enter')
  await expect(page.getByRole('dialog')).toContainText('No room needs heat right now.')
  await page.keyboard.press('Escape')

  // One room calls for heat: the boiler fires at once and the headline names the room.
  await setRoom(page, 'Lounge', 'manual', 25)
  await expect(chipLabel).toHaveText('Heating')
  await expect(title).toHaveText('Heating Lounge to 25.0°')
  await chip.click()
  await expect(page.getByRole('dialog')).toContainText('A room needs heat, so the boiler is firing.')
  await page.keyboard.press('Escape')

  // Nothing calls any more, but the boiler finishes its minimum run, counting down.
  await setRoom(page, 'Lounge', 'off')
  await expect(chipLabel).toHaveText(/^Finishing run · 0:\d\d$/)
  await expect(title).toHaveText('Boiler finishing its run')
  const first = await chipLabel.textContent()
  await expect(chipLabel).not.toHaveText(first!) // ticks without a refetch
  await chip.click()
  await expect(page.getByRole('dialog')).toContainText('minimum 4-minute run')
  await page.keyboard.press('Escape')
  await expect(chipLabel).toHaveText('Idle', { timeout: 75_000 })
  await expect(title).toHaveText('Heating is off')
})

test('the headline names the heating room, the heating group, or how many rooms heat', async ({ page }) => {
  await signIn(page, ADMIN)
  await expect(page.getByRole('button', { name: 'Add room' })).toBeVisible()
  const title = page.getByRole('heading', { level: 1 })
  const spare = (await call(page, 'POST', '/api/rooms', { name: 'Spare' })) as { id: string }
  await call(page, 'PUT', `/api/rooms/${spare.id}/valves/${encodeURIComponent('Spare TRV')}`)
  const lounge = await roomByName(page, 'Lounge')
  const bedroom = await roomByName(page, 'Bedroom')
  const kids = (await call(page, 'POST', '/api/groups', { name: 'Kids', rooms: [lounge.id, bedroom.id] })) as { id: string }

  await setRoom(page, 'Bedroom', 'manual', 24)
  await expect(title).toHaveText('Heating Bedroom to 24.0°')

  await call(page, 'POST', `/api/groups/${kids.id}/control`, { mode: 'manual', target: 25 })
  await expect(title).toHaveText('Heating the Kids group to 25.0°')

  await setRoom(page, 'Spare', 'manual', 25)
  await expect(title).toHaveText('Heating 3 rooms to 25.0°')
  await setRoom(page, 'Spare', 'manual', 24)
  await expect(title).toHaveText('Heating 3 rooms')

  await call(page, 'POST', '/api/house', { mode: 'off' })
  await expect(title).toHaveText(/^(Boiler finishing its run|Heating is off)$/)
})

test('whole house only boosts every room and turns the boosts off', async ({ page }) => {
  await signIn(page, ADMIN)
  await expect(page.getByRole('button', { name: 'Add room' })).toBeVisible()
  await call(page, 'POST', '/api/house', { mode: 'schedule' })
  await page.reload()
  const card = page.locator('[data-slot="card"]', { has: page.getByRole('heading', { name: 'Whole house' }) })
  await expect(card.getByRole('button', { name: 'Set every room' })).toHaveCount(0)
  await expect(card.getByRole('button', { name: /^All (on|off)$/ })).toHaveCount(0)
  const off = card.getByRole('button', { name: 'Boost off' })
  await expect(off).toBeDisabled()
  await card.getByRole('button', { name: '1 h' }).click()
  await expect(card).toContainText('Every room is boosted')
  await off.click()
  await expect(card).toContainText('Every room is on its schedule')
  await expect(off).toBeDisabled()
})

// BDD: The dashboard says when there is no history yet
test('the dashboard says when there is no history yet', async ({ page }) => {
  await signIn(page, ADMIN)
  await expect(page.getByRole('button', { name: 'Add room' })).toBeVisible()
  await page.goto('/dashboard')
  await expect(page.getByText('No history yet')).toBeVisible()
  await expect(page.getByText('within five minutes')).toBeVisible()
})

// BDD: The dashboard charts each room, the house median and the boiler
test('the dashboard charts each room, the house median and the boiler', async ({ page }) => {
  const now = Date.now()
  const iso = (minutesAgo: number) => new Date(now - minutesAgo * 60_000).toISOString()
  const points = (base: number) =>
    Array.from({ length: 24 }, (_, k) => ({ at: iso(115 - k * 5), current: base + (k % 4) * 0.2, target: 21 }))
  const ranges: string[] = []
  await page.route('**/api/history?*', (route) => {
    ranges.push(new URL(route.request().url()).searchParams.get('range')!)
    return route.fulfill({
      json: {
        from: iso(24 * 60),
        to: iso(0),
        rooms: [
          { id: 'lounge', name: 'Lounge', points: points(19) },
          { id: 'bedroom', name: 'Bedroom', points: points(17) },
        ],
        house: points(18).map(({ at, current }) => ({ at, current })),
        boiler: [
          { state: 'idle', from: iso(26 * 60), to: iso(90) },
          { state: 'heating', from: iso(90), to: iso(50) },
          { state: 'cycling', from: iso(50), to: iso(46) },
          { state: 'idle', from: iso(46), to: null },
        ],
      },
    })
  })

  await signIn(page, ADMIN)
  await expect(page.getByRole('button', { name: 'Add room' })).toBeVisible()
  await page.goto('/dashboard')
  await expect(page.locator('.room-line .recharts-line-curve')).toHaveCount(2)
  await expect(page.locator('.house-line .recharts-line-curve')).toBeVisible()
  await expect(page.locator('.boiler-heating')).toHaveCount(1)
  await expect(page.locator('.boiler-cycling')).toHaveCount(1)
  await expect(page.getByText('Boiler on')).toBeVisible()
  await expect(page.getByText('44 min')).toBeVisible()

  await page.getByRole('button', { name: /Bedroom/ }).click()
  await expect(page.locator('.room-line .recharts-line-curve')).toHaveCount(1)

  await page.getByRole('button', { name: '7 days' }).click()
  await expect.poll(() => ranges.at(-1)).toBe('7d')
  await page.getByRole('button', { name: '30 days' }).click()
  await expect.poll(() => ranges.at(-1)).toBe('30d')
})
