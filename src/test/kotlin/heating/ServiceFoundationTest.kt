package heating

import heating.testing.TestHeating
import heating.testing.anonymous
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ServiceFoundationTest {

    // BDD: Health endpoint reports ok without a token
    @Test
    fun `health endpoint reports ok without a token`() = TestHeating().api {
        val response = anonymous.get("/health")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("""{"status":"ok"}""", response.bodyAsText())
    }

    // BDD: API rejects requests without a bearer token
    @Test
    fun `api rejects requests without a bearer token`() = TestHeating().api {
        assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/rooms").status)
    }

    // BDD: API rejects requests with the wrong bearer token
    @Test
    fun `api rejects requests with the wrong bearer token`() = TestHeating().api {
        val response = anonymous.get("/api/rooms") { bearerAuth("nope") }
        assertEquals(HttpStatusCode.Unauthorized, response.status)
    }

    // BDD: API accepts requests with the correct bearer token
    @Test
    fun `api accepts requests with the correct bearer token`() = TestHeating().api { client ->
        val response = client.get("/api/rooms")
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("[]", response.bodyAsText())
    }

    // BDD: Service refuses to start without an API token
    @Test
    fun `service refuses to start without an api token`() {
        val error = assertFailsWith<IllegalStateException> { Config.fromEnv(emptyMap()) }
        assertEquals("HEATING_API_TOKEN must be set", error.message)
    }

    // BDD: OpenAPI document is served
    @Test
    fun `openapi document is served`() = TestHeating().api { client ->
        val response = client.get("/openapi.json")
        assertEquals(HttpStatusCode.OK, response.status)
        val paths = Json.parseToJsonElement(response.bodyAsText()).jsonObject["paths"]!!.jsonObject
        assertTrue("/api/rooms" in paths)
        assertTrue("/api/valves" in paths)
    }
}
