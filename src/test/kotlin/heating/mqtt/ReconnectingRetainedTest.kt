package heating.mqtt

import heating.setup.BrokerSettings
import heating.testing.FakeBrokers
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals

class ReconnectingRetainedTest {
    @Test
    fun `retained flag reaches subscribers through the reconnecting bus`() {
        val brokers = FakeBrokers()
        val broker = brokers.broker("mosquitto")
        broker.receive("z2m/front_door", """{"contact":true}""", retained = true)
        val bus = ReconnectingMqttBus(brokers) { Instant.EPOCH }
        val seen = mutableListOf<Pair<String, Boolean>>()
        bus.subscribeWithRetained("#") { t, _, r -> seen += t to r }
        bus.start(BrokerSettings("mosquitto", 1883, false, null, null))
        broker.receive("z2m/front_door", """{"contact":false}""")
        assertEquals(listOf("z2m/front_door" to true, "z2m/front_door" to false), seen)
    }
}
