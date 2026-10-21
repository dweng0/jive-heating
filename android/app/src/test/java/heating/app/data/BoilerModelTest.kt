package heating.app.data

import kotlin.test.Test
import kotlin.test.assertEquals

class BoilerModelTest {

    private val base = """"heatDemand":false,"holiday":null,"serverTime":"2026-10-17T08:00:00+01:00","timezone":"Europe/London""""

    @Test
    fun `status from an older service has no boiler`() {
        assertEquals(null, apiJson.decodeFromString<Status>("{$base}").boiler)
    }

    @Test
    fun `status carries the boiler state and when it ends`() {
        val s = apiJson.decodeFromString<Status>("""{$base,"boiler":{"state":"cycling","until":"2026-10-17T08:03:12+01:00"}}""")
        assertEquals(Boiler("cycling", "2026-10-17T08:03:12+01:00"), s.boiler)
        val idle = apiJson.decodeFromString<Status>("""{$base,"boiler":{"state":"idle","until":null}}""")
        assertEquals(Boiler("idle", null), idle.boiler)
    }

    @Test
    fun `status updated live event carries the boiler`() {
        val e = apiJson.decodeFromString<LiveEvent>("""{"type":"status.updated","status":{$base,"boiler":{"state":"resting","until":"2026-10-17T08:05:00+01:00"}}}""")
        assertEquals("resting", e.status?.boiler?.state)
    }
}
