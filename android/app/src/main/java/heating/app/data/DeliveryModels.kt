package heating.app.data

import kotlinx.serialization.Serializable

/** openapi: DeliveryAttempt. One try of a delivery: when, and why it failed. */
@Serializable
data class DeliveryAttempt(val at: String, val error: String)

/** openapi: Delivery. One notification for one person; [status] is queued, retrying, sent or failed. */
@Serializable
data class Delivery(
    val id: String,
    val at: String,
    val source: String,
    val person: String,
    val title: String,
    val body: String,
    val status: String,
    val tries: Int = 0,
    val sentAt: String? = null,
    val sentTo: List<String> = emptyList(),
    val attempts: List<DeliveryAttempt> = emptyList(),
)

/** openapi: Deliveries. The deliveries found, and how many are failed in all. */
@Serializable
data class DeliveriesList(val deliveries: List<Delivery> = emptyList(), val failed: Int = 0)

@Serializable
data class RetriedDeliveries(val retried: Int)
