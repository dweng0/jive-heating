package heating.app.ui.rules

import heating.app.data.FieldKind
import heating.app.data.LoggedEvent
import heating.app.data.RuleCondition
import heating.app.data.buildCondition
import heating.app.data.operatorsFor
import heating.app.data.seenFields
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ConditionMappingTest {
    private fun event(id: Long, body: String) =
        LoggedEvent(id, "2026-11-07T11:%02d:00Z".format(id), "z2m/front_door", Json.parseToJsonElement(body), json = body.startsWith("{"))

    private val events = listOf(
        event(1, """{"contact":true,"battery":93,"linkquality":80,"action":"single"}"""),
        event(2, """{"contact":false,"battery":93,"linkquality":75,"action":"double"}"""),
        event(3, """{"contact":false,"battery":91,"linkquality":75,"action":"hold","update":{"state":"idle"}}"""),
        event(4, """{"contact":true,"battery":91,"linkquality":60,"action":"single"}"""),
    )

    // BDD: Conditions are mapped from the payload's fields
    @Test
    fun `fields seen on the topic are offered with their recent values`() {
        val fields = seenFields(events).associateBy { it.path }
        assertEquals(setOf("contact", "battery", "linkquality", "action", "update.state"), fields.keys)
        assertEquals(FieldKind.BOOLEAN, fields.getValue("contact").kind)
        assertEquals(FieldKind.NUMBER, fields.getValue("battery").kind)
        assertEquals(FieldKind.TEXT, fields.getValue("action").kind)
        // Newest first, each value once
        assertEquals(listOf("true", "false"), fields.getValue("contact").recent)
        assertEquals(listOf("91", "93"), fields.getValue("battery").recent)
        assertEquals(listOf("single", "hold", "double"), fields.getValue("action").recent)
        // A text payload is the field "value"
        assertEquals(FieldKind.TEXT, seenFields(listOf(event(5, "\"away\""))).single().also { assertEquals("value", it.path) }.kind)
    }

    // BDD: Conditions are mapped from the payload's fields
    @Test
    fun `the operators offered fit the field`() {
        assertEquals(listOf("is true", "is false", "exists"), operatorsFor(FieldKind.BOOLEAN).map { it.label })
        assertEquals(listOf("=", "≠", ">", "<", "≥", "≤", "exists"), operatorsFor(FieldKind.NUMBER).map { it.label })
        assertEquals(listOf("=", "≠", "contains", "exists"), operatorsFor(FieldKind.TEXT).map { it.label })
        assertEquals(false, operatorsFor(FieldKind.BOOLEAN).first().takesValue)
        assertEquals(false, operatorsFor(FieldKind.NUMBER).last().takesValue)
        assertEquals(true, operatorsFor(FieldKind.TEXT).first().takesValue)
    }

    // BDD: Conditions are mapped from the payload's fields
    @Test
    fun `a condition is built from the field, operator and value picked or typed`() {
        val bool = operatorsFor(FieldKind.BOOLEAN)
        assertEquals(RuleCondition("contact", "=", JsonPrimitive(true)), buildCondition("contact", bool[0], ""))
        assertEquals(RuleCondition("contact", "=", JsonPrimitive(false)), buildCondition("contact", bool[1], ""))
        assertEquals(RuleCondition("contact", "exists"), buildCondition("contact", bool[2], ""))
        val num = operatorsFor(FieldKind.NUMBER)
        assertEquals(RuleCondition("battery", "<", JsonPrimitive(20L)), buildCondition("battery", num.first { it.label == "<" }, "20"))
        assertEquals(RuleCondition("battery", "≥", JsonPrimitive(12.5)), buildCondition("battery", num.first { it.label == "≥" }, "12.5"))
        val text = operatorsFor(FieldKind.TEXT)
        assertEquals(RuleCondition("action", "=", JsonPrimitive("hold")), buildCondition("action", text[0], "hold"))
        assertEquals(RuleCondition("action", "contains", JsonPrimitive("ol")), buildCondition("action", text[2], "ol"))
        // A number field typed with text that is not a number is kept as typed rather than lost
        assertEquals(JsonPrimitive("abc"), buildCondition("battery", num[0], "abc").value)
    }
}
