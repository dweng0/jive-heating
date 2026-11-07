package heating.app.ui.account

import heating.app.data.Delivery
import heating.app.data.DeliveryAttempt
import heating.app.data.FakeHeatingApi
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class FailedDeliveriesTest {
    private fun failed(id: String, person: String) = Delivery(
        id, "2026-11-07T08:00:00Z", "Front door opened", person, "Front door opened", "Opened", "failed", 4,
        attempts = listOf(DeliveryAttempt("2026-11-07T08:01:00Z", "phone: push service unreachable or refusing")),
    )

    // BDD: A delivery that keeps failing goes to failed deliveries
    @Test
    fun `a delivery that keeps failing goes to failed deliveries`() = runTest {
        assertEquals("1 failed notification", failedNotifications(1))
        assertEquals("2 failed notifications", failedNotifications(2))
        assertEquals("phone: push service unreachable or refusing", lastFailure(failed("d1", "jane")))

        val api = FakeHeatingApi()
        api.deliveryList += listOf(failed("d1", "jane"), failed("d2", "sam"))
        assertEquals(2, api.deliveries("failed").failed)

        api.retryDelivery("d1")
        assertEquals(listOf("d2"), api.deliveries("failed").deliveries.map { it.id })
        api.clearDelivery("d2")
        assertEquals(0, api.deliveries("failed").failed)

        api.deliveryList += failed("d3", "jane")
        assertEquals(1, api.retryAllDeliveries())
        assertEquals(0, api.deliveries("failed").failed)
    }
}
