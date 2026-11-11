package heating.energy

import heating.heatingJson
import heating.history.RoomPoint
import heating.testing.MutableClock
import heating.testing.TestHeating
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Where the house's energy went: GET /api/energy/flow. */
class EnergyFlowTest {
    // Midnight on 11 November in London (UTC in November), and noon.
    private val midnight = Instant.parse("2026-11-11T00:00:00Z")
    private val noon = midnight.plus(Duration.ofHours(12))

    private fun at(minutes: Long) = midnight.plus(Duration.ofMinutes(minutes))

    private fun flow(t: TestHeating, period: String = "today", status: HttpStatusCode = HttpStatusCode.OK): JsonObject? {
        var result: JsonObject? = null
        t.api { client ->
            val response = client.get("/api/energy/flow?period=$period")
            assertEquals(status, response.status, response.bodyAsText())
            if (status == HttpStatusCode.OK) result = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        return result
    }

    private fun room(t: TestHeating, name: String): String {
        var id = ""
        t.api { client ->
            id = heatingJson.parseToJsonElement(client.post("/api/rooms") {
                contentType(ContentType.Application.Json); setBody("""{"name":"$name"}""")
            }.bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        }
        return id
    }

    private fun settings(t: TestHeating, body: String) = t.api { client ->
        assertEquals(HttpStatusCode.OK, client.put("/api/settings/energy") { contentType(ContentType.Application.Json); setBody(body) }.status)
    }

    /** Links by the names of their ends, to kWh. */
    private fun JsonObject.links(): Map<Pair<String, String>, Double> {
        val names = this["nodes"]!!.jsonArray.map { it.jsonObject }.associate { it["id"]!!.jsonPrimitive.content to it["name"]!!.jsonPrimitive.content }
        return this["links"]!!.jsonArray.map { it.jsonObject }.associate {
            (names.getValue(it["from"]!!.jsonPrimitive.content) to names.getValue(it["to"]!!.jsonPrimitive.content)) to it["kwh"]!!.jsonPrimitive.double
        }
    }

    private fun JsonObject.node(name: String) = this["nodes"]!!.jsonArray.map { it.jsonObject }.single { it["name"]!!.jsonPrimitive.content == name }

    // BDD: Where the house's energy went
    @Test
    fun `where the house's energy went`() {
        val t = TestHeating(clock = MutableClock(noon))
        t.discover(meters = listOf("Main Clamp", "Kettle", "Oven", "Lights Clamp", "Lamp", "Fridge"))
        val kitchen = room(t, "Kitchen")
        room(t, "Spare Room")
        t.api { client ->
            assertEquals(HttpStatusCode.OK, client.put("/api/meters/Main Clamp/house").status)
            client.post("/api/energy/areas") { contentType(ContentType.Application.Json); setBody("""{"name":"Lights"}""") }
            fun job(meter: String, body: String) = kotlinx.coroutines.runBlocking {
                assertEquals(HttpStatusCode.OK, client.put("/api/meters/$meter/job") { contentType(ContentType.Application.Json); setBody(body) }.status, meter)
            }
            job("Kettle", """{"job":"monitored","room":"$kitchen"}""")
            job("Oven", """{"job":"monitored","room":"$kitchen"}""")
            job("Lamp", """{"job":"monitored","area":"lights"}""")
            job("Fridge", """{"job":"monitored"}""")
            job("Lights Clamp", """{"job":"area","area":"lights"}""")
        }
        settings(t, """{"boilerAverageKw":11.05}""")
        (1..12).forEach { i ->
            val a = at(5L * i)
            t.app.energyHistory.add(MeterSample(a, "Main Clamp", 1.0, 11.8 / 12))
            t.app.energyHistory.addDevice(MeterSample(a, "Kettle", 1.0, 1.2 / 12, kitchen))
            t.app.energyHistory.addDevice(MeterSample(a, "Oven", 1.0, 1.8 / 12, kitchen))
            // Behind the Lights clamp: part of its figure, counted once.
            t.app.energyHistory.addDevice(MeterSample(a, "Lamp", 1.0, 0.3 / 12, area = "lights"))
            t.app.energyHistory.addArea(MeterSample(a, "Lights Clamp", 1.0, 0.9 / 12, "lights"))
            // In no room or area: a place of its own.
            t.app.energyHistory.addDevice(MeterSample(a, "Fridge", 1.0, 0.4 / 12))
        }
        // Two hours of boiler: 22.1 kWh of gas at 11.05 kW.
        t.app.history.boilerChanged("heating", at(60))
        t.app.history.boilerChanged("idle", at(180))

        val body = flow(t)!!
        assertEquals("GBP", body["currency"]!!.jsonPrimitive.content)
        assertTrue(body.containsKey("from") && body.containsKey("to"))
        assertEquals("source", body.node("Electricity")["kind"]!!.jsonPrimitive.content)
        assertEquals(11.8, body.node("Electricity")["kwh"]!!.jsonPrimitive.double, 1e-6)
        assertEquals("place", body.node("Kitchen")["kind"]!!.jsonPrimitive.content)
        assertEquals("device", body.node("Kettle")["kind"]!!.jsonPrimitive.content)
        assertTrue(body.node("Kitchen")["cost"]!!.jsonPrimitive.double > 0)
        val links = body.links()
        assertEquals(3.0, links.getValue("Electricity" to "Kitchen"), 1e-6)
        assertEquals(0.9, links.getValue("Electricity" to "Lights"), 1e-6)
        assertEquals(0.4, links.getValue("Electricity" to "Fridge"), 1e-6)
        assertEquals(7.5, links.getValue("Electricity" to "Everything else"), 1e-6)
        assertEquals(1.2, links.getValue("Kitchen" to "Kettle"), 1e-6)
        assertEquals(1.8, links.getValue("Kitchen" to "Oven"), 1e-6)
        assertEquals(0.3, links.getValue("Lights" to "Lamp"), 1e-6)
        assertEquals(22.1, links.getValue("Gas" to "Heating"), 1e-6)
        // A room using nothing is left out.
        assertTrue(body["nodes"]!!.jsonArray.none { it.jsonObject["name"]!!.jsonPrimitive.content == "Spare Room" })

        flow(t, "yesterday")
        flow(t, "week")
        flow(t, "month")
        flow(t, "year", HttpStatusCode.BadRequest)
    }

    // BDD: Heating's gas is split between the rooms that called for it
    @Test
    fun `heating's gas is split between the rooms that called for it`() {
        val t = TestHeating(clock = MutableClock(noon))
        val lounge = room(t, "Lounge")
        val bedroom = room(t, "Bedroom")
        settings(t, """{"boilerAverageKw":5.0}""")
        // The boiler burns 01:00-06:00: Lounge below its target 01:00-04:00, Bedroom 04:00-05:00, nobody 05:00-06:00.
        t.app.history.boilerChanged("heating", at(60))
        t.app.history.boilerChanged("idle", at(360))
        (0L..(12L * 60) step 5).forEach { m ->
            val a = at(m)
            t.app.history.addPoints(listOf(
                RoomPoint(a, lounge, 18.0, if (m in 60 until 240) 21.0 else 16.0),
                RoomPoint(a, bedroom, 18.0, if (m in 240 until 300) 20.0 else 15.0),
            ))
        }

        val body = flow(t)!!
        val links = body.links()
        assertEquals(25.0, links.getValue("Gas" to "Heating"), 1e-6)
        assertEquals(15.0, links.getValue("Heating" to "Lounge"), 1e-6)
        assertEquals(5.0, links.getValue("Heating" to "Bedroom"), 1e-6)
        assertEquals(5.0, links.getValue("Heating" to "Heating, no room calling"), 1e-6)
        // The meter does not know which room used the gas.
        val names = body["nodes"]!!.jsonArray.map { it.jsonObject }.associate { it["id"]!!.jsonPrimitive.content to it["name"]!!.jsonPrimitive.content }
        val toLounge = body["links"]!!.jsonArray.map { it.jsonObject }.single { names[it["to"]!!.jsonPrimitive.content] == "Lounge" }
        assertTrue(toLounge["estimated"]!!.jsonPrimitive.boolean)
    }
}
