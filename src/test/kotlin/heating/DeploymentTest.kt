package heating

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DeploymentTest {
    private val dockerfile get() = File("Dockerfile").readLines().map { it.trim() }

    // BDD: Docker image builds and runs the service
    @Test
    fun `dockerfile runs the service on port 8080 as a non-root user`() {
        val lines = dockerfile
        val froms = lines.filter { it.startsWith("FROM ") }
        assertTrue(froms.size >= 2, "multi-stage build expected")
        assertTrue(froms.any { it.contains("17") && it.contains("jdk") }, "build stage uses JDK 17")
        assertTrue(froms.last().contains("17") && froms.last().contains("jre"), "runtime stage uses a JRE 17")
        assertTrue(lines.any { it.contains("gradlew installDist") })
        assertTrue("EXPOSE 8080" in lines)
        val user = lines.last { it.startsWith("USER ") }.removePrefix("USER ").trim()
        assertFalse(user == "root" || user == "0", "must not run as root")
        assertTrue(lines.indexOf(lines.last { it.startsWith("USER ") }) > lines.indexOfLast { it.startsWith("FROM ") })
        assertTrue(lines.any { it.startsWith("ENTRYPOINT") || it.startsWith("CMD") })
    }

    // BDD: Example environment file documents every variable
    @Test
    fun `env example lists every variable with placeholders only`() {
        val text = File(".env.example").readText()
        val keys = text.lines().filter { "=" in it && !it.trimStart().startsWith("#") }
            .associate { it.substringBefore('=').trim() to it.substringAfter('=').trim() }
        for (name in listOf("HEATING_API_TOKEN", "MQTT_URL", "Z2M_BASE_TOPIC", "HEATING_TIMEZONE", "HEATING_DB_PATH")) {
            assertTrue(name in keys, "$name missing")
        }
        assertEquals("change-me", keys["HEATING_API_TOKEN"])
        assertEquals("tcp://mosquitto:1883", keys["MQTT_URL"])
        assertEquals("zigbee2mqtt", keys["Z2M_BASE_TOPIC"])
        assertEquals("Europe/London", keys["HEATING_TIMEZONE"])
        assertEquals("/data/heating.db", keys["HEATING_DB_PATH"])
        assertFalse(Regex("""\b\d{1,3}\.\d{1,3}\.\d{1,3}\.\d{1,3}\b""").containsMatchIn(text), "no IP addresses")
    }
}
