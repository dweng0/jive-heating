package heating.api

import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.application.hooks.ResponseSent
import io.ktor.server.request.httpMethod
import io.ktor.server.request.path
import org.slf4j.LoggerFactory

private val log = LoggerFactory.getLogger("heating.auth")

/**
 * One INFO line for each API request refused as not signed in: which cookies and headers arrived (names only, never values)
 * and the browser, so a browser that drops the session cookie can be told apart from a session that ended.
 */
val RefusedLog = createApplicationPlugin("RefusedLog") {
    on(ResponseSent) { call ->
        if (call.response.status() != HttpStatusCode.Unauthorized || !call.request.path().startsWith("/api/")) return@on
        val headers = call.request.headers
        val cookies = call.request.cookies.rawCookies.keys.sorted()
        log.info(
            "refused {} {}: {}; {}; {}; {}",
            call.request.httpMethod.value,
            call.request.path(),
            if (cookies.isEmpty()) "no cookies" else "cookies ${cookies.joinToString(", ")}",
            if (headers[HttpHeaders.Authorization] == null) "no Authorization" else "Authorization present",
            "client ${headers[WebSession.CLIENT_HEADER] ?: "-"}",
            "ua ${headers[HttpHeaders.UserAgent]?.take(200)?.replace(Regex("[\\p{Cntrl}]"), "?") ?: "-"}",
        )
    }
}
