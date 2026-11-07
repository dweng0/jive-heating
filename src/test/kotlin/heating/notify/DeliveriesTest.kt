package heating.notify

import heating.heatingJson
import heating.testing.TEST_TOKEN
import heating.testing.TestHeating
import heating.testing.anonymous
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import java.time.Duration
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

class DeliveriesTest {

    /** Admin "jane" and members "sam" and "ann", each with a phone "<name>-phone". Returns name to user id. */
    private fun household(t: TestHeating): Map<String, String> {
        val jane = t.app.users.setup(TEST_TOKEN, "jane", "correct horse").second
        val sam = t.app.users.create("sam", "correct horse", admin = false)
        val ann = t.app.users.create("ann", "correct horse", admin = false)
        val ids = mapOf("jane" to jane.id, "sam" to sam.id, "ann" to ann.id)
        ids.forEach { (name, id) -> t.app.notificationSettings.register(id, "$name-phone") }
        return ids
    }

    private fun Deliveries.all() = search(DeliveryQuery()).reversed()

    private fun later(t: TestHeating, d: Duration) {
        t.clock.advance(d)
        t.app.tick()
    }

    private suspend fun ApplicationTestBuilder.token(t: TestHeating, name: String): String =
        t.app.users.login("127.0.0.1", name, "correct horse").first

    private suspend fun ApplicationTestBuilder.getAs(token: String, path: String): HttpResponse =
        anonymous.get(path) { bearerAuth(token) }

    private suspend fun ApplicationTestBuilder.postAs(token: String, path: String): HttpResponse =
        anonymous.post(path) { bearerAuth(token); contentType(ContentType.Application.Json); setBody("{}") }

    private fun json(r: HttpResponse, body: String) = heatingJson.parseToJsonElement(body)

    // BDD: A notification is queued then delivered
    @Test
    fun `a notification is queued then delivered`() {
        val t = TestHeating()
        val ids = household(t)
        val queued = t.app.deliveries.enqueue(listOf(ids.getValue("jane"), ids.getValue("sam")), "Front door opened", "The front door was opened", "Front door opened")
        assertEquals(2, queued.size)

        assertEquals(setOf("jane-phone", "sam-phone"), t.pushed.map { it.token }.toSet())
        val sent = queued.map { assertNotNull(t.app.deliveries.get(it)) }
        sent.forEach {
            assertEquals(DeliveryStatus.SENT, it.status)
            assertEquals(t.clock.instant().toEpochMilli(), it.sentAt)
            assertEquals(1, it.sentTo.size)
            assertEquals(1, it.tries)
        }
        assertEquals(listOf(ids.getValue("jane"), ids.getValue("sam")).toSet(), sent.map { it.userId }.toSet())
    }

    // BDD: A notification is queued then delivered
    @Test
    fun `deliveries queued before a restart are sent after it`() {
        val t = TestHeating()
        val ids = household(t)
        t.pushFails = true
        val queued = t.app.deliveries.enqueue(listOf(ids.getValue("jane")), "Front door opened", "The front door was opened", "Front door opened")
        assertEquals(DeliveryStatus.RETRYING, t.app.deliveries.get(queued.single())!!.status)

        t.restart()
        t.pushFails = false
        later(t, Duration.ofSeconds(30))
        assertEquals(DeliveryStatus.SENT, t.app.deliveries.get(queued.single())!!.status)
        assertEquals(listOf("jane-phone"), t.pushed.map { it.token })
    }

    // BDD: A failed delivery is retried with backoff
    @Test
    fun `a failed delivery is retried with backoff`() {
        val t = TestHeating()
        val ids = household(t)
        t.pushFails = true
        val id = t.app.deliveries.enqueue(listOf(ids.getValue("jane")), "Front door opened", "Opened", "Front door opened").single()
        fun now() = t.app.deliveries.get(id)!!
        assertEquals(1, now().tries)
        assertEquals(DeliveryStatus.RETRYING, now().status)

        later(t, Duration.ofSeconds(29))
        assertEquals(1, now().tries)
        later(t, Duration.ofSeconds(1))
        assertEquals(2, now().tries)
        later(t, Duration.ofSeconds(119))
        assertEquals(2, now().tries)
        later(t, Duration.ofSeconds(1))
        assertEquals(3, now().tries)
        t.pushFails = false
        later(t, Duration.ofMinutes(9) + Duration.ofSeconds(59))
        assertEquals(3, now().tries)
        later(t, Duration.ofSeconds(1))
        assertEquals(DeliveryStatus.SENT, now().status)
        assertEquals(4, now().tries)
    }

    // BDD: A failed delivery is retried with backoff
    @Test
    fun `a phone that is no longer registered is removed and not retried while other devices still get it`() {
        val t = TestHeating()
        val ids = household(t)
        t.app.notificationSettings.register(ids.getValue("jane"), "jane-old-phone")
        t.unregistered += "jane-old-phone"
        val id = t.app.deliveries.enqueue(listOf(ids.getValue("jane")), "Hello", "Body", "Test").single()

        assertEquals(DeliveryStatus.SENT, t.app.deliveries.get(id)!!.status)
        assertEquals(1, t.app.deliveries.get(id)!!.tries)
        assertEquals(listOf("jane-phone"), t.app.notificationSettings.devicesOf(ids.getValue("jane")))
        assertEquals(setOf("jane-phone", "jane-old-phone"), t.pushed.map { it.token }.toSet())
        later(t, Duration.ofMinutes(15))
        assertEquals(2, t.pushed.size)
    }

    // BDD: A delivery that keeps failing goes to failed deliveries
    @Test
    fun `a delivery that keeps failing goes to failed deliveries`() {
        val t = TestHeating()
        val ids = household(t)
        t.pushFails = true
        val id = t.app.deliveries.enqueue(listOf(ids.getValue("jane")), "Front door opened", "Opened", "Front door opened").single()
        later(t, Duration.ofSeconds(30))
        later(t, Duration.ofMinutes(2))
        later(t, Duration.ofMinutes(10))
        val failed = t.app.deliveries.get(id)!!
        assertEquals(DeliveryStatus.FAILED, failed.status)
        assertEquals(4, failed.attempts.size)
        assertTrue(failed.attempts.all { it.error.contains("jane-phone".takeLast(6)) && it.at.isNotEmpty() })
        // Nothing more is tried.
        later(t, Duration.ofHours(1))
        assertEquals(4, failed.attempts.size)
        assertEquals(DeliveryStatus.FAILED, t.app.deliveries.get(id)!!.status)

        t.api {
            val jane = token(t, "jane")
            val list = heatingJson.parseToJsonElement(getAs(jane, "/api/deliveries?status=failed").bodyAsText()).jsonObject
            assertEquals(1, list["failed"]!!.jsonPrimitive.int)
            val row = list["deliveries"]!!.jsonArray.single().jsonObject
            assertEquals("jane", row["person"]!!.jsonPrimitive.content)
            assertEquals("Front door opened", row["title"]!!.jsonPrimitive.content)
            assertEquals(4, row["attempts"]!!.jsonArray.size)

            // Retry queues it again (and it is sent once the push service is back).
            t.pushFails = false
            assertEquals(HttpStatusCode.OK, postAs(jane, "/api/deliveries/$id/retry").status)
            assertEquals(DeliveryStatus.SENT, t.app.deliveries.get(id)!!.status)
            assertEquals(HttpStatusCode.NotFound, postAs(jane, "/api/deliveries/$id/retry").status)
        }
    }

    // BDD: A delivery that keeps failing goes to failed deliveries
    @Test
    fun `retry all queues every failed delivery and clear removes one`() {
        val t = TestHeating()
        val ids = household(t)
        t.pushFails = true
        val a = t.app.deliveries.enqueue(listOf(ids.getValue("jane")), "A", "a", "Test").single()
        val b = t.app.deliveries.enqueue(listOf(ids.getValue("sam")), "B", "b", "Test").single()
        val c = t.app.deliveries.enqueue(listOf(ids.getValue("ann")), "C", "c", "Test").single()
        later(t, Duration.ofSeconds(30)); later(t, Duration.ofMinutes(2)); later(t, Duration.ofMinutes(10))
        assertEquals(3, t.app.deliveries.failedCount())
        t.pushFails = false
        t.api {
            val jane = token(t, "jane")
            assertEquals(HttpStatusCode.NoContent, anonymous.delete("/api/deliveries/$c") { bearerAuth(jane) }.status)
            assertNull(t.app.deliveries.get(c))
            val r = heatingJson.parseToJsonElement(postAs(jane, "/api/deliveries/retry-all").bodyAsText()).jsonObject
            assertEquals(2, r["retried"]!!.jsonPrimitive.int)
        }
        assertEquals(setOf(DeliveryStatus.SENT), listOf(a, b).map { t.app.deliveries.get(it)!!.status }.toSet())
        assertEquals(0, t.app.deliveries.failedCount())
    }

    // BDD: A delivery that keeps failing goes to failed deliveries
    @Test
    fun `a notification older than 24 hours is retried with sent late after its title`() {
        val t = TestHeating()
        val ids = household(t)
        t.pushFails = true
        val id = t.app.deliveries.enqueue(listOf(ids.getValue("jane")), "Front door opened", "Opened", "Test").single()
        later(t, Duration.ofSeconds(30)); later(t, Duration.ofMinutes(2)); later(t, Duration.ofMinutes(10))
        t.clock.advance(Duration.ofHours(25))
        t.pushFails = false
        assertTrue(t.app.deliveries.retry(id))
        assertEquals("Front door opened (sent late)", t.pushed.last().title)
    }

    // BDD: Deliveries are searched in the app
    @Test
    fun `deliveries are searched newest first with filters and sorting and members get 403`() {
        val t = TestHeating()
        val ids = household(t)
        val event = DeliveryEvent(id = "e1", topic = "z2m/front_door", payload = heatingJson.parseToJsonElement("""{"contact":false}"""), at = t.clock.instant().toString())
        t.app.deliveries.enqueue(listOf(ids.getValue("sam")), "Front door opened", "Opened", "Front door opened", ruleId = "r1", event = event)
        t.clock.advance(Duration.ofSeconds(10))
        t.pushFails = true
        t.app.deliveries.enqueue(listOf(ids.getValue("jane")), "Heating finished", "Done", "Heating alert")
        t.clock.advance(Duration.ofSeconds(10))
        t.pushFails = false
        t.app.deliveries.enqueue(listOf(ids.getValue("ann")), "Garage still on", "On", "Area notification")

        t.api {
            val jane = token(t, "jane")
            fun rows(path: String) = heatingJson.parseToJsonElement(kotlinx.coroutines.runBlocking { getAs(jane, path).bodyAsText() })
                .jsonObject["deliveries"]!!.jsonArray.map { it.jsonObject }
            fun titles(path: String) = rows(path).map { it["title"]!!.jsonPrimitive.content }

            assertEquals(listOf("Garage still on", "Heating finished", "Front door opened"), titles("/api/deliveries"))
            assertEquals(listOf("Heating finished"), titles("/api/deliveries?status=retrying"))
            assertEquals(listOf("Front door opened"), titles("/api/deliveries?rule=Front+door+opened"))
            assertEquals(listOf("Front door opened"), titles("/api/deliveries?person=sam"))
            assertEquals(emptyList(), titles("/api/deliveries?person=nobody"))
            val from = t.clock.instant().minus(Duration.ofSeconds(15)).toString()
            assertEquals(listOf("Garage still on", "Heating finished"), titles("/api/deliveries?from=$from"))
            assertEquals(listOf("Ann".lowercase(), "jane", "sam"), rows("/api/deliveries?sort=person&dir=asc").map { it["person"]!!.jsonPrimitive.content })
            assertEquals(listOf("sent", "retrying", "sent"), rows("/api/deliveries").map { it["status"]!!.jsonPrimitive.content })

            // Opening one shows the event that caused it; a heating alert has none, only its source.
            val open = rows("/api/deliveries").last()["id"]!!.jsonPrimitive.content
            val one = heatingJson.parseToJsonElement(getAs(jane, "/api/deliveries/$open").bodyAsText()).jsonObject
            assertEquals("z2m/front_door", one["event"]!!.jsonObject["topic"]!!.jsonPrimitive.content)
            val alert = rows("/api/deliveries")[1]
            assertTrue(alert["event"] == null || alert["event"] is kotlinx.serialization.json.JsonNull)
            assertEquals("Heating alert", alert["source"]!!.jsonPrimitive.content)

            // Members get 403.
            val sam = token(t, "sam")
            assertEquals(HttpStatusCode.Forbidden, getAs(sam, "/api/deliveries").status)
            assertEquals(HttpStatusCode.Forbidden, getAs(sam, "/api/deliveries/$open").status)
        }
    }

    // BDD: Deliveries are searched in the app
    @Test
    fun `sent deliveries are kept 30 days`() {
        val t = TestHeating()
        val ids = household(t)
        val sent = t.app.deliveries.enqueue(listOf(ids.getValue("jane")), "Old", "old", "Test").single()
        t.pushFails = true
        val failed = t.app.deliveries.enqueue(listOf(ids.getValue("jane")), "Stuck", "stuck", "Test").single()
        later(t, Duration.ofDays(29))
        assertNotNull(t.app.deliveries.get(sent))
        later(t, Duration.ofDays(2))
        assertNull(t.app.deliveries.get(sent))
        assertNotNull(t.app.deliveries.get(failed))
    }
}
