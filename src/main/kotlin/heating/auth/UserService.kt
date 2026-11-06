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
data class Caller(
    val userId: String?,
    val username: String,
    val admin: Boolean,
    val rooms: Set<String>? = null,
    /** The session (sign-in) the caller's token belongs to; null for the service token. */
    val sessionId: String? = null,
) {
    fun canSee(roomId: String) = rooms == null || roomId in rooms
}

/** Sign-in refused because the username or address is locked for [retryAfterSeconds]. */
class TooManyAttempts(val retryAfterSeconds: Long) : RuntimeException("too many attempts, try again later")

/**
 * Someone signed in or out, for the activity feed. [value] is "in" or "out"; [detail] is the device signed in on, or the
 * admin who signed them out (null when they signed themselves out).
 */
data class SessionEvent(val username: String, val value: String, val detail: String?)

/** A user's sessions in brief, for the admin's user list. */
data class Presence(val lastSeen: Instant?, val activeNow: Boolean, val sessions: Int)

/** Users, sign-in with throttling, sessions, and the admin's user management. */
class UserService(
    private val repository: UserRepository,
    private val passwords: Passwords,
    private val serviceToken: String,
    private val now: () -> Instant,
    private val sessionRepository: SessionRepository? = null,
) {
    private var users: List<User> = repository.load()
    private var sessions: List<SessionRecord> = sessionRepository?.load() ?: emptyList()
    private val sessionListeners = java.util.concurrent.CopyOnWriteArrayList<(SessionEvent) -> Unit>()

    /** Told when someone signs in or out. */
    fun onSession(listener: (SessionEvent) -> Unit) {
        sessionListeners += listener
    }

    /** How many times the sessions were saved. */
    val sessionWrites: Int get() = sessionRepository?.writes ?: 0
    private val tokens = SessionTokens(repository.sessionSecret(), now)
    private val byUsername = Throttle(free = 4, now = now)
    private val byAddress = Throttle(free = 19, now = now)

    @Synchronized
    fun setupRequired(): Boolean = users.isEmpty()

    @Synchronized
    fun list(): List<User> = users

    /** The first admin, allowed only with the service token and only while there are no users. */
    fun setup(token: String, username: String, password: String, device: String = "Unknown device"): Pair<String, User> {
        if (!isServiceToken(token)) {
            actionLog.info("setup refused: wrong service token")
            throw ApiException(HttpStatusCode.Unauthorized, "wrong service token")
        }
        synchronized(this) {
            if (users.isNotEmpty()) throw ApiException.conflict("setup is already done")
            val user = newUser(username, password, admin = true)
            save(users + user)
            actionLog.info("setup: first admin {} created", user.username)
            return issue(user, device) to user
        }
    }

    /**
     * Checks a password, throttled per username and per address. Unknown users and wrong passwords
     * get the same answer, and take as long.
     */
    fun login(address: String, username: String, password: String, device: String = "Unknown device"): Pair<String, User> {
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
            actionLog.info("sign in {} ok on {}", user.username, device)
            return issue(user, device) to user
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
        val id = seen(session) ?: return null
        return Caller(user.id, user.username, user.admin, if (user.admin) null else user.rooms?.toSet(), id)
    }

    /**
     * Notes a request made with [session], writing at most once a minute. Returns the session's id, or null if it was
     * signed out. A token from before sessions were kept gets a session of its own, "Earlier sign-in", the first time.
     */
    private fun seen(session: Session): String? = synchronized(this) {
        val at = now()
        val id = session.sessionId ?: "earlier-${session.userId}-${session.version}"
        val record = sessions.find { it.id == id }
        when {
            record == null && session.sessionId != null -> return null
            record == null -> {
                val signedIn = session.expires.minus(SessionTokens.LIFETIME)
                saveSessions(sessions + SessionRecord(id, session.userId, EARLIER, signedIn.toString(), at.toString(), session.expires.toString()))
            }
            record.signedOutAt != null -> return null
            java.time.Duration.between(Instant.parse(record.lastSeen), at) >= SEEN_EVERY ->
                saveSessions(sessions.map { if (it.id == id) it.copy(lastSeen = at.toString()) else it })
        }
        id
    }

    /** [userId]'s sessions, newest first: live ones, and those that ended (signed out or expired) in the last 30 days. */
    fun sessionsOf(userId: String): List<SessionRecord> = synchronized(this) {
        if (users.none { it.id == userId }) throw ApiException.notFound("no such user")
        val at = now()
        sessions.filter { it.userId == userId }
            .map { it.ended(at)?.let { end -> it.copy(signedOutAt = it.signedOutAt ?: end.toString()) } ?: it }
            .filter { r -> r.signedOutAt == null || java.time.Duration.between(Instant.parse(r.signedOutAt), at) <= KEEP_ENDED }
            .sortedByDescending { it.signedInAt }
    }

    /** When [userId] was last seen, whether that was in the last five minutes, and how many live sessions they have. */
    fun presence(userId: String): Presence = synchronized(this) {
        val at = now()
        val mine = sessions.filter { it.userId == userId }
        val last = mine.maxOfOrNull { Instant.parse(it.lastSeen) }
        Presence(last, last != null && java.time.Duration.between(last, at) < ACTIVE_FOR, mine.count { it.ended(at) == null })
    }

    /** Signs out the session behind [token], if it is one; the service token and unknown tokens do nothing. */
    fun signOut(token: String) {
        val session = tokens.read(token) ?: return
        val id = session.sessionId ?: "earlier-${session.userId}-${session.version}"
        val user = synchronized(this) { users.find { it.id == session.userId } } ?: return
        if (endSessions { it.id == id && it.userId == user.id } > 0) {
            if (session.sessionId == null) {
                // An earlier token cannot be told apart from the user's other earlier ones: end them all.
                synchronized(this) { save(users.map { if (it.id == user.id) it.copy(sessionVersion = it.sessionVersion + 1) else it }) }
            }
            actionLog.info("signed out {}", user.username)
            tell(SessionEvent(user.username, "out", null))
        }
    }

    /** Admin: signs out session [sessionId] of [userId]. 404 if it is not a live session of theirs. */
    fun signOutSession(userId: String, sessionId: String, by: Caller) {
        val user = synchronized(this) { users.find { it.id == userId } } ?: throw ApiException.notFound("no such user")
        if (endSessions { it.id == sessionId && it.userId == userId } == 0) throw ApiException.notFound("no such session")
        if (sessionId.startsWith("earlier-")) {
            synchronized(this) { save(users.map { if (it.id == userId) it.copy(sessionVersion = it.sessionVersion + 1) else it }) }
        }
        actionLog.info("{} signed out a session of {}", by.username, user.username)
        tell(SessionEvent(user.username, "out", by.username))
    }

    /** Admin: signs out every session of [userId]. */
    fun signOutEverywhere(userId: String, by: Caller) {
        val user = synchronized(this) {
            val user = users.find { it.id == userId } ?: throw ApiException.notFound("no such user")
            save(users.map { if (it.id == userId) it.copy(sessionVersion = it.sessionVersion + 1) else it })
            user
        }
        endSessions { it.userId == userId }
        actionLog.info("{} signed {} out everywhere", by.username, user.username)
        tell(SessionEvent(user.username, "out", by.username))
    }

    /** Marks every live session matching [which] signed out now; returns how many. */
    private fun endSessions(which: (SessionRecord) -> Boolean): Int = synchronized(this) {
        val at = now()
        val ending = sessions.filter { which(it) && it.ended(at) == null }.map { it.id }.toSet()
        if (ending.isNotEmpty()) saveSessions(sessions.map { if (it.id in ending) it.copy(signedOutAt = at.toString()) else it })
        ending.size
    }

    /** When this session ended: signed out, or its token expired; null while it is live. */
    private fun SessionRecord.ended(at: Instant): Instant? =
        signedOutAt?.let(Instant::parse) ?: Instant.parse(expires).takeIf { !at.isBefore(it) }

    private fun saveSessions(updated: List<SessionRecord>) {
        sessions = updated
        sessionRepository?.save(updated)
    }

    private fun tell(event: SessionEvent) = sessionListeners.forEach { runCatching { it(event) } }

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
        if (password != null) endSessions { it.userId == id }
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
        saveSessions(sessions.filter { it.userId != id })
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
        // This device carries on in a new session; every other one ends.
        val device = synchronized(this) { sessions.find { it.id == caller.sessionId }?.device } ?: "Unknown device"
        endSessions { it.userId == user.id }
        return issue(updated, device, announce = false)
    }

    /** Ends every session of the caller. */
    fun logoutAll(caller: Caller) = synchronized(this) {
        val id = caller.userId ?: throw ApiException.badRequest("the service token has no sessions")
        save(users.map { if (it.id == id) it.copy(sessionVersion = it.sessionVersion + 1) else it })
        endSessions { it.userId == id }
        actionLog.info("signed out everywhere by {}", caller.username)
        tell(SessionEvent(caller.username, "out", null))
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

    /** A token for a new session of [user] on [device]. */
    private fun issue(user: User, device: String, announce: Boolean = true): String {
        val at = now()
        val id = UUID.randomUUID().toString()
        synchronized(this) {
            saveSessions(sessions + SessionRecord(id, user.id, device, at.toString(), at.toString(), at.plus(SessionTokens.LIFETIME).toString()))
        }
        if (announce) tell(SessionEvent(user.username, "in", device))
        return tokens.issue(user.id, user.sessionVersion, id)
    }

    private fun save(updated: List<User>) {
        users = updated
        repository.save(updated)
    }

    private fun isServiceToken(token: String) = MessageDigest.isEqual(token.toByteArray(), serviceToken.toByteArray())

    companion object {
        const val WRONG = "wrong username or password"
        const val SERVICE = "service"
        /** The device of a session from a token minted before sessions were kept. */
        const val EARLIER = "Earlier sign-in"
        private val SEEN_EVERY = java.time.Duration.ofMinutes(1)
        private val ACTIVE_FOR = java.time.Duration.ofMinutes(5)
        private val KEEP_ENDED = java.time.Duration.ofDays(30)
        private val USERNAME = Regex("""[A-Za-z0-9 ._-]{1,32}""")
    }
}
