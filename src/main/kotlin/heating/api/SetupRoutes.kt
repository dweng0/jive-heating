package heating.api

import heating.ApiException
import heating.HeatingApp
import heating.activity.displayName
import heating.auth.Caller
import heating.setup.BaseTopicRequest
import heating.setup.BrokerRequest
import heating.setup.BrokerTestFailed
import heating.setup.SetupService
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/** Connection setup: the broker and Zigbee2MQTT. Registered inside [adminOnly]. */
fun Route.setupRoutes(heating: HeatingApp) {
    get("/api/setup/status") {
        val setup = heating.setup
        call.respond(
            setup?.status() ?: buildJsonObject {
                put("broker", "connected")
                put("zigbee2mqtt", "configured")
                put("complete", true)
            },
        )
    }

    fun setup(): SetupService = heating.setup ?: throw ApiException.notFound("connection setup is not available")

    get("/api/setup/broker") {
        call.respond(setup().broker() ?: throw ApiException.notFound("no broker saved"))
    }

    post("/api/setup/broker/test") {
        val request = call.receive<BrokerRequest>()
        val failure = setup().test(request)
        call.respond(
            buildJsonObject {
                put("ok", failure == null)
                if (failure != null) {
                    put("reason", failure.reason)
                    put("message", failure.message)
                }
            },
        )
    }

    put("/api/setup/broker") {
        val request = call.receive<BrokerRequest>()
        try {
            val view = call.acting { setup().saveBroker(request) }
            heating.activity.record("setup", displayName(call.principal<Caller>()?.username ?: "unknown"), null, JsonPrimitive("broker"), adminOnly = true)
            call.respond(view)
        } catch (e: BrokerTestFailed) {
            call.respond(
                HttpStatusCode.UnprocessableEntity,
                buildJsonObject {
                    put("error", e.failure.message)
                    put("reason", e.failure.reason)
                },
            )
        }
    }

    get("/api/setup/zigbee2mqtt/discover") {
        call.respond(setup().discover())
    }

    put("/api/setup/zigbee2mqtt") {
        val request = call.receive<BaseTopicRequest>()
        val base = call.acting { setup().saveBaseTopic(request) }
        heating.activity.record("setup", displayName(call.principal<Caller>()?.username ?: "unknown"), null, JsonPrimitive("zigbee2mqtt"), adminOnly = true)
        call.respond(buildJsonObject { put("baseTopic", base) })
    }

    post("/api/setup/test-notification") {
        val userId = call.principal<Caller>()?.userId ?: throw ApiException.badRequest("a notification goes to a signed-in admin")
        call.respond(setup().sendTestNotification(userId))
    }

    post("/api/setup/complete") {
        call.acting { setup().complete() }
        call.respond(HttpStatusCode.NoContent)
    }
}
