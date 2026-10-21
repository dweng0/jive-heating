package heating.mqtt

import heating.Config
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class MqttLoginTest {
    private fun env(vararg pairs: Pair<String, String>) = mapOf("HEATING_API_TOKEN" to "x") + pairs

    // BDD: Broker login comes from the environment
    @Test
    fun `broker login comes from the environment`() {
        val config = Config.fromEnv(env("MQTT_USERNAME" to "heating", "MQTT_PASSWORD" to "broker-pass"))
        val options = PahoMqttBus.connectOptions(config.mqttUsername, config.mqttPassword)
        assertEquals("heating", options.userName)
        assertContentEquals("broker-pass".toCharArray(), options.password)
    }

    // BDD: Broker connection is anonymous when no login is set
    @Test
    fun `broker connection is anonymous when no login is set`() {
        val config = Config.fromEnv(env("MQTT_USERNAME" to " ", "MQTT_PASSWORD" to ""))
        assertNull(config.mqttUsername)
        assertNull(config.mqttPassword)
        val options = PahoMqttBus.connectOptions(config.mqttUsername, config.mqttPassword)
        assertNull(options.userName)
        assertNull(options.password)
        assertNull(Config.fromEnv(env()).mqttUsername)
    }

    // BDD: Service refuses a broker password without a username
    @Test
    fun `service refuses a broker password without a username`() {
        val error = assertFailsWith<IllegalStateException> { Config.fromEnv(env("MQTT_PASSWORD" to "broker-pass")) }
        assertEquals("MQTT_PASSWORD needs MQTT_USERNAME", error.message)
    }

    @Test
    fun `a username alone is allowed for brokers that need no password`() {
        val config = Config.fromEnv(env("MQTT_USERNAME" to "heating"))
        val options = PahoMqttBus.connectOptions(config.mqttUsername, config.mqttPassword)
        assertEquals("heating", options.userName)
        assertNull(options.password)
    }
}
