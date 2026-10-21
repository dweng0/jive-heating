package heating.api

import heating.auth.SessionTokens
import io.ktor.http.Cookie
import io.ktor.http.CookieEncoding
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.http.auth.parseAuthorizationHeader
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpMethod
import java.net.URI

/**
 * The web app's session: the same signed token the phone sends as a bearer token, kept in an
 * HttpOnly `__Host-` cookie instead. A cookie is sent by the browser on its own, so a request
 * authenticated by it must show it came from our page:
 * - changes (anything but GET/HEAD) carry `X-Heat-Client: web`, a header another site's page
 *   cannot add without a CORS preflight we never grant. SameSite=Strict alone is not enough here:
 *   sibling subdomains of the same site count as same-site.
 * - a WebSocket must come from an Origin on this host.
 */
object WebSession {
    const val COOKIE = "__Host-heat"
    const val CLIENT_HEADER = "X-Heat-Client"

    fun cookie(token: String) = Cookie(
        name = COOKIE, value = token, encoding = CookieEncoding.RAW,
        maxAge = SessionTokens.LIFETIME.seconds.toInt(), path = "/",
        secure = true, httpOnly = true, extensions = mapOf("SameSite" to "Strict"),
    )

    fun cleared() = Cookie(
        name = COOKIE, value = "", encoding = CookieEncoding.RAW,
        maxAge = 0, path = "/", secure = true, httpOnly = true, extensions = mapOf("SameSite" to "Strict"),
    )

    /** The credential behind [call]: the Authorization header, else the cookie when the request passes the checks above. */
    fun authHeader(call: ApplicationCall): HttpAuthHeader? {
        call.request.headers[HttpHeaders.Authorization]?.let { return parseAuthorizationHeader(it) }
        val token = call.request.cookies[COOKIE, CookieEncoding.RAW]?.takeIf { it.isNotEmpty() } ?: return null
        val isWebSocket = call.request.headers[HttpHeaders.Upgrade].equals("websocket", ignoreCase = true)
        val allowed = when {
            isWebSocket -> sameOrigin(call)
            call.request.httpMethod == HttpMethod.Get || call.request.httpMethod == HttpMethod.Head -> true
            else -> call.request.headers[CLIENT_HEADER] == "web"
        }
        return if (allowed) HttpAuthHeader.Single("Bearer", token) else null
    }

    private fun sameOrigin(call: ApplicationCall): Boolean {
        val origin = call.request.headers[HttpHeaders.Origin]?.let { runCatching { URI(it) }.getOrNull() } ?: return false
        val originHost = origin.host ?: return false
        val originPort = origin.port.takeIf { it != -1 && it != 80 && it != 443 }
        // The host the browser asked for: Host, or X-Forwarded-Host when a proxy (cloudflared) rewrote Host.
        val candidates = listOfNotNull(call.request.headers[HttpHeaders.Host], call.request.headers[HttpHeaders.XForwardedHost])
            .ifEmpty { listOf("${call.request.local.serverHost}:${call.request.local.serverPort}") }
        return candidates.any { authority ->
            val host = authority.substringBefore(':')
            val port = authority.substringAfter(':', "").toIntOrNull()?.takeIf { it != 80 && it != 443 }
            originHost.equals(host, ignoreCase = true) && originPort == port
        }
    }
}
