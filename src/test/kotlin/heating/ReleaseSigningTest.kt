package heating

import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** The Android release build's signing, checked by running the Android build itself (needs the Android SDK). */
class ReleaseSigningTest {

    private val android = File("android").absoluteFile

    private fun gradle(env: Map<String, String?>, vararg args: String): Pair<Int, String> {
        val pb = ProcessBuilder(listOf(File(android, "gradlew").path, "-p", android.path, "--console=plain", "-PnoPush") + args)
            .redirectErrorStream(true)
        for ((k, v) in env) if (v == null) pb.environment().remove(k) else pb.environment()[k] = v
        val p = pb.start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor(10, TimeUnit.MINUTES)
        return p.exitValue() to out
    }

    private val settings = listOf("HEATING_RELEASE_KEYSTORE", "HEATING_RELEASE_STORE_PASSWORD", "HEATING_RELEASE_KEY_ALIAS", "HEATING_RELEASE_KEY_PASSWORD")

    // BDD: Release builds are signed with the stable key
    @Test
    fun `release builds are signed with the stable key`() {
        assumeTrue(File(android, "local.properties").isFile, "no Android SDK here")

        // Without the key the release build fails, naming what is missing.
        val (code, out) = gradle(settings.associateWith { null }, ":app:assembleRelease", "--dry-run")
        assertNotEquals(0, code, out)
        assertTrue(settings.all { it in out } && "not set" in out, out)

        // With it, the release variant is signed with that key, not the debug one.
        val dir = Files.createTempDirectory("release-key").toFile()
        val store = File(dir, "test.keystore")
        val keytool = ProcessBuilder(
            "keytool", "-genkeypair", "-keystore", store.path, "-storepass", "secret1", "-keypass", "secret1", "-alias", "jive",
            "-keyalg", "RSA", "-keysize", "2048", "-validity", "1", "-dname", "CN=Test",
        ).redirectErrorStream(true).start()
        keytool.waitFor(1, TimeUnit.MINUTES)
        assertTrue(store.isFile, keytool.inputStream.bufferedReader().readText())
        val (code2, report) = gradle(
            mapOf("HEATING_RELEASE_KEYSTORE" to store.path, "HEATING_RELEASE_STORE_PASSWORD" to "secret1",
                "HEATING_RELEASE_KEY_ALIAS" to "jive", "HEATING_RELEASE_KEY_PASSWORD" to "secret1"),
            ":app:signingReport",
        )
        assertTrue(code2 == 0, report)
        val release = report.substringAfter("Variant: release").substringBefore("Variant:")
        assertTrue(store.path in release && "Alias: jive" in release, release)
    }
}
