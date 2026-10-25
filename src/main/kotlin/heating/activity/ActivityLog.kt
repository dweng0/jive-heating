package heating.activity

import org.slf4j.LoggerFactory
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/**
 * One thing that changed in the heating. [actor] is a person's display name, or null when nobody did it
 * (a knob turn, a schedule, the boiler). [value] is a target (number) or a state (string). [room] and [rooms]
 * are names for display; [roomIds] are the rooms the event is about, for deciding who may see it.
 * [count] is set on a collapsed run of boiler cycles. [device] names the plug an "offline" event is about
 * (null for valves and sensors, whose events name their room). [adminOnly] events are never shown to members, whichever rooms they can see (plugs are admin only).
 */
data class Activity(
    val id: Long,
    val at: Instant,
    val kind: String,
    val actor: String?,
    val room: String?,
    val value: JsonPrimitive?,
    val reason: String?,
    val rooms: List<String>,
    val roomIds: List<String> = emptyList(),
    val count: Int? = null,
    val device: String? = null,
    val adminOnly: Boolean = false,
)

/**
 * The last [KEEP] activity events, in their own table of the service's SQLite file. Recording never
 * throws: a broken log must not stop the heating.
 */
class ActivityLog(path: String, private val now: () -> Instant) {
    private val log = LoggerFactory.getLogger(ActivityLog::class.java)

    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path").also {
        it.createStatement().use { s ->
            s.execute(
                "CREATE TABLE IF NOT EXISTS activity (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, kind TEXT NOT NULL, " +
                    "actor TEXT, room TEXT, value TEXT, reason TEXT, rooms TEXT NOT NULL, room_ids TEXT NOT NULL DEFAULT '[]', " +
                    "device TEXT, admin_only INTEGER NOT NULL DEFAULT 0)",
            )
        }
        // Logs made before plugs were in the feed lack the last two columns.
        val columns = it.createStatement().use { s ->
            s.executeQuery("PRAGMA table_info(activity)").use { rs -> buildSet { while (rs.next()) add(rs.getString("name")) } }
        }
        it.createStatement().use { s ->
            if ("device" !in columns) s.execute("ALTER TABLE activity ADD COLUMN device TEXT")
            if ("admin_only" !in columns) s.execute("ALTER TABLE activity ADD COLUMN admin_only INTEGER NOT NULL DEFAULT 0")
        }
    }

    private val listeners = CopyOnWriteArrayList<(Activity) -> Unit>()

    private val quietDepth = ThreadLocal.withInitial { 0 }

    /** Told of every event after it is stored. */
    fun onAdded(listener: (Activity) -> Unit) {
        listeners += listener
    }

    /** Run [block] without recording, for a bulk change that records one event for the whole. */
    fun <T> quiet(block: () -> T): T {
        quietDepth.set(quietDepth.get() + 1)
        try {
            return block()
        } finally {
            quietDepth.set(quietDepth.get() - 1)
        }
    }

    fun record(
        kind: String,
        actor: String?,
        room: String?,
        value: JsonPrimitive? = null,
        reason: String? = null,
        rooms: List<String> = emptyList(),
        roomIds: List<String> = emptyList(),
        device: String? = null,
        adminOnly: Boolean = false,
    ) {
        if (quietDepth.get() > 0) return
        val added = try {
            insert(Activity(0, Instant.EPOCH, kind, actor, room, value, reason, rooms, roomIds, device = device, adminOnly = adminOnly))
        } catch (e: Exception) {
            log.warn("could not record activity {}: {}", kind, e.message)
            return
        }
        listeners.forEach { runCatching { it(added) } }
    }

    @Synchronized
    private fun insert(unstamped: Activity): Activity {
        val event = unstamped.copy(at = now())
        val id = connection.prepareStatement(
            "INSERT INTO activity (at, kind, actor, room, value, reason, rooms, room_ids, device, admin_only) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            java.sql.Statement.RETURN_GENERATED_KEYS,
        ).use { s ->
            s.setLong(1, event.at.toEpochMilli())
            s.setString(2, event.kind)
            s.setString(3, event.actor)
            s.setString(4, event.room)
            s.setString(5, event.value?.toString())
            s.setString(6, event.reason)
            s.setString(7, strings(event.rooms))
            s.setString(8, strings(event.roomIds))
            s.setString(9, event.device)
            s.setInt(10, if (event.adminOnly) 1 else 0)
            s.executeUpdate()
            s.generatedKeys.use { it.next(); it.getLong(1) }
        }
        connection.prepareStatement("DELETE FROM activity WHERE id <= ?").use { s ->
            s.setLong(1, id - KEEP)
            s.executeUpdate()
        }
        return event.copy(id = id)
    }

    /** Up to [limit] events, newest first, older than event [before] when given. */
    @Synchronized
    fun recent(limit: Int, before: Long? = null): List<Activity> =
        connection.prepareStatement(
            "SELECT id, at, kind, actor, room, value, reason, rooms, room_ids, device, admin_only FROM activity WHERE id < ? ORDER BY id DESC LIMIT ?",
        ).use { s ->
            s.setLong(1, before ?: Long.MAX_VALUE)
            s.setInt(2, limit)
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(
                            Activity(
                                rs.getLong(1), Instant.ofEpochMilli(rs.getLong(2)), rs.getString(3), rs.getString(4), rs.getString(5),
                                rs.getString(6)?.let { Json.parseToJsonElement(it).jsonPrimitive }, rs.getString(7),
                                strings(rs.getString(8)), strings(rs.getString(9)),
                                device = rs.getString(10), adminOnly = rs.getInt(11) != 0,
                            ),
                        )
                    }
                }
            }
        }

    fun close() = connection.close()

    private fun strings(list: List<String>): String = buildJsonArray { list.forEach { add(JsonPrimitive(it)) } }.toString()

    private fun strings(json: String): List<String> = (Json.parseToJsonElement(json) as JsonArray).map { it.jsonPrimitive.content }

    companion object {
        const val KEEP = 500
    }
}

const val BOILER = "boiler"

/** An admin turned the boiler off or back on: the whole house's, so every viewer sees it. */
const val OVERRIDE = "override"

/** "john" becomes "John". */
fun displayName(username: String): String = username.replaceFirstChar { it.titlecase(java.util.Locale.UK) }

/**
 * What a person who may see only some rooms is shown of this event: null if none of it is theirs.
 * Boiler events are the whole house's and always shown, with other rooms removed. [canSee] null means every room.
 * [adminOnly] events are shown to admins only.
 */
fun Activity.forViewer(canSee: ((String) -> Boolean)?, admin: Boolean): Activity? {
    if (adminOnly && !admin) return null
    if (canSee == null) return this
    val keep = roomIds.indices.filter { canSee(roomIds[it]) }
    val names = if (rooms.size == roomIds.size) keep.map { rooms[it] } else rooms
    if (kind == BOILER || kind == OVERRIDE) return copy(rooms = if (rooms.size == roomIds.size) names else emptyList(), roomIds = keep.map { roomIds[it] })
    if (keep.isEmpty()) return null
    return copy(roomIds = keep.map { roomIds[it] }, rooms = names)
}

private val CYCLE_WINDOW = Duration.ofMinutes(20)

/** Boiler events within 20 minutes that start more than one run are shown as one "cycled" line. */
fun collapseBoilerCycles(newestFirst: List<Activity>): List<Activity> {
    val oldestFirst = newestFirst.reversed()
    val out = ArrayList<Activity>()
    var i = 0
    while (i < oldestFirst.size) {
        val first = oldestFirst[i]
        if (first.kind != BOILER) {
            out += first
            i++
            continue
        }
        val run = mutableListOf(first)
        var j = i + 1
        while (j < oldestFirst.size && Duration.between(first.at, oldestFirst[j].at) <= CYCLE_WINDOW) {
            if (oldestFirst[j].kind == BOILER) run += oldestFirst[j]
            j++
        }
        val runs = run.count { it.value?.content == "heating" }
        if (runs >= 2) {
            out += first.copy(
                at = run.last().at, value = JsonPrimitive("cycled"), reason = null, count = runs,
                rooms = run.flatMap { it.rooms }.distinct(), roomIds = run.flatMap { it.roomIds }.distinct(),
            )
            // Events of other kinds inside the stretch keep their place.
            out += oldestFirst.subList(i + 1, j).filter { it.kind != BOILER }
            i = j
        } else {
            out += first
            i++
        }
    }
    return out.reversed()
}
