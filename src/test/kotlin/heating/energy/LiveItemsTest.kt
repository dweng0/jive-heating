package heating.energy

import heating.heatingJson
import heating.testing.TestHeating
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LiveItemsTest {

    // BDD: Live power carries every meter's watts
    @Test
    fun `live power carries every meter's watts`() {
        val t = TestHeating()
        t.discover(meters = listOf("Main Clamp", "Shower Clamp", "Oven Clamp", "Kettle Plug", "Fridge Plug", "Garage Clamp"))
        var kitchen = ""
        t.api { client ->
            kitchen = heatingJson.parseToJsonElement(client.post("/api/rooms") { contentType(ContentType.Application.Json); setBody("""{"name":"Kitchen"}""") }.bodyAsText())
                .jsonObject["id"]!!.jsonPrimitive.content
            for (area in listOf("Bathroom", "Kitchen counter")) {
                client.post("/api/energy/areas") { contentType(ContentType.Application.Json); setBody("""{"name":"$area"}""") }
            }
            assertEquals(HttpStatusCode.OK, client.put("/api/meters/Main Clamp/house").status)
            suspend fun put(path: String, body: String) =
                assertEquals(HttpStatusCode.OK, client.put(path) { contentType(ContentType.Application.Json); setBody(body) }.status, path)
            put("/api/meters/Shower Clamp/job", """{"job":"area","area":"bathroom"}""")
            put("/api/meters/Shower Clamp/label", """{"label":"Shower"}""")
            put("/api/meters/Oven Clamp/job", """{"job":"monitored","room":"$kitchen"}""")
            put("/api/meters/Oven Clamp/label", """{"label":"Oven"}""")
            put("/api/meters/Kettle Plug/job", """{"job":"monitored","area":"kitchen-counter"}""")
            put("/api/meters/Fridge Plug/job", """{"job":"monitored"}""")
        }
        for ((m, w) in listOf("Main Clamp" to 11500, "Shower Clamp" to 9500, "Oven Clamp" to 1200, "Kettle Plug" to 0, "Fridge Plug" to 90, "Garage Clamp" to 400)) {
            t.meterState(m, """{"energy":1.0,"power":$w}""")
        }

        val items = t.app.livePower.now().items.associateBy { it.meter }
        assertEquals(setOf("Shower Clamp", "Oven Clamp", "Kettle Plug", "Fridge Plug", "Garage Clamp"), items.keys, "every meter but the house")
        assertEquals(ItemPower("Shower Clamp", "Shower", 9500.0, room = null, area = "bathroom", job = "area"), items["Shower Clamp"])
        assertEquals(ItemPower("Oven Clamp", "Oven", 1200.0, room = kitchen, area = null, job = "monitored"), items["Oven Clamp"])
        assertEquals(ItemPower("Kettle Plug", "Kettle Plug", 0.0, room = null, area = "kitchen-counter", job = "monitored"), items["Kettle Plug"])
        assertEquals(ItemPower("Fridge Plug", "Fridge Plug", 90.0, room = null, area = null, job = "monitored"), items["Fridge Plug"])
        // A clamp that has not been given a job is still seen.
        assertEquals(ItemPower("Garage Clamp", "Garage Clamp", 400.0, room = null, area = null, job = null), items["Garage Clamp"])

        // Offline: no live watts.
        t.availability("Fridge Plug", online = false)
        assertNull(t.app.livePower.now().items.first { it.meter == "Fridge Plug" }.watts)
    }
}
