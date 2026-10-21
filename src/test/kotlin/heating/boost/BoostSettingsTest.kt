package heating.boost

import heating.energy.memberSam
import heating.energy.send
import heating.heatingJson
import heating.testing.TestHeating
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals

class BoostSettingsTest {

    private fun settings(t: TestHeating): JsonObject {
        var result: JsonObject? = null
        t.api { client ->
            val response = client.get("/api/settings/boost")
            assertEquals(HttpStatusCode.OK, response.status)
            result = heatingJson.parseToJsonElement(response.bodyAsText()).jsonObject
        }
        return result!!
    }

    private fun JsonObject.defaultTarget() = this["defaultTarget"]!!.jsonPrimitive.double

    // BDD: New installs boost to 22 by default
    @Test
    fun `new installs boost to 22 by default`() {
        assertEquals(22.0, settings(TestHeating()).defaultTarget())
    }

    private fun put(t: TestHeating, body: String): HttpStatusCode {
        var status = HttpStatusCode.OK
        t.api { client ->
            status = client.put("/api/settings/boost") {
                contentType(ContentType.Application.Json)
                setBody(body)
            }.status
        }
        return status
    }

    // BDD: An admin's chosen boost temperature survives a restart
    @Test
    fun `an admin's chosen boost temperature survives a restart`() {
        val t = TestHeating()
        assertEquals(HttpStatusCode.OK, put(t, """{"defaultTarget":20.5}"""))
        assertEquals(20.5, settings(t).defaultTarget())
        t.restart()
        assertEquals(20.5, settings(t).defaultTarget())
    }

    // BDD: Impossible boost defaults are refused
    @Test
    fun `impossible boost defaults are refused`() {
        val t = TestHeating()
        val refused = listOf(
            """{"defaultTarget":35.0}""",
            """{"defaultTarget":4.5}""",
            """{"defaultTarget":20.3}""",
            """{"defaultTarget":"warm"}""",
            """{}""",
        )
        for (body in refused) {
            assertEquals(HttpStatusCode.BadRequest, put(t, body), body)
            assertEquals(22.0, settings(t).defaultTarget(), body)
        }
        assertEquals(HttpStatusCode.OK, put(t, """{"defaultTarget":30.0}"""))
        assertEquals(HttpStatusCode.OK, put(t, """{"defaultTarget":5.0}"""))
    }

    // BDD: Non-admins read the boost default but cannot change it
    @Test
    fun `non-admins read the boost default but cannot change it`() {
        val t = TestHeating()
        t.api {
            val sam = memberSam()
            val read = send(sam, HttpMethod.Get, "/api/settings/boost")
            assertEquals(HttpStatusCode.OK, read.status)
            assertEquals(22.0, heatingJson.parseToJsonElement(read.bodyAsText()).jsonObject.defaultTarget())
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Put, "/api/settings/boost", """{"defaultTarget":18.0}""").status)
        }
        assertEquals(22.0, settings(t).defaultTarget())
    }
}
