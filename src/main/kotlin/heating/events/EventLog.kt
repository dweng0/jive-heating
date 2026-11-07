package heating.events

import heating.ApiException
import heating.mqtt.TopicFilter
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64

/** One MQTT message the service saw. [json] payloads are stored as JSON text, anything else as plain text. */
data class Event(
    val id: Long,
    val at: Instant,
    val topic: String,
    val payload: String,
    val json: Boolean,
    val retained: Boolean,
    val truncated: Boolean,
)

/** What a search asks for; see [EventLog.search]. */
data class EventQuery(
    val topic: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
    val text: String? = null,
    val field: String? = null,
    /** "at" or "topic". */
    val sortBy: String = "at",
    val descending: Boolean = true,
    val limit: Int = 100,
    val cursor: String? = null,
)

data class EventPage(val events: List<Event>, val next: String?, val total: Long)

/** A topic seen in the log: how many messages, when last, and what the last one said. */
data class TopicSummary(val topic: String, val count: Long, val lastSeen: Instant, val lastPayload: String, val json: Boolean)

/**
 * Every MQTT message the service saw, in its own table of the service's SQLite file. Writing is meant to
 * happen in batches ([addAll]) off the MQTT thread; see [EventRecorder].
 */
class EventLog(path: String) {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path").also {
        it.createStatement().use { s ->
            s.execute(
                "CREATE TABLE IF NOT EXISTS events (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, topic TEXT NOT NULL, " +
                    "payload TEXT NOT NULL, json INTEGER NOT NULL, retained INTEGER NOT NULL, truncated INTEGER NOT NULL)",
            )
            s.execute("CREATE INDEX IF NOT EXISTS events_at ON events (at)")
            s.execute("CREATE INDEX IF NOT EXISTS events_topic ON events (topic, at)")
        }
        org.sqlite.Function.create(it, "mqtt_match", object : org.sqlite.Function() {
            override fun xFunc() {
                val filter = value_text(0)
                val topic = value_text(1)
                result(if (filter != null && topic != null && TopicFilter.matches(filter, topic)) 1 else 0)
            }
        })
    }

    /** Writes [events] in one transaction. */
    @Synchronized
    fun addAll(events: List<Event>) {
        if (events.isEmpty()) return
        connection.autoCommit = false
        try {
            connection.prepareStatement("INSERT INTO events (at, topic, payload, json, retained, truncated) VALUES (?, ?, ?, ?, ?, ?)").use { s ->
                for (e in events) {
                    s.setLong(1, e.at.toEpochMilli())
                    s.setString(2, e.topic)
                    s.setString(3, e.payload)
                    s.setInt(4, if (e.json) 1 else 0)
                    s.setInt(5, if (e.retained) 1 else 0)
                    s.setInt(6, if (e.truncated) 1 else 0)
                    s.addBatch()
                }
                s.executeBatch()
            }
            connection.commit()
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }

    @Synchronized
    fun count(): Long = connection.createStatement().use { s -> s.executeQuery("SELECT COUNT(*) FROM events").use { it.next(); it.getLong(1) } }

    /** Roughly how many bytes the stored events take. */
    @Synchronized
    fun bytes(): Long = connection.createStatement().use { s ->
        s.executeQuery("SELECT COALESCE(SUM(LENGTH(CAST(topic AS BLOB)) + LENGTH(CAST(payload AS BLOB)) + $ROW_OVERHEAD), 0) FROM events").use { it.next(); it.getLong(1) }
    }

    /** Deletes events at or before [before]; returns how many. */
    @Synchronized
    fun deleteOlderThan(before: Instant): Int =
        connection.prepareStatement("DELETE FROM events WHERE at < ?").use { s ->
            s.setLong(1, before.toEpochMilli())
            s.executeUpdate()
        }

    /** Deletes the oldest events until at most [maxBytes] are left; returns how many went. */
    @Synchronized
    fun trimTo(maxBytes: Long): Int {
        val excess = bytes() - maxBytes
        if (excess <= 0) return 0
        val cutoff = connection.prepareStatement(
            "SELECT id FROM (SELECT id, SUM(LENGTH(CAST(topic AS BLOB)) + LENGTH(CAST(payload AS BLOB)) + $ROW_OVERHEAD) OVER (ORDER BY id) AS running FROM events) " +
                "WHERE running >= ? ORDER BY id LIMIT 1",
        ).use { s ->
            s.setLong(1, excess)
            s.executeQuery().use { if (it.next()) it.getLong(1) else Long.MAX_VALUE }
        }
        return connection.prepareStatement("DELETE FROM events WHERE id <= ?").use { s ->
            s.setLong(1, cutoff)
            s.executeUpdate()
        }
    }

    @Synchronized
    fun search(q: EventQuery): EventPage {
        val where = mutableListOf<String>()
        val args = mutableListOf<Any>()
        q.topic?.let {
            if (it.contains('+') || it.contains('#')) {
                where += "mqtt_match(?, topic)"
            } else {
                where += "topic = ?"
            }
            args += it
        }
        q.from?.let { where += "at >= ?"; args += it.toEpochMilli() }
        q.to?.let { where += "at <= ?"; args += it.toEpochMilli() }
        q.text?.let {
            where += "(instr(lower(topic), lower(?)) > 0 OR instr(lower(payload), lower(?)) > 0)"
            args += it
            args += it
        }
        q.field?.let {
            where += "CASE WHEN json = 1 THEN json_type(payload, ?) END IS NOT NULL"
            args += "$.$it"
        }
        val filterSql = if (where.isEmpty()) "" else " WHERE " + where.joinToString(" AND ")
        val total = connection.prepareStatement("SELECT COUNT(*) FROM events$filterSql").use { s ->
            args.forEachIndexed { i, a -> s.set(i + 1, a) }
            s.executeQuery().use { it.next(); it.getLong(1) }
        }

        val key = if (q.sortBy == "topic") "topic" else "at"
        val dir = if (q.descending) "DESC" else "ASC"
        val pageWhere = where.toMutableList()
        val pageArgs = args.toMutableList()
        q.cursor?.let {
            val (value, id) = decodeCursor(it)
            pageWhere += "($key, id) ${if (q.descending) "<" else ">"} (?, ?)"
            pageArgs += if (key == "at") (value.toLongOrNull() ?: throw ApiException.badRequest("bad cursor")) else value
            pageArgs += id
        }
        val pageSql = if (pageWhere.isEmpty()) "" else " WHERE " + pageWhere.joinToString(" AND ")
        val rows = connection.prepareStatement(
            "SELECT id, at, topic, payload, json, retained, truncated FROM events$pageSql ORDER BY $key $dir, id $dir LIMIT ?",
        ).use { s ->
            pageArgs.forEachIndexed { i, a -> s.set(i + 1, a) }
            s.setInt(pageArgs.size + 1, q.limit + 1)
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(Event(rs.getLong(1), Instant.ofEpochMilli(rs.getLong(2)), rs.getString(3), rs.getString(4), rs.getInt(5) == 1, rs.getInt(6) == 1, rs.getInt(7) == 1))
                    }
                }
            }
        }
        val page = rows.take(q.limit)
        val next = if (rows.size > q.limit) page.last().let { encodeCursor(if (key == "at") it.at.toEpochMilli().toString() else it.topic, it.id) } else null
        return EventPage(page, next, total)
    }

    @Synchronized
    fun topics(): List<TopicSummary> = connection.createStatement().use { s ->
        s.executeQuery(
            "SELECT e.topic, g.c, e.at, e.payload, e.json FROM events e JOIN (SELECT MAX(id) AS mid, COUNT(*) AS c FROM events GROUP BY topic) g ON e.id = g.mid ORDER BY e.topic",
        ).use { rs ->
            buildList { while (rs.next()) add(TopicSummary(rs.getString(1), rs.getLong(2), Instant.ofEpochMilli(rs.getLong(3)), rs.getString(4), rs.getInt(5) == 1)) }
        }
    }

    private fun java.sql.PreparedStatement.set(i: Int, value: Any) = when (value) {
        is Long -> setLong(i, value)
        else -> setString(i, value.toString())
    }

    private fun encodeCursor(value: String, id: Long) = Base64.getUrlEncoder().withoutPadding().encodeToString("$id\n$value".toByteArray())

    private fun decodeCursor(cursor: String): Pair<String, Long> = try {
        val text = String(Base64.getUrlDecoder().decode(cursor))
        text.substringAfter('\n') to text.substringBefore('\n').toLong()
    } catch (e: IllegalArgumentException) {
        throw ApiException.badRequest("bad cursor")
    }

    companion object {
        /** What a row costs besides its topic and payload (id, times, flags, index entries). */
        const val ROW_OVERHEAD = 60
    }
}
