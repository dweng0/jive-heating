package heating

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import heating.rooms.Mode
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import heating.testing.anonymous
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** The action log: one INFO line per thing done, naming who asked, never holding secrets. */
class ActionLogTest {

    /** Collect every log line (all loggers) written while [block] runs. */
    private fun captured(block: () -> Unit): List<String> {
        val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME) as Logger
        val appender = ListAppender<ILoggingEvent>().apply { start() }
        root.addAppender(appender)
        try {
            block()
        } finally {
            root.detachAppender(appender)
        }
        return appender.list.map { "${it.loggerName} ${it.level} ${it.formattedMessage}" }
    }

    private fun lounge(t: TestHeating): String {
        t.discover("Lounge TRV 1")
        val room = t.app.rooms.create("Lounge")
        t.app.rooms.assignValve(room.id, "Lounge TRV 1")
        return room.id
    }

    @Test
    fun `a heat demand flip logs which zones are calling and why`() {
        val t = TestHeating()
        val roomId = lounge(t)
        t.app.zones.setMode(roomId, Mode.MANUAL, 21.0)
        val lines = captured {
            t.valveState("Lounge TRV 1", """{"local_temperature":19.0}""")
            // Warm again straight away: min-run holds the boiler on, logged once however many ticks.
            t.valveState("Lounge TRV 1", """{"local_temperature":21.5}""")
            repeat(3) {
                t.clock.advance(Duration.ofMinutes(1))
                t.app.tick()
            }
            t.clock.advance(Duration.ofMinutes(10))
            t.app.tick()
        }
        val actions = lines.filter { it.startsWith("heating.actions INFO ") }.map { it.removePrefix("heating.actions INFO ") }
        assertTrue("demand on (calling: Lounge [19.0 -> 21.0])" in actions, actions.toString())
        assertEquals(1, actions.count { it.startsWith("demand wants off, holding on for min-run") }, actions.toString())
        assertTrue("demand off (no zones calling)" in actions, actions.toString())
    }

    @Test
    fun `an API mode change logs the signed-in username`() {
        val t = TestHeating()
        val roomId = lounge(t)
        val lines = captured {
            t.api { _ ->
                val setup = anonymous.post("/api/auth/setup") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"serviceToken":"$TEST_TOKEN","username":"jane","password":"correct horse"}""")
                }
                val token = Json.parseToJsonElement(setup.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
                val response = anonymous.put("/api/zones/$roomId/mode") {
                    bearerAuth(token)
                    contentType(ContentType.Application.Json)
                    setBody("""{"mode":"manual","target":21.0}""")
                }
                assertEquals(HttpStatusCode.OK, response.status)
                // The service token is "service".
                val viaService = anonymous.post("/api/house") {
                    bearerAuth(TEST_TOKEN)
                    contentType(ContentType.Application.Json)
                    setBody("""{"mode":"off"}""")
                }
                assertEquals(HttpStatusCode.OK, viaService.status)
            }
        }
        assertTrue("heating.actions INFO zone Lounge mode manual target 21.0 by jane" in lines, lines.toString())
        assertTrue("heating.actions INFO house mode off by service" in lines, lines.toString())
        assertFalse(lines.any { "correct horse" in it || TEST_TOKEN in it }, lines.toString())
    }

    @Test
    fun `a failed sign-in logs the username but never the password`() {
        val t = TestHeating()
        val password = "S3cret-Passw0rd-xyz"
        val lines = captured {
            t.api { _ ->
                anonymous.post("/api/auth/setup") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"serviceToken":"$TEST_TOKEN","username":"sam","password":"correct horse"}""")
                }
                repeat(6) {
                    anonymous.post("/api/auth/login") {
                        contentType(ContentType.Application.Json)
                        setBody("""{"username":"sam","password":"$password"}""")
                    }
                }
                val ok = anonymous.post("/api/auth/login") {
                    contentType(ContentType.Application.Json)
                    setBody("""{"username":"nobody","password":"$password"}""")
                }
                assertEquals(HttpStatusCode.Unauthorized, ok.status)
            }
        }
        assertTrue("heating.actions INFO sign in failed for sam" in lines, lines.toString())
        assertTrue("heating.actions INFO sign in failed for nobody" in lines, lines.toString())
        assertTrue(lines.any { it.startsWith("heating.actions INFO sign in locked for sam") }, lines.toString())
        assertFalse(lines.any { password in it || "correct horse" in it || TEST_TOKEN in it }, lines.toString())
    }
}
