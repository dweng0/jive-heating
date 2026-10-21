package heating.app.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class HeatingApiTest {

    private val json = headersOf(HttpHeaders.ContentType, "application/json")
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(handler: suspend MockRequestHandleScope.(HttpRequestData) -> HttpResponseData): HeatingApi {
        val engine = MockEngine { request ->
            requests += request
            handler(request)
        }
        return KtorHeatingApi(ServerConfig("https://heating.example", "secret"), engine)
    }

    private fun HttpRequestData.bodyText() = (body as OutgoingContent.ByteArrayContent).bytes().decodeToString()

    private val room = """
        {"id":"r1","name":"Lounge","control":"grouped","mode":"manual","current":19.5,"target":21.0,
         "heating":true,"valves":["Lounge TRV 1"],"notices":[],"smartTemperatureControl":true,
         "zones":[{"id":"r1","valves":["Lounge TRV 1"],"mode":"manual","manualTarget":21.0,"target":21.0,
                   "current":19.5,"heating":true,"callingForHeat":true}]}
    """.trimIndent()

    @Test
    fun `rooms are fetched with the bearer token`() = runTest {
        val api = api { respond("[$room]", HttpStatusCode.OK, json) }

        val rooms = api.rooms()

        assertEquals("Lounge", rooms.single().name)
        assertEquals(21.0, rooms.single().zones.single().target)
        assertEquals("https://heating.example/api/rooms", requests.single().url.toString())
        assertEquals("Bearer secret", requests.single().headers[HttpHeaders.Authorization])
    }

    // BDD: The Android boiler chip opens the activity tray
    @Test
    fun `activity is fetched newest first with paging`() = runTest {
        val api = api {
            respond(
                """{"events":[{"id":7,"at":"2026-10-18T12:00:00+01:00","kind":"setpoint","actor":"John","room":"Utility Room","value":21.0,"reason":null,"rooms":[],"count":null},
                    {"id":6,"at":"2026-10-18T11:59:00+01:00","kind":"boiler","actor":null,"room":null,"value":"heating","reason":"calling","rooms":["Lounge"],"count":null}]}""",
                HttpStatusCode.OK, json,
            )
        }

        val events = api.activity(limit = 20, before = 9)

        assertEquals(listOf(7L, 6L), events.map { it.id })
        assertEquals(21.0, events[0].value?.content?.toDouble())
        assertEquals("heating", events[1].value?.content)
        assertEquals("https://heating.example/api/activity?limit=20&before=9", requests.single().url.toString())
    }

    @Test
    fun `history is fetched for a range with rooms, house and boiler periods`() = runTest {
        val api = api {
            respond(
                """
                {"from":"2026-10-16T14:00:00+01:00","to":"2026-10-17T14:00:00+01:00",
                 "rooms":[{"id":"r1","name":"Lounge","points":[{"at":"2026-10-17T13:55:00+01:00","current":19.5,"target":21.0}]}],
                 "house":[{"at":"2026-10-17T13:55:00+01:00","current":19.5}],
                 "boiler":[{"state":"heating","from":"2026-10-17T13:00:00+01:00","to":null}]}
                """.trimIndent(),
                HttpStatusCode.OK, json,
            )
        }

        val history = api.history("7d")

        assertEquals("/api/history", requests.single().url.encodedPath)
        assertEquals("7d", requests.single().url.parameters["range"])
        assertEquals(19.5, history.rooms.single().points.single().current)
        assertEquals(null, history.house.single().target)
        assertEquals("heating", history.boiler.single().state)
        assertEquals(null, history.boiler.single().to)
    }

    @Test
    fun `set mode sends the mode and target to the zone`() = runTest {
        val api = api { respond(room, HttpStatusCode.OK, json) }

        api.setMode("r1", Mode.MANUAL, 21.5)

        val request = requests.single()
        assertEquals(HttpMethod.Put, request.method)
        assertEquals("/api/zones/r1/mode", request.url.encodedPath)
        assertEquals("""{"mode":"manual","target":21.5}""", request.bodyText())
    }

    @Test
    fun `boost posts minutes and target`() = runTest {
        val api = api { respond(room, HttpStatusCode.OK, json) }

        api.boost("r1", minutes = 60, target = 22.0)

        assertEquals(HttpMethod.Post, requests.single().method)
        assertEquals("/api/zones/r1/boost", requests.single().url.encodedPath)
        assertEquals("""{"minutes":60,"target":22.0}""", requests.single().bodyText())
    }

    @Test
    fun `zone ids with spaces are url encoded`() = runTest {
        val api = api { respond(room, HttpStatusCode.OK, json) }

        api.cancelBoost("r1:Lounge TRV 1")

        assertEquals("/api/zones/r1:Lounge%20TRV%201/boost", requests.single().url.encodedPath)
    }

    @Test
    fun `server errors surface the error message`() = runTest {
        val api = api { respond("""{"error":"cannot boost during a holiday"}""", HttpStatusCode.Conflict, json) }

        val error = assertFailsWith<HeatingApiException> { api.boost("r1", 30, 21.0) }

        assertEquals(409, error.status)
        assertEquals("cannot boost during a holiday", error.message)
    }

    @Test
    fun `saving a day puts its blocks`() = runTest {
        val week = (1..7).joinToString(",", "{", "}") { i ->
            val day = Day.entries[i - 1].name.lowercase()
            """"$day":{"blocks":[{"start":"00:00","target":16.0}]}"""
        }
        val api = api { respond(week, HttpStatusCode.OK, json) }

        api.saveDay("r1", Day.MONDAY, listOf(Block("00:00", 16.0), Block("07:00", 20.0)))

        assertEquals("/api/zones/r1/schedule/monday", requests.single().url.encodedPath)
        assertEquals(
            """{"blocks":[{"start":"00:00","target":16.0},{"start":"07:00","target":20.0}]}""",
            requests.single().bodyText(),
        )
    }

    @Test
    fun `whole house control posts the mode and returns every room`() = runTest {
        val api = api { respond("[$room]", HttpStatusCode.OK, json) }

        val rooms = api.controlHouse(GroupControlRequest(mode = Mode.MANUAL, target = 21.0))

        assertEquals("Lounge", rooms.single().name)
        assertEquals(HttpMethod.Post, requests.single().method)
        assertEquals("/api/house", requests.single().url.encodedPath)
        assertEquals("""{"mode":"manual","target":21.0}""", requests.single().bodyText())
    }

    @Test
    fun `whole house schedule, off and boost bodies leave out unused fields`() = runTest {
        val api = api { respond("[$room]", HttpStatusCode.OK, json) }

        api.controlHouse(GroupControlRequest(mode = Mode.SCHEDULE))
        api.controlHouse(GroupControlRequest(mode = Mode.OFF))
        api.controlHouse(GroupControlRequest(boost = 60, target = 22.0))

        assertEquals(
            listOf("""{"mode":"schedule"}""", """{"mode":"off"}""", """{"target":22.0,"boost":60}"""),
            requests.map { it.bodyText() },
        )
    }

    @Test
    fun `cancelling every boost deletes the house boost`() = runTest {
        val api = api { respond("[$room]", HttpStatusCode.OK, json) }

        api.cancelHouseBoosts()

        assertEquals(HttpMethod.Delete, requests.single().method)
        assertEquals("/api/house/boost", requests.single().url.encodedPath)
    }
}
