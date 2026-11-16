package heating.energy

import heating.heatingJson
import heating.testing.TestHeating
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.isSuccess
import io.ktor.http.contentType
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Instant
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

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

        // Today's kWh has a test of its own.
        val items = t.app.livePower.now().items.associateBy { it.meter }.mapValues { it.value.copy(todayKwh = null) }
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

    // BDD: A meter with a job shows even when its device was ignored
    @Test
    fun `a meter with a job shows even when its device was ignored`() {
        val t = TestHeating()
        t.discover(meters = listOf("Main Clamp", "Lights Clamp", "Hallway Plug"))
        t.api { client ->
            client.post("/api/energy/areas") { contentType(ContentType.Application.Json); setBody("""{"name":"Lights"}""") }
            assertEquals(HttpStatusCode.OK, client.put("/api/meters/Main Clamp/house").status)
            assertEquals(HttpStatusCode.OK, client.put("/api/meters/Lights Clamp/job") { contentType(ContentType.Application.Json); setBody("""{"job":"area","area":"lights"}""") }.status)
            for (d in listOf("Lights Clamp", "Hallway Plug")) assertTrue(client.put("/api/devices/$d/ignored").status.isSuccess(), d)
        }
        for ((m, w) in listOf("Main Clamp" to 500, "Lights Clamp" to 110, "Hallway Plug" to 20)) t.meterState(m, """{"energy":1.0,"power":$w}""")

        val items = t.app.livePower.now().items.associateBy { it.meter }.mapValues { it.value.copy(todayKwh = null) }
        assertEquals(ItemPower("Lights Clamp", "Lights Clamp", 110.0, room = null, area = "lights", job = "area"), items["Lights Clamp"])
        assertNull(items["Hallway Plug"], "ignoring still hides a meter with no job")
    }

    // BDD: Live power carries each meter's use today
    @Test
    fun `live power carries each meter's use today`() {
        val t = TestHeating()
        t.discover(meters = listOf("Main Clamp", "Fridge Plug", "Lights Clamp", "Garage Clamp"))
        t.api { client ->
            client.post("/api/energy/areas") { contentType(ContentType.Application.Json); setBody("""{"name":"Lights"}""") }
            assertEquals(HttpStatusCode.OK, client.put("/api/meters/Main Clamp/house").status)
            assertEquals(HttpStatusCode.OK, client.put("/api/meters/Lights Clamp/job") { contentType(ContentType.Application.Json); setBody("""{"job":"area","area":"lights"}""") }.status)
            assertEquals(HttpStatusCode.OK, client.put("/api/meters/Fridge Plug/job") { contentType(ContentType.Application.Json); setBody("""{"job":"monitored"}""") }.status)
        }
        t.app.energySettings.update { it.copy(electricityPencePerKwh = 24.5) }
        // The household's midnight: 2026-10-11T23:00Z (London is on summer time); now is 09:00Z.
        val h = t.app.energyHistory
        h.addDevice(MeterSample(Instant.parse("2026-10-11T12:00:00Z"), "Fridge Plug", null, 0.3))
        h.addDevice(MeterSample(Instant.parse("2026-10-11T23:00:00Z"), "Fridge Plug", null, 0.05))   // covers yesterday's last 5 min
        h.addDevice(MeterSample(Instant.parse("2026-10-12T03:00:00Z"), "Fridge Plug", null, 0.15))
        h.addDevice(MeterSample(Instant.parse("2026-10-12T09:00:00Z"), "Fridge Plug", null, 0.05))
        h.addArea(MeterSample(Instant.parse("2026-10-12T09:00:00Z"), "Lights Clamp", null, 0.1, room = "lights"))
        h.add(MeterSample(Instant.parse("2026-10-12T05:00:00Z"), "Main Clamp", null, 3.0))
        h.add(MeterSample(Instant.parse("2026-10-12T09:00:00Z"), "Main Clamp", null, 2.0))
        for ((m, w) in listOf("Main Clamp" to 1000, "Fridge Plug" to 100, "Lights Clamp" to 0, "Garage Clamp" to 50)) t.meterState(m, """{"energy":1.0,"power":$w}""")

        var view = t.app.livePower.now()
        var items = view.items.associateBy { it.meter }
        assertEquals(0.2, items["Fridge Plug"]!!.todayKwh!!, 1e-9)
        assertEquals(0.1, items["Lights Clamp"]!!.todayKwh!!, 1e-9)
        assertNull(items["Garage Clamp"]!!.todayKwh, "nothing records a meter with no job")
        assertEquals(5.0, view.houseTodayKwh!!, 1e-9)
        assertEquals(24.5, view.pencePerKwh)
        assertEquals("GBP", view.currency)

        // Since the last sample, its live watts are added on: 36 s at 100 W is 0.001 kWh.
        t.clock.advance(Duration.ofSeconds(36))
        view = t.app.livePower.now()
        items = view.items.associateBy { it.meter }
        assertEquals(0.201, items["Fridge Plug"]!!.todayKwh!!, 1e-9)
        assertEquals(5.01, view.houseTodayKwh!!, 1e-9)
    }
}
