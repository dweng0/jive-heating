package heating.api

import heating.Actor
import heating.ApiException
import heating.HeatingApp
import heating.actionLog
import heating.auth.Caller
import heating.rules.Rule
import heating.rules.RuleRequest
import heating.untrusted
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put

/** Notification rules. Registered inside [adminOnly]. */
fun Route.ruleRoutes(heating: HeatingApp) {
    val rules = heating.rules

    suspend fun io.ktor.server.application.ApplicationCall.request(): RuleRequest =
        runCatching { receive<RuleRequest>() }.getOrElse { throw ApiException.badRequest("the rule is not valid JSON of the expected shape") }

    get("/api/rules") {
        call.respond(rules.list())
    }

    post("/api/rules") {
        val request = call.request()
        val caller = call.principal<Caller>()!!
        val owner = caller.userId ?: throw ApiException.badRequest("a rule belongs to a signed-in admin")
        val rule = call.acting {
            rules.create(request, owner, caller.username).also { actionLog.info("rule {} added{}", untrusted(it.name), Actor.by()) }
        }
        call.respond(HttpStatusCode.Created, rule)
    }

    get("/api/rules/{id}") {
        call.respond(rules.get(call.parameters["id"]!!))
    }

    put("/api/rules/{id}") {
        val id = call.parameters["id"]!!
        rules.get(id)
        val request = call.request()
        call.respond(call.acting { rules.replace(id, request).also { actionLog.info("rule {} changed{}", untrusted(it.name), Actor.by()) } })
    }

    get("/api/rules/{id}/history") {
        val limit = call.request.queryParameters["limit"]?.toIntOrNull()?.coerceIn(1, 1000) ?: 100
        call.respond(rules.historyOf(call.parameters["id"]!!, limit))
    }

    delete("/api/rules/{id}") {
        val id = call.parameters["id"]!!
        call.acting { rules.delete(id).also { actionLog.info("rule {} deleted{}", untrusted(it.name), Actor.by()) } }
        call.respond(HttpStatusCode.NoContent)
    }
}
