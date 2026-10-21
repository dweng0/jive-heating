package heating.energy

import heating.heatingJson
import heating.testing.MutableClock
import heating.testing.TestHeating
import io.ktor.client.request.get
import io.ktor.client.request.request
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class MonitoredDevicesTest {

    private fun meters(t: TestHeating, query: String = ""): List<JsonObject> {
        var result = emptyList<JsonObject>()
        t.api { client ->
            result = heatingJson.parseToJsonElement(client.get("/api/meters$query").bodyAsText()).jsonArray.map { it.jsonObject }
        }
        return result
    }

    private fun names(meters: List<JsonObject>) = meters.map { it["name"]!!.jsonPrimitive.content }

    private fun monitored(t: TestHeating) = meters(t).filter { it["monitored"]!!.jsonPrimitive.boolean }.let(::names)

    private fun call(t: TestHeating, method: HttpMethod, path: String): HttpResponse {
        lateinit var response: HttpResponse
        t.api { client -> response = client.request(path.replace(" ", "%20")) { this.method = method } }
        return response
    }

    private fun error(t: TestHeating, method: HttpMethod, path: String): Pair<HttpStatusCode, String> {
        var result = HttpStatusCode.OK to ""
        t.api { client ->
            val response = client.request(path.replace(" ", "%20")) { this.method = method }
            result = response.status to heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject["error"]!!.jsonPrimitive.content
        }
        return result
    }

    // BDD: An admin monitors a plug
    @Test
    fun `an admin monitors a plug`() {
        val t = TestHeating()
        t.discover(
            meters = listOf("Main Clamp", "Fridge Plug"),
            exposing = mapOf("Lamp Plug" to listOf("state"), "Kettle Plug" to listOf("state", "power")),
        )
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/meters/Main Clamp/house").status)
        assertEquals(listOf("Fridge Plug"), names(meters(t, "?unassigned=true")))

        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/meters/Fridge Plug/monitored").status)
        assertEquals(emptyList(), names(meters(t, "?unassigned=true")))
        assertEquals(listOf("Fridge Plug"), monitored(t))
        // Monitoring twice is fine.
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/meters/Fridge Plug/monitored").status)

        assertEquals(HttpStatusCode.NoContent, call(t, HttpMethod.Delete, "/api/meters/Fridge Plug/monitored").status)
        assertEquals(emptyList(), monitored(t))
        assertEquals(listOf("Fridge Plug"), names(meters(t, "?unassigned=true")))
        assertEquals(HttpStatusCode.NotFound, call(t, HttpMethod.Delete, "/api/meters/Fridge Plug/monitored").status)

        assertEquals(HttpStatusCode.Conflict, call(t, HttpMethod.Put, "/api/meters/Main Clamp/monitored").status)
        assertEquals(emptyList(), monitored(t))

        val noEnergy = HttpStatusCode.UnprocessableEntity to "This device does not report energy"
        assertEquals(noEnergy, error(t, HttpMethod.Put, "/api/meters/Lamp Plug/monitored"))
        assertEquals(noEnergy, error(t, HttpMethod.Put, "/api/meters/Kettle Plug/monitored"))
        assertEquals(HttpStatusCode.NotFound, call(t, HttpMethod.Put, "/api/meters/Hall Plug/monitored").status)

        t.api {
            val sam = memberSam()
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Put, "/api/meters/Fridge%20Plug/monitored").status)
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Delete, "/api/meters/Fridge%20Plug/monitored").status)
        }
        assertEquals(emptyList(), monitored(t))
    }

    private fun roomOf(t: TestHeating, meter: String) =
        meters(t).single { it["name"]!!.jsonPrimitive.content == meter }["room"]?.jsonPrimitive?.takeUnless { it is JsonNull }?.content

    // BDD: An admin puts a monitored plug in a room
    @Test
    fun `an admin puts a monitored plug in a room`() {
        val t = TestHeating()
        t.discover(meters = listOf("Fridge Plug", "Kettle Plug", "Lamp Plug"))
        listOf("Fridge Plug", "Kettle Plug").forEach { name ->
            assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/meters/$name/monitored").status)
        }
        val kitchen = t.app.rooms.create("Kitchen").id
        val utility = t.app.rooms.create("Utility").id

        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/rooms/$kitchen/meters/Fridge Plug").status)
        assertEquals(kitchen, roomOf(t, "Fridge Plug"))
        assertEquals(null, roomOf(t, "Kettle Plug"))

        assertEquals(HttpStatusCode.NoContent, call(t, HttpMethod.Delete, "/api/rooms/$kitchen/meters/Fridge Plug").status)
        assertEquals(null, roomOf(t, "Fridge Plug"))
        assertEquals(listOf("Fridge Plug", "Kettle Plug"), monitored(t))
        assertEquals(HttpStatusCode.NotFound, call(t, HttpMethod.Delete, "/api/rooms/$kitchen/meters/Fridge Plug").status)

        // A room holds any number of plugs.
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/rooms/$kitchen/meters/Fridge Plug").status)
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/rooms/$kitchen/meters/Kettle Plug").status)
        assertEquals(kitchen, roomOf(t, "Fridge Plug"))
        assertEquals(kitchen, roomOf(t, "Kettle Plug"))

        // A plug is in at most one room: another room moves it.
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/rooms/$utility/meters/Kettle Plug").status)
        assertEquals(utility, roomOf(t, "Kettle Plug"))
        assertEquals(HttpStatusCode.NotFound, call(t, HttpMethod.Delete, "/api/rooms/$kitchen/meters/Kettle Plug").status)

        assertEquals(HttpStatusCode.Conflict, call(t, HttpMethod.Put, "/api/rooms/$kitchen/meters/Lamp Plug").status)
        assertEquals(null, roomOf(t, "Lamp Plug"))
        assertEquals(HttpStatusCode.NotFound, call(t, HttpMethod.Put, "/api/rooms/attic/meters/Fridge Plug").status)

        t.api {
            val sam = memberSam()
            // Sam can see the kitchen, so the answer is 403, not the 404 of a room hidden from them.
            val users = t.app.users
            users.setRooms(users.list().single { it.username == "sam" }.id, listOf(kitchen), mapOf(kitchen to "Kitchen"))
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Put, "/api/rooms/$kitchen/meters/Lamp%20Plug").status)
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Delete, "/api/rooms/$kitchen/meters/Fridge%20Plug").status)
        }
        assertEquals(kitchen, roomOf(t, "Fridge Plug"))

        // Kept across restarts, and a plug no longer monitored is in no room.
        t.restart()
        assertEquals(kitchen, t.app.monitoredMeters.roomOf("Fridge Plug"))
        assertEquals(HttpStatusCode.NoContent, call(t, HttpMethod.Delete, "/api/meters/Fridge Plug/monitored").status)
        assertEquals(null, roomOf(t, "Fridge Plug"))
    }

    // BDD: The energy dashboard lists monitored devices without double counting
    @Test
    fun `the energy dashboard lists monitored devices without double counting`() {
        // Midnight in London, then two hours of today recorded.
        val start = Instant.parse("2026-10-11T23:00:00Z")
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("Main Clamp", "Fridge Plug"))
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/meters/Main Clamp/house").status)
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/meters/Fridge Plug/monitored").status)
        t.clock.advance(Duration.ofHours(2))

        val history = t.app.energyHistory
        // Yesterday's use is not today's.
        history.addDevice(MeterSample(start.minus(Duration.ofMinutes(5)), "Fridge Plug", 1000.0, 5.0))
        (1..24).forEach { i ->
            val at = start.plus(Duration.ofMinutes(5L * i))
            history.add(MeterSample(at, "Main Clamp", 1500.0 + i / 3.0, 1.0 / 3.0))
            // Stale for one five minutes: unknown, not counted.
            val kwh = if (i == 12) null else 0.05 + (if (i == 13) 0.05 else 0.0)
            history.addDevice(MeterSample(at, "Fridge Plug", 1000.0 + i * 0.05, kwh))
        }

        t.api { client ->
            val today = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject
            val fridge = today["devices"]!!.jsonArray.single().jsonObject
            assertEquals("Fridge Plug", fridge["meter"]!!.jsonPrimitive.content)
            assertEquals(1.2, fridge["kwh"]!!.jsonPrimitive.double, 1e-9)
            // 1.2 kWh at the default 27p per kWh: 32.4p, which the apps show as £0.32.
            assertEquals(32.4, fridge["cost"]!!.jsonPrimitive.double, 1e-9)
            assertEquals("GBP", today["currency"]!!.jsonPrimitive.content)

            assertEquals(8.0, today["electricityKwh"]!!.jsonPrimitive.double, 1e-9)
            val parts = listOf("gasCost", "electricityCost", "standingCost").sumOf { today[it]!!.jsonPrimitive.double }
            assertEquals(parts, today["totalCost"]!!.jsonPrimitive.double, 0.11)
        }
    }

    // BDD: A room's energy is the sum of its plugs
    @Test
    fun `a room's energy is the sum of its plugs`() {
        val start = Instant.parse("2026-10-11T23:00:00Z")
        val t = TestHeating(clock = MutableClock(start))
        val plugs = listOf("Fridge Plug", "Kettle Plug", "Lamp Plug", "Charger Plug")
        t.discover(meters = listOf("Main Clamp") + plugs)
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/meters/Main Clamp/house").status)
        plugs.forEach { assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/meters/$it/monitored").status) }
        val kitchen = t.app.rooms.create("Kitchen").id
        val lounge = t.app.rooms.create("Lounge").id
        t.app.rooms.create("Hall")
        listOf("Fridge Plug" to kitchen, "Kettle Plug" to kitchen, "Lamp Plug" to lounge).forEach { (plug, room) ->
            assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/rooms/$room/meters/$plug").status)
        }
        t.clock.advance(Duration.ofHours(1))

        val history = t.app.energyHistory
        val use = mapOf("Fridge Plug" to 0.1, "Kettle Plug" to 0.025, "Lamp Plug" to 0.1 / 12, "Charger Plug" to 0.5)
        (1..12).forEach { i ->
            val at = start.plus(Duration.ofMinutes(5L * i))
            history.add(MeterSample(at, "Main Clamp", 1500.0 + i, 1.0))
            use.forEach { (plug, kwh) -> history.addDevice(MeterSample(at, plug, 100.0 + i * kwh, kwh, t.app.monitoredMeters.roomOf(plug))) }
        }

        t.api { client ->
            val today = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject
            val rooms = today["rooms"]!!.jsonArray.map { it.jsonObject }
            // Only rooms with plugs, by name.
            assertEquals(listOf("Kitchen", "Lounge"), rooms.map { it["name"]!!.jsonPrimitive.content })
            val (k, l) = rooms
            assertEquals(kitchen, k["room"]!!.jsonPrimitive.content)
            assertEquals(1.5, k["kwh"]!!.jsonPrimitive.double, 1e-9)
            // 1.5 kWh at the default 27p per kWh: 40.5p, shown as £0.41 (the spec's "40p" rounds down).
            assertEquals(40.5, k["cost"]!!.jsonPrimitive.double, 1e-9)
            assertEquals(false, k["incomplete"]!!.jsonPrimitive.boolean)
            assertEquals(lounge, l["room"]!!.jsonPrimitive.content)
            assertEquals(0.1, l["kwh"]!!.jsonPrimitive.double, 1e-9)
            assertEquals(2.7, l["cost"]!!.jsonPrimitive.double, 1e-9)

            // Devices still lists each plug, the charger in no room under devices only.
            assertEquals(plugs.sorted(), today["devices"]!!.jsonArray.map { it.jsonObject["meter"]!!.jsonPrimitive.content })
            // Rooms are "of which": the house total is the main meter's alone.
            assertEquals(12.0, today["electricityKwh"]!!.jsonPrimitive.double, 1e-9)
            val parts = listOf("gasCost", "electricityCost", "standingCost").sumOf { today[it]!!.jsonPrimitive.double }
            assertEquals(parts, today["totalCost"]!!.jsonPrimitive.double, 0.11)
        }
    }

    // BDD: Moving a plug does not move its past use
    @Test
    fun `moving a plug does not move its past use`() {
        // Midnight on 12 October, UK time.
        val start = Instant.parse("2026-10-11T23:00:00Z")
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("Main Clamp", "Kettle Plug"))
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/meters/Kettle Plug/monitored").status)
        val kitchen = t.app.rooms.create("Kitchen").id
        val lounge = t.app.rooms.create("Lounge").id
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/rooms/$kitchen/meters/Kettle Plug").status)
        t.app.meterRecorder.start()

        fun reading(kwh: Double) {
            t.meterState("Kettle Plug", """{"energy":$kwh,"power":2000}""")
            t.clock.advance(Duration.ofMinutes(5))
            t.app.tick()
        }
        reading(100.0)
        reading(100.5)

        // The next day it is moved to the lounge.
        t.clock.advance(Duration.ofHours(24))
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/rooms/$lounge/meters/Kettle Plug").status)
        reading(100.5)
        reading(100.7)

        fun rooms(period: String): Map<String, Double> {
            var result = emptyMap<String, Double>()
            t.api { client ->
                val dashboard = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=$period").bodyAsText()).jsonObject
                result = dashboard["rooms"]!!.jsonArray.associate {
                    it.jsonObject["name"]!!.jsonPrimitive.content to it.jsonObject["kwh"]!!.jsonPrimitive.double
                }
            }
            return result
        }
        assertEquals(mapOf("Kitchen" to 0.5), rooms("yesterday"))
        assertEquals(mapOf("Lounge" to 0.2), rooms("today"))

        val samples = t.app.energyHistory.deviceSamples("Kettle Plug", start, t.clock.instant())
        assertEquals(listOf(kitchen, kitchen, lounge, lounge), samples.map { it.room })
    }

    // BDD: Removing a room or a plug leaves no stray room use
    @Test
    fun `removing a room or a plug leaves no stray room use`() {
        val start = Instant.parse("2026-10-11T23:00:00Z")
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("Fridge Plug"))
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/meters/Fridge Plug/monitored").status)
        t.app.meterRecorder.start()

        fun reading(kwh: Double) {
            t.meterState("Fridge Plug", """{"energy":$kwh,"power":100}""")
            t.clock.advance(Duration.ofMinutes(5))
            t.app.tick()
        }
        fun plugsIn(room: String): List<String> {
            var result = emptyList<String>()
            t.api { client ->
                val report = heatingJson.parseToJsonElement(client.get("/api/rooms/$room/plugs").bodyAsText()).jsonObject
                result = report["plugs"]!!.jsonArray.map { it.jsonObject["meter"]!!.jsonPrimitive.content }
            }
            return result
        }
        fun fridgeKwh(): Double {
            var result = 0.0
            t.api { client ->
                val today = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject
                result = today["devices"]!!.jsonArray.single().jsonObject["kwh"]!!.jsonPrimitive.double
            }
            return result
        }

        // Kitchen is deleted.
        val kitchen = t.app.rooms.create("Kitchen").id
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/rooms/$kitchen/meters/Fridge Plug").status)
        reading(100.0)
        reading(100.1)
        assertEquals(HttpStatusCode.NoContent, call(t, HttpMethod.Delete, "/api/rooms/$kitchen").status)
        assertEquals(null, roomOf(t, "Fridge Plug"))
        assertEquals(listOf("Fridge Plug"), monitored(t))
        assertEquals(0.1, fridgeKwh(), 1e-9)
        assertEquals(2, t.app.energyHistory.deviceSamples("Fridge Plug", start, t.clock.instant()).size)
        t.restart()
        assertEquals(null, t.app.monitoredMeters.roomOf("Fridge Plug"))

        // Fridge Plug leaves Zigbee2MQTT.
        t.app.meterRecorder.start()
        val newKitchen = t.app.rooms.create("Kitchen").id
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/rooms/$newKitchen/meters/Fridge Plug").status)
        reading(100.2)
        assertEquals(listOf("Fridge Plug"), plugsIn(newKitchen))
        t.discover(meters = emptyList())
        assertEquals(null, t.app.monitoredMeters.roomOf("Fridge Plug"))
        assertEquals(emptyList(), plugsIn(newKitchen))
        assertEquals(0.2, fridgeKwh(), 1e-9)
        assertEquals(3, t.app.energyHistory.deviceSamples("Fridge Plug", start, t.clock.instant()).size)

        // Paired again, it is still in no room.
        t.discover(meters = listOf("Fridge Plug"))
        assertEquals(null, roomOf(t, "Fridge Plug"))
        t.restart()
        assertEquals(null, t.app.monitoredMeters.roomOf("Fridge Plug"))
    }

    // BDD: The Dashboard shows each room's electricity
    @Test
    fun `each monitored device says which room it is in, so plugs in no room can be grouped`() {
        val t = TestHeating(clock = MutableClock(Instant.parse("2026-10-11T23:00:00Z")))
        t.discover(meters = listOf("Main Clamp", "Fridge Plug", "Charger Plug"))
        listOf("Fridge Plug", "Charger Plug").forEach { assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/meters/$it/monitored").status) }
        val kitchen = t.app.rooms.create("Kitchen").id
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/rooms/$kitchen/meters/Fridge Plug").status)

        t.api { client ->
            val today = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject
            val rooms = today["devices"]!!.jsonArray.associate { it.jsonObject["meter"]!!.jsonPrimitive.content to it.jsonObject["room"]?.jsonPrimitive?.contentOrNull }
            assertEquals(mapOf("Charger Plug" to null, "Fridge Plug" to kitchen), rooms)
        }
    }

    // BDD: The Dashboard shows each monitored device
    @Test
    fun `each monitored device says whether its readings are incomplete`() {
        val start = Instant.parse("2026-10-11T23:00:00Z")
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("Main Clamp", "Fridge Plug", "Garage Plug"))
        listOf("Fridge Plug", "Garage Plug").forEach { name ->
            assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/meters/$name/monitored").status)
        }
        t.clock.advance(Duration.ofHours(1))

        val history = t.app.energyHistory
        // Yesterday's stale sample is not today's.
        history.addDevice(MeterSample(start, "Garage Plug", 50.0, null))
        (1..12).forEach { i ->
            val at = start.plus(Duration.ofMinutes(5L * i))
            history.addDevice(MeterSample(at, "Fridge Plug", 1000.0 + i * 0.05, 0.05))
            // Stale for two five minutes: unknown, so the cost shown is only so far.
            history.addDevice(MeterSample(at, "Garage Plug", 50.0 + i * 0.1, if (i in 4..5) null else 0.1))
        }
        // Both are reporting now, so neither is offline now.
        t.meterState("Fridge Plug", """{"power":85,"energy":1000.6}""")
        t.meterState("Garage Plug", """{"power":10,"energy":51.2}""")

        t.api { client ->
            val today = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject
            val devices = today["devices"]!!.jsonArray.associateBy { it.jsonObject["meter"]!!.jsonPrimitive.content }
            val fridge = devices["Fridge Plug"]!!.jsonObject
            assertEquals(false, fridge["incomplete"]!!.jsonPrimitive.boolean)
            assertEquals(0, fridge["missingMinutes"]!!.jsonPrimitive.double.toInt())
            val garage = devices["Garage Plug"]!!.jsonObject
            assertEquals(true, garage["incomplete"]!!.jsonPrimitive.boolean)
            assertEquals(10, garage["missingMinutes"]!!.jsonPrimitive.double.toInt())
            assertEquals(1.0, garage["kwh"]!!.jsonPrimitive.double, 1e-9)
        }
    }

    // BDD: A room with an incomplete plug reading is marked incomplete
    @Test
    fun `a room with an incomplete plug reading is marked incomplete`() {
        val start = Instant.parse("2026-10-11T23:00:00Z")
        val t = TestHeating(clock = MutableClock(start))
        val plugs = listOf("Fridge Plug", "Kettle Plug", "Lamp Plug")
        t.discover(meters = listOf("Main Clamp") + plugs)
        plugs.forEach { assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/meters/$it/monitored").status) }
        val kitchen = t.app.rooms.create("Kitchen").id
        val lounge = t.app.rooms.create("Lounge").id
        listOf("Fridge Plug" to kitchen, "Kettle Plug" to kitchen, "Lamp Plug" to lounge).forEach { (plug, room) ->
            assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/rooms/$room/meters/$plug").status)
        }
        t.clock.advance(Duration.ofHours(1))

        val history = t.app.energyHistory
        (1..12).forEach { i ->
            val at = start.plus(Duration.ofMinutes(5L * i))
            // The fridge is stale for 40 minutes; the kettle for 10 of the same minutes.
            history.addDevice(MeterSample(at, "Fridge Plug", 100.0 + i * 0.1, if (i in 3..10) null else 0.1, kitchen))
            history.addDevice(MeterSample(at, "Kettle Plug", 200.0 + i * 0.2, if (i in 6..7) null else 0.2, kitchen))
            history.addDevice(MeterSample(at, "Lamp Plug", 300.0 + i * 0.01, 0.01, lounge))
        }

        t.api { client ->
            val today = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject
            val rooms = today["rooms"]!!.jsonArray.associateBy { it.jsonObject["name"]!!.jsonPrimitive.content }
            val k = rooms["Kitchen"]!!.jsonObject
            assertEquals(true, k["incomplete"]!!.jsonPrimitive.boolean)
            // Minutes the room had no reading, not plug-minutes: the kettle's 10 are inside the fridge's 40.
            assertEquals(40, k["missingMinutes"]!!.jsonPrimitive.double.toInt())
            // Only the use read so far, flagged incomplete rather than the stale time passing as 0 kWh.
            assertEquals(4 * 0.1 + 10 * 0.2, k["kwh"]!!.jsonPrimitive.double, 1e-9)
            val l = rooms["Lounge"]!!.jsonObject
            assertEquals(false, l["incomplete"]!!.jsonPrimitive.boolean)
            assertEquals(0, l["missingMinutes"]!!.jsonPrimitive.double.toInt())
        }
    }

    /**
     * Today's dashboard with the Main Clamp as main meter and two plugs, the fridge in the kitchen and the
     * charger in no room, after an hour of samples: [house] and [plug] give each five minutes' kWh (null = stale).
     */
    private fun everythingElse(house: (Int) -> Double?, plug: (String, Int) -> Double?): JsonObject {
        val start = Instant.parse("2026-10-11T23:00:00Z")
        val t = TestHeating(clock = MutableClock(start))
        t.discover(meters = listOf("Main Clamp", "Fridge Plug", "Charger Plug"))
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/meters/Main Clamp/house").status)
        listOf("Fridge Plug", "Charger Plug").forEach { assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/meters/$it/monitored").status) }
        val kitchen = t.app.rooms.create("Kitchen").id
        assertEquals(HttpStatusCode.OK, call(t, HttpMethod.Put, "/api/rooms/$kitchen/meters/Fridge Plug").status)
        t.clock.advance(Duration.ofHours(1))
        (1..12).forEach { i ->
            val at = start.plus(Duration.ofMinutes(5L * i))
            t.app.energyHistory.add(MeterSample(at, "Main Clamp", 1500.0 + i, house(i)))
            listOf("Fridge Plug", "Charger Plug").forEach { t.app.energyHistory.addDevice(MeterSample(at, it, 100.0 + i, plug(it, i))) }
        }
        lateinit var today: JsonObject
        t.api { client -> today = heatingJson.parseToJsonElement(client.get("/api/dashboard?period=today").bodyAsText()).jsonObject }
        return today["everythingElse"]!!.jsonObject
    }

    // BDD: The energy dashboard shows everything else
    @Test
    fun `the energy dashboard shows everything else`() {
        // 8.0 kWh on the main meter; 1.0 in the kitchen's fridge and 0.6 on the charger in no room.
        val use = mapOf("Fridge Plug" to 1.0 / 12, "Charger Plug" to 0.6 / 12)
        val rest = everythingElse({ 8.0 / 12 }) { plug, _ -> use.getValue(plug) }
        // Main meter minus all monitored plugs, not only those in rooms.
        assertEquals(6.4, rest["kwh"]!!.jsonPrimitive.double, 1e-9)
        // At the default 27p per kWh.
        assertEquals(172.8, rest["cost"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(false, rest["incomplete"]!!.jsonPrimitive.boolean)
        assertEquals(0, rest["missingMinutes"]!!.jsonPrimitive.double.toInt())

        // A stale plug: what it used then is unknown, so everything else is not guessed complete.
        val stalePlug = everythingElse({ 8.0 / 12 }) { plug, i -> if (plug == "Charger Plug" && i in 4..5) null else use.getValue(plug) }
        assertEquals(true, stalePlug["incomplete"]!!.jsonPrimitive.boolean)
        assertEquals(10, stalePlug["missingMinutes"]!!.jsonPrimitive.double.toInt())

        // A stale main meter.
        val staleHouse = everythingElse({ i -> if (i == 6) null else 8.0 / 12 }) { plug, _ -> use.getValue(plug) }
        assertEquals(true, staleHouse["incomplete"]!!.jsonPrimitive.boolean)
        assertEquals(5, staleHouse["missingMinutes"]!!.jsonPrimitive.double.toInt())

        // Plugs reading more than the main meter: 0, never negative.
        val over = everythingElse({ 1.0 / 12 }) { plug, _ -> use.getValue(plug) }
        assertEquals(0.0, over["kwh"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(0.0, over["cost"]!!.jsonPrimitive.double, 1e-9)
    }
}
