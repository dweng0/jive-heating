package heating.app.data

import kotlinx.serialization.Serializable

/** openapi: SetupStatus. [broker] is missing, connected or disconnected; [since] and [reason] come only with disconnected. */
@Serializable
data class SetupStatus(
    val broker: String = "missing",
    val zigbee2mqtt: String = "missing",
    val complete: Boolean = false,
    val since: String? = null,
    val reason: String? = null,
)

/** openapi: BrokerView. Never carries the password. */
@Serializable
data class BrokerView(val host: String, val port: Int, val tls: Boolean = false, val username: String? = null, val passwordSet: Boolean = false)

/** openapi: BrokerRequest. A [password] left out keeps the saved one; "" clears it. */
@Serializable
data class BrokerRequest(val host: String, val port: Int, val tls: Boolean = false, val username: String? = null, val password: String? = null)

/** POST /api/setup/broker/test: [ok], or why not ([reason] unknown_host, refused, timeout, not_authorized, tls) with a [message]. */
@Serializable
data class BrokerTestResult(val ok: Boolean, val reason: String? = null, val message: String? = null)

/** openapi: BridgeFound. */
@Serializable
data class BridgeFound(val baseTopic: String, val version: String? = null, val devices: Int = 0)

@Serializable
data class BaseTopicRequest(val baseTopic: String)

@Serializable
data class BaseTopicSaved(val baseTopic: String)

/** POST /api/setup/test-notification: whether it went out, and whether the admin has a phone or browser registered. */
@Serializable
data class TestNotificationResult(val sent: Boolean = false, val registered: Boolean = false)
