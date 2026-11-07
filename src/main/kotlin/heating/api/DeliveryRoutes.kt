package heating.api

import heating.Actor
import heating.ApiException
import heating.HeatingApp
import heating.notify.Delivery
import heating.notify.DeliveryAttempt
import heating.notify.DeliveryEvent
import heating.notify.DeliveryQuery
import heating.notify.DeliveryStatus
import heating.notify.Deliveries
import heating.actionLog
import io.ktor.http.HttpStatusCode
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import java.time.Instant
import kotlinx.serialization.Serializable

/** One delivery as admins see it. [event] is what caused it, or null (heating alerts): then [source] says what did. */
@Serializable
data class DeliveryView(
    val id: String,
    val at: String,
    val source: String,
    val ruleId: String? = null,
    val userId: String,
    val person: String,
    val title: String,
    val body: String,
    val status: String,
    val tries: Int,
    val sentAt: String? = null,
    val sentTo: List<String> = emptyList(),
    val attempts: List<DeliveryAttempt> = emptyList(),
    val nextAttemptAt: String? = null,
    val event: DeliveryEvent? = null,
)

@Serializable
data class DeliveryPerson(val id: String, val name: String)

/** The deliveries found, how many are failed in all, and the choices for the Rule and Person filters. */
@Serializable
data class DeliveriesView(
    val deliveries: List<DeliveryView>,
    val failed: Int,
    val sources: List<String>,
    val people: List<DeliveryPerson>,
)

private val SORTS: Map<String, Comparator<DeliveryView>> = mapOf(
    "time" to compareBy { it.at },
    "rule" to compareBy(String.CASE_INSENSITIVE_ORDER) { it.source },
    "person" to compareBy(String.CASE_INSENSITIVE_ORDER) { it.person },
    "status" to compareBy { it.status },
    "tries" to compareBy { it.tries },
)

/** Notification deliveries: searching, and retrying or clearing failed ones. Registered inside [adminOnly]. */
fun Route.deliveryRoutes(heating: HeatingApp) {
    val deliveries = heating.deliveries

    fun view(d: Delivery): DeliveryView {
        val person = heating.users.list().firstOrNull { it.id == d.userId }?.username ?: "(removed)"
        return DeliveryView(
            d.id, Instant.ofEpochMilli(d.createdAt).toString(), d.source, d.ruleId, d.userId, person, d.title, d.body,
            d.status.label, d.tries, d.sentAt?.let { Instant.ofEpochMilli(it).toString() }, d.sentTo, d.attempts,
            d.nextAt.takeIf { d.status == DeliveryStatus.QUEUED || d.status == DeliveryStatus.RETRYING }?.let { Instant.ofEpochMilli(it).toString() },
            d.event,
        )
    }

    get("/api/deliveries") {
        val q = call.request.queryParameters
        val status = q["status"]?.let { s -> DeliveryStatus.entries.firstOrNull { it.label == s } ?: throw ApiException.badRequest("unknown status") }
        val userId = q["person"]?.let { name -> heating.users.list().firstOrNull { it.username == name }?.id ?: "none" }
        val query = DeliveryQuery(
            status, q["rule"], userId,
            q["from"]?.let { runCatching { Instant.parse(it) }.getOrNull() ?: throw ApiException.badRequest("from is not a time") },
            q["to"]?.let { runCatching { Instant.parse(it) }.getOrNull() ?: throw ApiException.badRequest("to is not a time") },
        )
        val sort = q["sort"]?.let { SORTS[it] ?: throw ApiException.badRequest("unknown sort") }
        var found = deliveries.search(query).map(::view)
        if (sort != null) found = found.sortedWith(if (q["dir"] == "asc") sort else sort.reversed())
        val limit = q["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 500
        call.respond(
            DeliveriesView(
                found.take(limit), deliveries.failedCount(), deliveries.sources(),
                heating.users.list().map { DeliveryPerson(it.id, it.username) },
            ),
        )
    }

    get("/api/deliveries/{id}") {
        call.respond(view(deliveries.get(call.parameters["id"]!!) ?: throw ApiException.notFound("delivery not found")))
    }

    post("/api/deliveries/retry-all") {
        val n = call.acting { deliveries.retryAll().also { actionLog.info("retry {} failed notifications{}", it, Actor.by()) } }
        call.respond(mapOf("retried" to n))
    }

    post("/api/deliveries/{id}/retry") {
        val id = call.parameters["id"]!!
        if (!call.acting { deliveries.retry(id).also { if (it) actionLog.info("retry notification {}{}", id, Actor.by()) } }) throw ApiException.notFound("no failed delivery with that id")
        call.respond(view(deliveries.get(id)!!))
    }

    delete("/api/deliveries/{id}") {
        val id = call.parameters["id"]!!
        if (!call.acting { deliveries.clear(id).also { if (it) actionLog.info("clear notification {}{}", id, Actor.by()) } }) throw ApiException.notFound("delivery not found")
        call.respond(HttpStatusCode.NoContent)
    }
}
