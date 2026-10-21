package heating.notify

import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Who asked for a zone's latest change: a signed-in person, or someone at the radiator's knob. */
sealed interface Who {
    data class Person(val username: String) : Who
    data object Knob : Who
}

/**
 * Remembers the latest user action per zone, so a zone that then starts calling for heat can be put
 * down to that person (or knob). Schedules, holidays and boosts running out record nothing.
 */
class ActionTracker(private val now: () -> Instant) {
    private class Action(val who: Who, val at: Instant)

    private val latest = ConcurrentHashMap<String, Action>()

    fun record(zoneId: String, who: Who) {
        latest[zoneId] = Action(who, now())
    }

    /** Who changed [zoneId] within [WINDOW] (a valve can take a while to confirm a new target), once. */
    fun take(zoneId: String): Who? {
        val action = latest.remove(zoneId) ?: return null
        return action.who.takeIf { Duration.between(action.at, now()) <= WINDOW }
    }

    companion object {
        val WINDOW: Duration = Duration.ofMinutes(5)
    }
}
