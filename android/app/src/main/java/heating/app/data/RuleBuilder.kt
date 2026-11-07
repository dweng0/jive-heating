package heating.app.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

/** The last part of a topic, which for a device is its name: "front_door" from "z2m/front_door". */
fun topicDevice(topic: String): String = topic.trimEnd('/').substringAfterLast('/')

private fun readable(device: String): String = device.replace('_', ' ').trim().replaceFirstChar { it.uppercase() }

/** [text] as the JSON value a condition on [field] compares with: as typed in the [seen] payload, else by how it reads. */
fun typedValue(text: String, seen: JsonElement?): JsonPrimitive {
    val sample = seen as? JsonPrimitive
    if (sample != null && sample.isString) return JsonPrimitive(text)
    text.toBooleanStrictOrNull()?.let { if (sample == null || sample.content.toBooleanStrictOrNull() != null) return JsonPrimitive(it) }
    if (sample == null || sample.content.toDoubleOrNull() != null) {
        text.toLongOrNull()?.let { return JsonPrimitive(it) }
        text.toDoubleOrNull()?.let { return JsonPrimitive(it) }
    }
    return JsonPrimitive(text)
}

/**
 * The rule "Notify me when…" starts: [topic] with, for a chosen [field] and [value], that condition, trigger "changes to",
 * and a name, title and message made from the device. [payload] is a recent payload on the topic, to type the value.
 */
fun draftFromEvent(topic: String, payload: JsonElement?, field: String?, value: String?): RuleRequest {
    val device = topicDevice(topic)
    if (field.isNullOrEmpty()) {
        return RuleRequest(name = readable(device), topic = topic, title = device, message = "$device: {{payload}}")
    }
    val seen = payload?.let { payloadFields(it).firstOrNull { f -> f.path == field }?.value }
    return if (value.isNullOrEmpty()) {
        RuleRequest(
            name = "${readable(device)} $field", topic = topic, conditions = listOf(RuleCondition(field, "exists")),
            title = device, message = "$device: $field is {{payload.$field}}",
        )
    } else {
        RuleRequest(
            name = "${readable(device)} $field $value", topic = topic, conditions = listOf(RuleCondition(field, "=", typedValue(value, seen))),
            title = device, message = "$device: $field is $value",
        )
    }
}

/** How a rule test marks an event: whether the rule would have sent for it, and if not why. */
fun previewLabel(event: RuleTestEvent): String =
    if (event.status == "would_send") "Would send" else "Would not send: ${event.result}"

/** A condition as the builder shows it: "contact = false", "battery exists". */
fun conditionText(c: RuleCondition): String {
    val v = c.value as? JsonPrimitive
    return if (c.operator == "exists" || v == null) "${c.field} ${c.operator}" else "${c.field} ${c.operator} ${v.content}"
}
