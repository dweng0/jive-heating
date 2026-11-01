package heating.energy

import heating.heatingJson
import heating.testing.TestHeating
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import io.ktor.client.HttpClient
import kotlin.test.Test
import kotlin.test.assertEquals

class EnergyAreasTest {
    private suspend fun HttpClient.add(name: String): HttpResponse = post("/api/energy/areas") {
        contentType(ContentType.Application.Json)
        setBody("""{"name":"$name"}""")
    }

    private suspend fun HttpClient.rename(id: String, name: String): HttpResponse = patch("/api/energy/areas/$id") {
        contentType(ContentType.Application.Json)
        setBody("""{"name":"$name"}""")
    }

    private suspend fun HttpClient.areas(): List<JsonObject> =
        heatingJson.parseToJsonElement(get("/api/energy/areas").bodyAsText()).jsonArray.map { it.jsonObject }

    private fun JsonObject.text(key: String) = this[key]!!.jsonPrimitive.content

    // BDD: An admin creates an energy area
    @Test
    fun `an admin creates an energy area, renames it and deletes it`() {
        TestHeating().api { client ->
            val created = client.add("Garage/Office")
            assertEquals(HttpStatusCode.Created, created.status)
            assertEquals("garage-office", heatingJson.parseToJsonElement(created.bodyAsText()).jsonObject.text("id"))

            val listed = client.areas().single()
            assertEquals("garage-office", listed.text("id"))
            assertEquals("Garage/Office", listed.text("name"))

            val renamed = client.rename("garage-office", "Workshop")
            assertEquals(HttpStatusCode.OK, renamed.status)
            val after = client.areas().single()
            assertEquals("garage-office", after.text("id"), "renaming keeps the id")
            assertEquals("Workshop", after.text("name"))

            assertEquals(HttpStatusCode.NoContent, client.delete("/api/energy/areas/garage-office").status)
            assertEquals(emptyList(), client.areas())
            assertEquals(HttpStatusCode.NotFound, client.delete("/api/energy/areas/garage-office").status)
            assertEquals(HttpStatusCode.NotFound, client.rename("garage-office", "Shed").status)
        }
    }

    // BDD: An admin creates an energy area
    @Test
    fun `a name already used by another area gets 409`() {
        TestHeating().api { client ->
            client.add("Garage/Office")
            client.add("Lights")
            assertEquals(HttpStatusCode.Conflict, client.add("Lights").status)
            assertEquals(HttpStatusCode.Conflict, client.add("  lights ").status)
            assertEquals(HttpStatusCode.Conflict, client.rename("garage-office", "Lights").status)
            assertEquals("Garage/Office", client.areas().first { it.text("id") == "garage-office" }.text("name"))
            assertEquals(HttpStatusCode.OK, client.rename("lights", "LIGHTS").status, "an area may keep its own name")
        }
    }

    @Test
    fun `a blank name gets 400 and names with the same id stay apart`() {
        TestHeating().api { client ->
            assertEquals(HttpStatusCode.BadRequest, client.add("   ").status)
            client.add("Garage/Office")
            val second = client.add("Garage Office")
            assertEquals(HttpStatusCode.Created, second.status)
            assertEquals("garage-office-2", heatingJson.parseToJsonElement(second.bodyAsText()).jsonObject.text("id"))
            assertEquals("area", heatingJson.parseToJsonElement(client.add("///").bodyAsText()).jsonObject.text("id"))
        }
    }

    @Test
    fun `areas outlast a restart`() {
        val t = TestHeating()
        t.api { client -> client.add("Lights") }
        t.restart()
        t.api { client -> assertEquals("Lights", client.areas().single().text("name")) }
    }

    // BDD: An admin creates an energy area
    @Test
    fun `a member gets 403`() {
        TestHeating().api {
            val sam = memberSam()
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Get, "/api/energy/areas").status)
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Post, "/api/energy/areas", """{"name":"Lights"}""").status)
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Patch, "/api/energy/areas/lights", """{"name":"Lamps"}""").status)
            assertEquals(HttpStatusCode.Forbidden, send(sam, HttpMethod.Delete, "/api/energy/areas/lights").status)
        }
    }
}
