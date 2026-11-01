package heating.app.data

import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EnergyAreasApiTest {
    private val requests = mutableListOf<HttpRequestData>()

    private fun api(status: HttpStatusCode, body: String) = KtorHeatingApi(
        ServerConfig("https://heating.example", "secret"),
        MockEngine { request ->
            requests += request
            respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))
        },
    )

    // BDD: An admin creates an energy area
    @Test
    fun `adding an area posts its name and reads the id`() = runTest {
        val area = api(HttpStatusCode.Created, """{"id":"garage-office","name":"Garage/Office"}""").addEnergyArea("Garage/Office")
        val request = requests.single()
        assertEquals(HttpMethod.Post, request.method)
        assertEquals("https://heating.example/api/energy/areas", request.url.toString())
        assertEquals("""{"name":"Garage/Office"}""", (request.body as TextContent).text)
        assertEquals(EnergyArea("garage-office", "Garage/Office"), area)
    }

    // BDD: An admin creates an energy area
    @Test
    fun `areas are listed, renamed and deleted by id`() = runTest {
        val list = api(HttpStatusCode.OK, """[{"id":"garage-office","name":"Garage/Office"}]""").energyAreas()
        assertEquals("garage-office", list.single().id)
        val one = api(HttpStatusCode.OK, """{"id":"garage-office","name":"Workshop"}""")
        assertEquals("Workshop", one.renameEnergyArea("garage-office", "Workshop").name)
        one.deleteEnergyArea("garage-office")
        assertEquals(listOf(HttpMethod.Get, HttpMethod.Patch, HttpMethod.Delete), requests.map { it.method })
        assertEquals("https://heating.example/api/energy/areas/garage-office", requests.last().url.toString())
    }

    // BDD: An admin creates an energy area
    @Test
    fun `a name already used is refused with the service's message`() = runTest {
        val refused = assertFailsWith<HeatingApiException> {
            api(HttpStatusCode.Conflict, """{"error":"an area called Lights already exists"}""").addEnergyArea("Lights")
        }
        assertEquals(409, refused.status)
        assertEquals("an area called Lights already exists", refused.message)
    }
}
