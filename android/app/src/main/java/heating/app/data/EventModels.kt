package heating.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.time.Duration
import java.time.Instant

/** openapi: Event. One MQTT message the service saw. */
@Serializable
data class LoggedEvent(
    val id: Long,
    val at: String,
    val topic: String,
    val payload: JsonElement,
    val json: Boolean = false,
    val retained: Boolean = false,
    val truncated: Boolean = false,
)

/** openapi: EventPage. [next] is the cursor for the next page, null on the last. */
@Serializable
data class EventPage(val events: List<LoggedEvent> = emptyList(), val next: String? = null, val total: Long = 0)

/** openapi: EventTopic. A topic seen, with its message count, last seen time and last payload. */
@Serializable
data class EventTopic(val topic: String, val count: Long, val lastSeen: String, val lastPayload: JsonElement, val json: Boolean = false)

enum class EventSort(val param: String) { AT("at"), TOPIC("topic") }

/** A Field value chip: [field] equals (or, with [not], differs from) [value]. */
data class FieldValue(val field: String, val value: String, val not: Boolean = false)

enum class ChipKind { TOPIC, DEVICE, FIELD, FIELD_VALUE, TEXT, RANGE, RETAINED }

data class EventChip(val kind: ChipKind, val label: String)

data class EventRange(val key: String, val label: String, val hours: Long)

val EVENT_RANGES = listOf(
    EventRange("1h", "Last hour", 1),
    EventRange("24h", "Last 24 h", 24),
    EventRange("7d", "Last 7 days", 24 * 7),
    EventRange("30d", "Last 30 days", 24 * 30),
)

const val EVENT_PAGE_SIZE = 100

/** What the chips ask for; one of each kind. */
data class EventFilter(
    val topic: String? = null,
    val device: String? = null,
    val field: String? = null,
    val fieldValue: FieldValue? = null,
    val text: String? = null,
    val range: String? = null,
    val retained: Boolean? = null,
) {
    fun chips(): List<EventChip> = buildList {
        topic?.let { add(EventChip(ChipKind.TOPIC, "Topic: $it")) }
        device?.let { add(EventChip(ChipKind.DEVICE, "Device: $it")) }
        field?.let { add(EventChip(ChipKind.FIELD, "Field: $it")) }
        fieldValue?.let { add(EventChip(ChipKind.FIELD_VALUE, "${it.field} ${if (it.not) "≠" else "="} ${it.value}")) }
        text?.let { add(EventChip(ChipKind.TEXT, "Text: $it")) }
        range?.let { key -> add(EventChip(ChipKind.RANGE, EVENT_RANGES.firstOrNull { it.key == key }?.label ?: key)) }
        retained?.let { add(EventChip(ChipKind.RETAINED, if (it) "Retained" else "Not retained")) }
    }

    /** This filter with [add]'s chips in it: one chip of each kind, and Field and Field value replace each other. */
    fun with(add: EventFilter) = EventFilter(
        topic = add.topic ?: topic,
        device = add.device ?: device,
        field = add.field ?: if (add.fieldValue != null) null else field,
        fieldValue = add.fieldValue ?: if (add.field != null) null else fieldValue,
        text = add.text ?: text,
        range = add.range ?: range,
        retained = add.retained ?: retained,
    )

    fun without(kind: ChipKind) = when (kind) {
        ChipKind.TOPIC -> copy(topic = null)
        ChipKind.DEVICE -> copy(device = null)
        ChipKind.FIELD -> copy(field = null)
        ChipKind.FIELD_VALUE -> copy(fieldValue = null)
        ChipKind.TEXT -> copy(text = null)
        ChipKind.RANGE -> copy(range = null)
        ChipKind.RETAINED -> copy(retained = null)
    }
}

/** The query parameters of GET /api/events for these chips; never more than [EVENT_PAGE_SIZE] events. */
fun eventParams(filter: EventFilter, sort: EventSort, descending: Boolean, cursor: String? = null, now: Instant = Instant.now()): List<Pair<String, String>> = buildList {
    filter.topic?.let { add("topic" to it) }
    filter.device?.let { add("device" to it) }
    (filter.fieldValue?.field ?: filter.field)?.let { add("field" to it) }
    filter.fieldValue?.let {
        add("value" to it.value)
        if (it.not) add("op" to "ne")
    }
    filter.text?.let { add("text" to it) }
    EVENT_RANGES.firstOrNull { it.key == filter.range }?.let { add("from" to now.minus(Duration.ofHours(it.hours)).toString()) }
    filter.retained?.let { add("retained" to it.toString()) }
    add("sort" to "${sort.param} ${if (descending) "desc" else "asc"}")
    add("limit" to EVENT_PAGE_SIZE.toString())
    cursor?.let { add("cursor" to it) }
}

/** The sort after tapping [column]'s heading: the same column flips direction; Time starts newest first, Topic A to Z. */
fun toggleSort(sort: EventSort, descending: Boolean, column: EventSort): Pair<EventSort, Boolean> =
    if (column == sort) column to !descending else column to (column == EventSort.AT)

/** "contact = false" or "battery != 50" as a Field value; null when [text] is not of that shape. */
fun parseFieldValue(text: String): FieldValue? {
    val m = Regex("^\\s*([A-Za-z0-9_-]+(?:\\.[A-Za-z0-9_-]+)*)\\s*(!=|=)\\s*(.+?)\\s*$").find(text) ?: return null
    return FieldValue(m.groupValues[1], m.groupValues[3], m.groupValues[2] == "!=")
}

/** A chip the search box offers, as the filter to add. */
data class EventSuggestion(val label: String, val add: EventFilter)

private val FILTERABLE_PATH = Regex("[A-Za-z0-9_-]+(\\.[A-Za-z0-9_-]+)*")

fun filterablePath(path: String) = FILTERABLE_PATH.matches(path)

/** The Zigbee2MQTT base topic, found from its bridge topics; "zigbee2mqtt" when none are seen. */
fun z2mBase(topics: List<EventTopic>): String =
    topics.firstOrNull { it.topic.endsWith("/bridge/devices") }?.topic?.removeSuffix("/bridge/devices") ?: "zigbee2mqtt"

/** Matching topics, devices, fields and filters for what is typed; with nothing typed, the time ranges and retained. */
fun suggest(text: String, topics: List<EventTopic>): List<EventSuggestion> {
    val q = text.trim()
    if (q.isEmpty()) {
        return EVENT_RANGES.map { EventSuggestion(it.label, EventFilter(range = it.key)) } +
            EventSuggestion("Retained", EventFilter(retained = true)) + EventSuggestion("Not retained", EventFilter(retained = false))
    }
    val lower = q.lowercase()
    val out = mutableListOf<EventSuggestion>()
    parseFieldValue(q)?.let { out += EventSuggestion("${it.field} ${if (it.not) "≠" else "="} ${it.value}", EventFilter(fieldValue = it)) }
    if (q.any { it == '+' || it == '#' || it == '/' }) out += EventSuggestion("Topic: $q", EventFilter(topic = q))
    topics.filter { it.topic.lowercase().contains(lower) }.take(5).forEach { out += EventSuggestion("Topic: ${it.topic}", EventFilter(topic = it.topic)) }
    val base = z2mBase(topics)
    topics.mapNotNull { t -> t.topic.takeIf { it.startsWith("$base/") }?.removePrefix("$base/")?.takeIf { '/' !in it && it != "bridge" } }
        .distinct().filter { it.lowercase().contains(lower) }.take(5).forEach { out += EventSuggestion("Device: $it", EventFilter(device = it)) }
    topics.filter { it.json }.flatMap { payloadFields(it.lastPayload).map { f -> f.path } }.filter { filterablePath(it) }
        .distinct().filter { it.lowercase().contains(lower) }.take(5).forEach { out += EventSuggestion("Field: $it", EventFilter(field = it)) }
    EVENT_RANGES.filter { it.label.lowercase().contains(lower) }.forEach { out += EventSuggestion(it.label, EventFilter(range = it.key)) }
    if ("retained".contains(lower)) out += EventSuggestion("Retained", EventFilter(retained = true))
    if ("not retained".contains(lower)) out += EventSuggestion("Not retained", EventFilter(retained = false))
    out += EventSuggestion("Text: $q", EventFilter(text = q))
    return out
}

/** A payload on one line, cut off after [max] characters. */
fun shortPayload(payload: JsonElement, max: Int = 100): String {
    val text = if (payload is JsonPrimitive && payload.isString) payload.content else payload.toString()
    return if (text.length > max) text.take(max) + "…" else text
}

data class PayloadField(val path: String, val value: JsonElement)

/** Every field of a JSON object payload with its path ("contact", "update.state"); arrays are shown as one value. */
fun payloadFields(payload: JsonElement, prefix: String = ""): List<PayloadField> {
    if (payload !is JsonObject) return if (prefix.isEmpty()) emptyList() else listOf(PayloadField(prefix, payload))
    return payload.entries.flatMap { (key, value) ->
        val path = if (prefix.isEmpty()) key else "$prefix.$key"
        if (value is JsonObject) payloadFields(value, path) else listOf(PayloadField(path, value))
    }
}

/** A field value as a Field value chip would hold it: text without quotes, other values as written. */
fun valueText(value: JsonElement): String = when (value) {
    is JsonPrimitive -> if (value.isString) value.content else value.toString()
    is JsonNull -> "null"
    is JsonArray, is JsonObject -> value.toString()
}

/** The payload as JSON laid out over several lines; text payloads as they are. */
fun prettyPayload(event: LoggedEvent): String =
    if (event.json) kotlinx.serialization.json.Json { prettyPrint = true }.encodeToString(JsonElement.serializer(), event.payload)
    else (event.payload as? JsonPrimitive)?.content ?: event.payload.toString()

/** Where "Notify me when…" goes until the rule builder exists: a placeholder carrying the topic, field and value. */
fun ruleRoute(topic: String, field: String? = null, value: String? = null): String =
    "rules/new?topic=${percent(topic)}&field=${percent(field.orEmpty())}&value=${percent(value.orEmpty())}"

private fun percent(text: String) = java.net.URLEncoder.encode(text, "UTF-8").replace("+", "%20")

/** A level of the topic tree: [count] messages here and below, [own] set when messages were published on exactly this path. */
data class TopicNode(val name: String, val path: String, val count: Long, val lastSeen: String, val own: EventTopic?, val children: List<TopicNode>) {
    /** The topic filter choosing this node shows: the topic itself, or everything under a level. */
    val filter get() = if (children.isEmpty()) path else "$path/#"
}

/** Topics split on "/" into a tree, each level with the count and last seen of everything under it. */
fun topicTree(topics: List<EventTopic>, prefix: String = "", depth: Int = 0): List<TopicNode> =
    topics.groupBy { it.topic.split('/').getOrElse(depth) { "" } }.map { (name, group) ->
        val path = if (prefix.isEmpty()) name else "$prefix/$name"
        TopicNode(
            name = name,
            path = path,
            count = group.sumOf { it.count },
            lastSeen = group.maxOf { it.lastSeen },
            own = group.firstOrNull { it.topic == path },
            children = topicTree(group.filter { it.topic != path }, path, depth + 1),
        )
    }

const val STALE_DAYS = 7L

/** A topic with no message for [STALE_DAYS] days is greyed. */
fun isStale(lastSeen: String, now: Instant = Instant.now()): Boolean =
    Duration.between(Instant.parse(lastSeen), now) > Duration.ofDays(STALE_DAYS)

/** New events from a poll join the top of [current] (newest first), without repeats. */
fun mergeNewest(current: List<LoggedEvent>, fresh: List<LoggedEvent>): List<LoggedEvent> {
    val known = current.mapTo(HashSet()) { it.id }
    return fresh.filter { it.id !in known }.sortedByDescending { it.id } + current
}
