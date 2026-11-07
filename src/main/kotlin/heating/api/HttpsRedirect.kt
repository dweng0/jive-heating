package heating.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.uri
import io.ktor.server.response.header
import io.ktor.server.response.respond

/**
 * A request the tunnel took over plain http goes to https instead: the session cookie is Secure, so a browser on an
 * http page drops it and every request after sign-in looks signed out. Straight to the service (no proxy) is left alone.
 */
val HttpsRedirect = createApplicationPlugin("HttpsRedirect") {
    onCall { call ->
        val headers = call.request.headers
        if (!headers["X-Forwarded-Proto"].equals("http", ignoreCase = true)) return@onCall
        val host = headers[HttpHeaders.XForwardedHost] ?: headers[HttpHeaders.Host] ?: return@onCall
        call.response.header(HttpHeaders.Location, "https://$host${call.request.uri}")
        call.respond(HttpStatusCode.PermanentRedirect)
    }
}
