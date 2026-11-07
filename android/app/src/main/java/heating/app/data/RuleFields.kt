package heating.app.data

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive

enum class FieldKind { BOOLEAN, NUMBER, TEXT }

/** A field seen on a topic: its [path] ("value" for a text payload), what it holds and its [recent] values, newest first. */
data class SeenField(val path: String, val kind: FieldKind, val recent: List<String>)

private const val RECENT_VALUES = 5

private fun kindOf(value: JsonElement): FieldKind {
    val p = value as? JsonPrimitive ?: return FieldKind.TEXT
    return when {
        p.isString -> FieldKind.TEXT
        p.content.toBooleanStrictOrNull() != null -> FieldKind.BOOLEAN
        p.content.toDoubleOrNull() != null -> FieldKind.NUMBER
        else -> FieldKind.TEXT
    }
}

/** The fields in [events] (any order) with their kind, from the newest value, and the recent values each held. */
fun seenFields(events: List<LoggedEvent>): List<SeenField> {
    val values = linkedMapOf<String, MutableList<JsonElement>>()
    events.sortedByDescending { it.id }.forEach { e ->
        val fields = if (e.json) payloadFields(e.payload) else listOf(PayloadField("value", e.payload))
        fields.forEach { values.getOrPut(it.path) { mutableListOf() } += it.value }
    }
    return values.map { (path, list) ->
        SeenField(path, kindOf(list.first()), list.map { valueText(it) }.distinct().take(RECENT_VALUES))
    }
}

/** An operator the builder offers: its [label], the rule [operator] it means, a fixed [fixed] value ("is true"), or whether one is typed. */
data class OperatorChoice(val label: String, val operator: String, val fixed: Boolean? = null, val takesValue: Boolean = fixed == null && operator != "exists")

/** What can be asked of a field of this kind; every field can be asked whether it exists. */
fun operatorsFor(kind: FieldKind): List<OperatorChoice> = when (kind) {
    FieldKind.BOOLEAN -> listOf(OperatorChoice("is true", "=", true), OperatorChoice("is false", "=", false))
    FieldKind.NUMBER -> listOf("=", "≠", ">", "<", "≥", "≤").map { OperatorChoice(it, it) }
    FieldKind.TEXT -> listOf("=", "≠", "contains").map { OperatorChoice(it, it) }
} + OperatorChoice("exists", "exists")

/** The condition for [field] with [choice] and, when it takes one, [value]; numbers that read as numbers are kept as numbers. */
fun buildCondition(field: String, choice: OperatorChoice, value: String): RuleCondition = when {
    choice.fixed != null -> RuleCondition(field, choice.operator, JsonPrimitive(choice.fixed))
    !choice.takesValue -> RuleCondition(field, choice.operator)
    choice.operator == "contains" -> RuleCondition(field, choice.operator, JsonPrimitive(value))
    else -> RuleCondition(field, choice.operator, typedValue(value, null))
}
