package heating.app.data

import heating.app.data.FakeHeatingApi.Companion.room
import heating.app.data.FakeHeatingApi.Companion.zone
import heating.app.ui.boostSettingLine
import heating.app.ui.boostStart
import heating.app.ui.noRoomsText
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class HeatingStoreTest {

    private val api = FakeHeatingApi().apply {
        rooms["lounge"] = room("lounge", "Lounge")
        rooms["kitchen"] = room("kitchen", "Kitchen")
    }

    @Test
    fun `start loads rooms and status`() = runTest {
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()

        assertEquals(listOf("Lounge", "Kitchen"), store.state.value.rooms.map { it.name })
        assertEquals(false, store.state.value.status?.heatDemand)
        assertEquals(false, store.state.value.loading)
    }

    // BDD: The apps start a boost at the household default
    @Test
    fun `boosts start at the household default`() = runTest {
        val store = HeatingStore(api, backgroundScope)
        assertEquals(22.0, store.state.value.boostTarget)
        api.boost = BoostSettings(defaultTarget = 20.0)
        store.start()
        runCurrent()

        assertEquals(20.0, store.state.value.boostTarget)
        // Whole house: the default itself. A room or group: 2 above its target, never below the default.
        assertEquals(20.0, boostStart(store.state.value.boostTarget, 17.0))
        assertEquals(21.0, boostStart(store.state.value.boostTarget, 19.0))
        assertEquals(22.0, boostStart(store.state.value.boostTarget, null)) // no target counts as 20.0
    }

    // BDD: An admin sets the default boost temperature in the app
    @Test
    fun `an admin saves the household boost temperature`() = runTest {
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()
        assertEquals("Default boost temperature 22.0°C", boostSettingLine(store.state.value.boostTarget))

        store.saveBoostTarget(20.5)
        runCurrent()

        assertTrue("saveBoostSettings 20.5" in api.calls)
        assertEquals(20.5, store.state.value.boostTarget)
        assertEquals("Default boost temperature 20.5°C", boostSettingLine(store.state.value.boostTarget))
    }

    @Test
    fun `a member's app also loads the household boost temperature`() = runTest {
        api.me = AccountUser("u-sam", "sam", admin = false)
        api.boost = BoostSettings(defaultTarget = 19.5)
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()

        assertEquals(19.5, store.state.value.boostTarget)
    }

    // BDD: The apps show that the boiler is forced off
    @Test
    fun `every app learns the boiler is forced off, at start and live, and an admin can end it`() = runTest {
        api.boilerOverride = BoilerOverride("jane", "2026-10-25T09:00:00+00:00")
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()
        assertEquals("jane", store.state.value.boilerOverride?.by, "loaded at start")

        store.boilerOn()
        runCurrent()
        assertNull(store.state.value.boilerOverride)
        assertTrue("boilerOn" in api.calls)

        api.live.send(LiveEvent(LiveEvent.BOILER_OVERRIDE, boilerOverride = BoilerOverrideStatus(BoilerOverride("jane", "2026-10-25T10:00:00+00:00"))))
        runCurrent()
        assertEquals("2026-10-25T10:00:00+00:00", store.state.value.boilerOverride?.since, "pushed live")

        store.boilerOn()
        runCurrent()
        store.boilerOff()
        runCurrent()
        assertEquals("jane", store.state.value.boilerOverride?.by)
    }

    @Test
    fun `a member's app is told the boiler is forced off`() = runTest {
        api.me = AccountUser("u-john", "john", admin = false)
        api.boilerOverride = BoilerOverride("jane", "2026-10-25T09:00:00+00:00")
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()
        assertEquals("jane", store.state.value.boilerOverride?.by)
        api.live.send(LiveEvent(LiveEvent.BOILER_OVERRIDE, boilerOverride = BoilerOverrideStatus(null)))
        runCurrent()
        assertNull(store.state.value.boilerOverride)
    }

    @Test
    fun `live room update replaces the room`() = runTest {
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()

        api.live.send(LiveEvent("room.updated", room = room("lounge", "Lounge", zone("lounge", Mode.MANUAL, 23.0))))
        runCurrent()

        assertEquals(23.0, store.state.value.room("lounge")?.target)
    }

    @Test
    fun `live room deleted removes it and status updates apply`() = runTest {
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()

        api.live.send(LiveEvent("room.deleted", roomId = "kitchen"))
        api.live.send(LiveEvent("status.updated", status = api.status.copy(heatDemand = true)))
        runCurrent()

        assertEquals(listOf("Lounge"), store.state.value.rooms.map { it.name })
        assertEquals(true, store.state.value.status?.heatDemand)
    }

    // BDD: The Android boiler chip opens the activity tray
    @Test
    fun `live activity events are kept for an open tray`() = runTest {
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()

        val event = ActivityEvent(9, "2026-10-18T12:00:00+01:00", "knob", room = "Lounge", value = kotlinx.serialization.json.JsonPrimitive(22.0))
        api.live.send(LiveEvent("activity.added", activity = event))
        runCurrent()

        assertEquals(event, store.state.value.latestActivity)
    }

    @Test
    fun `setting manual mode applies the returned room`() = runTest {
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()

        store.setMode("lounge", Mode.MANUAL, 21.5)
        runCurrent()

        assertTrue("setMode lounge MANUAL 21.5" in api.calls)
        assertEquals(Mode.MANUAL, store.state.value.room("lounge")?.mode)
        assertEquals(21.5, store.state.value.room("lounge")?.target)
    }

    @Test
    fun `failed action shows the server's message and keeps state`() = runTest {
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()
        api.fail = HeatingApiException(409, "cannot boost during a holiday")

        store.boost("lounge", 60, 22.0)
        runCurrent()

        assertEquals("Cannot boost during a holiday.", store.state.value.message)
        assertNull(store.state.value.room("lounge")?.zones?.single()?.boost)
        store.clearMessage()
        assertNull(store.state.value.message)
    }

    @Test
    fun `dial changes are sent once after the user stops`() = runTest {
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()

        store.nudgeTarget("lounge", 21.0)
        store.nudgeTarget("lounge", 21.5)
        store.nudgeTarget("lounge", 22.0)
        assertEquals(22.0, store.state.value.pendingTargets["lounge"])
        advanceTimeBy(HeatingStore.TARGET_DEBOUNCE_MS + 1)
        runCurrent()

        assertEquals(listOf("setMode lounge MANUAL 22.0"), api.calls.filter { it.startsWith("setMode") })
        assertNull(store.state.value.pendingTargets["lounge"])
    }

    @Test
    fun `dial change in schedule mode switches the zone to manual`() = runTest {
        // Contract v1 has no app-initiated "override until next block"; the dial sets manual.
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()

        store.nudgeTarget("lounge", 22.0)
        // backgroundScope work does not count for advanceUntilIdle; advance past the debounce instead.
        advanceTimeBy(HeatingStore.TARGET_DEBOUNCE_MS + 1)
        runCurrent()

        assertTrue(api.calls.any { it == "setMode lounge MANUAL 22.0" })
    }

    @Test
    fun `groups load with rooms and follow live updates`() = runTest {
        api.groups["up"] = Group("up", "Upstairs", listOf("lounge"), 19.5, 21.0, "manual", true)
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()
        assertEquals(listOf("Upstairs"), store.state.value.groups.map { it.name })

        api.live.send(LiveEvent("groups.updated", groups = listOf(Group("up", "Top floor", listOf("lounge"), 20.0, null, "mixed", false))))
        runCurrent()
        assertEquals("mixed", store.state.value.group("up")?.mode)
    }

    @Test
    fun `group control is sent once and rooms are reloaded`() = runTest {
        api.groups["up"] = Group("up", "Upstairs", listOf("lounge", "kitchen"), null, null, "schedule", false)
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()
        api.calls.clear()

        store.controlGroup("up", GroupControlRequest(mode = Mode.MANUAL, target = 21.0))
        runCurrent()

        assertEquals(listOf("controlGroup up GroupControlRequest(mode=MANUAL, target=21.0, boost=null)", "rooms"), api.calls)
        assertEquals(21.0, store.state.value.group("up")?.target)
    }

    @Test
    fun `setting every room replaces the rooms and refetches groups`() = runTest {
        api.groups["up"] = Group("up", "Upstairs", listOf("lounge"), null, null, "schedule", false)
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()
        api.calls.clear()
        api.groups["up"] = api.groups.getValue("up").copy(mode = "manual", target = 21.0)

        store.controlHouse(GroupControlRequest(mode = Mode.MANUAL, target = 21.0))
        runCurrent()

        assertEquals(listOf("controlHouse GroupControlRequest(mode=MANUAL, target=21.0, boost=null)", "groups"), api.calls)
        assertEquals(listOf(21.0, 21.0), store.state.value.rooms.map { it.target })
        assertTrue(store.state.value.rooms.all { it.mode == Mode.MANUAL })
        assertEquals("manual", store.state.value.group("up")?.mode)
    }

    @Test
    fun `all on schedule and all off apply to every room`() = runTest {
        api.rooms["lounge"] = room("lounge", "Lounge", zone("lounge", Mode.MANUAL, 23.0))
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()

        store.controlHouse(GroupControlRequest(mode = Mode.SCHEDULE))
        runCurrent()
        assertTrue(store.state.value.rooms.all { it.mode == Mode.SCHEDULE })

        store.controlHouse(GroupControlRequest(mode = Mode.OFF))
        runCurrent()
        assertTrue(store.state.value.rooms.all { it.mode == Mode.OFF })
    }

    @Test
    fun `boost all then cancel all boosts`() = runTest {
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()

        store.controlHouse(GroupControlRequest(boost = 60, target = 22.0))
        runCurrent()
        assertTrue("controlHouse GroupControlRequest(mode=null, target=22.0, boost=60)" in api.calls)
        assertEquals(listOf(22.0, 22.0), store.state.value.rooms.map { it.zones.single().boost?.target })

        store.cancelHouseBoosts()
        runCurrent()
        assertTrue("cancelHouseBoosts" in api.calls)
        assertTrue(store.state.value.rooms.all { r -> r.zones.all { it.boost == null } })
    }

    @Test
    fun `failed house action shows the message and keeps the rooms`() = runTest {
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()
        api.fail = HeatingApiException(409, "cannot boost during a holiday")

        store.controlHouse(GroupControlRequest(boost = 30, target = 22.0))
        runCurrent()

        assertEquals("Cannot boost during a holiday.", store.state.value.message)
        assertTrue(store.state.value.rooms.all { r -> r.zones.all { it.boost == null } })
    }

    @Test
    fun `the connection is live as soon as the live feed opens`() = runTest {
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()
        assertEquals(ConnectionState.CONNECTING, store.state.value.connection)

        api.live.send(LiveEvent(LiveEvent.CONNECTED))
        runCurrent()
        assertEquals(ConnectionState.LIVE, store.state.value.connection)
    }

    @Test
    fun `an unreachable service shows in the chip, not as a pop-up`() = runTest {
        api.fail = java.net.UnknownHostException("Unable to resolve host \"heating.example\": No address associated with hostname")
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()

        assertNull(store.state.value.message)
        assertEquals(ConnectionState.OFFLINE, store.state.value.connection)
        assertEquals("Can't reach the heating service. Check this phone's internet connection.", store.state.value.connectionProblem)
    }

    // BDD: A member's app shows only their rooms
    @Test
    fun `a member's app loads only their rooms and never asks for the whole house`() = runTest {
        // The service returns only the rooms sam can see, and 403 for status and groups.
        api.me = AccountUser("u-sam", "sam", admin = false, rooms = listOf("bedroom"))
        api.rooms.clear()
        api.rooms["bedroom"] = room("bedroom", "Bedroom")
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()

        assertEquals(listOf("me", "rooms", "boostSettings", "boiler"), api.calls)
        val state = store.state.value
        assertEquals(false, state.admin)
        assertEquals(listOf("Bedroom"), state.rooms.map { it.name })
        assertNull(state.status)
        assertTrue(state.groups.isEmpty())
        assertNull(state.message)

        // Live events about the whole house or other rooms do not reach a member's screen.
        api.live.send(LiveEvent("status.updated", status = api.status.copy(heatDemand = true)))
        api.live.send(LiveEvent("groups.updated", groups = listOf(Group("up", "Upstairs", listOf("lounge"), null, null, "schedule", false))))
        api.live.send(LiveEvent("room.updated", room = room("lounge", "Lounge")))
        runCurrent()
        assertNull(store.state.value.status)
        assertTrue(store.state.value.groups.isEmpty())
        assertEquals(listOf("Bedroom"), store.state.value.rooms.map { it.name })
    }

    // BDD: Live updates only carry rooms a member can see
    @Test
    fun `a member's rooms follow access changes straight away`() = runTest {
        api.me = AccountUser("u-sam", "sam", admin = false, rooms = listOf("bedroom"))
        api.rooms.clear()
        api.rooms["bedroom"] = room("bedroom", "Bedroom")
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()
        api.calls.clear()

        // An admin gives sam the Lounge: the service says so and the app fetches sam and the rooms again.
        api.me = api.me.copy(rooms = listOf("lounge", "bedroom"))
        api.rooms.clear()
        api.rooms["lounge"] = room("lounge", "Lounge")
        api.rooms["bedroom"] = room("bedroom", "Bedroom")
        api.live.send(LiveEvent("access.updated"))
        runCurrent()
        assertEquals(listOf("me", "rooms", "boostSettings", "boiler"), api.calls)
        assertEquals(listOf("Lounge", "Bedroom"), store.state.value.rooms.map { it.name })

        // Made an admin: the whole house loads.
        api.me = api.me.copy(admin = true)
        api.live.send(LiveEvent("access.updated"))
        runCurrent()
        assertEquals(true, store.state.value.admin)
        assertEquals(false, store.state.value.status?.heatDemand)

        // A member again, with the Bedroom taken away.
        api.me = api.me.copy(admin = false, rooms = listOf("lounge"))
        api.rooms.remove("bedroom")
        api.live.send(LiveEvent("access.updated"))
        runCurrent()
        assertEquals(false, store.state.value.admin)
        assertNull(store.state.value.status)
        assertEquals(listOf("Lounge"), store.state.value.rooms.map { it.name })
    }

    @Test
    fun `a member with no rooms is told to ask an admin`() = runTest {
        api.me = AccountUser("u-kit", "kit", admin = false)
        api.rooms.clear()
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()

        assertEquals("No rooms yet. Ask an admin to give you access.", noRoomsText(store.state.value.admin))
        assertEquals(false, store.state.value.loading)
        assertTrue(store.state.value.rooms.isEmpty())
    }

    @Test
    fun `an admin loads the whole house`() = runTest {
        val store = HeatingStore(api, backgroundScope)
        store.start()
        runCurrent()
        assertEquals(listOf("me", "rooms", "boostSettings", "boiler", "status", "groups"), api.calls)
        assertEquals(true, store.state.value.admin)
    }

    @Test
    fun `errors never show raw exception text`() {
        assertEquals("Something went wrong. Please try again.", friendlyError(IllegalStateException("java.lang.Boom at heating.Foo.bar(Foo.kt:12)")))
        assertEquals("The heating service took too long to answer.", friendlyError(java.net.SocketTimeoutException("timeout")))
        assertEquals("Lost the connection to the heating service.", friendlyError(java.io.EOFException("eof")))
    }
}
