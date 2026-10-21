package heating.auth

import heating.Actor
import heating.ApiException
import heating.actionLog
import heating.untrusted
import io.ktor.http.HttpStatusCode
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID

/**
 * Who is calling: a signed-in user, or a script holding the service token (admin, no user).
 * [rooms] are the room ids the caller may see, or null for every room.
 */
data class Caller(val userId: String?, val username: String, val admin: Boolean, val rooms: Set<String>? = null) {
    fun canSee(roomId: String) = rooms == null || roomId in rooms
}

/** Sign-in refused because the username or address is locked for [retryAfterSeconds]. */
class TooManyAttempts(val retryAfterSeconds: Long) : RuntimeException("too many attempts, try again later")

/** Users, sign-in with throttling, sessions, and the admin's user management. */
class UserService(
    private val repository: UserRepository,
    private val passwords: Passwords,
    private val serviceToken: String,
    private val now: () -> Instant,
) {
    private var users: List<User> = repository.load()
    private val tokens = SessionTokens(repository.sessionSecret(), now)
    private val byUsername = Throttle(free = 4, now = now)
    private val byAddress = Throttle(free = 19, now = now)

    @Synchronized
    fun setupRequired(): Boolean = users.isEmpty()

    @Synchronized
    fun list(): List<User> = users

    /** The first admin, allowed only with the service token and only while there are no users. */
    fun setup(token: String, username: String, password: String): Pair<String, User> {
        if (!isServiceToken(token)) {
            actionLog.info("setup refused: wrong service token")
            throw ApiException(HttpStatusCode.Unauthorized, "wrong service token")
        }
        synchronized(this) {
            if (users.isNotEmpty()) throw ApiException.conflict("setup is already done")
            val user = newUser(username, password, admin = true)
            save(users + user)
            actionLog.info("setup: first admin {} created", user.username)
            return issue(user) to user
        }
    }

    /**
     * Checks a password, throttled per username and per address. Unknown users and wrong passwords
     * get the same answer, and take as long.
     */
    fun login(address: String, username: String, password: String): Pair<String, User> {
        val nameKey = "user:" + key(username)
        val addressKey = "addr:$address"
        val name = untrusted(username)
        val nameAttempt = byUsername.begin(nameKey) ?: run {
            actionLog.info("sign in locked for {} (too many attempts for this username)", name)
            throw TooManyAttempts(byUsername.retryAfter(nameKey))
        }
        val addressAttempt = byAddress.begin(addressKey) ?: run {
            nameAttempt.undo()
            actionLog.info("sign in locked for {} (too many attempts from this address)", name)
            throw TooManyAttempts(byAddress.retryAfter(addressKey))
        }
        val user = find(username)
        if (password.length <= Passwords.MAX_LENGTH && passwords.verify(password, user?.passwordHash) && user != null) {
            byUsername.clear(nameKey)
            addressAttempt.undo()
            actionLog.info("sign in {} ok", user.username)
            return issue(user) to user
        }
        actionLog.info("sign in failed for {}", name)
        throw ApiException(HttpStatusCode.Unauthorized, WRONG)
    }

    /** The caller behind a bearer token: the service token, or a live session of an existing user. */
    fun authenticate(token: String): Caller? {
        if (isServiceToken(token)) return Caller(null, SERVICE, admin = true)
        val session = tokens.read(token) ?: return null
        val user = synchronized(this) { users.find { it.id == session.userId } } ?: return null
        if (user.sessionVersion != session.version) return null
        return Caller(user.id, user.username, user.admin, if (user.admin) null else user.rooms?.toSet())
    }

    fun create(username: String, password: String, admin: Boolean): User = synchronized(this) {
        val user = newUser(username, password, admin)
        save(users + user)
        actionLog.info("user {} created{}{}", user.username, if (admin) " (admin)" else "", Actor.by())
        user
    }

    /**
     * Admin edit: a new password ends the user's sessions; the last admin cannot be demoted.
     * Promoting a member keeps their rooms, so demoting them again gives the same rooms back.
     */
    fun update(id: String, password: String?, admin: Boolean?): User = synchronized(this) {
        val user = users.find { it.id == id } ?: throw ApiException.notFound("no such user")
        if (admin == false && user.admin && users.count { it.admin } == 1) {
            throw ApiException.conflict("cannot remove the last admin")
        }
        var updated = user.copy(admin = admin ?: user.admin)
        // A member made admin keeps their list of rooms for when they are made a member again;
        // an admin who never had one starts with none, like any new member.
        if (user.admin && !updated.admin && updated.rooms == null) updated = updated.copy(rooms = emptyList())
        if (password != null) updated = withPassword(updated, password)
        save(users.map { if (it.id == id) updated else it })
        val what = listOfNotNull(admin?.let { "admin $it" }, password?.let { "password reset" }).joinToString(", ")
        actionLog.info("user {} updated ({}){}", user.username, what, Actor.by())
        updated
    }

    /**
     * Admin choice of the rooms member [id] can see. [known] maps every existing room id to its name;
     * an id not in it is refused. Admins see every room, so they have no list to set.
     */
    fun setRooms(id: String, rooms: List<String>, known: Map<String, String>): User = synchronized(this) {
        val user = users.find { it.id == id } ?: throw ApiException.notFound("no such user")
        if (user.admin) throw ApiException.conflict("admins see every room")
        rooms.firstOrNull { it !in known }?.let { throw ApiException.badRequest("no such room: ${untrusted(it)}") }
        val updated = user.copy(rooms = rooms.distinct())
        save(users.map { if (it.id == id) updated else it })
        actionLog.info("user {} rooms set to {}{}", user.username, roomNames(updated.rooms!!, known), Actor.by())
        updated
    }

    /**
     * Members from before room access saw every room. On start they are given every room that
     * exists now, written down, so they keep what they had and new rooms are not added for them.
     */
    fun grantLegacyMembers(roomIds: List<String>) = synchronized(this) {
        val legacy = users.filter { !it.admin && it.rooms == null }
        if (legacy.isEmpty()) return@synchronized
        save(users.map { u -> if (u in legacy) u.copy(rooms = roomIds) else u })
        legacy.forEach { actionLog.info("user {} from before room access keeps every room ({})", it.username, roomIds.size) }
    }

    /** A deleted room comes off every member's list, so a new room with the same name is not given back. */
    fun forgetRoom(roomId: String) = synchronized(this) {
        val holders = users.filter { roomId in it.rooms.orEmpty() }
        if (holders.isEmpty()) return@synchronized
        save(users.map { u -> if (u in holders) u.copy(rooms = u.rooms!! - roomId) else u })
        actionLog.info("deleted room {} taken off {}", roomId, holders.joinToString(", ") { it.username })
    }

    private fun roomNames(ids: List<String>, known: Map<String, String>) =
        if (ids.isEmpty()) "none" else ids.map { known[it] ?: it }.sorted().joinToString(", ")

    fun delete(id: String) = synchronized(this) {
        val user = users.find { it.id == id } ?: throw ApiException.notFound("no such user")
        if (user.admin && users.count { it.admin } == 1) throw ApiException.conflict("cannot delete the last admin")
        save(users.filter { it.id != id })
        actionLog.info("user {} deleted{}", user.username, Actor.by())
    }

    /** Changes the caller's own password and ends their other sessions; returns a fresh token for this one. */
    fun changePassword(caller: Caller, current: String, new: String): String {
        val user = synchronized(this) { users.find { it.id == caller.userId } }
            ?: throw ApiException.badRequest("the service token has no password")
        if (!passwords.verify(current, user.passwordHash)) {
            actionLog.info("password change refused for {}: wrong current password", user.username)
            throw ApiException(HttpStatusCode.Unauthorized, "wrong current password")
        }
        val updated = synchronized(this) {
            withPassword(user, new).also { u -> save(users.map { if (it.id == u.id) u else it }) }
        }
        actionLog.info("password changed by {}", user.username)
        return issue(updated)
    }

    /** Ends every session of the caller. */
    fun logoutAll(caller: Caller) = synchronized(this) {
        val id = caller.userId ?: throw ApiException.badRequest("the service token has no sessions")
        save(users.map { if (it.id == id) it.copy(sessionVersion = it.sessionVersion + 1) else it })
        actionLog.info("signed out everywhere by {}", caller.username)
    }

    private fun withPassword(user: User, password: String): User {
        Passwords.problem(password)?.let { throw ApiException.badRequest(it) }
        return user.copy(passwordHash = passwords.hash(password), sessionVersion = user.sessionVersion + 1)
    }

    private fun newUser(username: String, password: String, admin: Boolean): User {
        val name = username.trim()
        if (!USERNAME.matches(name)) {
            throw ApiException.badRequest("username must be 1-32 letters, digits, spaces, dots, dashes or underscores")
        }
        if (find(name) != null) throw ApiException.conflict("username already taken")
        Passwords.problem(password)?.let { throw ApiException.badRequest(it) }
        return User(
            UUID.randomUUID().toString(), name, passwords.hash(password), admin, now().toString(),
            rooms = if (admin) null else emptyList(),
        )
    }

    private fun find(username: String): User? = synchronized(this) {
        users.find { key(it.username) == key(username) }
    }

    /**
     * One spelling per username, used for both lookup and throttling. Locale-free ASCII lowercasing:
     * `equals(ignoreCase)` would also match lookalikes such as "ſam" (long s) to "sam", each with a
     * throttle count of its own.
     */
    private fun key(username: String) = username.trim().lowercase(Locale.ROOT)

    private fun issue(user: User) = tokens.issue(user.id, user.sessionVersion)

    private fun save(updated: List<User>) {
        users = updated
        repository.save(updated)
    }

    private fun isServiceToken(token: String) = MessageDigest.isEqual(token.toByteArray(), serviceToken.toByteArray())

    companion object {
        const val WRONG = "wrong username or password"
        const val SERVICE = "service"
        private val USERNAME = Regex("""[A-Za-z0-9 ._-]{1,32}""")
    }
}
