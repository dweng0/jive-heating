package heating

import heating.testing.TestHeating
import heating.testing.anonymous
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import java.nio.file.Files
import kotlin.test.Test
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
}
