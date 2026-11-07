package heating.rules

import heating.heatingJson
import kotlinx.serialization.Serializable
import java.sql.Connection
import java.sql.DriverManager
import java.time.Instant

/** What a rule did with a message that matched. */
object Outcome {
    const val SENT = "sent"
    const val COOLDOWN = "skipped by cooldown"
    const val QUIET = "skipped by quiet hours"
    const val HELD = "held until quiet hours end"
    const val PAUSED = "paused"
    const val SUMMARY = "summary sent"
}

/** One match in a rule's history, as GET /api/rules/{id}/history lists it. [text] reads "sent to 3 people", "skipped by cooldown" and so on; [deliveryIds] link to /api/deliveries/{id}. */
@Serializable
data class RuleHistoryEntry(
    val at: String,
    val topic: String,
    val payload: String,
    val outcome: String,
    val text: String,
    val recipients: Int = 0,
    val deliveryIds: List<String> = emptyList(),
    val detail: String? = null,
)

/** Every match of every rule and what became of it, in a table of the service's SQLite file. */
class RuleHistory(path: String) {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path").also {
        it.createStatement().use { s ->
            s.execute(
                "CREATE TABLE IF NOT EXISTS rule_history (id INTEGER PRIMARY KEY AUTOINCREMENT, rule_id TEXT NOT NULL, at INTEGER NOT NULL, json TEXT NOT NULL)",
            )
            s.execute("CREATE INDEX IF NOT EXISTS rule_history_rule ON rule_history (rule_id, at)")
        }
    }

    @Synchronized
    fun add(ruleId: String, at: Instant, topic: String, payload: String, outcome: String, deliveryIds: List<String> = emptyList(), detail: String? = null) {
        val n = deliveryIds.size
        val text = when (outcome) {
            Outcome.SENT -> "sent to $n ${if (n == 1) "person" else "people"}"
            Outcome.SUMMARY -> "summary sent to $n ${if (n == 1) "person" else "people"}"
            else -> outcome
        }
        val entry = RuleHistoryEntry(at.toString(), topic, payload.take(MAX_PAYLOAD), outcome, text, n, deliveryIds, detail)
        connection.prepareStatement("INSERT INTO rule_history (rule_id, at, json) VALUES (?, ?, ?)").use { s ->
            s.setString(1, ruleId)
            s.setLong(2, at.toEpochMilli())
            s.setString(3, heatingJson.encodeToString(RuleHistoryEntry.serializer(), entry))
            s.executeUpdate()
        }
    }

    /** Rule [ruleId]'s history, newest first. */
    @Synchronized
    fun of(ruleId: String, limit: Int): List<RuleHistoryEntry> =
        connection.prepareStatement("SELECT json FROM rule_history WHERE rule_id = ? ORDER BY at DESC, id DESC LIMIT ?").use { s ->
            s.setString(1, ruleId)
            s.setInt(2, limit)
            s.executeQuery().use { rs ->
                buildList { while (rs.next()) add(heatingJson.decodeFromString(RuleHistoryEntry.serializer(), rs.getString(1))) }
            }
        }

    /** When each rule last sent (or sent a summary), for the rules that did within the kept history. */
    @Synchronized
    fun lastSent(): Map<String, Instant> =
        connection.prepareStatement("SELECT rule_id, MAX(at) FROM rule_history WHERE json LIKE ? OR json LIKE ? GROUP BY rule_id").use { s ->
            s.setString(1, "%\"outcome\":\"${Outcome.SENT}\"%")
            s.setString(2, "%\"outcome\":\"${Outcome.SUMMARY}\"%")
            s.executeQuery().use { rs ->
                buildMap { while (rs.next()) put(rs.getString(1), Instant.ofEpochMilli(rs.getLong(2))) }
            }
        }

    @Synchronized
    fun forget(ruleId: String) {
        connection.prepareStatement("DELETE FROM rule_history WHERE rule_id = ?").use { s ->
            s.setString(1, ruleId)
            s.executeUpdate()
        }
    }

    /** Drops entries before [before]. */
    @Synchronized
    fun deleteOlderThan(before: Instant): Int =
        connection.prepareStatement("DELETE FROM rule_history WHERE at < ?").use { s ->
            s.setLong(1, before.toEpochMilli())
            s.executeUpdate()
        }

    companion object {
        const val MAX_PAYLOAD = 4000
    }
}
