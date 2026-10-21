package heating.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SensorModelTest {

    private fun room(json: String) = apiJson.decodeFromString<Room>(json)

    private val base = """"id":"m","name":"Bedroom 3","control":"grouped","mode":"manual","current":20.9,"target":21.0,
        "heating":false,"valves":["Bedroom 3 TRV"],"zones":[],"notices":[],"smartTemperatureControl":true"""

    @Test
    fun `room from an older service has no sensor`() {
        val r = room("{$base}")
        assertEquals(null, r.sensor)
        assertFalse(r.sensorOffline)
    }

    @Test
    fun `room measured by its sensor`() {
        val r = room("""{$base,"sensor":"Landing Smoke","temperatureSource":"sensor"}""")
        assertEquals("Landing Smoke", r.sensor)
        assertFalse(r.sensorOffline)
    }

    @Test
    fun `room whose sensor is offline falls back to valves`() {
        assertTrue(room("""{$base,"sensor":"Landing Smoke","temperatureSource":"valves"}""").sensorOffline)
    }

    @Test
    fun `sensor updated live event decodes`() {
        val e = apiJson.decodeFromString<LiveEvent>(
            """{"type":"sensor.updated","sensor":{"name":"Landing Smoke","room":"Bedroom 3","online":false,"temperature":20.9,"battery":90,"lowBattery":false}}""",
        )
        assertEquals(false, e.sensor?.online)
    }
}
