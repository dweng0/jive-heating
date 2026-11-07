package heating.rules

import heating.mqtt.MqttBus
import heating.mqtt.Z2mTopics
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** A rule worth having for a Zigbee2MQTT [device]; [setUp] when a rule already does it (same topic, same first condition). */
@Serializable
data class Suggestion(
    val name: String,
    val topic: String,
    val conditions: List<Condition>,
    val trigger: String,
    val title: String,
    val message: String,
    val device: String,
    val setUp: Boolean = false,
)

/**
 * Rules worth having for each Zigbee2MQTT device, from what it exposes on the retained bridge/devices message:
 * contact, battery, water_leak, occupancy, tamper and each action value. The message is kept from the bus, not the
 * event log, because the log cuts a large household's device list short.
 */
class RuleSuggestions(private val topics: Z2mTopics) {
    @Volatile
    private var offered: List<Suggestion> = emptyList()

    fun start(bus: MqttBus) {
        bus.subscribe(topics.bridgeDevices) { _, payload ->
            val devices = runCatching { Json.parseToJsonElement(payload) }.getOrNull() as? JsonArray ?: return@subscribe
            offered = suggestionsFor(devices, topics.base)
        }
    }

    /** The suggestions, only [device]'s when given, each marked whether one of [rules] already does it. */
    fun list(rules: List<Rule>, device: String? = null): List<Suggestion> =
        offered.filter { device == null || it.device == device }.map { it.copy(setUp = alreadySetUp(it, rules)) }

    companion object {
        fun suggestionsFor(devices: JsonArray, base: String): List<Suggestion> = devices.flatMap { element ->
            val d = element as? JsonObject ?: return@flatMap emptyList()
            val device = d.text("friendly_name")
            if (device.isNullOrEmpty() || d.text("type") == "Coordinator") return@flatMap emptyList()
            val exposes = flat((d["definition"] as? JsonObject)?.get("exposes") ?: d["exposes"])
            val props = exposes.mapNotNull { e -> e.text("property")?.let { it to e } }.toMap()
            val topic = "$base/$device"
            fun make(name: String, field: String, operator: String, value: JsonPrimitive, message: String) =
                Suggestion(name, topic, listOf(Condition(field, operator, value)), Rules.CHANGES_TO, device, message, device)
            buildList {
                if ("contact" in props) add(make("$device opened", "contact", "=", JsonPrimitive(false), "$device opened"))
                if ("battery" in props) add(make("$device battery low", "battery", "<", JsonPrimitive(15), "$device battery is low ({{payload.battery}}%)"))
                if ("water_leak" in props) add(make("$device leak detected", "water_leak", "=", JsonPrimitive(true), "$device detected a leak"))
                if ("occupancy" in props) add(make("$device motion detected", "occupancy", "=", JsonPrimitive(true), "$device detected motion"))
                if ("tamper" in props) add(make("$device tampered with", "tamper", "=", JsonPrimitive(true), "$device was tampered with"))
                props["action"]?.let { action ->
                    (action["values"] as? JsonArray).orEmpty().mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
                        .forEach { v -> add(make("$device $v", "action", "=", JsonPrimitive(v), "$device: $v")) }
                }
            }
        }

        /** Whether a rule already does what [s] offers: same topic, and a condition the same as its first. */
        fun alreadySetUp(s: Suggestion, rules: List<Rule>): Boolean {
            val c = s.conditions.first()
            return rules.any { r -> r.topic == s.topic && r.conditions.any { it.field == c.field && it.operator == c.operator && same(it.value, c.value) } }
        }

        private fun same(a: JsonElement?, b: JsonElement?): Boolean {
            if (a !is JsonPrimitive || b !is JsonPrimitive) return a == b
            if (a.isString != b.isString) return false
            val x = a.content.toDoubleOrNull()
            val y = b.content.toDoubleOrNull()
            return if (!a.isString && x != null && y != null) x == y else a.content == b.content
        }

        private fun flat(list: JsonElement?): List<JsonObject> =
            (list as? JsonArray).orEmpty().filterIsInstance<JsonObject>().flatMap { listOf(it) + flat(it["features"]) }

        private fun JsonObject.text(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
    }
}
