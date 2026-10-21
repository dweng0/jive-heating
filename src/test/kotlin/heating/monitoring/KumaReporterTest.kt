package heating.monitoring

import heating.Config
import heating.HeatingApp
import heating.mqtt.InMemoryMqttBus
import heating.store.SqliteStore
import heating.testing.MutableClock
import heating.testing.TEST_TOKEN
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.nio.file.Files
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KumaReporterTest {

    // BDD: Offline valves are reported to Uptime Kuma
    @Test
    fun `offline valves are reported to uptime kuma`() {
        val clock = MutableClock(Instant.parse("2026-10-12T09:00:00Z"))
        val bus = InMemoryMqttBus()
        val db = Files.createTempFile("heating-kuma", ".db").toString()
        val sent = mutableListOf<String>()
        val config = Config(apiToken = TEST_TOKEN, dbPath = db, kumaPushUrl = "http://kuma/api/push/abc")
        val app = HeatingApp(config, bus, SqliteStore(db), clock, httpGet = { sent += it })
        app.start()
        val devices = buildJsonArray {
            listOf("A", "B").forEach { name ->
                add(buildJsonObject {
                    put("friendly_name", name)
                    put("model_id", "TRVZB")
                })
            }
        }
        bus.receive("zigbee2mqtt/bridge/devices", devices.toString(), retained = true)

        clock.advance(Duration.ofMinutes(1))
        app.tick()
        assertTrue(sent.last().startsWith("http://kuma/api/push/abc?status=up"), sent.last())

        bus.receive("zigbee2mqtt/B/availability", """{"state":"offline"}""")
        clock.advance(Duration.ofMinutes(1))
        app.tick()
        assertEquals("http://kuma/api/push/abc?status=down&msg=offline%3A+B", sent.last())
    }

    // BDD: Offline room sensors are reported to Uptime Kuma
    @Test
    fun `offline room sensors are reported to uptime kuma`() {
        val t = heating.testing.TestHeating(kumaPushUrl = "http://kuma/api/push/abc")
        t.discover("A", sensors = listOf("Landing Smoke", "Spare Smoke"))
        val bedroom3 = t.app.rooms.create("Bedroom 3")
        t.app.rooms.assignValve(bedroom3.id, "A")
        t.sensorState("Landing Smoke", """{"temperature":20.9}""")
        t.sensorState("Spare Smoke", """{"temperature":18.0}""")
        t.app.roomSensors.assign(bedroom3.id, "Landing Smoke")

        t.availability("Spare Smoke", online = false)
        t.clock.advance(Duration.ofMinutes(1))
        t.app.tick()
        assertTrue(t.httpSent.last().startsWith("http://kuma/api/push/abc?status=up"), t.httpSent.last())

        t.availability("Landing Smoke", online = false)
        t.clock.advance(Duration.ofMinutes(1))
        t.app.tick()
        assertEquals("http://kuma/api/push/abc?status=down&msg=offline%3A+Landing+Smoke", t.httpSent.last())
    }
}
