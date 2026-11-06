package heating.energy

import heating.api.heatingModule
import heating.heatingJson
import heating.testing.TestHeating
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.URI
import java.net.http.HttpClient
import java.net.http.WebSocket
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals

/** Admins letting chosen members read the Energy tab. */
class EnergyAccessTest {

    private fun JsonObject.energy() = this["energy"]!!.jsonPrimitive.boolean

    private fun users(t: TestHeating): List<JsonObject> {
        var list = emptyList<JsonObject>()
        t.api { client -> list = heatingJson.parseToJsonElement(client.get("/api/admin/users").bodyAsText()).jsonArray.map { it.jsonObject } }
        return list
    }

    private fun setEnergy(t: TestHeating, id: String, energy: Boolean): HttpStatusCode {
        var status = HttpStatusCode.OK
        t.api { client ->
            status = client.put("/api/admin/users/$id/energy") { contentType(ContentType.Application.Json); setBody("""{"energy":$energy}""") }.status
        }
        return status
    }

    /** Member "sam" signed in; returns sam's token and id. */
    private fun sam(t: TestHeating): Pair<String, String> {
        var token = ""
        var id = ""
        t.api {
            token = memberSam()
            id = heatingJson.parseToJsonElement(send(token, HttpMethod.Get, "/api/auth/me").bodyAsText()).jsonObject["id"]!!.jsonPrimitive.content
        }
        return token to id
    }

    private fun me(t: TestHeating, token: String): JsonObject {
        var body: JsonObject? = null
        t.api { body = heatingJson.parseToJsonElement(send(token, HttpMethod.Get, "/api/auth/me").bodyAsText()).jsonObject }
        return body!!
    }

    // BDD: An admin gives a member energy access
    @Test
    fun `an admin gives a member energy access`() {
        val t = TestHeating()
        val (token, id) = sam(t)
        assertEquals(false, users(t).single { it["username"]!!.jsonPrimitive.content == "sam" }.energy())
        assertEquals(false, me(t, token).energy())

        assertEquals(HttpStatusCode.OK, setEnergy(t, id, true))
        assertEquals(true, users(t).single { it["username"]!!.jsonPrimitive.content == "sam" }.energy())
        assertEquals(true, users(t).single { it["username"]!!.jsonPrimitive.content == "jane" }.energy())
        assertEquals(true, me(t, token).energy())
        t.restart()
        assertEquals(true, users(t).single { it["username"]!!.jsonPrimitive.content == "sam" }.energy())

        assertEquals(HttpStatusCode.OK, setEnergy(t, id, false))
        assertEquals(false, me(t, token).energy())

        val janeId = users(t).single { it["username"]!!.jsonPrimitive.content == "jane" }["id"]!!.jsonPrimitive.content
        assertEquals(HttpStatusCode.Conflict, setEnergy(t, janeId, false))
        t.api { assertEquals(HttpStatusCode.Forbidden, send(token, HttpMethod.Put, "/api/admin/users/$id/energy", """{"energy":true}""").status) }
    }

    // BDD: Energy access lets a member read the Energy tab and nothing more
    @Test
    fun `energy access lets a member read the energy tab and nothing more`() {
        val t = TestHeating()
        t.discover(meters = listOf("Main Clamp"))
        t.api { client -> assertEquals(HttpStatusCode.OK, client.put("/api/meters/Main%20Clamp/house").status) }
        val (token, id) = sam(t)
        val reads = listOf("/api/dashboard", "/api/power", "/api/energy/history")
        val adminOnly = listOf(
            HttpMethod.Get to "/api/meters",
            HttpMethod.Get to "/api/settings/energy",
            HttpMethod.Put to "/api/settings/energy",
            HttpMethod.Get to "/api/energy/areas",
        )

        t.api {
            reads.forEach { assertEquals(HttpStatusCode.Forbidden, send(token, HttpMethod.Get, it).status, it) }
            adminOnly.forEach { (m, p) -> assertEquals(HttpStatusCode.Forbidden, send(token, m, p, "{}").status, p) }
        }
        assertEquals(403, socketStatus(t, token))

        setEnergy(t, id, true)
        t.api {
            reads.forEach { assertEquals(HttpStatusCode.OK, send(token, HttpMethod.Get, it).status, it) }
            adminOnly.forEach { (m, p) -> assertEquals(HttpStatusCode.Forbidden, send(token, m, p, "{\"dailyKwhTarget\":5}").status, p) }
        }
        assertEquals(101, socketStatus(t, token))
    }

    /** 101 when /ws/power opens for [token], else the status it was refused with. */
    private fun socketStatus(t: TestHeating, token: String): Int {
        val server = embeddedServer(Netty, port = 0, host = "127.0.0.1") { heatingModule(t.app) }.start(wait = false)
        try {
            val port = runBlocking { server.engine.resolvedConnectors().first().port }
            return runCatching {
                HttpClient.newHttpClient().newWebSocketBuilder().header("Authorization", "Bearer $token")
                    .buildAsync(URI("ws://127.0.0.1:$port/ws/power"), object : WebSocket.Listener {}).get(5, TimeUnit.SECONDS)
                    .also { it.abort() }
                101
            }.getOrElse { e -> (e.cause as java.net.http.WebSocketHandshakeException).response.statusCode() }
        } finally {
            server.stop(0, 0)
        }
    }
}
