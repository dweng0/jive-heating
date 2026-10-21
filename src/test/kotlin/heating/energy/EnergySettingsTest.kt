package heating.energy

import heating.heatingJson
import heating.testing.TestHeating
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class EnergySettingsTest {

    private fun settings(t: TestHeating): JsonObject {
        var result: JsonObject? = null
        t.api { client ->
            val response = client.get("/api/settings/energy")
            assertEquals(HttpStatusCode.OK, response.status)
            result = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        return result!!
    }

    private fun JsonObject.number(key: String) = this[key]!!.jsonPrimitive.double

    // BDD: Energy settings start with default prices
    @Test
    fun `energy settings start with default prices`() {
        val body = settings(TestHeating())
        assertEquals(11.0, body.number("gasPencePerKwh"))
        assertEquals(30.0, body.number("gasStandingPencePerDay"))
        assertEquals(12.0, body.number("boilerAverageKw"))
        assertEquals(27.0, body.number("electricityPencePerKwh"))
        assertEquals(50.0, body.number("electricityStandingPencePerDay"))
        assertEquals(JsonNull, body["electricityMeter"])
    }

    private fun put(t: TestHeating, body: String): HttpStatusCode {
        var status = HttpStatusCode.OK
        t.api { client ->
            status = client.put("/api/settings/energy") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.status
        }
        return status
    }

    // BDD: An admin changes the energy prices
    @Test
    fun `an admin changes the energy prices`() {
        val t = TestHeating()
        assertEquals(HttpStatusCode.OK, put(t, """{"gasPencePerKwh":6.5,"electricityPencePerKwh":24.5}"""))
        val check = { body: JsonObject ->
            assertEquals(6.5, body.number("gasPencePerKwh"))
            assertEquals(24.5, body.number("electricityPencePerKwh"))
            assertEquals(30.0, body.number("gasStandingPencePerDay"))
            assertEquals(12.0, body.number("boilerAverageKw"))
            assertEquals(50.0, body.number("electricityStandingPencePerDay"))
            assertEquals(JsonNull, body["electricityMeter"])
        }
        check(settings(t))
        t.restart()
        check(settings(t))
    }

    // BDD: Energy settings refuse impossible values
    @Test
    fun `energy settings refuse impossible values`() {
        val t = TestHeating()
        t.discover("Bedroom 3 TRV", meters = listOf("Main Clamp"))
        val before = settings(t)
        val refused = listOf(
            """{"gasPencePerKwh":-1.0}""",
            """{"gasStandingPencePerDay":-0.5}""",
            """{"electricityPencePerKwh":-24.5,"gasPencePerKwh":6.5}""",
            """{"electricityStandingPencePerDay":-50.0}""",
            """{"boilerAverageKw":0}""",
            """{"boilerAverageKw":100.5}""",
            """{"electricityMeter":"Garage Plug"}""",
            """{"electricityMeter":"Bedroom 3 TRV"}""",
            """{"gasPencePerKwh":"cheap"}""",
        )
        for (body in refused) {
            assertEquals(HttpStatusCode.BadRequest, put(t, body), body)
            assertEquals(before, settings(t), body)
        }
        assertEquals(HttpStatusCode.OK, put(t, """{"boilerAverageKw":100.0,"electricityMeter":"Main Clamp"}"""))
    }

    // BDD: Money is shown in the household's currency
    @Test
    fun `money is shown in the household's currency`() {
        val t = TestHeating()
        assertEquals("GBP", settings(t)["currency"]!!.jsonPrimitive.content)
        assertEquals(HttpStatusCode.OK, put(t, """{"currency":"EUR"}"""))
        assertEquals("EUR", settings(t)["currency"]!!.jsonPrimitive.content)
        assertEquals(11.0, settings(t).number("gasPencePerKwh"))
        t.restart()
        assertEquals("EUR", settings(t)["currency"]!!.jsonPrimitive.content)

        for (body in listOf("""{"currency":"XYZ"}""", """{"currency":"euro"}""", """{"currency":3}""")) {
            assertEquals(HttpStatusCode.BadRequest, put(t, body), body)
            assertEquals("EUR", settings(t)["currency"]!!.jsonPrimitive.content, body)
        }

        // Costs come with the currency they are in, so the apps can show "€1.25".
        t.api { client ->
            val dashboard = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject
            assertEquals("EUR", dashboard["currency"]!!.jsonPrimitive.content)
        }
    }

    // BDD: Only admins see or change energy settings
    @Test
    fun `only admins see or change energy settings`() {
        val t = TestHeating()
        t.api {
            val sam = memberSam()
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Get, "/api/settings/energy").status)
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Put, "/api/settings/energy", """{"gasPencePerKwh":1.0}""").status)
        }
        assertEquals(11.0, settings(t).number("gasPencePerKwh"))
    }
}
