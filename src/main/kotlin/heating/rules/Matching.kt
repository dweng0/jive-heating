package heating.rules

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull

/** Whether a payload meets a rule's conditions. */
object Matching {
    /** All of [conditions] hold for [payload] (none always holds). */
    fun conditionsMet(conditions: List<Condition>, payload: String): Boolean = conditions.all { holds(it, payload) }

    /** The first condition [payload] fails, or null. */
    fun firstFailing(conditions: List<Condition>, payload: String): Condition? = conditions.firstOrNull { !holds(it, payload) }

    fun holds(c: Condition, payload: String): Boolean {
        val actual = Payloads.field(payload, c.field) ?: return false
        if (c.operator == "exists") return true
        val expected = c.value as? JsonPrimitive ?: return false
        return when (c.operator) {
            "=" -> equal(actual, expected)
            "≠" -> !equal(actual, expected)
            ">", "<", "≥", "≤" -> {
                val a = number(actual) ?: return false
                val e = number(expected) ?: return false
                when (c.operator) {
                    ">" -> a > e
                    "<" -> a < e
                    "≥" -> a >= e
                    else -> a <= e
                }
            }
            "contains" -> when (actual) {
                is JsonArray -> actual.any { it is JsonPrimitive && it.content == expected.content }
                is JsonPrimitive -> actual.content.contains(expected.content)
                else -> false
            }
            else -> false
        }
    }

    private fun number(e: JsonElement): Double? = (e as? JsonPrimitive)?.let { if (it.isString) it.content.toDoubleOrNull() else it.doubleOrNull }

    private fun equal(actual: JsonElement, expected: JsonPrimitive): Boolean {
        if (actual !is JsonPrimitive) return false
        val a = number(actual)
        val e = number(expected)
        if (a != null && e != null) return a == e
        return actual.content == expected.content
    }
}
