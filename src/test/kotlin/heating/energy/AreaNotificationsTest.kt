package heating.energy

import heating.heatingJson
import heating.testing.MutableClock
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class AreaNotificationsTest {

    private suspend fun ApplicationTestBuilder.post(path: String, body: String, token: String): HttpResponse =
        client.post(path) {
            contentType(ContentType.Application.Json)
            setBody(body)
            bearerAuth(token)
        }

    private suspend fun ApplicationTestBuilder.tokenOf(response: HttpResponse): String =
        heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content

    /**
     * Admins "jane" and "john", member "sam" with energy access and member "ann" without, each signed in with a phone
     * "<name>-phone"; area "Lights" measured by "Lights Clamp".
     */
    private suspend fun ApplicationTestBuilder.household(t: TestHeating): Map<String, String> {
        val jane = tokenOf(client.post("/api/auth/setup") {
            contentType(ContentType.Application.Json)
            setBody("""{"serviceToken":"$TEST_TOKEN","username":"jane","password":"correct horse"}""")
        })
        post("/api/admin/users", """{"username":"john","password":"correct horse","admin":true}""", jane)
        val samId = heatingJson.parseToJsonElement(post("/api/admin/users", """{"username":"sam","password":"correct horse","admin":false}""", jane).bodyAsText())
            .jsonObject["id"]!!.jsonPrimitive.content
        client.put("/api/admin/users/$samId/energy") { contentType(ContentType.Application.Json); setBody("""{"energy":true}"""); bearerAuth(jane) }
        post("/api/admin/users", """{"username":"ann","password":"correct horse","admin":false}""", jane)
        val tokens = mapOf("jane" to jane) + listOf("john", "sam", "ann").associateWith { name ->
            tokenOf(client.post("/api/auth/login") {
                contentType(ContentType.Application.Json)
                setBody("""{"username":"$name","password":"correct horse"}""")
            })
        }
        tokens.forEach { (name, token) -> client.put("/api/me/devices/$name-phone") { bearerAuth(token) } }
        t.discover(meters = listOf("Lights Clamp"))
        post("/api/energy/areas", """{"name":"Lights"}""", jane)
        assertEquals(HttpStatusCode.OK, client.put("/api/energy/areas/lights/whole-meter/Lights Clamp") { bearerAuth(jane) }.status)
        return tokens
    }

    /** The area's notification as "1.5 me jane", or null when it has none. */
    private suspend fun ApplicationTestBuilder.alert(token: String, area: String = "lights"): String? {
        val response = client.get("/api/energy/areas/$area/alert") { bearerAuth(token) }
        assertEquals(HttpStatusCode.OK, response.status, response.bodyAsText())
        val body = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
        if (body["hours"] == JsonNull) {
            assertEquals(JsonNull, body["notify"])
            assertEquals(JsonNull, body["by"])
            return null
        }
        return "${body["hours"]!!.jsonPrimitive.double} ${body["notify"]!!.jsonPrimitive.content} ${body["by"]!!.jsonPrimitive.content}"
    }

    private suspend fun ApplicationTestBuilder.mine(token: String): Boolean =
        heatingJson.parseToJsonElement(client.get("/api/energy/areas/lights/alert") { bearerAuth(token) }.bodyAsText()).jsonObject["mine"]!!.jsonPrimitive.content.toBoolean()

    private suspend fun ApplicationTestBuilder.setAlert(token: String, hours: String, notify: String = "\"me\"", area: String = "lights"): HttpResponse =
        client.put("/api/energy/areas/$area/alert") {
            contentType(ContentType.Application.Json)
            setBody("""{"hours":$hours,"notify":$notify}""")
            bearerAuth(token)
        }

    // BDD: An admin sets up a notification for an area
    @Test
    fun `an admin sets up a notification for an area`() {
        val t = TestHeating()
        var tokens = emptyMap<String, String>()
        t.api {
            tokens = household(t)
            val jane = tokens.getValue("jane")
            assertEquals(HttpStatusCode.OK, setAlert(jane, "1.5").status)
            assertEquals("1.5 me jane", alert(jane))
            assertEquals("1.5 me jane", alert(tokens.getValue("john")))
            assertEquals(true, mine(jane))
            assertEquals(false, mine(tokens.getValue("john")))
            // A later one replaces it.
            assertEquals(HttpStatusCode.OK, setAlert(tokens.getValue("john"), "2", "\"everyone\"").status)
            assertEquals("2.0 everyone john", alert(jane))
            setAlert(jane, "1.5")
            listOf("0", "0.25", "24.5", "-1", "1.2").forEach { assertEquals(HttpStatusCode.BadRequest, setAlert(jane, it).status, it) }
            assertEquals(HttpStatusCode.BadRequest, setAlert(jane, "1", "\"nobody\"").status)
            assertEquals(HttpStatusCode.NotFound, setAlert(jane, "1", area = "nowhere").status)
            assertEquals(HttpStatusCode.Forbidden, setAlert(tokens.getValue("sam"), "1").status)
            assertEquals(HttpStatusCode.Forbidden, client.get("/api/energy/areas/lights/alert") { bearerAuth(tokens.getValue("sam")) }.status)
        }
        t.restart()
        t.api {
            val jane = tokens.getValue("jane")
            assertEquals("1.5 me jane", alert(jane))
            assertEquals(HttpStatusCode.NoContent, client.delete("/api/energy/areas/lights/alert") { bearerAuth(jane) }.status)
            assertEquals(null, alert(jane))
            // Deleting the area forgets its notifications.
            setAlert(jane, "2")
            client.delete("/api/energy/areas/lights") { bearerAuth(jane) }
            post("/api/energy/areas", """{"name":"Lights"}""", jane)
            assertEquals(null, alert(jane))
        }
    }

    // BDD: An area still using power after the hours notifies
    @Test
    fun `an area still using power after the hours notifies`() {
        val t = TestHeating(clock = MutableClock(Instant.parse("2026-11-07T18:00:00Z")))
        var tokens = emptyMap<String, String>()
        t.api {
            tokens = household(t)
            assertEquals(HttpStatusCode.OK, setAlert(tokens.getValue("jane"), "1").status)
        }
        fun lights(watts: Int) = t.meterState("Lights Clamp", """{"power":$watts}""")
        fun minutes(n: Long) = repeat(n.toInt()) {
            t.clock.advance(Duration.ofMinutes(1))
            t.app.tick()
        }
        fun sent() = t.pushed.filter { it.title.contains("Lights") }

        lights(85)
        t.app.tick()
        minutes(59)
        lights(85)
        assertEquals(emptyList(), sent())
        minutes(1)
        assertEquals(listOf("jane-phone"), sent().map { it.token })
        assertEquals("Lights still on", sent().single().title)
        assertEquals("Lights has been using power for 1 h, now 85 W", sent().single().body)

        // Still on: no more.
        lights(90)
        minutes(60)
        assertEquals(1, sent().size)

        // Off, then on again: the time starts again.
        lights(3)
        t.app.tick()
        lights(85)
        t.app.tick()
        minutes(30)
        lights(85)
        // Dropping to 5 W or under starts the time again.
        lights(5)
        t.app.tick()
        lights(85)
        t.app.tick()
        minutes(45)
        assertEquals(1, sent().size)
        lights(85)
        minutes(15)
        assertEquals(2, sent().size)

        // To everyone with energy access: both admins and sam, not ann.
        t.api { setAlert(tokens.getValue("john"), "0.5", "\"everyone\"") }
        lights(3)
        t.app.tick()
        t.pushed.clear()
        lights(85)
        t.app.tick()
        minutes(29)
        lights(85)
        minutes(1)
        assertEquals(listOf("jane-phone", "john-phone", "sam-phone"), sent().map { it.token }.sorted())
        assertEquals("Lights has been using power for 30 min, now 85 W", sent().first().body)
    }
}
