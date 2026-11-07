package heating.app.ui.rules

import heating.app.data.EventTopic
import heating.app.data.RuleCondition
import heating.app.data.RuleRequest
import heating.app.data.hasField
import heating.app.data.isWildcard
import heating.app.data.matchingTopics
import heating.app.data.perDevice
import heating.app.data.swapDevice
import heating.app.data.topicMatches
import heating.app.data.widenedMatches
import heating.app.data.widenedTopic
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TopicWideningTest {
    private fun topic(name: String, payload: String, json: Boolean = true) =
        EventTopic(name, 1, "2026-11-07T12:00:00Z", if (json) Json.parseToJsonElement(payload) else JsonPrimitive(payload), json)

    private val topics = listOf(
        topic("z2m/front_door", """{"contact":false,"battery":90}"""),
        topic("z2m/back_door", """{"contact":true}"""),
        topic("z2m/hall_button", """{"action":"single"}"""),
        topic("z2m/bridge", """{"state":"online"}"""),
        topic("z2m/front_door/availability", "online", json = false),
        topic("other/front_door", """{"contact":false}"""),
    )

    // BDD: The topic is widened to similar devices
    @Test
    fun `any device like this widens the device to a wildcard and lists the matching topics with the field`() {
        assertEquals("z2m/+", widenedTopic("z2m/front_door"))
        assertNull(widenedTopic("front_door"))
        assertNull(widenedTopic("z2m/+"))
        assertNull(widenedTopic("z2m/#"))
        assertTrue(isWildcard("z2m/+"))
        assertTrue(isWildcard("z2m/#"))
        assertFalse(isWildcard("z2m/front_door"))

        assertTrue(topicMatches("z2m/+", "z2m/front_door"))
        assertFalse(topicMatches("z2m/+", "z2m/front_door/availability"))
        assertTrue(topicMatches("z2m/#", "z2m/front_door/availability"))
        assertFalse(topicMatches("z2m/+", "other/front_door"))
        assertTrue(topicMatches("z2m/front_door", "z2m/front_door"))
        assertFalse(topicMatches("z2m/front_door/x", "z2m/front_door"))

        // Zigbee2MQTT's own bridge topics are left out
        assertEquals(listOf("z2m/front_door", "z2m/back_door", "z2m/hall_button"), matchingTopics("z2m/+", topics).map { it.topic })
        assertTrue(hasField(topics[0], "contact"))
        assertFalse(hasField(topics[2], "contact"))
        assertFalse(hasField(topics[4], "contact"))

        val matched = widenedMatches("z2m/+", listOf(RuleCondition("contact", "=", JsonPrimitive(false))), topics)
        assertEquals(listOf("z2m/front_door" to true, "z2m/back_door" to true, "z2m/hall_button" to false), matched.map { it.topic to it.hasFields })
        // with no conditions no topic is said to have the field
        assertEquals(listOf(false, false, false), widenedMatches("z2m/+", emptyList(), topics).map { it.hasFields })
    }

    // BDD: The topic is widened to similar devices
    @Test
    fun `picking devices makes one rule per device with the device swapped into its texts`() {
        assertEquals("back_door opened", swapDevice("front_door opened", "front_door", "back_door"))
        assertEquals("same", swapDevice("same", "", "back_door"))
        val draft = RuleRequest(
            name = "Front door contact false", topic = "z2m/+", conditions = listOf(RuleCondition("contact", "=", JsonPrimitive(false))),
            title = "front_door", message = "{{device}}: contact is false",
        )
        val back = perDevice(draft, started = "z2m/front_door", topic = "z2m/back_door")
        assertEquals("z2m/back_door", back.topic)
        assertEquals("Back door contact false", back.name)
        assertEquals("back_door", back.title)
        assertEquals("back_door: contact is false", back.message)
        assertEquals(draft.conditions, back.conditions)
    }
}
