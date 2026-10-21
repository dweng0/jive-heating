package heating.api

import heating.ApiException
import heating.auth.Caller
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.RouteScopedPlugin
import io.ktor.server.application.createRouteScopedPlugin
import io.ktor.server.auth.AuthenticationChecked
import io.ktor.server.auth.principal
import io.ktor.server.routing.Route
import io.ktor.server.routing.RouteSelector
import io.ktor.server.routing.RouteSelectorEvaluation
import io.ktor.server.routing.RoutingResolveContext

/*
 * Who may use which /api route. Every signed-in route is registered in [heatingModule] inside one of:
 * - nothing extra: anyone signed in (their own account, the app download, the room list, which
 *   [roomRoutes] filters to the rooms they can see);
 * - [roomScoped]: routes on one room (`{id}`) or one zone (`{zoneId}`, "roomId" or "roomId:valve").
 *   A room the caller cannot see answers 404, exactly as a room that does not exist;
 * - [adminOnly]: the whole house and fitting out rooms. Members get 403. Inside [roomScoped] the
 *   404 comes first, so a member learns nothing about rooms they cannot see.
 * Admins and the service token pass every check.
 */

/** Routes on one room or zone: a room the caller cannot see answers 404. */
fun Route.roomScoped(build: Route.() -> Unit): Route = guarded(RoomScope, build)

/** Routes only admins may use: everyone else gets 403. */
fun Route.adminOnly(build: Route.() -> Unit): Route = guarded(AdminOnly, build)

/** For a route that is admin-only only for some requests, such as renaming through PATCH. */
fun ApplicationCall.requireAdmin() {
    if (principal<Caller>()?.admin != true) throw ApiException(HttpStatusCode.Forbidden, "admins only")
}

private val RoomScope: RouteScopedPlugin<Unit> = createRouteScopedPlugin("RoomScope") {
    on(AuthenticationChecked) { call ->
        val caller = call.principal<Caller>() ?: return@on
        call.parameters["id"]?.let { if (!caller.canSee(it)) throw ApiException.notFound("room not found") }
        call.parameters["zoneId"]?.let { if (!caller.canSee(it.substringBefore(':'))) throw ApiException.notFound("zone not found") }
    }
}

private val AdminOnly: RouteScopedPlugin<Unit> = createRouteScopedPlugin("AdminOnly") {
    on(AuthenticationChecked) { call ->
        // No caller: authentication itself answers 401.
        val caller = call.principal<Caller>() ?: return@on
        if (!caller.admin) throw ApiException(HttpStatusCode.Forbidden, "admins only")
    }
}

/** A child route that matches whatever its parent matches, with [plugin] guarding everything built in it. */
private fun Route.guarded(plugin: RouteScopedPlugin<Unit>, build: Route.() -> Unit): Route =
    createChild(GuardSelector(plugin.key.name)).apply {
        install(plugin)
        build()
    }

private class GuardSelector(private val name: String) : RouteSelector() {
    override suspend fun evaluate(context: RoutingResolveContext, segmentIndex: Int) = RouteSelectorEvaluation.Transparent
    override fun toString() = "(guard $name)"
}
