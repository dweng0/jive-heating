package heating.web

import heating.testing.TestHeating
import heating.testing.anonymous
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The web app as it ships: web/index.html plus everything in web/public, as the build lays them out. */
fun shippedWebDir(): Path {
    val dir = Files.createTempDirectory("web")
    File("web/public").copyRecursively(dir.toFile())
    File("web/index.html").copyTo(dir.resolve("index.html").toFile())
    return dir
}

/** Width and height from a PNG's IHDR chunk; fails if [bytes] is not a PNG. */
fun pngSize(bytes: ByteArray): Pair<Int, Int> {
    val signature = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 0x0D, 0x0A, 0x1A, 0x0A)
    assertTrue(bytes.size > 24 && bytes.copyOfRange(0, 8).contentEquals(signature), "not a PNG")
    val header = ByteBuffer.wrap(bytes, 16, 8)
    return header.int to header.int
}

class InstallableWebAppTest {

    // BDD: The web app can be installed to the home screen
    @Test
    fun `the web app can be installed to the home screen`() {
        TestHeating(webDir = shippedWebDir().toString()).api {
            val manifest = anonymous.get("/manifest.webmanifest")
            assertEquals(HttpStatusCode.OK, manifest.status)
            assertEquals("application/manifest+json", manifest.headers[HttpHeaders.ContentType]!!.substringBefore(';'))
            val json = Json.parseToJsonElement(manifest.bodyAsText()).jsonObject
            assertEquals("Heating", json["name"]!!.jsonPrimitive.content)
            assertEquals("/", json["start_url"]!!.jsonPrimitive.content)
            assertEquals("standalone", json["display"]!!.jsonPrimitive.content)
            val icons = json["icons"]!!.jsonArray.map { it.jsonObject }
            for (size in listOf(192, 512)) {
                val icon = icons.first { it["sizes"]!!.jsonPrimitive.content == "${size}x$size" }
                assertEquals("image/png", icon["type"]!!.jsonPrimitive.content)
                val png = anonymous.get(icon["src"]!!.jsonPrimitive.content)
                assertEquals(HttpStatusCode.OK, png.status)
                assertEquals(size to size, pngSize(png.readRawBytes()))
            }

            val page = anonymous.get("/")
            val html = page.bodyAsText()
            assertTrue(html.contains("""<link rel="manifest" href="/manifest.webmanifest""""), html)
            val touch = assertNotNull(Regex("""<link rel="apple-touch-icon" sizes="180x180" href="([^"]+)"""").find(html), html)
            assertEquals(180 to 180, pngSize(anonymous.get(touch.groupValues[1]).readRawBytes()))
            assertTrue(html.contains("""<meta name="theme-color""""), html)
            assertTrue(page.headers["Content-Security-Policy"]!!.startsWith("default-src 'self'"))
            assertTrue(manifest.headers["Content-Security-Policy"]!!.startsWith("default-src 'self'"))
        }
    }

    // BDD: The service worker is served from the root and never cached stale
    @Test
    fun `the service worker is served from the root and never cached stale`() {
        TestHeating(webDir = shippedWebDir().toString()).api {
            val sw = anonymous.get("/sw.js")
            assertEquals(HttpStatusCode.OK, sw.status)
            assertTrue(sw.headers[HttpHeaders.ContentType]!!.contains("javascript"), sw.headers[HttpHeaders.ContentType])
            assertEquals("no-cache", sw.headers[HttpHeaders.CacheControl])
            // Registration with scope "/" and keeping /api out of the cache are checked in a browser by web/e2e/installable.spec.ts.
            val main = File("web/src/main.tsx").readText() + File("web/src/lib/serviceWorker.ts").readText()
            assertTrue(Regex("""register\('/sw\.js', \{ scope: '/'[,}]""").containsMatchIn(main), "registered with scope /")
        }
    }

    // BDD: An installed app with no connection says so
    @Test
    fun `an installed app with no connection says so`() {
        TestHeating(webDir = shippedWebDir().toString()).api {
            val sw = anonymous.get("/sw.js").bodyAsText()
            assertTrue(sw.contains("/offline.html"), "the service worker keeps the offline page")
            val offline = anonymous.get("/offline.html")
            assertEquals(HttpStatusCode.OK, offline.status)
            val html = offline.bodyAsText()
            assertTrue(html.contains("Can&rsquo;t reach the heating") || html.contains("Can't reach the heating"), html)
            assertTrue(html.contains(">Try again</button>"), html)
            // The page script loads from our origin: the CSP allows no inline script.
            assertTrue(!html.contains("<script>") && html.contains("""<script src="/offline.js""""), html)
            assertEquals(HttpStatusCode.OK, anonymous.get("/offline.js").status)
        }
    }
}
