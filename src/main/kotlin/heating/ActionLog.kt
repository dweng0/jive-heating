package heating

import heating.rooms.Control
import heating.rooms.Room
import org.slf4j.Logger
import org.slf4j.LoggerFactory

/**
 * One INFO line per thing the service did or was told to do, so an incident can be read from
 * `docker logs home-heating`. Never log tokens, cookies or passwords here.
 */
val actionLog: Logger = LoggerFactory.getLogger("heating.actions")

/**
 * Who asked for the change being made on this thread: the signed-in username, "service" for the
 * service token, or none for the service's own periodic work. Set around a non-suspending block,
 * so it never leaks to another call sharing the thread.
 */
object Actor {
    private val current = ThreadLocal<String?>()

    fun <T> runAs(name: String, block: () -> T): T {
        val previous = current.get()
        current.set(name)
        try {
            return block()
        } finally {
            current.set(previous)
        }
    }

    val name: String? get() = current.get()

    /** " by jane", or "" when no one asked (periodic work). */
    fun by(): String = current.get()?.let { " by $it" } ?: ""
}

/** How a zone is named in the log: the room's name, plus the valve for an independent room. */
fun Room.zoneName(zoneId: String): String =
    if (control == Control.INDEPENDENT && ':' in zoneId) "$name/${zoneId.substringAfter(':')}" else name

/** One decimal, so 21.0 and 21.5 read the same way everywhere. */
fun Double.logged(): String = "%.1f".format(java.util.Locale.ROOT, this)

/** Text a client sent, made safe for one log line: printable name characters only, at most 32. */
fun untrusted(text: String): String = text.take(32).replace(Regex("[^A-Za-z0-9 ._@-]"), "?")
