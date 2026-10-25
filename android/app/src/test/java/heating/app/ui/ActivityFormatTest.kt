package heating.app.ui

import heating.app.data.ActivityEvent
import heating.app.ui.components.mergeActivity
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals

class ActivityFormatTest {

    private val london = ZoneId.of("Europe/London")

    private fun event(
        id: Long,
        kind: String,
        value: JsonPrimitive? = null,
        actor: String? = null,
        room: String? = null,
        reason: String? = null,
        rooms: List<String> = emptyList(),
        count: Int? = null,
        at: String = "2026-10-18T12:00:00+01:00",
        device: String? = null,
    ) = ActivityEvent(id, at, kind, actor, room, value, reason, rooms, count, device)

    private val setpoint = event(1, "setpoint", JsonPrimitive(21.0), "John", "Utility Room")

    // BDD: The Android boiler chip opens the activity tray
    @Test
    fun `a person setting a room is a sentence on a tablet and a few words on a phone`() {
        assertEquals("John turned the Utility Room thermostat to 21°", activityText(setpoint, brief = false))
        assertEquals("John · Utility 21°", activityText(setpoint, brief = true))
    }

    // BDD: The Android boiler chip opens the activity tray
    @Test
    fun `boiler lines name the room that reached temperature`() {
        val heating = event(2, "boiler", JsonPrimitive("heating"), reason = "calling", rooms = listOf("Utility Room"))
        val cycling = event(3, "boiler", JsonPrimitive("cycling"), reason = "reached-temp", rooms = listOf("Utility Room"))
        val idle = event(4, "boiler", JsonPrimitive("idle"), reason = "no-demand")
        val all = listOf(heating, cycling, idle)
        assertEquals(listOf("Boiler is now heating", "Utility Room has reached temperature, boiler cycling down", "Boiler is now idle"), all.map { activityText(it, false) })
        assertEquals(listOf("Boiler heating", "Utility at temp", "Boiler idle"), all.map { activityText(it, true) })
        assertEquals("Boiler cycled ×2", activityText(event(5, "boiler", JsonPrimitive("cycled"), count = 2), true))
    }

    // BDD: The Android boiler chip opens the activity tray
    @Test
    fun `knob schedule offline and holiday events read naturally`() {
        val knob = event(1, "knob", JsonPrimitive(22.0), room = "Lounge")
        assertEquals("The Lounge thermostat was turned to 22°", activityText(knob, false))
        assertEquals("Lounge knob 22°", activityText(knob, true))
        assertEquals("Schedule set Lounge to 21°", activityText(event(2, "schedule", JsonPrimitive(21.0), room = "Lounge"), false))
        assertEquals("Lounge went offline", activityText(event(3, "offline", JsonPrimitive("offline"), room = "Lounge"), false))
        assertEquals("Jane started a holiday", activityText(event(4, "holiday", JsonPrimitive("start"), actor = "Jane"), false))
        assertEquals("The boost in Bedroom ended", activityText(event(5, "boost", room = "Bedroom"), false))
    }

    @Test
    fun `an unresponsive valve is named with its room`() {
        val stuck = event(1, "unresponsive", JsonPrimitive("unresponsive"), room = "Utility")
        val fine = event(2, "unresponsive", JsonPrimitive("responding"), room = "Utility")
        assertEquals("Utility is not responding to commands", activityText(stuck, false))
        assertEquals("Utility is responding again", activityText(fine, false))
        assertEquals("Utility not responding", activityText(stuck, true))
    }

    // BDD: The apps show that the boiler is forced off
    @Test
    fun `an admin turning the boiler off or on reads naturally`() {
        val off = event(1, "override", JsonPrimitive("off"), actor = "Jane")
        val on = event(2, "override", JsonPrimitive("on"), actor = "Jane")
        assertEquals("Jane turned the boiler off", activityText(off, false))
        assertEquals("Jane turned the boiler back on", activityText(on, false))
        assertEquals("Boiler turned off", activityText(off, true))
    }

    // BDD: A plug losing contact is in the activity feed
    @Test
    fun `a plug losing contact is named with its room`() {
        val off = event(1, "offline", JsonPrimitive("offline"), room = "Bedroom 1", device = "TV Plug")
        val back = event(2, "offline", JsonPrimitive("online"), room = "Bedroom 1", device = "TV Plug")
        val roomless = event(3, "offline", JsonPrimitive("offline"), device = "Kettle Plug")
        assertEquals("TV Plug in Bedroom 1 went offline", activityText(off, false))
        assertEquals("TV Plug in Bedroom 1 is back online", activityText(back, false))
        assertEquals("Kettle Plug went offline", activityText(roomless, false))
        assertEquals(listOf("TV Plug offline", "TV Plug online", "Kettle Plug offline"), listOf(off, back, roomless).map { activityText(it, true) })
    }

    // BDD: The Android tray can be read with a screen reader
    @Test
    fun `talkback reads the full sentence with its time even where the brief wording is shown`() {
        val now = Instant.parse("2026-10-18T11:05:00Z") // 12:05 in London, five minutes after the event
        assertEquals("John · Utility 21°", activityText(setpoint, brief = true))
        assertEquals("John turned the Utility Room thermostat to 21°, 5m ago", activityDescription(setpoint, now, london))
    }

    // BDD: The Android boiler chip opens the activity tray
    @Test
    fun `times read now minutes hours yesterday then the date`() {
        val now = Instant.parse("2026-10-18T11:00:00Z")
        fun ago(at: String) = agoText(at, now, london)
        assertEquals("now", ago("2026-10-18T11:59:30+01:00"))
        assertEquals("5m ago", ago("2026-10-18T11:55:00+01:00"))
        assertEquals("2h ago", ago("2026-10-18T10:00:00+01:00"))
        assertEquals("yesterday", ago("2026-10-17T09:00:00+01:00"))
        assertEquals("15 Oct", ago("2026-10-15T09:00:00+01:00"))
    }

    // BDD: The Android boiler chip opens the activity tray
    @Test
    fun `older and pushed events join the list newest first without repeats`() {
        val page = listOf(event(3, "knob"), event(2, "knob"))
        val merged = mergeActivity(listOf(event(5, "knob"), event(3, "knob")), page)
        assertEquals(listOf(5L, 3L, 2L), merged.map { it.id })
        assertEquals(listOf(5L, 3L, 2L, 1L), mergeActivity(listOf(event(1, "knob")), merged).map { it.id })
    }

    // BDD: An empty feed says so
    @Test
    fun `an empty feed says nothing has changed yet once it has loaded`() {
        assertEquals("Nothing has changed yet", activityEmptyText(loaded = true, failed = false, events = emptyList()))
        // Not before the first page arrives, not over the retry row, and not once there is something to show.
        assertEquals(null, activityEmptyText(loaded = false, failed = false, events = emptyList()))
        assertEquals(null, activityEmptyText(loaded = true, failed = true, events = emptyList()))
        assertEquals(null, activityEmptyText(loaded = true, failed = false, events = listOf(setpoint)))
    }
}
