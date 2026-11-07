package heating.rules

import heating.ApiException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The {{placeholders}} a rule's title and message may use: {{topic}}, {{topic.last}} (its last level), {{device}}
 * (the Zigbee2MQTT friendly name; "?" on other topics), {{payload}} and {{value}} (the whole payload) and
 * {{payload.some.field}}. A field the payload lacks fills in as "?".
 */
object Templates {
    private val PLACEHOLDER = Regex("""\{\{\s*([^{}]*?)\s*}}""")

    private fun known(name: String) =
        name in setOf("topic", "topic.last", "device", "payload", "value") || (name.startsWith("payload.") && name.length > "payload.".length)

    fun validate(field: String, text: String) {
        PLACEHOLDER.findAll(text).forEach {
            if (!known(it.groupValues[1])) throw ApiException.badRequest("$field has an unknown placeholder {{${it.groupValues[1]}}}")
        }
        if (text.replace(PLACEHOLDER, "").let { "{{" in it || "}}" in it }) throw ApiException.badRequest("$field has an unfinished placeholder")
    }

    /** [text] with each placeholder filled in from the message on [topic] with [payload]; [z2mBase] is the Zigbee2MQTT base topic. */
    fun fill(text: String, topic: String, payload: String, z2mBase: String): String = PLACEHOLDER.replace(text) { m ->
        val name = m.groupValues[1]
        val filled = when {
            name == "topic" -> topic
            name == "topic.last" -> topic.substringAfterLast('/')
            name == "device" -> if (topic.startsWith("$z2mBase/")) topic.removePrefix("$z2mBase/") else "?"
            name == "payload" -> payload
            name == "value" -> Payloads.field(payload, "value")?.let(::show) ?: "?"
            name.startsWith("payload.") -> Payloads.field(payload, name.removePrefix("payload."))?.let(::show) ?: "?"
            else -> "?"
        }
        Regex.escapeReplacement(filled)
    }

    fun show(e: JsonElement): String = when (e) {
        is JsonNull -> "?"
        is JsonPrimitive -> e.content
        else -> e.toString()
    }
}

/** Reading a message's payload as a rule sees it. */
object Payloads {
    /** The payload as a JSON object, or null for anything else (text, a bare number, an array). */
    fun obj(payload: String): JsonObject? = runCatching { Json.parseToJsonElement(payload) }.getOrNull() as? JsonObject

    /**
     * The value at [path] (dot separated) of a JSON object payload; for any other payload only "value" resolves,
     * to the whole payload, as a number when it reads as one. Null means missing (a JSON null counts as missing).
     */
    fun field(payload: String, path: String): JsonElement? {
        val root = obj(payload)
        if (root == null) {
            if (path != "value") return null
            val text = payload.trim()
            if (text.toDoubleOrNull()?.isFinite() == true) {
                runCatching { Json.parseToJsonElement(text) }.getOrNull()?.let { if (it is JsonPrimitive) return it }
                return JsonPrimitive(text.toDouble())
            }
            return JsonPrimitive(payload)
        }
        var current: JsonElement = root
        for (part in path.split('.')) {
            current = when (val c = current) {
                is JsonObject -> c[part]
                is JsonArray -> part.toIntOrNull()?.let { c.getOrNull(it) }
                else -> null
            } ?: return null
        }
        return current.takeUnless { it is JsonNull }
    }
}
