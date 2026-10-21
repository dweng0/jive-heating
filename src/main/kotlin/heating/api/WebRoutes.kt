package heating.api

import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.response.respondFile
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import java.io.File

private const val CSP = "default-src 'self'; connect-src 'self'; img-src 'self' data:; " +
    "style-src 'self' 'unsafe-inline'; font-src 'self' data:; object-src 'none'; base-uri 'none'; " +
    "form-action 'self'; frame-ancestors 'none'"

private val MANIFEST = ContentType("application", "manifest+json")

/**
 * Serves the built web app from [dir]: real files as they are, any other page path as index.html
 * (the app routes in the browser). /api and /ws paths never fall through to the page.
 */
fun Route.webRoutes(dir: String) {
    val root = File(dir).canonicalFile
    get("{path...}") {
        val path = call.request.path()
        if (path.startsWith("/api/") || path == "/api" || path == "/ws") {
            call.respond(HttpStatusCode.NotFound, ApiError("not found"))
            return@get
        }
        val requested = File(root, path.trimStart('/')).canonicalFile
        val file = requested.takeIf { it.isFile && it.path.startsWith(root.path + File.separator) } ?: File(root, "index.html")
        call.response.header("Content-Security-Policy", CSP)
        call.response.header("X-Content-Type-Options", "nosniff")
        call.response.header("Referrer-Policy", "same-origin")
        // The page and the service worker are always revalidated, so a new version reaches every browser.
        if (file.name == "index.html" || file.path == File(root, "sw.js").path) call.response.header("Cache-Control", "no-cache")
        if (file.extension == "webmanifest") {
            call.respondBytes(file.readBytes(), MANIFEST)
            return@get
        }
        call.respondFile(file)
    }
}
