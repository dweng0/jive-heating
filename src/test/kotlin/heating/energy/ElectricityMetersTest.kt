package heating.energy

import heating.heatingJson
import heating.testing.MutableClock
import heating.testing.TestHeating
import java.time.Duration
import java.time.Instant
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class ElectricityMetersTest {

    private fun meters(t: TestHeating, query: String = ""): List<JsonObject> {
        var result = emptyList<JsonObject>()
        t.api { client ->
            val response = client.get("/api/meters$query")
            assertEquals(HttpStatusCode.OK, response.status)
            result = heatingJson.parseToJsonElement(response.bodyAsText()).jsonArray.map { it.jsonObject }
        }
        return result
    }

    private fun names(meters: List<JsonObject>) = meters.map { it["name"]!!.jsonPrimitive.content }

    // BDD: Electricity meters are discovered from the bridge device list
    @Test
    fun `electricity meters are discovered from the bridge device list`() {
        val t = TestHeating()
        t.discover("Bedroom 3 TRV", sensors = listOf("Landing Smoke"), meters = listOf("Main Clamp"))
        assertEquals(listOf("Main Clamp"), names(meters(t)))
    }

    // BDD: Meter readings are ingested from its state topic
    @Test
    fun `meter readings are ingested from its state topic`() {
        val t = TestHeating()
        t.discover(meters = listOf("Main Clamp"))
        t.meterState("Main Clamp", """{"energy":1523.4,"power":412}""")
        val clamp = meters(t).single()
        assertEquals("Main Clamp", clamp["name"]!!.jsonPrimitive.content)
        assertEquals(1523.4, clamp["energy"]!!.jsonPrimitive.double)
        assertEquals(412.0, clamp["power"]!!.jsonPrimitive.double)
        assertEquals(true, clamp["online"]!!.jsonPrimitive.boolean)
        assertEquals(false, clamp["inUse"]!!.jsonPrimitive.boolean)
        assertEquals(false, clamp["stale"]!!.jsonPrimitive.boolean)
    }

    private fun putSettings(t: TestHeating, body: String) = t.api { client ->
        val response = client.put("/api/settings/energy") {
            contentType(ContentType.Application.Json)
            setBody(body)
        }
        assertEquals(HttpStatusCode.OK, response.status)
    }

    // BDD: New meters are listed as unassigned
    @Test
    fun `new meters are listed as unassigned`() {
        val t = TestHeating()
        t.discover(meters = listOf("Main Clamp", "Garage Plug"))
        putSettings(t, """{"electricityMeter":"Garage Plug"}""")
        assertEquals(listOf("Main Clamp"), names(meters(t, "?unassigned=true")))
        assertEquals(listOf("Main Clamp", "Garage Plug"), names(meters(t)))
    }

    private fun electricityMeter(t: TestHeating): String? {
        var result: String? = null
        t.api { client ->
            val body = heatingJson.parseToJsonElement(client.get("/api/settings/energy").bodyAsText()).jsonObject
            result = body["electricityMeter"]!!.jsonPrimitive.contentOrNull
        }
        return result
    }

    private fun house(t: TestHeating, method: HttpMethod, meter: String): HttpStatusCode {
        var status = HttpStatusCode.OK
        t.api { client -> status = client.request("/api/meters/${meter.replace(" ", "%20")}/house") { this.method = method }.status }
        return status
    }

    private fun inUse(t: TestHeating) = meters(t).filter { it["inUse"]!!.jsonPrimitive.boolean }.let(::names)

    // BDD: An admin chooses the house electricity meter
    @Test
    fun `an admin chooses the house electricity meter`() {
        val t = TestHeating()
        t.discover(meters = listOf("Main Clamp", "Garage Plug"))
        assertEquals(HttpStatusCode.OK, house(t, HttpMethod.Put, "Main Clamp"))
        assertEquals("Main Clamp", electricityMeter(t))
        assertEquals(listOf("Main Clamp"), inUse(t))

        assertEquals(HttpStatusCode.OK, house(t, HttpMethod.Put, "Garage Plug"))
        assertEquals("Garage Plug", electricityMeter(t))
        assertEquals(listOf("Garage Plug"), inUse(t))

        assertEquals(HttpStatusCode.NotFound, house(t, HttpMethod.Delete, "Main Clamp"))
        assertEquals(HttpStatusCode.OK, house(t, HttpMethod.Put, "Main Clamp"))
        assertEquals(HttpStatusCode.OK, house(t, HttpMethod.Delete, "Main Clamp"))
        assertEquals(null, electricityMeter(t))
        assertEquals(emptyList(), inUse(t))
        assertEquals(HttpStatusCode.NotFound, house(t, HttpMethod.Put, "Hall Plug"))

        t.api {
            val sam = memberSam()
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Put, "/api/meters/Main%20Clamp/house").status)
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Delete, "/api/meters/Main%20Clamp/house").status)
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Get, "/api/meters").status)
        }
        assertEquals(null, electricityMeter(t))
    }

    // BDD: Unpairing the house meter clears it
    @Test
    fun `unpairing the house meter clears it`() {
        val t = TestHeating()
        t.discover("Bedroom 3 TRV", meters = listOf("Main Clamp"))
        assertEquals(HttpStatusCode.OK, house(t, HttpMethod.Put, "Main Clamp"))
        t.discover("Bedroom 3 TRV")
        assertEquals(null, electricityMeter(t))
        t.api { client ->
            val body = heatingJson.parseToJsonElement(client.get("/api/settings/energy").bodyAsText()).jsonObject
            assertEquals(listOf("Main Clamp was removed from Zigbee2MQTT"), body["notices"]!!.jsonArray.map { it.jsonPrimitive.content })
        }
        assertEquals(emptyList(), names(meters(t)))

        // Choosing a meter again clears the notice.
        t.discover("Bedroom 3 TRV", meters = listOf("Main Clamp"))
        assertEquals(HttpStatusCode.OK, house(t, HttpMethod.Put, "Main Clamp"))
        t.api { client ->
            val body = heatingJson.parseToJsonElement(client.get("/api/settings/energy").bodyAsText()).jsonObject
            assertEquals(0, body["notices"]!!.jsonArray.size)
        }
    }

    // BDD: A meter that stops reporting is shown as stale, not as zero use
    @Test
    fun `a meter that stops reporting is shown as stale, not as zero use`() {
        // Midnight in London, so the whole of today so far has been recorded.
        val t = TestHeating(clock = MutableClock(Instant.parse("2026-10-11T23:00:00Z")))
        t.discover(meters = listOf("Main Clamp"))
        assertEquals(HttpStatusCode.OK, house(t, HttpMethod.Put, "Main Clamp"))
        t.meterState("Main Clamp", """{"energy":1523.4,"power":412}""")
        repeat(8) {
            t.clock.advance(Duration.ofMinutes(5))
            t.app.tick()
        }

        val clamp = meters(t).single()
        assertEquals(true, clamp["stale"]!!.jsonPrimitive.boolean)
        assertEquals("2026-10-12T00:00:00+01:00", clamp["lastReading"]!!.jsonPrimitive.content)

        t.api { client ->
            val today = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject
            // Stale from 30 minutes on: the samples at 30, 35 and 40 minutes are missing, not 0 kWh.
            assertEquals(15, today["missingMinutes"]!!.jsonPrimitive.int)
            assertEquals(true, today["incomplete"]!!.jsonPrimitive.boolean)
            val day = today["days"]!!.jsonArray.single().jsonObject
            assertEquals(15, day["missingMinutes"]!!.jsonPrimitive.int)
            assertEquals(true, day["incomplete"]!!.jsonPrimitive.boolean)
        }

        // Reporting again, it is no longer stale.
        t.meterState("Main Clamp", """{"energy":1524.0,"power":412}""")
        assertEquals(false, meters(t).single()["stale"]!!.jsonPrimitive.boolean)
    }
}
