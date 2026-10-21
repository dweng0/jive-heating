package heating.auth

import java.time.Duration
import java.time.Instant

/**
 * Failure backoff per key (a username or an address), after openGym's rate-limit.js.
 *
 * The first [free] failures cost nothing; each one after that locks the key for [base], doubling
 * every time up to [max]. A key forgets after [forget] without a failure, or on [clear].
 * An attempt counts as a failure from the moment it starts ([attempt]) and is taken back with
 * [Attempt.undo] once it turns out to be a success, so guesses sent side by side are all counted
 * before any of them is answered. In memory only: a restart forgets, and locks are minutes long.
 */
class Throttle(
    private val free: Int,
    private val now: () -> Instant,
    private val base: Duration = Duration.ofMinutes(1),
    private val max: Duration = Duration.ofHours(1),
    private val forget: Duration = Duration.ofDays(1),
    private val maxKeys: Int = 10_000,
) {
    private class Entry(var failures: Int, var last: Instant, var until: Instant)

    private val entries = LinkedHashMap<String, Entry>()

    /** Seconds [key] still has to wait, 0 when it may try. */
    @Synchronized
    fun retryAfter(key: String): Long {
        val until = entries[key]?.until ?: return 0
        val left = Duration.between(now(), until)
        return if (left.isNegative || left.isZero) 0 else (left.toMillis() + 999) / 1000
    }

    /**
     * Checks and counts in one step: null when [key] is locked (nothing counted), otherwise the
     * attempt, already counted as a failure. Requests racing each other cannot all pass the check.
     */
    @Synchronized
    fun begin(key: String): Attempt? = if (retryAfter(key) > 0) null else attempt(key)

    /** One more failure for [key], counted now. Returns the lock it started, in seconds (0 for none). */
    @Synchronized
    fun attempt(key: String): Attempt {
        val time = now()
        val entry = entries[key]?.takeIf { Duration.between(maxOf(it.last, it.until), time) <= forget }
            ?: Entry(0, time, Instant.EPOCH)
        val previousUntil = entry.until
        entry.failures += 1
        entry.last = time
        var lock = Duration.ZERO
        if (entry.failures > free) {
            val doublings = minOf(30, entry.failures - free - 1)
            lock = minOf(max, base.multipliedBy(1L shl doublings))
            entry.until = time.plus(lock)
        }
        entries.remove(key)
        if (entries.size >= maxKeys) evict(time)
        entries[key] = entry
        return Attempt(key, entry, previousUntil, lock.seconds)
    }

    inner class Attempt internal constructor(
        private val key: String,
        private val entry: Any,
        private val previousUntil: Instant,
        val lockSeconds: Long,
    ) {
        private var open = true

        /** It was not a failure after all: take this attempt back. */
        fun undo() = synchronized(this@Throttle) {
            if (!open) return@synchronized
            open = false
            val e = entries[key]?.takeIf { it === entry } ?: return@synchronized
            e.failures -= 1
            if (lockSeconds > 0 && e.failures <= free) e.until = previousUntil
            if (e.failures <= 0 && !e.until.isAfter(now())) entries.remove(key)
        }
    }

    /** A success: [key] starts over. */
    @Synchronized
    fun clear(key: String) {
        entries.remove(key)
    }

    /** Make room: drop the least recently used unlocked key; locked keys go last so a flood cannot reset them. */
    private fun evict(time: Instant) {
        val victim = entries.entries.firstOrNull { !it.value.until.isAfter(time) } ?: entries.entries.first()
        entries.remove(victim.key)
    }
}
