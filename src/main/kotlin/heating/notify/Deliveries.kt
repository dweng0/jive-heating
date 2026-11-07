package heating.notify

import heating.heatingJson
import java.sql.Connection
import java.sql.DriverManager
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.Executor
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import org.slf4j.LoggerFactory

enum class DeliveryStatus {
    QUEUED, RETRYING, SENT, FAILED;

    val label: String get() = name.lowercase()
}

/** One phone or browser a delivery can go to. [key] identifies it (a token or an endpoint), [label] is safe to show. */
data class DeliveryDevice(val key: String, val label: String)

/** Where a delivery's notification goes: a person's devices, and the push services behind them. */
interface DeliveryTransport {
    fun devices(userId: String): List<DeliveryDevice>

    /** Sends to [device]; the push service's answer. An [PushResult.UNREGISTERED] device is removed by the transport. Must not throw. */
    fun send(userId: String, device: DeliveryDevice, message: WebPushMessage): PushResult
}

/** The event that caused a delivery, as the rule saw it: its [id] (if events are kept), [topic], [payload] and [at] (ISO time). */
@Serializable
data class DeliveryEvent(
    val id: String? = null,
    val topic: String? = null,
    val payload: JsonElement? = null,
    val at: String? = null,
)

@Serializable
data class DeliveryAttempt(val at: String, val error: String)

/** A delivery as stored: one notification for one person. Times are epoch milliseconds. */
@Serializable
data class Delivery(
    val id: String,
    val userId: String,
    val title: String,
    val body: String,
    val source: String,
    val createdAt: Long,
    val roomId: String? = null,
    val ruleId: String? = null,
    val event: DeliveryEvent? = null,
    val status: DeliveryStatus = DeliveryStatus.QUEUED,
    /** Tries made in all, and since it was last queued (a retry by an admin starts the backoff again). */
    val tries: Int = 0,
    val cycleTries: Int = 0,
    val nextAt: Long = createdAt,
    val sentAt: Long? = null,
    val sentTo: List<String> = emptyList(),
    /** Keys of the devices that already took it, so a retry skips them. */
    val sentKeys: List<String> = emptyList(),
    val attempts: List<DeliveryAttempt> = emptyList(),
)

/** What to search deliveries for; null fields match everything. */
data class DeliveryQuery(
    val status: DeliveryStatus? = null,
    val source: String? = null,
    val userId: String? = null,
    val from: Instant? = null,
    val to: Instant? = null,
)

/**
 * The durable queue every notification goes through (heating alerts, area notifications, rule notifications):
 * [enqueue] stores one delivery per person in the service's SQLite file and [process] (run on every tick, and
 * straight after [enqueue]) sends the due ones to each of the person's devices through [transport] on [executor].
 * A failed try is retried after 30 seconds, 2 minutes and 10 minutes; after that the delivery is FAILED until
 * an admin retries or clears it. Sent deliveries are kept [KEEP]; failed ones stay.
 */
class Deliveries(
    path: String,
    private val transport: DeliveryTransport,
    private val executor: Executor,
    private val now: () -> Instant,
) {
    private val connection: Connection = DriverManager.getConnection("jdbc:sqlite:$path").also {
        it.createStatement().use { s ->
            s.execute(
                "CREATE TABLE IF NOT EXISTS deliveries (id TEXT PRIMARY KEY, user_id TEXT NOT NULL, created_at INTEGER NOT NULL, " +
                    "status TEXT NOT NULL, next_at INTEGER NOT NULL, json TEXT NOT NULL)",
            )
            s.execute("CREATE INDEX IF NOT EXISTS deliveries_due ON deliveries (status, next_at)")
        }
    }

    private val processing = Any()

    /**
     * Queues [title] and [body] for each of [userIds], one delivery each, and starts sending. [source] says what
     * sent it ("Heating alert", "Area notification", or a rule's name); [ruleId] and [event] link a rule's delivery
     * to its rule and the event that fired it; a tap opens [roomId]. Returns the new delivery ids, in order.
     */
    fun enqueue(
        userIds: Collection<String>,
        title: String,
        body: String,
        source: String,
        roomId: String? = null,
        ruleId: String? = null,
        event: DeliveryEvent? = null,
    ): List<String> {
        val at = now().toEpochMilli()
        val ids = userIds.distinct().map { userId ->
            val id = UUID.randomUUID().toString()
            save(Delivery(id, userId, title, body, source, at, roomId, ruleId, event))
            id
        }
        if (ids.isNotEmpty()) executor.execute(::process)
        return ids
    }

    /** Whether [userId] has any phone or browser to notify. */
    fun hasDevices(userId: String): Boolean = transport.devices(userId).isNotEmpty()

    /** Called on every tick: sends what is due on [executor], and drops sent deliveries older than [KEEP]. */
    val tick = heating.Periodic {
        purge()
        if (hasDue()) executor.execute(::process)
    }

    /** Sends every due delivery now. Never throws. */
    fun process() = synchronized(processing) {
        runCatching { due().forEach { attempt(it) } }.onFailure { log.warn("delivery queue failed: {}", it.message) }
    }

    private fun attempt(delivery: Delivery) {
        val at = now()
        val late = Duration.between(Instant.ofEpochMilli(delivery.createdAt), at) > LATE
        val message = WebPushMessage(if (late) delivery.title + LATE_SUFFIX else delivery.title, delivery.body, delivery.roomId)
        val sent = delivery.sentKeys.toMutableList()
        val took = delivery.sentTo.toMutableList()
        val errors = mutableListOf<String>()
        transport.devices(delivery.userId).filter { it.key !in sent }.forEach { device ->
            val result = try {
                transport.send(delivery.userId, device, message)
            } catch (e: Exception) {
                errors += "${device.label}: ${e.message ?: e.javaClass.simpleName}"
                return@forEach
            }
            when (result) {
                PushResult.SENT -> { sent += device.key; took += device.label }
                // Gone for good: removed by the transport, never retried.
                PushResult.UNREGISTERED -> Unit
                PushResult.FAILED -> errors += "${device.label}: push service unreachable or refusing"
            }
        }
        val tries = delivery.tries + 1
        val cycle = delivery.cycleTries + 1
        val updated = if (errors.isEmpty()) {
            delivery.copy(status = DeliveryStatus.SENT, tries = tries, cycleTries = cycle, sentAt = at.toEpochMilli(), sentTo = took, sentKeys = sent)
        } else {
            val attempts = delivery.attempts + DeliveryAttempt(at.toString(), errors.joinToString("; "))
            val failed = cycle > BACKOFF.size
            delivery.copy(
                status = if (failed) DeliveryStatus.FAILED else DeliveryStatus.RETRYING,
                tries = tries, cycleTries = cycle, sentTo = took, sentKeys = sent, attempts = attempts,
                nextAt = if (failed) delivery.nextAt else at.plus(BACKOFF[cycle - 1]).toEpochMilli(),
            )
        }
        if (updated.status == DeliveryStatus.FAILED) log.warn("notification \"{}\" failed after {} tries", delivery.title, tries)
        save(updated)
    }

    // --- admin ---

    fun get(id: String): Delivery? = load("SELECT json FROM deliveries WHERE id = ?") { it.setString(1, id) }.firstOrNull()

    /** Deliveries matching [query], newest first. */
    fun search(query: DeliveryQuery): List<Delivery> = load("SELECT json FROM deliveries ORDER BY created_at DESC, rowid DESC") {}
        .filter { d ->
            (query.status == null || d.status == query.status) &&
                (query.source == null || d.source == query.source) &&
                (query.userId == null || d.userId == query.userId) &&
                (query.from == null || d.createdAt >= query.from.toEpochMilli()) &&
                (query.to == null || d.createdAt < query.to.toEpochMilli())
        }

    @Synchronized
    fun failedCount(): Int = connection.prepareStatement("SELECT COUNT(*) FROM deliveries WHERE status = 'FAILED'").use { s ->
        s.executeQuery().use { rs -> rs.next(); rs.getInt(1) }
    }

    /** Distinct sources, for filtering. */
    fun sources(): List<String> = search(DeliveryQuery()).map { it.source }.distinct().sorted()

    /** Queues failed delivery [id] again; false if there is none. The backoff starts afresh. */
    fun retry(id: String): Boolean {
        val d = get(id)?.takeIf { it.status == DeliveryStatus.FAILED } ?: return false
        save(d.copy(status = DeliveryStatus.QUEUED, cycleTries = 0, nextAt = now().toEpochMilli()))
        executor.execute(::process)
        return true
    }

    /** Queues every failed delivery again; how many. */
    fun retryAll(): Int {
        val failed = search(DeliveryQuery(status = DeliveryStatus.FAILED))
        failed.forEach { save(it.copy(status = DeliveryStatus.QUEUED, cycleTries = 0, nextAt = now().toEpochMilli())) }
        if (failed.isNotEmpty()) executor.execute(::process)
        return failed.size
    }

    /** Removes delivery [id]; false if there is none. */
    @Synchronized
    fun clear(id: String): Boolean = connection.prepareStatement("DELETE FROM deliveries WHERE id = ?").use { s ->
        s.setString(1, id)
        s.executeUpdate() > 0
    }

    // --- storage ---

    @Synchronized
    private fun save(d: Delivery) {
        connection.prepareStatement(
            "INSERT INTO deliveries (id, user_id, created_at, status, next_at, json) VALUES (?, ?, ?, ?, ?, ?) " +
                "ON CONFLICT(id) DO UPDATE SET status = excluded.status, next_at = excluded.next_at, json = excluded.json",
        ).use { s ->
            s.setString(1, d.id)
            s.setString(2, d.userId)
            s.setLong(3, d.createdAt)
            s.setString(4, d.status.name)
            s.setLong(5, d.nextAt)
            s.setString(6, heatingJson.encodeToString(Delivery.serializer(), d))
            s.executeUpdate()
        }
    }

    @Synchronized
    private fun load(sql: String, bind: (java.sql.PreparedStatement) -> Unit): List<Delivery> =
        connection.prepareStatement(sql).use { s ->
            bind(s)
            s.executeQuery().use { rs ->
                buildList { while (rs.next()) add(heatingJson.decodeFromString(Delivery.serializer(), rs.getString(1))) }
            }
        }

    private fun due(): List<Delivery> = load(
        "SELECT json FROM deliveries WHERE status IN ('QUEUED', 'RETRYING') AND next_at <= ? ORDER BY created_at, rowid",
    ) { it.setLong(1, now().toEpochMilli()) }

    @Synchronized
    private fun hasDue(): Boolean = connection.prepareStatement(
        "SELECT 1 FROM deliveries WHERE status IN ('QUEUED', 'RETRYING') AND next_at <= ? LIMIT 1",
    ).use { s ->
        s.setLong(1, now().toEpochMilli())
        s.executeQuery().use { it.next() }
    }

    @Synchronized
    private fun purge() {
        connection.prepareStatement("DELETE FROM deliveries WHERE status = 'SENT' AND created_at < ?").use { s ->
            s.setLong(1, now().minus(KEEP).toEpochMilli())
            s.executeUpdate()
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(Deliveries::class.java)

        /** Waits before the 2nd, 3rd and 4th tries. */
        val BACKOFF: List<Duration> = listOf(Duration.ofSeconds(30), Duration.ofMinutes(2), Duration.ofMinutes(10))
        val LATE: Duration = Duration.ofHours(24)
        const val LATE_SUFFIX = " (sent late)"
        val KEEP: Duration = Duration.ofDays(30)
    }
}
