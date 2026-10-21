package heating.app.ui.account

import heating.app.data.AccountApi
import heating.app.data.AccountUser
import heating.app.data.FakeHeatingApi.Companion.room
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class RoomAccessTest {
    private val requests = mutableListOf<HttpRequestData>()

    /** Answers like the service: the user list carries each person's rooms, and a PUT returns the member. */
    private fun account(body: String) = AccountApi("https://heat.example", "t", MockEngine { request ->
        requests += request
        respond(body, HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
    })

    private val rooms = listOf(room("lounge", "Lounge"), room("bedroom", "Bedroom"))

    // BDD: An admin ticks each member's rooms on the people screen
    @Test
    fun `an admin ticks each member's rooms and each change saves the full list`() = runTest {
        val users = account(
            """[{"id":"u-jane","username":"jane","admin":true,"rooms":["lounge","bedroom"]},
                {"id":"u-sam","username":"sam","admin":false,"rooms":["bedroom"]}]""",
        ).users()
        val (jane, sam) = users

        // Admins see every room and get no tick boxes; a member's rooms are ticked.
        assertEquals("Sees every room", roomsSummary(jane))
        assertEquals(emptyList(), roomTicks(jane, rooms))
        assertEquals("Sees 1 room", roomsSummary(sam))
        assertEquals(listOf(RoomTick("lounge", "Lounge", false), RoomTick("bedroom", "Bedroom", true)), roomTicks(sam, rooms))

        // Ticking Lounge saves straight away, with every room sam should see, in room order.
        val ticked = tickRoom(rooms, sam.rooms, "lounge", true)
        assertEquals(listOf("lounge", "bedroom"), ticked)
        val saved = account("""{"id":"u-sam","username":"sam","admin":false,"rooms":["lounge","bedroom"]}""").setUserRooms("u-sam", ticked)
        assertEquals(listOf("lounge", "bedroom"), saved.rooms)
        val put = requests.last()
        assertEquals("PUT", put.method.value)
        assertEquals("/api/admin/users/u-sam/rooms", put.url.encodedPath)
        assertEquals("""{"rooms":["lounge","bedroom"]}""", (put.body as OutgoingContent.ByteArrayContent).bytes().decodeToString())

        // Unticking takes it out again.
        assertEquals(listOf("lounge"), tickRoom(rooms, saved.rooms, "bedroom", false))
        assertEquals("Sees no rooms", roomsSummary(AccountUser("u-kit", "kit", admin = false)))
    }
}
