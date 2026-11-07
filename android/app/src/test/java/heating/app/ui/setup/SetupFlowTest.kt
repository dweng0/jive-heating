package heating.app.ui.setup

import heating.app.data.BridgeFound
import heating.app.data.BrokerTestResult
import heating.app.data.FakeHeatingApi
import heating.app.data.SetupStatus
import kotlinx.coroutines.test.runTest
import java.time.ZoneId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SetupFlowTest {
    // BDD: A new install with no broker asks for connection setup
    @Test
    fun `a new install with no broker needs setup`() = runTest {
        val api = FakeHeatingApi()
        val status = api.setupStatus()
        assertEquals(SetupStatus("missing", "missing", complete = false), status)
        assertTrue(!status.complete)
    }

    // BDD: A broker test explains why it failed
    @Test
    fun `a failed broker test points at its field and keeps what was typed`() = runTest {
        val api = FakeHeatingApi()
        val form = BrokerForm(host = "mosquito", username = "heating", password = "secret")
        api.brokerTest = BrokerTestResult(false, "unknown_host", "Can't find \"mosquito\". Check the host name.")
        assertEquals(BrokerCheck.Failed(BrokerField.Host, "Can't find \"mosquito\". Check the host name."), api.checkBroker(form))
        api.brokerTest = BrokerTestResult(false, "refused", "Nothing answered on mosquitto:1884.")
        assertEquals(BrokerField.Port, (api.checkBroker(form.copy(host = "mosquitto", port = "1884")) as BrokerCheck.Failed).field)
        api.brokerTest = BrokerTestResult(false, "timeout", "mosquitto:1883 didn't answer within 5 seconds.")
        assertEquals(BrokerField.Host, (api.checkBroker(form) as BrokerCheck.Failed).field)
        api.brokerTest = BrokerTestResult(false, "not_authorized", "The broker turned down that username and password.")
        assertEquals(BrokerField.Password, (api.checkBroker(form) as BrokerCheck.Failed).field)
        api.brokerTest = BrokerTestResult(false, "tls", "Couldn't make a secure connection: handshake failed.")
        assertEquals(BrokerField.Tls, (api.checkBroker(form) as BrokerCheck.Failed).field)

        // Host must be filled and port 1 to 65535: nothing is tried otherwise.
        val tried = api.calls.size
        assertEquals(BrokerField.Host, (api.checkBroker(BrokerForm(host = " ")) as BrokerCheck.Failed).field)
        assertEquals(BrokerField.Port, (api.checkBroker(BrokerForm(host = "mosquitto", port = "0")) as BrokerCheck.Failed).field)
        assertEquals(BrokerField.Port, (api.checkBroker(BrokerForm(host = "mosquitto", port = "65536")) as BrokerCheck.Failed).field)
        assertEquals(tried, api.calls.size)
    }

    // BDD: An admin tests a broker before saving it
    @Test
    fun `a good broker test sends the typed details and saves nothing`() = runTest {
        val api = FakeHeatingApi()
        assertEquals(BrokerCheck.Ok, api.checkBroker(BrokerForm(host = "mosquitto", username = "heating", password = "secret")))
        assertEquals(listOf("testBroker mosquitto:1883 tls=false user=heating pw=secret"), api.calls)
    }

    // BDD: Broker passwords are never sent back
    @Test
    fun `an untouched password is left out and removing it sends an empty one`() {
        assertEquals(null, BrokerForm(host = "h", username = "u").toRequest().password)
        assertEquals("", BrokerForm(host = "h", username = "u", clearPassword = true).toRequest().password)
        assertEquals("pw", BrokerForm(host = "h", username = "u", password = "pw").toRequest().password)
    }

    // BDD: Zigbee2MQTT is found on the broker
    @Test
    fun `a found bridge is offered with its version and device count`() {
        assertEquals("z2m (version 2.1.0, 14 devices)", bridgeLabel(BridgeFound("z2m", "2.1.0", 14)))
        assertEquals("attic (1 device)", bridgeLabel(BridgeFound("attic", null, 1)))
    }

    // BDD: Zigbee2MQTT is not found on the broker
    @Test
    fun `nothing found says what to check`() {
        assertEquals("No Zigbee2MQTT found on this broker. Check it's running and connected to the same broker, or type its base topic.", NO_BRIDGE_TEXT)
    }

    // BDD: Setup ends with a test notification
    @Test
    fun `finish marks setup complete`() = runTest {
        val api = FakeHeatingApi()
        assertTrue(api.sendTestNotification().registered)
        api.notificationRegistered = false
        assertTrue(!api.sendTestNotification().registered)
        api.completeSetup()
        assertTrue(api.setupStatus().complete)
    }

    // BDD: The broker connection drops after setup
    @Test
    fun `a lost broker is worded with the time it dropped`() {
        assertEquals("Lost connection to your broker since 18:04", lostText("2026-11-07T18:04:00Z", ZoneId.of("UTC")))
        assertEquals("Lost connection to your broker since 18:04", lostText("2026-11-07T17:04:00Z", ZoneId.of("Europe/Paris")))
        assertEquals("Lost connection to your broker", lostText(null))
    }
}
