package heating.energy

import heating.heatingJson
import heating.testing.MutableClock
import heating.testing.TestHeating
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** Energy areas on the energy dashboard, and how everything else counts them. */
class EnergyAreaDashboardTest {
    // Midnight in London, and noon that day.
    private val midnight = Instant.parse("2026-10-11T23:00:00Z")
    private val noon = midnight.plus(Duration.ofHours(12))

    private fun at(minutes: Long) = midnight.plus(Duration.ofMinutes(minutes))

    /** House meter "Main Clamp", plugs [plugs] monitored, areas [areas] created; the clock at noon. 25p per kWh. */
    private fun house(plugs: List<String>, areas: List<String>, clamps: List<String> = emptyList()): TestHeating {
        val t = TestHeating(clock = MutableClock(noon))
        t.discover(meters = listOf("Main Clamp") + plugs + clamps)
        t.api { client ->
            assertEquals(HttpStatusCode.OK, client.put("/api/meters/Main Clamp/house").status)
            client.put("/api/settings/energy") {
                contentType(ContentType.Application.Json)
                setBody("""{"electricityPencePerKwh":25.0}""")
            }
            plugs.forEach { client.put("/api/meters/$it/monitored") }
            areas.forEach { name ->
                client.post("/api/energy/areas") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"name":"$name"}""")
                }
            }
        }
        return t
    }

    private fun put(t: TestHeating, path: String) = t.api { client -> assertEquals(HttpStatusCode.OK, client.put(path).status, path) }

    /** [kwh] spread evenly over [samples] five-minute samples from midnight. */
    private fun house(t: TestHeating, kwh: Double, samples: Int = 12) = (1..samples).forEach { i ->
        t.app.energyHistory.add(MeterSample(at(5L * i), "Main Clamp", 1.0, kwh / samples))
    }

    private fun plug(t: TestHeating, meter: String, kwh: Double, room: String? = null, area: String? = null, samples: Int = 12) = (1..samples).forEach { i ->
        t.app.energyHistory.addDevice(MeterSample(at(5L * i), meter, 1.0, kwh / samples, room, area = area))
    }

    private fun clamp(t: TestHeating, meter: String, area: String, kwh: Double, samples: Int = 12) = (1..samples).forEach { i ->
        t.app.energyHistory.addArea(MeterSample(at(5L * i), meter, 1.0, kwh / samples, area))
    }

    private fun dashboard(t: TestHeating): JsonObject {
        var result: JsonObject? = null
        t.api { client ->
            val response = client.get("/api/dashboard?period=today")
            assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
            result = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        return result!!
    }

    private fun JsonObject.number(key: String) = this[key]!!.jsonPrimitive.double

    private fun JsonObject.text(key: String) = this[key]?.takeUnless { it is JsonNull }?.jsonPrimitive?.content

    private fun JsonObject.list(key: String) = this[key]!!.jsonArray.map { it.jsonObject }

    private fun JsonObject.area(id: String) = list("areas").single { it.text("area") == id }

    // BDD: An area with no clamp is the sum of its plugs
    @Test
    fun `an area with no clamp is the sum of its plugs`() {
        val t = house(plugs = listOf("Desk Plug", "Heater Plug"), areas = listOf("Garage/Office"))
        house(t, 8.0)
        plug(t, "Desk Plug", 0.2, area = "garage-office")
        plug(t, "Heater Plug", 0.3, area = "garage-office")

        val area = dashboard(t).area("garage-office")
        assertEquals("Garage/Office", area.text("name"))
        assertEquals(0.5, area.number("kwh"), 1e-9)
        assertEquals(12.5, area.number("cost"), 1e-9)
        assertNull(area.text("wholeMeter"))
        assertNull(area["rest"]?.takeUnless { it is JsonNull })
        assertEquals(listOf("Desk Plug" to 0.2, "Heater Plug" to 0.3), area.list("plugs").map { it.text("meter") to it.number("kwh") })
    }

    // BDD: An area's plugs are part of its clamp's figure
    @Test
    fun `an area's plugs are part of its clamp's figure`() {
        val t = house(plugs = listOf("Desk Plug"), areas = listOf("Garage/Office"), clamps = listOf("Garage Clamp"))
        put(t, "/api/energy/areas/garage-office/whole-meter/Garage Clamp")
        house(t, 8.0)
        clamp(t, "Garage Clamp", "garage-office", 1.0)
        plug(t, "Desk Plug", 0.2, area = "garage-office")

        val area = dashboard(t).area("garage-office")
        assertEquals(1.0, area.number("kwh"), 1e-9, "not 1.2")
        assertEquals("Garage Clamp", area.text("wholeMeter"))
        assertEquals(0.2, area.list("plugs").single().number("kwh"), 1e-9)
        assertEquals(0.8, area.number("rest"), 1e-9)
        assertEquals(20.0, area.number("restCost"), 1e-9)

        // Plugs adding up to more than the clamp leave a rest of 0, never negative.
        plug(t, "Desk Plug", 1.0, area = "garage-office")
        assertEquals(0.0, dashboard(t).area("garage-office").number("rest"), 1e-9)
    }

    // BDD: Everything else counts an area once
    @Test
    fun `everything else counts an area once`() {
        val t = house(
            plugs = listOf("Desk Plug", "Fridge Plug"),
            areas = listOf("Garage/Office", "Lights"),
            clamps = listOf("Garage Clamp", "Lights Clamp"),
        )
        val kitchen = t.app.rooms.create("Kitchen").id
        put(t, "/api/energy/areas/garage-office/whole-meter/Garage Clamp")
        put(t, "/api/energy/areas/lights/whole-meter/Lights Clamp")
        house(t, 8.0)
        clamp(t, "Garage Clamp", "garage-office", 1.0)
        plug(t, "Desk Plug", 0.2, area = "garage-office")
        clamp(t, "Lights Clamp", "lights", 0.5)
        plug(t, "Fridge Plug", 1.2, room = kitchen)

        val dashboard = dashboard(t)
        // The house minus each whole-area meter, and minus only those plugs not behind one.
        assertEquals(5.3, dashboard["everythingElse"]!!.jsonObject.number("kwh"), 1e-9)
        assertEquals(8.0, dashboard.number("electricityKwh"), 1e-9)
        assertEquals(dashboard.number("gasCost") + dashboard.number("electricityCost") + dashboard.number("standingCost"), dashboard.number("totalCost"), 0.11)
        assertEquals(listOf("Desk Plug", "Fridge Plug"), dashboard.list("devices").map { it.text("meter") })
        assertEquals(listOf(kitchen), dashboard.list("rooms").map { it.text("room") })
        assertEquals(listOf("garage-office", "lights"), dashboard.list("areas").map { it.text("area") })

        // Its usage over time counts them the same way.
        t.api { client ->
            val usage = heatingJson.parseToJsonElement(client.get("/api/energy/else/usage?window=24h").bodyAsText()).jsonObject
            val kwh = usage.list("room").sumOf { it.number("kwh") }
            assertEquals(5.3, kwh, 1e-3) // each point is rounded to 4 places
        }

        // And its live card: 3000 W house, less 1000 W garage clamp (its 200 W desk plug inside it), 500 W lights, 300 W fridge.
        t.meterState("Main Clamp", """{"energy":1.0,"power":3000}""")
        t.meterState("Garage Clamp", """{"energy":1.0,"power":1000}""")
        t.meterState("Desk Plug", """{"energy":1.0,"power":200}""")
        t.meterState("Lights Clamp", """{"energy":1.0,"power":500}""")
        t.meterState("Fridge Plug", """{"energy":1.0,"power":300}""")
        t.api { client -> client.put("/api/energy/areas/garage-office/meters/Desk Plug"); client.put("/api/rooms/$kitchen/meters/Fridge Plug") }
        val live = t.app.livePower.now()
        assertEquals(1200.0, live.elseWatts!!, 1e-9)
        assertEquals(listOf("garage-office" to 1000.0, "lights" to 500.0), live.areas.map { it.area to it.watts })
        assertEquals(emptyList(), live.devices, "an area's plug is not a device in no room")
    }

    // BDD: Two areas that share a circuit say so
    @Test
    fun `two areas that share a circuit say so`() {
        val t = house(plugs = emptyList(), areas = listOf("Garage/Office", "Lights"), clamps = listOf("Garage Clamp", "Lights Clamp"))
        put(t, "/api/energy/areas/garage-office/whole-meter/Garage Clamp")
        put(t, "/api/energy/areas/lights/whole-meter/Lights Clamp")
        house(t, 8.0)
        clamp(t, "Garage Clamp", "garage-office", 1.0)
        clamp(t, "Lights Clamp", "lights", 0.5)
        val before = dashboard(t)

        put(t, "/api/energy/areas/lights/overlaps/garage-office")
        val after = dashboard(t)
        // The figures do not change: the shared use cannot be measured, so it is not guessed.
        assertEquals(before.list("areas").map { it.number("kwh") }, after.list("areas").map { it.number("kwh") })
        assertEquals(before["everythingElse"]!!.jsonObject.number("kwh"), after["everythingElse"]!!.jsonObject.number("kwh"))
        // Both areas say so, and everything else says it reads low by what both measure.
        assertEquals(listOf("Lights"), after.area("garage-office").names("overlaps"))
        assertEquals(listOf("Garage/Office"), after.area("lights").names("overlaps"))
        assertEquals(listOf(listOf("Garage/Office", "Lights")), after["everythingElse"]!!.jsonObject.pairs())
        t.api { client ->
            val areas = heatingJson.parseToJsonElement(client.get("/api/energy/areas").bodyAsText()).jsonArray.map { it.jsonObject }
            assertEquals(listOf(listOf("lights"), listOf("garage-office")), areas.map { it.names("overlaps") })
            val usage = heatingJson.parseToJsonElement(client.get("/api/energy/areas/lights/usage").bodyAsText()).jsonObject
            assertEquals(listOf("Garage/Office"), usage.names("overlaps"))
            val rest = heatingJson.parseToJsonElement(client.get("/api/energy/else/usage").bodyAsText()).jsonObject
            assertEquals(listOf(listOf("Garage/Office", "Lights")), rest.pairs())

            assertEquals(HttpStatusCode.NotFound, client.put("/api/energy/areas/lights/overlaps/shed").status)
            assertEquals(HttpStatusCode.BadRequest, client.put("/api/energy/areas/lights/overlaps/lights").status)
            // DELETE clears it, from either side.
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/energy/areas/garage-office/overlaps/lights").status)
            assertEquals(HttpStatusCode.NotFound, client.delete("/api/energy/areas/garage-office/overlaps/lights").status)
        }
        val cleared = dashboard(t)
        assertEquals(emptyList(), cleared.area("lights").names("overlaps"))
        assertEquals(emptyList(), cleared["everythingElse"]!!.jsonObject.pairs())
    }

    private fun JsonObject.names(key: String) = this[key]!!.jsonArray.map { it.jsonPrimitive.content }

    private fun JsonObject.pairs() = this["sharedCircuits"]!!.jsonArray.map { pair -> pair.jsonArray.map { it.jsonPrimitive.content } }
}
