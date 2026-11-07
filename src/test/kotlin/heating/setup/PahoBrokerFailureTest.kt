package heating.setup

import kotlin.test.Test
import kotlin.test.assertEquals

class PahoBrokerFailureTest {

    private fun reason(host: String, port: Int, tls: Boolean = false): BrokerFailure =
        try {
            PahoBrokerFactory(timeoutSeconds = 2).open(BrokerSettings(host, port, tls), { _, _, _ -> }, {}).close()
            error("expected a failure")
        } catch (e: BrokerFailure) {
            e
        }

    // BDD: A broker test explains why it failed
    @Test
    fun `real connection failures are told apart`() {
        assertEquals("unknown_host", reason("mosquito.invalid", 1883).reason)
        assertEquals("Can't find \"mosquito.invalid\". Check the host name.", reason("mosquito.invalid", 1883).message)
        assertEquals("refused", reason("127.0.0.1", 1).reason)
        assertEquals("Nothing answered on 127.0.0.1:1.", reason("127.0.0.1", 1).message)
    }
}
