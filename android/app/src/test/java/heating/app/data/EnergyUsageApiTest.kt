package heating.app.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class EnergyUsageApiTest {
    private val json = """{"currency":"GBP","from":"2026-10-11T12:00:00+01:00","to":"2026-10-12T12:00:00+01:00","earliest":"2026-10-01T00:05:00+01:00",
        "room":[{"at":"2026-10-12T11:55:00+01:00","watts":100.0,"kwh":0.0083,"cost":0.21,"incomplete":false},
                {"at":"2026-10-12T12:00:00+01:00","watts":null,"kwh":null,"cost":null,"incomplete":true}],
        "plugs":[{"meter":"Fridge Plug","points":[{"at":"2026-10-12T11:55:00+01:00","watts":80.0,"kwh":0.0067,"cost":0.17,"incomplete":false}]}]}"""

    private fun api(requests: MutableList<HttpRequestData>) = KtorHeatingApi(
        ServerConfig("https://heating.example", "secret"),
        MockEngine { request ->
            requests += request
            respond(json, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    // BDD: A room's use is read over time
    @Test
    fun `a room's use is read for a window`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val usage = api(requests).roomUsage("kitchen", "24h")
        assertEquals("https://heating.example/api/rooms/kitchen/plugs/usage?window=24h", requests.single().url.toString())
        assertEquals(100.0, usage.room.first().watts)
        assertEquals(null, usage.room.last().kwh)
        assertEquals(true, usage.room.last().incomplete)
        assertEquals("Fridge Plug", usage.plugs.single().meter)
    }

    // BDD: A room's use is read further back
    @Test
    fun `earlier use is asked for up to a time`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val usage = api(requests).roomUsage("kitchen", "7d", "2026-10-05T00:00:00Z")
        assertEquals("7d", requests.single().url.parameters["window"])
        assertEquals("2026-10-05T00:00:00Z", requests.single().url.parameters["to"])
        assertEquals("2026-10-01T00:05:00+01:00", usage.earliest)
    }

    // BDD: Everything else has its own dashboard
    @Test
    fun `everything else's use is read`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        api(requests).everythingElseUsage("30d")
        assertEquals("https://heating.example/api/energy/else/usage?window=30d", requests.single().url.toString())
    }
}
