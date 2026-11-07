package heating.setup

import heating.ApiException
import heating.Config
import heating.actionLog
import heating.heatingJson
import heating.mqtt.LinkState
import heating.mqtt.ReconnectingMqttBus
import heating.mqtt.Z2mTopics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.time.Duration

/** The broker as the API shows it: never the password. */
@Serializable
data class BrokerView(val host: String, val port: Int, val tls: Boolean, val username: String?, val passwordSet: Boolean)

/** A Zigbee2MQTT bridge found on the broker. */
@Serializable
data class BridgeFound(val baseTopic: String, val version: String?, val devices: Int)

@Serializable
data class BaseTopicRequest(val baseTopic: String? = null)

@Serializable
data class TestNotificationView(val sent: Boolean, val registered: Boolean)

/** A broker that failed its test; answered as 200 (testing) or 422 (saving). */
class BrokerTestFailed(val failure: BrokerFailure) : RuntimeException(failure.message)

/**
 * Connection setup: the broker and the Zigbee2MQTT base topic, saved in the database and applied to the running
 * service without a restart.
 */
class SetupService(
    private val settings: ConnectionSettings,
    private val bus: ReconnectingMqttBus,
    private val topics: Z2mTopics,
    private val config: Config,
    private val registered: (userId: String) -> Boolean,
    private val notify: (userId: String, title: String, body: String) -> Unit,
    private val changed: (what: String) -> Unit,
) {
    /** Connects to the saved broker, if there is one. Called once at startup, after everything has subscribed. */
    fun connect() {
        settings.broker()?.let { bus.start(it) }
    }

    fun status(): JsonObject {
        val link = bus.state()
        return buildJsonObject {
            put("broker", link.state)
            put("zigbee2mqtt", if (settings.baseTopic() == null) "missing" else "configured")
            put("complete", settings.complete())
            if (link.state == LinkState.DISCONNECTED) {
                put("since", link.since?.toString())
                put("reason", link.reason)
            }
        }
    }

    fun broker(): BrokerView? = settings.broker()?.let { BrokerView(it.host, it.port, it.tls, it.username, !it.password.isNullOrEmpty()) }

    private fun validated(request: BrokerRequest): BrokerSettings {
        val host = request.host?.trim().orEmpty()
        if (host.isEmpty()) throw ApiException.badRequest("host must be filled in")
        val port = request.port
        if (port == null || port !in 1..65535) throw ApiException.badRequest("port must be from 1 to 65535")
        return BrokerSettings(host, port, request.tls, request.username?.takeIf { it.isNotEmpty() }, request.password)
    }

    /** Tries the broker without saving or changing anything. */
    fun test(request: BrokerRequest): BrokerFailure? = bus.brokerFactory.test(resolved(request))

    /** The request as settings: a password left out is the saved one, "" is none. */
    private fun resolved(request: BrokerRequest): BrokerSettings {
        val candidate = validated(request)
        val password = when (request.password) {
            null -> settings.broker()?.password
            "" -> null
            else -> request.password
        }
        return candidate.copy(password = if (candidate.username == null) null else password)
    }

    /** Saves a broker that tests ok and reconnects to it. A password left out keeps the saved one; "" clears it. */
    fun saveBroker(request: BrokerRequest): BrokerView {
        val resolved = resolved(request)
        bus.brokerFactory.test(resolved)?.let { throw BrokerTestFailed(it) }
        settings.saveBroker(resolved)
        try {
            bus.switchTo(resolved)
        } catch (e: BrokerFailure) {
            // It tested fine a moment ago; keep trying in the background.
            bus.start(resolved)
        }
        actionLog.info("broker changed to {}:{}", heating.untrusted(resolved.host), resolved.port)
        changed("broker")
        return broker()!!
    }

    /** Zigbee2MQTT bridges announcing themselves on the broker (`<base>/bridge/info`, retained). */
    fun discover(): List<BridgeFound> {
        val infos = bus.collect("+/bridge/info", config.discoverWait, config.discoverGrace)
        if (infos.isEmpty()) return emptyList()
        val devices = bus.collect("+/bridge/devices", config.discoverGrace.coerceAtLeast(Duration.ofMillis(50)), Duration.ZERO)
            .associate { (topic, payload) -> topic.removeSuffix("/bridge/devices") to count(payload) }
        return infos.map { (topic, payload) ->
            val base = topic.removeSuffix("/bridge/info")
            val version = runCatching { heatingJson.parseToJsonElement(payload).jsonObject["version"]?.jsonPrimitive?.contentOrNull }.getOrNull()
            BridgeFound(base, version, devices[base] ?: 0)
        }.distinctBy { it.baseTopic }.sortedBy { it.baseTopic }
    }

    private fun count(payload: String): Int = (runCatching { heatingJson.parseToJsonElement(payload) }.getOrNull() as? JsonArray)?.size ?: 0

    /** Saves the base topic once Zigbee2MQTT there reads online, and moves the valve and meter subscriptions to it. */
    fun saveBaseTopic(request: BaseTopicRequest): String {
        val base = request.baseTopic?.trim()?.trim('/').orEmpty()
        if (base.isEmpty() || '+' in base || '#' in base) throw ApiException.badRequest("baseTopic must be filled in")
        val state = bus.collect("$base/bridge/state", config.discoverWait, Duration.ZERO).lastOrNull()?.second
        if (state == null || !online(state)) throw ApiException.unprocessable("Zigbee2MQTT at $base is offline")
        val old = topics.base
        settings.saveBaseTopic(base)
        if (old != base) {
            topics.base = base
            bus.rebase(old, base)
        }
        actionLog.info("zigbee2mqtt base topic changed to {}", heating.untrusted(base))
        changed("zigbee2mqtt")
        return base
    }

    private fun online(payload: String): Boolean {
        if (payload.trim() == "online") return true
        return runCatching { heatingJson.parseToJsonElement(payload).jsonObject["state"]?.jsonPrimitive?.contentOrNull == "online" }.getOrDefault(false)
    }

    fun sendTestNotification(userId: String): TestNotificationView {
        if (!registered(userId)) return TestNotificationView(sent = false, registered = false)
        notify(userId, "Test notification", "Notifications from your home are working")
        return TestNotificationView(sent = true, registered = true)
    }

    fun complete() {
        settings.markComplete()
        actionLog.info("connection setup finished")
    }
}
