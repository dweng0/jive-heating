package heating.events

import heating.heatingJson
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class EventExplorerTest {
    private suspend fun ApplicationTestBuilder.page(query: String): JsonObject {
        val response = client.get("/api/events?$query") { bearerAuth(TEST_TOKEN) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        return heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
    }

    private fun JsonObject.total() = this["total"]!!.jsonPrimitive.content.toInt()

    private fun JsonObject.topics() = this["events"]!!.jsonArray.map { it.jsonObject["topic"]!!.jsonPrimitive.content }

    // BDD: Events are filtered with chips
    @Test
    fun `device, field value and retained chips narrow the events`() {
        val t = TestHeating(configure = { it.copy(eventFlushMillis = 0) })
        t.bus.receive("zigbee2mqtt/Front Door", """{"contact":false,"battery":91}""")
        t.bus.receive("zigbee2mqtt/Front Door", """{"contact":true,"battery":91}""")
        t.bus.receive("zigbee2mqtt/Front Door/availability", """{"state":"online"}""")
        t.bus.receive("zigbee2mqtt/Back Door", """{"contact":false,"battery":50}""")
        t.bus.receive("garage/temp", "18.5", retained = true)
        t.app.events.flush()
        t.api {
            // Device: a Zigbee2MQTT friendly name, with its availability topic
            assertEquals(3, page("device=Front%20Door").total())
            // Field value: contact = false, and contact != false
            assertEquals(listOf("zigbee2mqtt/Back Door", "zigbee2mqtt/Front Door"), page("field=contact&value=false&sort=topic%20asc").topics())
            assertEquals(1, page("field=contact&value=false&op=ne").total())
            // A number matches whether or not it was sent with a decimal point
            assertEquals(1, page("field=battery&value=50.0").total())
            // Retained
            assertEquals(1, page("retained=true&topic=garage/%23").total())
            assertEquals(0, page("retained=false&topic=garage/%23").total())
            // All chips together
            assertEquals(1, page("device=Front%20Door&field=contact&value=false&retained=false").total())
        }
    }
}
