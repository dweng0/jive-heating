package heating.api

import heating.Change
import heating.ApiException
import heating.HeatingApp
import heating.auth.Caller
import heating.auth.User
import heating.auth.deviceOf
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.server.request.userAgent
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.auth.principal
import io.ktor.server.plugins.origin
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.RoutingCall
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.patch
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.serialization.Serializable

@Serializable
data class UserView(
    val id: String?,
    val username: String,
    val admin: Boolean,
    val createdAt: String? = null,
    /** Ids of every room this user can see now: all of them for an admin. */
    val rooms: List<String> = emptyList(),
    /** Admin's user list only: the last request made with any of their sessions. */
    val lastSeen: String? = null,
    /** Admin's user list only: a request in the last five minutes. */
    val activeNow: Boolean? = null,
    /** Admin's user list only: sessions not signed out or expired. */
    val sessions: Int? = null,
    /** May read the Energy tab: always for an admin. */
    val energy: Boolean = admin,
)

/** One sign-in on one device, for the admin. [current] is the session the caller is using. openapi: UserSession */
@Serializable
data class UserSessionView(
    val id: String,
    val device: String,
    val signedInAt: String,
    val lastSeen: String,
    val signedOutAt: String?,
    val current: Boolean,
)

@Serializable
data class SessionView(val token: String, val user: UserView)

@Serializable
data class AuthStatus(val setupRequired: Boolean)

@Serializable
data class SetupRequest(val serviceToken: String, val username: String, val password: String, val web: Boolean = false)

@Serializable
data class LoginRequest(val username: String, val password: String, val web: Boolean = false)

@Serializable
data class ChangePasswordRequest(val current: String, val new: String)

@Serializable
data class CreateUserRequest(val username: String, val password: String, val admin: Boolean = false)

@Serializable
data class SetRoomsRequest(val rooms: List<String>)

@Serializable
data class SetEnergyRequest(val energy: Boolean)

@Serializable
data class PatchUserRequest(val password: String? = null, val admin: Boolean? = null)

/** The rooms someone sees: every room when [rooms] is null (an admin, or a user from before room access). */
private fun HeatingApp.visibleRooms(rooms: Collection<String>?): List<String> =
    this.rooms.list().map { it.id }.filter { rooms == null || it in rooms }

fun HeatingApp.userView(user: User) =
    UserView(user.id, user.username, user.admin, user.createdAt, visibleRooms(if (user.admin) null else user.rooms), energy = user.admin || user.energy)

private fun HeatingApp.callerView(caller: Caller) =
    UserView(caller.userId, caller.username, caller.admin, rooms = visibleRooms(caller.rooms), energy = caller.energy)

/** Routes anyone may call: whether setup is needed, first-admin setup, and sign-in. */
fun Route.publicAuthRoutes(heating: HeatingApp) = route("/api/auth") {
    get("/status") { call.respond(AuthStatus(heating.users.setupRequired())) }
    post("/setup") {
        val body = call.receive<SetupRequest>()
        val (token, user) = heating.users.setup(body.serviceToken, body.username, body.password, deviceOf(call.request.userAgent()))
        call.respond(HttpStatusCode.Created, heating.session(call, token, user, body.web))
    }
    post("/login") {
        val body = call.receive<LoginRequest>()
        val (token, user) = heating.users.login(call.address(), body.username, body.password, deviceOf(call.request.userAgent()))
        call.respond(heating.session(call, token, user, body.web))
    }
    post("/logout") {
        // Push needs the session to say whose subscription it is, so it goes before the session ends.
        heating.forgetWebPushOnSignOut(call)
        // Ends this device's session; the cookie or bearer token says which.
        (WebSession.authHeader(call) as? HttpAuthHeader.Single)?.blob?.let(heating.users::signOut)
        call.response.cookies.append(WebSession.cleared())
        call.respond(HttpStatusCode.NoContent)
    }
}

/** Routes for a signed-in caller: who am I, change my password, sign out everywhere, and the admin's user list. */
fun Route.authRoutes(heating: HeatingApp) {
    route("/api/auth") {
        get("/me") {
            val caller = call.caller()
            call.respond(heating.callerView(caller))
        }
        post("/password") {
            val body = call.receive<ChangePasswordRequest>()
            val token = heating.users.changePassword(call.caller(), body.current, body.new)
            val user = heating.users.list().single { it.id == call.caller().userId }
            // A browser signed in with the cookie gets the fresh session as a new cookie, not in the body.
            val viaCookie = call.request.headers[HttpHeaders.Authorization] == null
            call.respond(heating.session(call, token, user, web = viaCookie))
        }
        post("/logout-all") {
            heating.users.logoutAll(call.caller())
            call.respond(HttpStatusCode.NoContent)
        }
    }
    route("/api/admin/users") {
        get {
            call.requireAdmin()
            call.respond(heating.users.list().map { user ->
                val presence = heating.users.presence(user.id)
                heating.userView(user).copy(lastSeen = presence.lastSeen?.toString(), activeNow = presence.activeNow, sessions = presence.sessions)
            })
        }
        get("/{id}/sessions") {
            call.requireAdmin()
            val current = call.caller().sessionId
            call.respond(heating.users.sessionsOf(call.parameters["id"]!!).map {
                UserSessionView(it.id, it.device, it.signedInAt, it.lastSeen, it.signedOutAt, it.id == current)
            })
        }
        delete("/{id}/sessions/{session}") {
            call.requireAdmin()
            heating.users.signOutSession(call.parameters["id"]!!, call.parameters["session"]!!, call.caller())
            call.respond(HttpStatusCode.NoContent)
        }
        delete("/{id}/sessions") {
            call.requireAdmin()
            heating.users.signOutEverywhere(call.parameters["id"]!!, call.caller())
            call.respond(HttpStatusCode.NoContent)
        }
        post {
            call.requireAdmin()
            val body = call.receive<CreateUserRequest>()
            call.respond(HttpStatusCode.Created, heating.userView(call.acting { heating.users.create(body.username, body.password, body.admin) }))
        }
        patch("/{id}") {
            call.requireAdmin()
            val body = call.receive<PatchUserRequest>()
            val user = call.acting { heating.users.update(call.parameters["id"]!!, body.password, body.admin) }
            if (body.admin != null) heating.changes.emit(Change.AccessChanged(user.id))
            call.respond(heating.userView(user))
        }
        put("/{id}/rooms") {
            call.requireAdmin()
            val body = call.receive<SetRoomsRequest>()
            val known = heating.rooms.list().associate { it.id to it.name }
            val user = call.acting { heating.users.setRooms(call.parameters["id"]!!, body.rooms, known) }
            heating.changes.emit(Change.AccessChanged(user.id))
            call.respond(heating.userView(user))
        }
        put("/{id}/energy") {
            call.requireAdmin()
            val body = call.receive<SetEnergyRequest>()
            val user = call.acting { heating.users.setEnergy(call.parameters["id"]!!, body.energy) }
            heating.changes.emit(Change.AccessChanged(user.id))
            call.respond(heating.userView(user))
        }
        delete("/{id}") {
            call.requireAdmin()
            val id = call.parameters["id"]!!
            call.acting { heating.users.delete(id) }
            heating.changes.emit(Change.AccessChanged(id))
            call.respond(HttpStatusCode.NoContent)
        }
    }
}

/** For the web app the token goes into an HttpOnly cookie and the body carries none, so page scripts never hold it. */
private fun HeatingApp.session(call: RoutingCall, token: String, user: User, web: Boolean): SessionView {
    if (!web) return SessionView(token, userView(user))
    call.response.cookies.append(WebSession.cookie(token))
    return SessionView("", userView(user))
}

private fun RoutingCall.caller(): Caller = principal<Caller>() ?: throw ApiException(HttpStatusCode.Unauthorized, "sign in first")

/** The client's address: Cloudflare's header when the tunnel forwarded the request, else the socket peer. */
private fun RoutingCall.address(): String = request.headers["CF-Connecting-IP"] ?: request.origin.remoteHost
