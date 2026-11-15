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
        override suspend fun downloadApk(to: File, progress: (Long, Long?) -> Unit) {
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
}
