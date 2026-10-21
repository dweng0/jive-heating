package heating.energy

import heating.testing.TEST_TOKEN
import heating.testing.anonymous
import io.ktor.client.request.bearerAuth
import io.ktor.client.request.post
import io.ktor.client.request.request
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpMethod
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** Sends [method] [path] with [token] and an optional JSON [body]. */
suspend fun ApplicationTestBuilder.send(token: String, method: HttpMethod, path: String, body: String? = null): HttpResponse =
    anonymous.request(path) {
        this.method = method
        bearerAuth(token)
        body?.let {
            contentType(ContentType.Application.Json)
            setBody(it)
        }
    }

/** Admin "jane" set up, member "sam" created and signed in; returns sam's session token. */
suspend fun ApplicationTestBuilder.memberSam(): String {
    val jane = token(post("/api/auth/setup", """{"serviceToken":"$TEST_TOKEN","username":"jane","password":"correct horse"}"""))
    post("/api/admin/users", """{"username":"sam","password":"correct horse","admin":false}""", jane)
    return token(post("/api/auth/login", """{"username":"sam","password":"correct horse"}"""))
}

private suspend fun ApplicationTestBuilder.post(path: String, body: String, token: String? = null): HttpResponse =
    anonymous.post(path) {
        contentType(ContentType.Application.Json)
        setBody(body)
        token?.let { bearerAuth(it) }
    }

private suspend fun token(response: HttpResponse): String =
    Json.parseToJsonElement(response.bodyAsText()).jsonObject["token"]!!.jsonPrimitive.content
