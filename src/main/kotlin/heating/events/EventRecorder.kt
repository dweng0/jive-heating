package heating.events

import heating.ApiException
import heating.Periodic
import heating.mqtt.MqttBus
import heating.mqtt.TopicFilter
import heating.store.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** How long events are kept and which topics are left out. Stored as one document. */
@Serializable
data class EventSettings(val days: Int = DEFAULT_DAYS, val excluded: List<String> = emptyList()) {
    companion object {
        const val DEFAULT_DAYS = 7
        const val MIN_DAYS = 1
        const val MAX_DAYS = 90
    }
}

/** How the event log is doing: what waits to be written, what was lost, what is stored. */
@Serializable
data class EventLogStatus(val queued: Int, val dropped: Long, val stored: Long, val bytes: Long, val maxBytes: Long)

/** An excluded topic filter and how many messages a minute it is sending (still counted, though not logged). */
@Serializable
data class ExcludedTopic(val topic: String, val perMinute: Int)

/**
 * Subscribes to every topic and logs each message. The MQTT handler only queues the message (cheap);
 * [flush] writes the queue in batches, every [flushMillis] ms on a background thread (0 = only when called, for tests).
 * Past [maxQueue] waiting events the oldest are dropped and counted.
 */
class EventRecorder(
    private val bus: MqttBus,
    val log: EventLog,
    private val store: Store,
    private val clock: Clock,
    private val flushMillis: Long = 500,
    private val maxQueue: Int = MAX_QUEUE,
    private val maxBytes: Long = MAX_BYTES,
) {
    private val logger = LoggerFactory.getLogger(EventRecorder::class.java)

    private class Pending(val at: Instant, val topic: String, val payload: String, val retained: Boolean, val truncated: Boolean)

    private val queue = ArrayDeque<Pending>()
    private var dropped = 0L

    /** When each excluded topic filter saw its recent messages, epoch millis. */
    private val excludedSeen = HashMap<String, ArrayDeque<Long>>()
    private val lock = Any()

    @Volatile
    private var settings: EventSettings = store.get(SETTINGS_KEY)?.let { runCatching { Json.decodeFromString<EventSettings>(it) }.getOrNull() } ?: EventSettings()

    fun settings(): EventSettings = settings

    fun update(days: Int?, excluded: List<String>?): EventSettings {
        if (days != null && days !in EventSettings.MIN_DAYS..EventSettings.MAX_DAYS) {
            throw ApiException.badRequest("days must be ${EventSettings.MIN_DAYS} to ${EventSettings.MAX_DAYS}")
        }
        excluded?.forEach { if (!validFilter(it)) throw ApiException.badRequest("not a topic filter: $it") }
        val next = EventSettings(days ?: settings.days, excluded?.distinct() ?: settings.excluded)
        synchronized(lock) {
            settings = next
            excludedSeen.keys.retainAll(next.excluded.toSet())
        }
        store.put(SETTINGS_KEY, Json.encodeToString(EventSettings.serializer(), next))
        return next
    }

    fun excluded(): List<ExcludedTopic> {
        val cutoff = clock.millis() - 60_000
        return synchronized(lock) {
            settings.excluded.map { filter ->
                val seen = excludedSeen[filter]
                seen?.let { while (it.isNotEmpty() && it.first() < cutoff) it.removeFirst() }
                ExcludedTopic(filter, seen?.size ?: 0)
            }
        }
    }

    fun status(): EventLogStatus = synchronized(lock) { queue.size to dropped }.let { (queued, lost) ->
        EventLogStatus(queued, lost, log.count(), log.bytes(), maxBytes)
    }

    fun start() {
        bus.subscribeWithRetained("#") { topic, payload, retained -> offer(topic, payload, retained) }
        if (flushMillis > 0) {
            Thread({
                while (true) {
                    Thread.sleep(flushMillis)
                    flush()
                }
            }, "event-log-writer").apply { isDaemon = true }.start()
        }
    }

    /** Queue one message. Never blocks and never throws: a broken log must not slow the heating. */
    fun offer(topic: String, payload: String, retained: Boolean) {
        val at = clock.instant()
        synchronized(lock) {
            val excludedBy = settings.excluded.filter { TopicFilter.matches(it, topic) }
            if (excludedBy.isNotEmpty()) {
                val ms = at.toEpochMilli()
                excludedBy.forEach { excludedSeen.getOrPut(it) { ArrayDeque() }.addLast(ms) }
                return
            }
            val cut = truncate(payload)
            if (queue.size >= maxQueue) {
                queue.removeFirst()
                dropped++
            }
            queue.addLast(Pending(at, topic, cut ?: payload, retained, cut != null))
        }
    }

    /** Write everything waiting, in batches. */
    fun flush() {
        while (true) {
            val batch = synchronized(lock) {
                val n = minOf(queue.size, BATCH)
                List(n) { queue.removeFirst() }
            }
            if (batch.isEmpty()) return
            try {
                log.addAll(batch.map { Event(0, it.at, it.topic, it.payload, !it.truncated && isJsonContainer(it.payload), it.retained, it.truncated) })
            } catch (e: Exception) {
                logger.error("could not write {} events", batch.size, e)
            }
        }
    }

    private var lastPruned: Instant? = null

    /** Once an hour: delete events older than the kept days, then the oldest while the log is over its size limit. */
    val retention = Periodic {
        val now = clock.instant()
        if (lastPruned?.let { Duration.between(it, now) < Duration.ofHours(1) } == true) return@Periodic
        lastPruned = now
        runCatching {
            val old = log.deleteOlderThan(now.minus(Duration.ofDays(settings.days.toLong())))
            val big = log.trimTo(maxBytes)
            if (old + big > 0) logger.info("event log: deleted {} events past {} days and {} over the size limit", old, settings.days, big)
        }.onFailure { logger.error("event log clean-up failed", it) }
    }

    companion object {
        const val MAX_QUEUE = 10_000
        const val MAX_BYTES = 500L * 1024 * 1024
        const val MAX_PAYLOAD_BYTES = 64 * 1024
        private const val BATCH = 500
        private const val SETTINGS_KEY = "event-log-settings"

        /** The payload cut to 64 KB of UTF-8, or null when it already fits. */
        fun truncate(payload: String): String? {
            if (payload.length * 3 <= MAX_PAYLOAD_BYTES) return null
            val bytes = payload.toByteArray(Charsets.UTF_8)
            if (bytes.size <= MAX_PAYLOAD_BYTES) return null
            return String(bytes, 0, MAX_PAYLOAD_BYTES, Charsets.UTF_8).trimEnd('�')
        }

        /** JSON objects and arrays are kept as JSON; numbers, words and the like stay text. */
        fun isJsonContainer(payload: String): Boolean {
            val first = payload.firstOrNull { !it.isWhitespace() } ?: return false
            if (first != '{' && first != '[') return false
            return runCatching { Json.parseToJsonElement(payload) }.getOrNull().let { it is JsonObject || it is JsonArray }
        }

        /** An MQTT topic filter: levels separated by "/", "+" alone in a level, "#" alone in the last. */
        fun validFilter(filter: String): Boolean {
            if (filter.isBlank()) return false
            val levels = filter.split('/')
            return levels.withIndex().all { (i, l) ->
                when {
                    l == "#" -> i == levels.lastIndex
                    l.contains('#') || (l.contains('+') && l != "+") -> false
                    else -> true
                }
            }
        }
    }
}
