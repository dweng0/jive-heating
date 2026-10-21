package heating.api

import heating.ApiException
import heating.HeatingApp
import heating.auth.Caller
import io.ktor.server.auth.principal
import heating.history.HistoryRecorder
import heating.history.RoomPoint
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.format.DateTimeFormatter

/** openapi: HistoryPoint */
@Serializable
data class HistoryPointView(val at: String, val current: Double?, val target: Double? = null)

/** openapi: RoomHistory */
@Serializable
data class RoomHistoryView(val id: String, val name: String, val points: List<HistoryPointView>)

/** openapi: BoilerPeriod */
@Serializable
data class BoilerPeriodView(val state: String, val from: String, val to: String?)

/** openapi: History */
@Serializable
data class HistoryView(
    val from: String,
    val to: String,
    val rooms: List<RoomHistoryView>,
    val house: List<HistoryPointView>,
    val boiler: List<BoilerPeriodView>,
)

/** How far back each range looks, and how long a point it averages into (null: the raw 5-minute points). */
internal enum class HistoryRange(val length: Duration, val bucket: Duration?) {
    DAY(Duration.ofHours(24), null),
    WEEK(Duration.ofDays(7), Duration.ofMinutes(30)),
    MONTH(Duration.ofDays(30), Duration.ofHours(2)),
    ;

    companion object {
        fun of(name: String?) = when (name ?: "24h") {
            "24h" -> DAY
            "7d" -> WEEK
            "30d" -> MONTH
            else -> throw ApiException.badRequest("range must be 24h, 7d or 30d")
        }
    }
}

fun Route.historyRoutes(heating: HeatingApp) {
    get("/api/history") {
        val range = HistoryRange.of(call.request.queryParameters["range"])
        val caller = call.principal<Caller>()!!
        val room = call.request.queryParameters["room"]
        // A room the caller cannot see answers as one that does not exist.
        if (room != null && (!caller.canSee(room) || heating.rooms.list().none { it.id == room })) {
            throw ApiException.notFound("room not found")
        }
        call.respond(heating.historyView(range.length, range.bucket, room?.let(::setOf) ?: caller.rooms, wholeHouse = caller.admin && room == null))
    }
}

/**
 * History of [only] the given rooms (null: every room). The whole-house median line and the boiler's
 * states are part of the whole house, so only admins asking for every room ([wholeHouse]) get them.
 */
private fun HeatingApp.historyView(length: Duration, bucket: Duration?, only: Set<String>?, wholeHouse: Boolean): HistoryView {
    val to = clock.instant()
    val from = to.minus(length)
    val names = rooms.list().filter { only == null || it.id in only }.associate { it.id to it.name }
    val points = history.points(from.minus(HistoryRecorder.SAMPLE_INTERVAL), to)
        .filter { it.roomId in names && it.at >= from }
        .let { if (bucket == null) it else average(it, bucket) }
    val byRoom = points.groupBy { it.roomId }
    val house = points.filter { it.current != null }.groupBy { it.at }.toSortedMap()
        .map { (at, ps) -> HistoryPointView(format(at), round(median(ps.map { it.current!! }))) }
    return HistoryView(
        from = format(from),
        to = format(to),
        rooms = names.filterKeys { it in byRoom }.map { (id, name) ->
            RoomHistoryView(id, name, byRoom.getValue(id).map { HistoryPointView(format(it.at), it.current, it.target) })
        },
        house = if (wholeHouse) house else emptyList(),
        boiler = if (!wholeHouse) emptyList() else history.boilerStates(from, to).map { BoilerPeriodView(it.state, format(it.from), it.to?.let(::format)) },
    )
}

/** Averages each room's points into buckets of [bucket], stamped with the bucket's start. */
private fun average(points: List<RoomPoint>, bucket: Duration): List<RoomPoint> {
    val size = bucket.toMillis()
    return points.groupBy { it.roomId to Instant.ofEpochMilli(it.at.toEpochMilli() / size * size) }
        .map { (key, ps) ->
            RoomPoint(key.second, key.first, mean(ps.mapNotNull { it.current }), mean(ps.mapNotNull { it.target }))
        }
        .sortedBy { it.at }
}

private fun mean(values: List<Double>) = if (values.isEmpty()) null else round(values.average())

private fun median(values: List<Double>): Double {
    val sorted = values.sorted()
    val mid = sorted.size / 2
    return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
}

private fun round(v: Double) = BigDecimal(v).setScale(1, RoundingMode.HALF_UP).toDouble()

private fun HeatingApp.format(at: Instant): String = at.atZone(config.timezone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)
