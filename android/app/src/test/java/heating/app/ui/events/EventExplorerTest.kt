package heating.app.ui.events

import heating.app.data.ChipKind
import heating.app.data.EventFilter
import heating.app.data.EventSort
import heating.app.data.FakeHeatingApi
import heating.app.data.FieldValue
import heating.app.data.LoggedEvent
import heating.app.data.eventParams
import heating.app.data.isStale
import heating.app.data.mergeNewest
import heating.app.data.payloadFields
import heating.app.data.prettyPayload
import heating.app.data.ruleRoute
import heating.app.data.shortPayload
import heating.app.data.suggest
import heating.app.data.toggleSort
import heating.app.data.topicTree
import heating.app.data.valueText
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class EventExplorerTest {
    private val now = Instant.parse("2026-11-07T12:00:00Z")

    private fun payload(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun event(id: Long, topic: String, body: String, minutesAgo: Long, retained: Boolean = false) =
        LoggedEvent(id, now.minusSeconds(minutesAgo * 60).toString(), topic, payload(body), json = body.startsWith("{"), retained = retained)

    private fun house() = FakeHeatingApi().apply {
        eventLog += event(1, "zigbee2mqtt/bridge/devices", """[{"friendly_name":"Front Door"}]""", 60 * 24 * 10, retained = true)
        eventLog += event(2, "zigbee2mqtt/Front Door", """{"contact":true,"battery":91,"update":{"state":"idle"}}""", 60 * 30)
        eventLog += event(3, "zigbee2mqtt/Front Door", """{"contact":false,"battery":91,"update":{"state":"idle"}}""", 30)
        eventLog += event(4, "zigbee2mqtt/Hall Button", """{"action":"single"}""", 20)
        eventLog += event(5, "home/mode", "\"away\"", 60 * 24 * 9, retained = true)
        eventLog += event(6, "garage/temp", """{"temperature":18.5}""", 10)
    }

    private suspend fun FakeHeatingApi.find(filter: EventFilter, sort: EventSort = EventSort.AT, descending: Boolean = true, cursor: String? = null) =
        events(eventParams(filter, sort, descending, cursor, now))

    // BDD: An admin opens the event explorer
    @Test
    fun `an admin opens the event explorer`() = runTest {
        val api = house()
        val page = api.find(EventFilter())
        // The newest events first, each with its time, topic, a short payload and a retained tag
        assertEquals(listOf("garage/temp", "zigbee2mqtt/Hall Button", "zigbee2mqtt/Front Door", "zigbee2mqtt/Front Door", "home/mode", "zigbee2mqtt/bridge/devices"), page.events.map { it.topic })
        assertEquals("""{"temperature":18.5}""", shortPayload(page.events.first().payload))
        assertEquals("away", shortPayload(page.events[4].payload))
        assertEquals(listOf(false, false, false, false, true, true), page.events.map { it.retained })
        assertEquals(60, shortPayload(payload("\"" + "x".repeat(200) + "\""), 60).length - 1)

        // New events join the top, without repeats
        api.eventLog += event(7, "garage/temp", """{"temperature":19}""", 0)
        val fresh = api.find(EventFilter()).events
        val merged = mergeNewest(page.events, fresh)
        assertEquals(7, merged.size)
        assertEquals(7L, merged.first().id)
        assertEquals(merged, mergeNewest(merged, fresh))
    }

    // BDD: Events are filtered with chips
    @Test
    fun `events are filtered with chips`() = runTest {
        val api = house()
        var filter = EventFilter()
        filter = filter.with(EventFilter(topic = "zigbee2mqtt/Front Door"))
        filter = filter.with(EventFilter(field = "contact"))
        filter = filter.with(EventFilter(range = "24h"))
        assertEquals(listOf("Topic: zigbee2mqtt/Front Door", "Field: contact", "Last 24 h"), filter.chips().map { it.label })
        val page = api.find(filter)
        assertEquals(1, page.total)
        assertEquals(3L, page.events.single().id)
        assertTrue(api.calls.last().contains("from=2026-11-06T12:00:00Z"))

        // Each chip can be removed on its own
        assertEquals(2L, api.find(filter.without(ChipKind.RANGE)).total)

        // Device, Field value, Text and Retained chips, and wildcard topics
        assertEquals(2L, api.find(EventFilter(device = "Front Door")).total)
        val value = EventFilter(fieldValue = FieldValue("contact", "false"))
        assertEquals(listOf("contact = false"), value.chips().map { it.label })
        assertEquals(1L, api.find(value).total)
        assertEquals(1L, api.find(EventFilter(fieldValue = FieldValue("contact", "false", not = true))).total)
        assertEquals(3L, api.find(EventFilter(topic = "zigbee2mqtt/+")).total)
        assertEquals(2L, api.find(EventFilter(retained = true)).total)
        assertEquals(1L, api.find(EventFilter(text = "away")).total)
        // Field and Field value replace each other
        assertEquals(EventFilter(fieldValue = FieldValue("contact", "false")), EventFilter(field = "battery").with(value))

        // Typing offers matching topics, devices, fields and filters as chips
        val topics = api.eventTopics()
        assertEquals(listOf("Topic: zigbee2mqtt/Front Door", "Device: Front Door", "Text: front"), suggest("front", topics).map { it.label })
        assertTrue("Field: contact" in suggest("contact", topics).map { it.label })
        assertEquals("contact = false", suggest("contact = false", topics).first().label)
        assertEquals("Topic: zigbee2mqtt/+", suggest("zigbee2mqtt/+", topics).first().label)
        assertEquals(listOf("Last hour", "Last 24 h", "Last 7 days", "Last 30 days", "Retained", "Not retained"), suggest("", topics).map { it.label })
    }

    // BDD: Events are sorted and paged
    @Test
    fun `events are sorted and paged`() = runTest {
        val api = FakeHeatingApi()
        repeat(250) { api.eventLog += event(it + 1L, "bulk/${(it % 50).toString().padStart(2, '0')}", """{"n":$it}""", 300L - it) }

        val first = api.find(EventFilter())
        assertEquals(100, first.events.size)
        assertEquals(250L, first.total)
        val second = api.find(EventFilter(), cursor = first.next)
        assertEquals(100, second.events.size)
        assertEquals(50, api.find(EventFilter(), cursor = second.next).events.size)
        assertEquals(null, api.find(EventFilter(), cursor = second.next).next)

        // Topic, then Time: the same column flips, a new one starts in its natural order
        var sort = EventSort.AT to true
        sort = toggleSort(sort.first, sort.second, EventSort.TOPIC)
        assertEquals(EventSort.TOPIC to false, sort)
        assertEquals("bulk/00", api.find(EventFilter(), sort.first, sort.second).events.first().topic)
        assertTrue(api.calls.last().contains("sort=topic asc"))
        sort = toggleSort(sort.first, sort.second, EventSort.TOPIC)
        assertEquals("bulk/49", api.find(EventFilter(), sort.first, sort.second).events.first().topic)
        sort = toggleSort(sort.first, sort.second, EventSort.AT)
        assertEquals(EventSort.AT to true, sort)
        sort = toggleSort(sort.first, sort.second, EventSort.AT)
        assertEquals(EventSort.AT to false, sort)

        // Nothing ever asks for more than a page
        assertTrue(api.calls.filter { it.startsWith("events") }.all { it.contains("limit=100") })
    }

    // BDD: An event's payload is shown in full
    @Test
    fun `an event's payload is shown in full`() = runTest {
        val e = house().eventLog[2]
        assertEquals(listOf("contact", "battery", "update.state"), payloadFields(e.payload).map { it.path })
        assertEquals(listOf("false", "91", "idle"), payloadFields(e.payload).map { valueText(it.value) })
        assertTrue(prettyPayload(e).contains("\"battery\": 91"))
        assertEquals("away", prettyPayload(house().eventLog[4]))

        // Filter by this adds a Field value chip; Show this topic filters to the topic
        val field = payloadFields(e.payload).first()
        val filtered = EventFilter().with(EventFilter(fieldValue = FieldValue(field.path, valueText(field.value))))
        assertEquals(listOf("contact = false"), filtered.chips().map { it.label })
        assertEquals(listOf("Topic: zigbee2mqtt/Front Door"), EventFilter().with(EventFilter(topic = e.topic)).chips().map { it.label })

        // Notify me when… opens the rule builder (a placeholder for now) with the topic and field
        assertEquals("rules/new?topic=zigbee2mqtt%2FFront%20Door&field=update.state&value=idle", ruleRoute(e.topic, "update.state", "idle"))
    }

    // BDD: Topics are browsed as a tree
    @Test
    fun `topics are browsed as a tree`() = runTest {
        val api = house()
        val tree = topicTree(api.eventTopics())
        assertEquals(listOf("garage", "home", "zigbee2mqtt"), tree.map { it.name }.sorted())
        val z2m = tree.first { it.name == "zigbee2mqtt" }
        assertEquals(4L, z2m.count)
        assertEquals("zigbee2mqtt/#", z2m.filter)
        val door = z2m.children.first { it.name == "Front Door" }
        assertEquals(2L, door.count)
        assertEquals("zigbee2mqtt/Front Door", door.filter)
        assertEquals("""{"contact":false,"battery":91,"update":{"state":"idle"}}""", shortPayload(door.own!!.lastPayload, 200))
        assertEquals(now.minusSeconds(30 * 60).toString(), door.lastSeen)
        assertEquals(listOf("devices"), z2m.children.first { it.name == "bridge" }.children.map { it.name })

        // Choosing a topic filters the table to it; one with no message for 7 days is greyed
        assertEquals(2L, api.find(EventFilter(topic = door.filter)).total)
        assertEquals(4L, api.find(EventFilter(topic = z2m.filter)).total)
        assertFalse(isStale(door.lastSeen, now))
        assertTrue(isStale(tree.first { it.name == "home" }.lastSeen, now))
        assertFalse(isStale(tree.first { it.name == "garage" }.lastSeen, now))
    }
}
