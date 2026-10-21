package heating.api

import heating.ApiException
import heating.HeatingApp
import heating.activity.Activity
import heating.activity.ActivityLog
import heating.activity.collapseBoilerCycles
import heating.activity.forViewer
import heating.auth.Caller
import io.ktor.server.auth.principal
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive
import java.time.format.DateTimeFormatter

/** One feed event; clients word it for their screen. */
@Serializable
data class ActivityView(
    val id: Long,
    val at: String,
    val kind: String,
    val actor: String?,
    val room: String?,
    val value: JsonPrimitive?,
    val reason: String?,
    val rooms: List<String>,
    val count: Int? = null,
    /** The plug an "offline" event is about; null for valves, sensors and every other kind. */
    val device: String? = null,
)

@Serializable
data class ActivityList(val events: List<ActivityView>)

private const val DEFAULT_LIMIT = 50
private const val MAX_LIMIT = 200

fun Route.activityRoutes(heating: HeatingApp) {
    get("/api/activity") {
        val limit = call.request.queryParameters["limit"]?.let { it.toIntOrNull() ?: 0 } ?: DEFAULT_LIMIT
        if (limit !in 1..MAX_LIMIT) throw ApiException.badRequest("limit must be 1 to $MAX_LIMIT")
        val before = call.request.queryParameters["before"]?.let { it.toLongOrNull() ?: throw ApiException.badRequest("before must be an event id") }
        val caller = call.principal<Caller>()!!
        val visible = heating.activity.recent(ActivityLog.KEEP, before).mapNotNull { it.forViewer(caller.visibleRooms(), caller.admin) }
        call.respond(ActivityList(collapseBoilerCycles(visible).take(limit).map { it.view(heating) }))
    }
}

/** Null when the caller sees every room. */
fun Caller.visibleRooms(): ((String) -> Boolean)? = if (rooms == null) null else ::canSee

fun Activity.view(heating: HeatingApp) = ActivityView(
    id, at.atZone(heating.config.timezone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME), kind, actor, room, value, reason, rooms, count, device,
)
