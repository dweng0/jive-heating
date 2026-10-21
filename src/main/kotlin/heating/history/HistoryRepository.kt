package heating.history

import java.sql.Connection
import java.sql.DriverManager
import java.sql.Types
import java.time.Instant

/** One room's temperature and effective target at a moment. */
data class RoomPoint(val at: Instant, val roomId: String, val current: Double?, val target: Double?)

/** What the boiler was doing from [from] until [to]; [to] is null for the state it is in now. */
data class BoilerPeriod(val state: String, val from: Instant, val to: Instant?)

/**
 * Room temperature points and boiler states, in their own tables of the service's SQLite file.
 * Kept apart from the JSON document store because there are many small rows queried by time.
 */
class HistoryRepository(path: String) {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path").also {
        it.createStatement().use { s ->
            s.execute("CREATE TABLE IF NOT EXISTS room_points (at INTEGER NOT NULL, room_id TEXT NOT NULL, current REAL, target REAL)")
            s.execute("CREATE INDEX IF NOT EXISTS room_points_at ON room_points (at)")
            s.execute(
                "CREATE TABLE IF NOT EXISTS boiler_states (id INTEGER PRIMARY KEY AUTOINCREMENT, state TEXT NOT NULL, " +
                    "from_at INTEGER NOT NULL, to_at INTEGER, seen_at INTEGER NOT NULL)",
            )
            s.execute("CREATE INDEX IF NOT EXISTS boiler_states_from ON boiler_states (from_at)")
        }
    }

    @Synchronized
    fun addPoints(points: List<RoomPoint>) = transaction {
        connection.prepareStatement("INSERT INTO room_points (at, room_id, current, target) VALUES (?, ?, ?, ?)").use { s ->
            points.forEach { p ->
                s.setLong(1, p.at.toEpochMilli())
                s.setString(2, p.roomId)
                s.setNullableDouble(3, p.current)
                s.setNullableDouble(4, p.target)
                s.addBatch()
            }
            s.executeBatch()
        }
    }

    /** Points with [from] <= at <= [to], oldest first. */
    @Synchronized
    fun points(from: Instant, to: Instant): List<RoomPoint> =
        connection.prepareStatement("SELECT at, room_id, current, target FROM room_points WHERE at >= ? AND at <= ? ORDER BY at").use { s ->
            s.setLong(1, from.toEpochMilli())
            s.setLong(2, to.toEpochMilli())
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        add(RoomPoint(Instant.ofEpochMilli(rs.getLong(1)), rs.getString(2), rs.getNullableDouble(3), rs.getNullableDouble(4)))
                    }
                }
            }
        }

    /** The boiler went into [state] at [at]: ends the open state and opens this one. */
    @Synchronized
    fun boilerChanged(state: String, at: Instant) = transaction {
        val open = openState()
        if (open != null && open.second == at) {
            // Changed again at the same moment: replace rather than keep an empty period.
            update("UPDATE boiler_states SET state = ?, seen_at = ? WHERE id = ?", state, at.toEpochMilli(), open.first)
        } else {
            if (open != null) update("UPDATE boiler_states SET to_at = ? WHERE id = ?", at.toEpochMilli(), open.first)
            update("INSERT INTO boiler_states (state, from_at, seen_at) VALUES (?, ?, ?)", state, at.toEpochMilli(), at.toEpochMilli())
        }
    }

    /** The service was alive at [at]; a restart ends the open state here rather than at the restart. */
    @Synchronized
    fun seen(at: Instant) {
        update("UPDATE boiler_states SET seen_at = ? WHERE to_at IS NULL", at.toEpochMilli())
    }

    /** After a restart: end the state left open at the last moment the service was seen. */
    @Synchronized
    fun closeOpenState() {
        update("UPDATE boiler_states SET to_at = seen_at WHERE to_at IS NULL")
    }

    /** States overlapping [from]..[to], oldest first. */
    @Synchronized
    fun boilerStates(from: Instant, to: Instant): List<BoilerPeriod> =
        connection.prepareStatement(
            "SELECT state, from_at, to_at FROM boiler_states WHERE from_at <= ? AND (to_at IS NULL OR to_at >= ?) ORDER BY from_at, id",
        ).use { s ->
            s.setLong(1, to.toEpochMilli())
            s.setLong(2, from.toEpochMilli())
            s.executeQuery().use { rs ->
                buildList {
                    while (rs.next()) {
                        val end = rs.getLong(3).takeUnless { rs.wasNull() }
                        add(BoilerPeriod(rs.getString(1), Instant.ofEpochMilli(rs.getLong(2)), end?.let(Instant::ofEpochMilli)))
                    }
                }
            }
        }

    /** Deletes points and finished states older than [cutoff]. */
    @Synchronized
    fun deleteBefore(cutoff: Instant) = transaction {
        update("DELETE FROM room_points WHERE at < ?", cutoff.toEpochMilli())
        update("DELETE FROM boiler_states WHERE to_at IS NOT NULL AND to_at < ?", cutoff.toEpochMilli())
    }

    fun close() = connection.close()

    /** Id and start of the open state, if any. */
    private fun openState(): Pair<Long, Instant>? =
        connection.prepareStatement("SELECT id, from_at FROM boiler_states WHERE to_at IS NULL ORDER BY id DESC LIMIT 1").use { s ->
            s.executeQuery().use { rs -> if (rs.next()) rs.getLong(1) to Instant.ofEpochMilli(rs.getLong(2)) else null }
        }

    private fun update(sql: String, vararg args: Any) {
        connection.prepareStatement(sql).use { s ->
            args.forEachIndexed { i, a ->
                when (a) {
                    is Long -> s.setLong(i + 1, a)
                    else -> s.setString(i + 1, a.toString())
                }
            }
            s.executeUpdate()
        }
    }

    private fun <T> transaction(block: () -> T): T {
        connection.autoCommit = false
        try {
            return block().also { connection.commit() }
        } catch (e: Exception) {
            connection.rollback()
            throw e
        } finally {
            connection.autoCommit = true
        }
    }
}

private fun java.sql.PreparedStatement.setNullableDouble(i: Int, v: Double?) =
    if (v == null) setNull(i, Types.REAL) else setDouble(i, v)

private fun java.sql.ResultSet.getNullableDouble(i: Int): Double? = getDouble(i).takeUnless { wasNull() }
