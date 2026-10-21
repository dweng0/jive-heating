package heating

import heating.mqtt.PahoMqttBus
import heating.rooms.Mode
import heating.store.SqliteStore
import org.awaitility.Awaitility.await
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.GenericContainer
import org.testcontainers.images.builder.Transferable
import org.testcontainers.utility.DockerImageName
import java.nio.file.Files
import java.time.Clock
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LiveMqttTest {

    // BDD: End to end against a real Mosquitto broker
    @Test
    fun `end to end against a real mosquitto broker`() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker is not available",
        )
        val broker = GenericContainer(DockerImageName.parse("eclipse-mosquitto:2"))
            .withExposedPorts(1883)
            .withCopyToContainer(
                Transferable.of("listener 1883\nallow_anonymous true\n"),
                "/mosquitto/config/mosquitto.conf",
            )
        broker.start()
        try {
            val url = "tcp://${broker.host}:${broker.getMappedPort(1883)}"

            // Independent observer of what actually reaches the broker.
            val received = CopyOnWriteArrayList<String>()
            val observer = MqttClient(url, "observer", MemoryPersistence()).apply { connect() }
            observer.subscribe("zigbee2mqtt/Test TRV/set") { _, message -> received += String(message.payload) }

            val config = Config(
                apiToken = "test-token",
                mqttUrl = url,
                dbPath = Files.createTempFile("heating-live", ".db").toString(),
            )
            val heating = HeatingApp(config, PahoMqttBus(url), SqliteStore(config.dbPath), Clock.systemUTC())
            heating.start()

            val publisher = MqttClient(url, "z2m", MemoryPersistence()).apply { connect() }
            publisher.publish(
                "zigbee2mqtt/bridge/devices",
                """[{"friendly_name":"Test TRV","type":"EndDevice","definition":{"model":"TRVZB"}}]""".toByteArray(),
                1, true,
            )
            await().atMost(Duration.ofSeconds(5)).until { heating.valves["Test TRV"] != null }

            val room = heating.rooms.create("Test")
            heating.rooms.assignValve(room.id, "Test TRV")
            heating.zones.setMode(room.id, Mode.MANUAL, 21.0)

            await().atMost(Duration.ofSeconds(5)).until {
                received.any { it == """{"system_mode":"heat","occupied_heating_setpoint":21.0}""" }
            }
            assertEquals(
                """{"system_mode":"heat","occupied_heating_setpoint":21.0}""",
                received.last { "occupied_heating_setpoint" in it },
            )
            observer.disconnect()
            publisher.disconnect()
        } finally {
            broker.stop()
        }
    }

    // Supporting behaviour (not a BDD scenario): a handler that publishes, triggered by a retained
    // message, must not stall the connection (it did: Paho's callback thread waited for its own ack).
    @Test
    fun `a handler that publishes does not stall the connection`() {
        assumeTrue(
            runCatching { DockerClientFactory.instance().isDockerAvailable }.getOrDefault(false),
            "Docker is not available",
        )
        val broker = GenericContainer(DockerImageName.parse("eclipse-mosquitto:2"))
            .withExposedPorts(1883)
            .withCopyToContainer(
                Transferable.of("listener 1883\nallow_anonymous true\n"),
                "/mosquitto/config/mosquitto.conf",
            )
        broker.start()
        try {
            val url = "tcp://${broker.host}:${broker.getMappedPort(1883)}"
            val publisher = MqttClient(url, "publisher", MemoryPersistence()).apply { connect() }
            publisher.publish("zigbee2mqtt/Smoke", """{"temperature":19.8}""".toByteArray(), 1, true)

            val echoed = java.util.concurrent.CountDownLatch(50)
            val observer = MqttClient(url, "observer", MemoryPersistence()).apply { connect() }
            observer.subscribe("zigbee2mqtt/TRV/set") { _, _ -> echoed.countDown() }

            val bus = PahoMqttBus(url, "under-test")
            // Like a sensor reading passed on to its room's valves: publish from inside the handler.
            bus.subscribe("zigbee2mqtt/Smoke") { _, payload -> bus.publish("zigbee2mqtt/TRV/set", payload) }
            repeat(49) { publisher.publish("zigbee2mqtt/Smoke", """{"temperature":20.$it}""".toByteArray(), 1, false) }

            assertTrue(echoed.await(20, java.util.concurrent.TimeUnit.SECONDS), "only ${50 - echoed.count} of 50 handler publishes arrived")
        } finally {
            broker.stop()
        }
    }
}
