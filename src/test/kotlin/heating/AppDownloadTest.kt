package heating

import heating.testing.TestHeating
import heating.testing.anonymous
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AppDownloadTest {

    // BDD: Signed-in people can download the Android app
    @Test
    fun `signed-in people can download the android app`() {
        val apk = Files.createTempFile("heating", ".apk")
        Files.write(apk, ByteArray(1234) { it.toByte() })
        TestHeating(androidApk = apk.toString()).api { client ->
            val info = client.get("/api/app").bodyAsText()
            assertTrue(info.contains("\"size\":1234"), info)

            val download = client.get("/api/app/android")
            assertEquals(HttpStatusCode.OK, download.status)
            assertEquals("application/vnd.android.package-archive", download.headers[HttpHeaders.ContentType])
            assertTrue(download.headers[HttpHeaders.ContentDisposition]!!.contains("heating.apk"))
            assertEquals(1234, download.readRawBytes().size)
        }
    }

    // BDD: The Android app is not served to strangers or when missing
    @Test
    fun `the android app is not served to strangers or when missing`() {
        val apk = Files.createTempFile("heating", ".apk")
        TestHeating(androidApk = apk.toString()).api {
            assertEquals(HttpStatusCode.Unauthorized, anonymous.get("/api/app/android").status)
        }
        TestHeating().api { client ->
            assertEquals("""{"android":null}""", client.get("/api/app").bodyAsText())
            assertEquals(HttpStatusCode.NotFound, client.get("/api/app/android").status)
        }
    }

    // BDD: The service says which app version it holds
    @Test
    fun `the service says which app version it holds`() {
        val dir = Files.createTempDirectory("android")
        val apk = dir.resolve("heating.apk")
        Files.write(apk, ByteArray(1234))
        Files.writeString(
            dir.resolve("output-metadata.json"),
            """{"version":3,"applicationId":"heating.app","variantName":"release","elements":[{"type":"SINGLE","filters":[],"attributes":[],"versionCode":42,"versionName":"0.9.0","outputFile":"app-release.apk"}],"elementType":"File"}""",
        )
        TestHeating(androidApk = apk.toString()).api { client ->
            val info = client.get("/api/app").bodyAsText()
            assertTrue(info.contains("\"size\":1234"), info)
            assertTrue(info.contains("\"versionCode\":42"), info)
            assertTrue(info.contains("\"versionName\":\"0.9.0\""), info)
        }
        Files.delete(dir.resolve("output-metadata.json"))
        TestHeating(androidApk = apk.toString()).api { client ->
            val info = client.get("/api/app").bodyAsText()
            assertTrue(info.contains("\"versionCode\":null") && info.contains("\"versionName\":null"), info)
        }
    }

    // BDD: An interrupted update download carries on where it stopped
    @Test
    fun `the service sends just the rest of the apk when asked for a range`() {
        val apk = Files.createTempFile("heating", ".apk")
        val bytes = ByteArray(1234) { (it * 7).toByte() }
        Files.write(apk, bytes)
        TestHeating(androidApk = apk.toString()).api { client ->
            val rest = client.get("/api/app/android") { header(HttpHeaders.Range, "bytes=1000-") }
            assertEquals(HttpStatusCode.PartialContent, rest.status)
            assertEquals("bytes 1000-1233/1234", rest.headers[HttpHeaders.ContentRange])
            assertEquals("234", rest.headers[HttpHeaders.ContentLength])
            assertContentEquals(bytes.copyOfRange(1000, 1234), rest.readRawBytes())

            val middle = client.get("/api/app/android") { header(HttpHeaders.Range, "bytes=10-19") }
            assertEquals(HttpStatusCode.PartialContent, middle.status)
            assertContentEquals(bytes.copyOfRange(10, 20), middle.readRawBytes())

            val past = client.get("/api/app/android") { header(HttpHeaders.Range, "bytes=5000-") }
            assertEquals(HttpStatusCode.RequestedRangeNotSatisfiable, past.status)
            assertEquals("bytes */1234", past.headers[HttpHeaders.ContentRange])

            assertEquals("bytes", client.get("/api/app/android").headers[HttpHeaders.AcceptRanges])
        }
    }
}
