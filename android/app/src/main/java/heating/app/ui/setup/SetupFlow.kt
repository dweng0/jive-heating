package heating.app.ui.setup

import heating.app.data.BridgeFound
import heating.app.data.BrokerRequest
import heating.app.data.HeatingApi
import heating.app.data.HeatingApiException
import heating.app.data.friendlyError
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** The fields of the Broker step, so a failed test can point at one. */
enum class BrokerField { Host, Port, Tls, Username, Password }

/** What the Broker step has typed. A blank [password] on a saved broker keeps the saved one; [clearPassword] removes it. */
data class BrokerForm(
    val host: String = "",
    val port: String = "1883",
    val tls: Boolean = false,
    val username: String = "",
    val password: String = "",
    val clearPassword: Boolean = false,
)

const val NO_BRIDGE_TEXT = "No Zigbee2MQTT found on this broker. Check it's running and connected to the same broker, or type its base topic."

/** The field a broker test's reason points at. */
fun fieldFor(reason: String?): BrokerField = when (reason) {
    "refused" -> BrokerField.Port
    "not_authorized" -> BrokerField.Password
    "tls" -> BrokerField.Tls
    else -> BrokerField.Host
}

/** Why the form cannot be sent, or null: host filled and port from 1 to 65535. */
fun BrokerForm.problem(): Pair<BrokerField, String>? {
    if (host.isBlank()) return BrokerField.Host to "Enter the broker's host name or address."
    val p = port.trim().toIntOrNull()
    if (p == null || p !in 1..65535) return BrokerField.Port to "Port must be from 1 to 65535."
    return null
}

fun BrokerForm.toRequest() = BrokerRequest(
    host = host.trim(),
    port = port.trim().toInt(),
    tls = tls,
    username = username.takeIf { it.isNotEmpty() },
    password = when {
        clearPassword -> ""
        password.isNotEmpty() -> password
        else -> null
    },
)

sealed interface BrokerCheck {
    data object Ok : BrokerCheck
    data class Failed(val field: BrokerField, val message: String) : BrokerCheck
}

/** Tests the typed broker: nothing is tried when the form is incomplete; a failure names the field it points to. */
suspend fun HeatingApi.checkBroker(form: BrokerForm): BrokerCheck {
    form.problem()?.let { (field, message) -> return BrokerCheck.Failed(field, message) }
    return try {
        val result = testBroker(form.toRequest())
        if (result.ok) BrokerCheck.Ok else BrokerCheck.Failed(fieldFor(result.reason), result.message ?: "The broker could not be reached.")
    } catch (e: HeatingApiException) {
        BrokerCheck.Failed(BrokerField.Host, friendlyError(e))
    } catch (e: java.io.IOException) {
        BrokerCheck.Failed(BrokerField.Host, friendlyError(e))
    }
}

/** "z2m (version 2.1.0, 14 devices)". */
fun bridgeLabel(b: BridgeFound): String {
    val devices = "${b.devices} ${if (b.devices == 1) "device" else "devices"}"
    return "${b.baseTopic} (${b.version?.let { "version $it, " }.orEmpty()}$devices)"
}

/** "Lost connection to your broker since 18:04", in the phone's time zone. */
fun lostText(since: String?, zone: ZoneId = ZoneId.systemDefault()): String {
    val time = since?.let { runCatching { OffsetDateTime.parse(it).atZoneSameInstant(zone).format(DateTimeFormatter.ofPattern("HH:mm")) }.getOrNull() }
        ?: return "Lost connection to your broker"
    return "Lost connection to your broker since $time"
}
