package heating.api

import heating.ApiException
import heating.HeatingApp
import heating.events.Event
import heating.events.EventLogStatus
import heating.events.EventQuery
import heating.events.ExcludedTopic
import heating.events.TopicSummary
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.time.Instant
import java.time.format.DateTimeParseException

/** One logged message. [payload] is the JSON itself when [json] is true, else the text. */
@Serializable
data class EventView(
    val id: Long,
    val at: String,
    val topic: String,
    val payload: JsonElement,
    val json: Boolean,
    val retained: Boolean,
    val truncated: Boolean,
)

@Serializable
data class EventPageView(val events: List<EventView>, val next: String?, val total: Long)

@Serializable
data class TopicView(val topic: String, val count: Long, val lastSeen: String, val lastPayload: JsonElement, val json: Boolean)

@Serializable
data class EventSettingsView(val days: Int, val excluded: List<ExcludedTopic>, val status: EventLogStatus)

/** Both fields are optional; what is left out stays as it was. */
@Serializable
data class EventSettingsRequest(val days: Int? = null, val excluded: List<String>? = null)

private fun payloadOf(payload: String, json: Boolean): JsonElement =
    if (json) Json.parseToJsonElement(payload) else JsonPrimitive(payload)

fun Event.view() = EventView(id, at.toString(), topic, payloadOf(payload, json), json, retained, truncated)

private const val DEFAULT_LIMIT = 100
private const val MAX_LIMIT = 1000
private val FIELD = Regex("[A-Za-z0-9_\\-]+(\\.[A-Za-z0-9_\\-]+)*")

/** The event log. Registered inside [adminOnly]. */
fun Route.eventRoutes(heating: HeatingApp) {
    get("/api/events") {
        val p = call.request.queryParameters
        fun time(name: String): Instant? = p[name]?.let {
            try {
                Instant.parse(it)
            } catch (e: DateTimeParseException) {
                throw ApiException.badRequest("$name must be a time like 2026-11-07T09:00:00Z")
            }
        }
        val limit = p["limit"]?.let { it.toIntOrNull() ?: 0 } ?: DEFAULT_LIMIT
        if (limit !in 1..MAX_LIMIT) throw ApiException.badRequest("limit must be 1 to $MAX_LIMIT")
        val sort = (p["sort"] ?: "at desc").trim().split(Regex("\\s+"))
        if (sort.size > 2 || sort[0] !in setOf("at", "topic") || sort.getOrNull(1) !in setOf(null, "asc", "desc")) {
            throw ApiException.badRequest("sort must be at or topic, then asc or desc")
        }
        val field = p["field"]?.takeIf { it.isNotEmpty() }
        if (field != null && !FIELD.matches(field)) throw ApiException.badRequest("field must be a JSON field name like contact or a.b")
        val query = EventQuery(
            topic = p["topic"]?.takeIf { it.isNotEmpty() },
            from = time("from"),
            to = time("to"),
            text = p["text"]?.takeIf { it.isNotEmpty() },
            field = field,
            value = p["value"]?.takeIf { it.isNotEmpty() && field != null },
            valueNot = p["op"] == "ne",
            device = p["device"]?.takeIf { it.isNotEmpty() }?.let { "${heating.topics.base}/$it" },
            retained = p["retained"]?.let {
                it.toBooleanStrictOrNull() ?: throw ApiException.badRequest("retained must be true or false")
            },
            sortBy = sort[0],
            descending = sort.getOrNull(1) != "asc",
            limit = limit,
            cursor = p["cursor"]?.takeIf { it.isNotEmpty() },
        )
        heating.events.flush()
        val page = heating.eventLog.search(query)
        call.respond(EventPageView(page.events.map { it.view() }, page.next, page.total))
    }

    get("/api/events/topics") {
        heating.events.flush()
        call.respond(heating.eventLog.topics().map { it.view() })
    }

    get("/api/events/settings") { call.respond(settingsView(heating)) }

    put("/api/events/settings") {
        val request = runCatching { call.receive<EventSettingsRequest>() }.getOrElse { throw ApiException.badRequest("days (1 to 90) and excluded (topic filters) are expected") }
        heating.events.update(request.days, request.excluded)
        call.respond(settingsView(heating))
    }
}

private fun TopicSummary.view() = TopicView(topic, count, lastSeen.toString(), payloadOf(lastPayload, json), json)

private fun settingsView(heating: HeatingApp) =
    EventSettingsView(heating.events.settings().days, heating.events.excluded(), heating.events.status())
