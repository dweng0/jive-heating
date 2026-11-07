package heating.mqtt

import heating.setup.BrokerClient
import heating.setup.BrokerFactory
import heating.setup.BrokerFailure
import heating.setup.BrokerSettings
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/** Whether the service is talking to its broker. */
data class LinkState(val state: String, val since: Instant? = null, val reason: String? = null) {
    companion object {
        const val MISSING = "missing"
        const val CONNECTED = "connected"
        const val DISCONNECTED = "disconnected"
    }
}

/**
 * The service's one MQTT connection, which can be pointed at another broker while running. It remembers every
 * subscription and replays them on each new connection. While there is no connection, publishes are dropped:
 * nothing published while the broker was away is replayed later. After a loss it retries from [tick] with backoff
 * of 1, 2, 4 seconds and so on, never more than 60.
 */
class ReconnectingMqttBus(
    private val factory: BrokerFactory,
    private val clock: () -> Instant,
) : MqttBus {
    private val log = LoggerFactory.getLogger(ReconnectingMqttBus::class.java)
    private val lock = Any()
    private val subscriptions = mutableListOf<Pair<String, (String, String, Boolean) -> Unit>>()
    private var client: BrokerClient? = null
    private var settings: BrokerSettings? = null
    private var generation = 0
    private var downSince: Instant? = null
    private var lastReason: String? = null
    private var retryDelay = Duration.ofSeconds(1)
    private var nextRetry: Instant? = null

    val brokerFactory: BrokerFactory get() = factory

    fun state(): LinkState = synchronized(lock) {
        when {
            settings == null -> LinkState(LinkState.MISSING)
            client != null -> LinkState(LinkState.CONNECTED)
            else -> LinkState(LinkState.DISCONNECTED, downSince, lastReason)
        }
    }

    /** Connect to [new] for the first time (a failure is retried from [tick]), or after a restart. */
    fun start(new: BrokerSettings) {
        synchronized(lock) { settings = new; downSince = clock(); lastReason = null }
        attempt()
    }

    /** Switch to [new] now: disconnect from the old broker, connect to the new one and subscribe to every topic. Throws [BrokerFailure]. */
    fun switchTo(new: BrokerSettings) {
        val (gen, old) = synchronized(lock) { ++generation to client }
        val opened = factory.open(new, { t, p, r -> dispatch(gen, t, p, r) }, { r -> lost(gen, r) })
        synchronized(lock) {
            client = opened
            settings = new
            downSince = null
            lastReason = null
            retryDelay = Duration.ofSeconds(1)
            nextRetry = null
        }
        runCatching { old?.close() }
        replay(opened)
        log.info("MQTT connected to {}", new.url)
    }

    private fun attempt() {
        val (gen, current) = synchronized(lock) { ++generation to settings }
        if (current == null) return
        try {
            val opened = factory.open(current, { t, p, r -> dispatch(gen, t, p, r) }, { r -> lost(gen, r) })
            synchronized(lock) {
                client = opened
                downSince = null
                lastReason = null
                retryDelay = Duration.ofSeconds(1)
                nextRetry = null
            }
            replay(opened)
            log.info("MQTT connected to {}", current.url)
        } catch (e: BrokerFailure) {
            failed(e.message)
        } catch (e: Exception) {
            failed(e.message ?: e.javaClass.simpleName)
        }
    }

    private fun failed(reason: String) = synchronized(lock) {
        lastReason = reason
        if (downSince == null) downSince = clock()
        nextRetry = clock().plus(retryDelay)
        log.warn("MQTT connection failed ({}); retrying in {} s", reason, retryDelay.seconds)
        retryDelay = minOf(retryDelay.multipliedBy(2), MAX_DELAY)
    }

    private fun lost(gen: Int, reason: String) {
        synchronized(lock) {
            if (gen != generation || client == null) return
            val old = client
            client = null
            downSince = clock()
            lastReason = reason
            retryDelay = Duration.ofSeconds(1)
            nextRetry = clock().plus(retryDelay)
            log.warn("MQTT connection lost ({}); retrying in {} s", reason, retryDelay.seconds)
            retryDelay = retryDelay.multipliedBy(2)
            runCatching { old?.close() }
        }
    }

    /** Called every tick: tries to reconnect when the connection is down and the backoff has passed. */
    fun tick() {
        val due = synchronized(lock) { client == null && settings != null && nextRetry?.let { !clock().isBefore(it) } == true }
        if (due) attempt()
    }

    private fun replay(opened: BrokerClient) {
        val filters = synchronized(lock) { subscriptions.map { it.first }.distinct() }
        filters.forEach { runCatching { opened.subscribe(it) }.onFailure { e -> log.warn("subscribe {} failed: {}", it, e.message) } }
    }

    private fun dispatch(gen: Int, topic: String, payload: String, retained: Boolean) {
        val handlers = synchronized(lock) {
            if (gen != generation) return
            subscriptions.filter { TopicFilter.matches(it.first, topic) }.map { it.second }
        }
        handlers.forEach { runCatching { it(topic, payload, retained) }.onFailure { e -> log.error("handler failed for {}", topic, e) } }
    }

    override fun publish(topic: String, payload: String, retained: Boolean) {
        val current = synchronized(lock) { client } ?: return
        try {
            current.publish(topic, payload, retained)
        } catch (e: Exception) {
            log.warn("publish to {} failed: {}", topic, e.message)
        }
    }

    override fun subscribe(filter: String, handler: (topic: String, payload: String) -> Unit) =
        subscribeWithRetained(filter) { t, p, _ -> handler(t, p) }

    override fun subscribeWithRetained(filter: String, handler: (topic: String, payload: String, retained: Boolean) -> Unit) {
        val current = synchronized(lock) {
            subscriptions += filter to handler
            client
        }
        current?.let { runCatching { it.subscribe(filter) }.onFailure { e -> log.warn("subscribe {} failed: {}", filter, e.message) } }
    }

    /**
     * Messages on [filter] that arrive within [wait] (retained ones come at once; after the first, [grace] more is
     * allowed for others), without keeping the subscription. Empty when there is no connection.
     */
    fun collect(filter: String, wait: Duration, grace: Duration): List<Pair<String, String>> {
        val found = java.util.concurrent.CopyOnWriteArrayList<Pair<String, String>>()
        val handler: (String, String, Boolean) -> Unit = { t, p, _ -> found += t to p }
        val current = synchronized(lock) { subscriptions += filter to handler; client }
        try {
            if (current == null) return emptyList()
            runCatching { current.subscribe(filter) }.onFailure { return emptyList() }
            val deadline = System.nanoTime() + wait.toNanos()
            var firstAt: Long? = null
            while (System.nanoTime() < deadline) {
                if (found.isNotEmpty() && firstAt == null) firstAt = System.nanoTime()
                if (firstAt != null && System.nanoTime() - firstAt >= grace.toNanos()) break
                Thread.sleep(20)
            }
            return found.toList()
        } finally {
            val others = synchronized(lock) {
                subscriptions.removeAll { it.second === handler }
                subscriptions.any { it.first == filter }
            }
            if (!others) current?.let { runCatching { it.unsubscribe(filter) } }
        }
    }

    /** Zigbee2MQTT's base topic changed: every subscription under "[old]/" moves to "[new]/". */
    fun rebase(old: String, new: String) {
        val (current, moved) = synchronized(lock) {
            val moved = mutableListOf<Pair<String, String>>()
            for (i in subscriptions.indices) {
                val (filter, handler) = subscriptions[i]
                if (filter.startsWith("$old/")) {
                    val to = "$new/" + filter.removePrefix("$old/")
                    subscriptions[i] = to to handler
                    moved += filter to to
                }
            }
            client to moved
        }
        current ?: return
        moved.map { it.first }.distinct().forEach { from ->
            if (synchronized(lock) { subscriptions.none { it.first == from } }) runCatching { current.unsubscribe(from) }
        }
        moved.map { it.second }.distinct().forEach { to -> runCatching { current.subscribe(to) } }
    }

    companion object {
        val MAX_DELAY: Duration = Duration.ofSeconds(60)
    }
}
