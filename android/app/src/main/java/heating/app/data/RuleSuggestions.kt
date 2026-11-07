package heating.app.data

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonPrimitive

/** openapi: RuleSuggestion. A rule worth having for a Zigbee2MQTT [device]; [setUp] when a rule already does it. */
@Serializable
data class RuleSuggestion(
    val name: String,
    val topic: String,
    val conditions: List<RuleCondition> = emptyList(),
    val trigger: String = TRIGGER_CHANGES_TO,
    val title: String = "",
    val message: String = "",
    val device: String = "",
    val setUp: Boolean = false,
)

/** The rule a suggestion would make, as the rule form starts it. */
fun RuleSuggestion.toRequest() = RuleRequest(name = name, topic = topic, conditions = conditions, trigger = trigger, title = title, message = message)

/** What a suggestion reads as: "contact changes to false", "battery < 15". */
fun suggestionText(s: RuleSuggestion): String {
    val c = s.conditions.firstOrNull() ?: return ""
    val v = c.value as? JsonPrimitive
    return if (c.operator == "=" && s.trigger == TRIGGER_CHANGES_TO && v != null) "${c.field} changes to ${v.content}" else conditionText(c)
}
