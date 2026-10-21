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

class RoomPlugsApiTest {
    // BDD: A plug is put in a room from the room's settings
    @Test
    fun `a room's plugs are read from the service`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                """{"room":"kitchen","currency":"GBP","kwh":4.6,"cost":124.2,"missingMinutes":40,"incomplete":true,
                    "plugs":[{"meter":"Fridge Plug","power":85.0,"kwh":0.4,"cost":10.8,"missingMinutes":40,"incomplete":true,
                              "offline":[{"from":"2026-10-12T14:20:00+01:00","to":"2026-10-12T15:00:00+01:00"}]},
                             {"meter":"Kettle Plug","power":null,"kwh":4.2,"cost":113.4,"missingMinutes":0,"incomplete":true,"offline":[],
                              "offlineSince":"2026-10-12T14:20:00+01:00"}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val plugs = KtorHeatingApi(ServerConfig("https://heating.example", "secret"), engine).roomPlugs("kitchen")

        assertEquals("https://heating.example/api/rooms/kitchen/plugs", requests.single().url.toString())
        assertEquals(124.2, plugs.cost)
        assertEquals(listOf("Fridge Plug", "Kettle Plug"), plugs.plugs.map { it.meter })
        assertEquals("2026-10-12T14:20:00+01:00", plugs.plugs.first().offline.single().from)
        assertEquals(null, plugs.plugs.last().power)
    }

    // BDD: A plug that is offline now says so
    @Test
    fun `a plug offline now says since when`() = runTest {
        val engine = MockEngine {
            respond(
                """{"room":"bedroom1","currency":"GBP","kwh":0.5,"cost":13.5,"missingMinutes":0,"incomplete":true,
                    "plugs":[{"meter":"TV Plug","power":null,"kwh":0.5,"cost":13.5,"missingMinutes":0,"incomplete":true,"offline":[],
                              "offlineSince":"2026-10-12T14:20:00+01:00"}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val plugs = KtorHeatingApi(ServerConfig("https://heating.example", "secret"), engine).roomPlugs("bedroom1")
        assertEquals("2026-10-12T14:20:00+01:00", plugs.plugs.single().offlineSince)
    }

    // BDD: A plug whose energy total stops going up is flagged
    @Test
    fun `a plug whose total is stuck says since when`() = runTest {
        val engine = MockEngine {
            respond(
                """{"room":"bedroom1","currency":"GBP","kwh":0.5,"cost":13.5,"missingMinutes":0,"incomplete":true,
                    "plugs":[{"meter":"TV Plug","power":60,"kwh":0.5,"cost":13.5,"missingMinutes":0,"incomplete":true,"offline":[],
                              "offlineSince":null,"totalStuckSince":"2026-10-12T14:00:00+01:00"}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val plugs = KtorHeatingApi(ServerConfig("https://heating.example", "secret"), engine).roomPlugs("bedroom1")
        assertEquals("2026-10-12T14:00:00+01:00", plugs.plugs.single().totalStuckSince)
    }

    // BDD: A room's electricity is charted over time
    @Test
    fun `a room's use over time is read for a range`() = runTest {
        val requests = mutableListOf<HttpRequestData>()
        val engine = MockEngine { request ->
            requests += request
            respond(
                """{"room":"kitchen","from":"2026-10-11T16:00:00+01:00","to":"2026-10-12T16:00:00+01:00",
                    "points":[{"at":"2026-10-12T13:00:00+01:00","kwh":0.1,"incomplete":false},{"at":"2026-10-12T14:00:00+01:00","kwh":null,"incomplete":true}]}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"),
            )
        }
        val history = KtorHeatingApi(ServerConfig("https://heating.example", "secret"), engine).roomEnergyHistory("kitchen", "7d")

        assertEquals("https://heating.example/api/rooms/kitchen/plugs/history?range=7d", requests.single().url.toString())
        assertEquals(listOf(0.1, null), history.points.map { it.kwh })
        assertEquals(listOf(false, true), history.points.map { it.incomplete })
    }
}
