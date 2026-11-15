package heating.app.update

import heating.app.data.KtorHeatingApi
import heating.app.data.ServerConfig
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppUpdaterTest {

    private class FakeSource(var info: AndroidAppInfo?, var failDownload: Boolean = false) : UpdateSource {
        var checks = 0
        override suspend fun appInfo(): AndroidAppInfo? { checks++; return info }
        override suspend fun downloadApk(to: File, from: Long, progress: (Long, Long?) -> Unit) {
            if (failDownload) throw IOException("connection reset")
            progress(0, 4); progress(2, 4)
            to.writeBytes(byteArrayOf(1, 2, 3, 4)); progress(4, 4)
        }
    }

    private class FakeInstaller(var allowed: Boolean = true) : Installer {
        val installed = mutableListOf<File>()
        var askedPermission = 0
        override fun canInstall() = allowed
        override fun requestPermission() { askedPermission++ }
        override fun install(apk: File) { installed += apk }
    }

    private class Clock(var now: Long = 1_000_000_000L)
    private class Prefs : LastCheck { override var at: Long = 0 }

    private fun updater(source: UpdateSource, installer: Installer = FakeInstaller(), clock: Clock = Clock(), installed: Int = 41) =
        AppUpdater(installed, source, installer, Files.createTempDirectory("updates").toFile(), { clock.now }, Prefs())

    // BDD: The app offers a newer version
    @Test
    fun `the app offers a newer version`() = runTest {
        val clock = Clock()
        val source = FakeSource(AndroidAppInfo(size = 4, versionCode = 42, versionName = "0.9.0"))
        val u = updater(source, clock = clock)
        u.check()
        assertEquals(UpdateState.Available("0.9.0"), u.state.value)
        assertEquals("Version 0.9.0 is ready", updateBanner(u.state.value))

        // At most once an hour, opening the app or Account again does not ask the service.
        clock.now += 59 * 60_000L; u.check(); assertEquals(1, source.checks)
        clock.now += 2 * 60_000L; u.check(); assertEquals(2, source.checks)

        for (code in listOf(41, 40)) {
            val same = updater(FakeSource(AndroidAppInfo(size = 4, versionCode = code, versionName = "0.8.0")))
            same.check()
            assertEquals(UpdateState.UpToDate, same.state.value)
            assertNull(updateBanner(same.state.value))
            assertEquals("Up to date", accountUpdateLine(same.state.value))
        }
        // No version on the service (no output-metadata.json, or no APK): nothing offered.
        val unknown = updater(FakeSource(AndroidAppInfo(size = 4)))
        unknown.check()
        assertNull(updateBanner(unknown.state.value))

        // The app reads the version from GET /api/app.
        val engine = MockEngine { request ->
            assertEquals("/api/app", request.url.encodedPath)
            respond("""{"android":{"size":4,"builtAt":"2026-11-15T08:00:00Z","versionCode":42,"versionName":"0.9.0"}}""",
                HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
        }
        assertEquals(AndroidAppInfo(4, 42, "0.9.0"), KtorHeatingApi(ServerConfig("https://heating.example", "t"), engine).appInfo())
    }

    // BDD: Updating downloads and installs the new version
    @Test
    fun `updating downloads and installs the new version`() = runTest {
        val source = FakeSource(AndroidAppInfo(size = 4, versionCode = 42, versionName = "0.9.0"))
        val installer = FakeInstaller()
        val u = updater(source, installer)
        val seen = mutableListOf<UpdateState>()
        u.check(); u.update { seen += it }
        assertTrue(seen.contains(UpdateState.Downloading("0.9.0", 0.5f)), "$seen")
        assertEquals(1, installer.installed.size)
        assertEquals(listOf<Byte>(1, 2, 3, 4), installer.installed[0].readBytes().toList())
        assertEquals(UpdateState.Installing("0.9.0"), u.state.value)

        // Not yet allowed to install apps: open the setting, then carry on once allowed.
        val blocked = FakeInstaller(allowed = false)
        val u2 = updater(source, blocked)
        u2.check(); u2.update()
        assertEquals(1, blocked.askedPermission)
        assertEquals(UpdateState.NeedsPermission("0.9.0"), u2.state.value)
        assertTrue(blocked.installed.isEmpty())
        blocked.allowed = true
        u2.resumed()
        assertEquals(1, blocked.installed.size)

        // A failed download says so and can be tried again; the installed app is untouched.
        val flaky = FakeSource(AndroidAppInfo(size = 4, versionCode = 42, versionName = "0.9.0"), failDownload = true)
        val inst3 = FakeInstaller()
        val u3 = updater(flaky, inst3)
        u3.check(); u3.update()
        assertEquals(UpdateState.Failed("0.9.0"), u3.state.value)
        assertEquals("Update failed", updateBanner(u3.state.value))
        assertTrue(inst3.installed.isEmpty())
        flaky.failDownload = false
        u3.update()
        assertEquals(1, inst3.installed.size)
    }

    /** An 8-byte APK whose connection drops after 3 bytes, [drops] times, then holds. */
    private class DroppingSource(var drops: Int) : UpdateSource {
        val apk = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8)
        val froms = mutableListOf<Long>()
        override suspend fun appInfo() = AndroidAppInfo(size = 8, versionCode = 42, versionName = "0.9.0")
        override suspend fun downloadApk(to: File, from: Long, progress: (Long, Long?) -> Unit) {
            froms += from
            assertEquals(from, if (to.exists()) to.length() else 0L, "asks from where the file got to")
            val end = if (drops > 0) minOf(apk.size, from.toInt() + 3) else apk.size
            to.appendBytes(apk.copyOfRange(from.toInt(), end)); progress(end.toLong(), 8)
            if (drops > 0) { drops--; throw IOException("stream canceled") }
        }
    }

    // BDD: An interrupted update download carries on where it stopped
    @Test
    fun `an interrupted update download carries on where it stopped`() = runTest {
        val source = DroppingSource(drops = 2)
        val installer = FakeInstaller()
        val u = updater(source, installer)
        u.check(); u.update()
        assertEquals(listOf(0L, 3L, 6L), source.froms, "each try asks for just the rest")
        assertEquals((1..8).map { it.toByte() }, installer.installed.single().readBytes().toList())

        // Five drops in a row: it gives up and says so, keeping the installed app.
        val hopeless = DroppingSource(drops = 99)
        val inst2 = FakeInstaller()
        val u2 = updater(hopeless, inst2)
        u2.check(); u2.update()
        assertEquals(5, hopeless.froms.size)
        assertEquals(UpdateState.Failed("0.9.0"), u2.state.value)
        assertTrue(inst2.installed.isEmpty())

        // The app asks the service for the rest with a Range header and adds the 206's bytes to what it has.
        val ranged = MockEngine { request ->
            assertEquals("bytes=2-", request.headers[HttpHeaders.Range])
            respond(byteArrayOf(3, 4), HttpStatusCode.PartialContent, headersOf(HttpHeaders.ContentRange, "bytes 2-3/4"))
        }
        val part = Files.createTempFile("apk", ".part").toFile().apply { writeBytes(byteArrayOf(1, 2)) }
        var last: Pair<Long, Long?>? = null
        KtorHeatingApi(ServerConfig("https://heating.example", "t"), ranged).downloadApk(part, 2) { d, t -> last = d to t }
        assertEquals(listOf<Byte>(1, 2, 3, 4), part.readBytes().toList())
        assertEquals(4L to 4L, last)

        // A service that ignores Range sends the whole APK: it replaces the part.
        val whole = MockEngine { respond(byteArrayOf(9, 8, 7, 6), HttpStatusCode.OK, headersOf(HttpHeaders.ContentLength, "4")) }
        KtorHeatingApi(ServerConfig("https://heating.example", "t"), whole).downloadApk(part, 4) { _, _ -> }
        assertEquals(listOf<Byte>(9, 8, 7, 6), part.readBytes().toList())
    }
}
