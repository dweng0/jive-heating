package heating.demand

import java.time.Duration
import java.time.Instant

/**
 * The boiler's on/off state with a minimum run time and a minimum rest time, so it never short-cycles.
 * Before the first change after startup there is no history, so it may switch freely.
 */
class BoilerDemand(
    private val minRun: Duration = Duration.ofMinutes(4),
    private val minRest: Duration = Duration.ofMinutes(5),
) {
    var on = false
        private set
    private var changedAt: Instant? = null

    /** Move towards [wanted] if the minimum time in the current state has passed. True if it flipped. */
    fun update(wanted: Boolean, now: Instant): Boolean {
        if (wanted == on) return false
        val since = changedAt
        val hold = if (on) minRun else minRest
        if (since != null && Duration.between(since, now) < hold) return false
        on = wanted
        changedAt = now
        return true
    }

    /** How much longer the boiler must stay [on] before it may switch, or null if it may switch now. */
    /** When the current minimum run or rest ends, or null when it already has (or there is no history). */
    fun holdEnds(): Instant? = changedAt?.plus(if (on) minRun else minRest)

    fun holdLeft(now: Instant): Duration? {
        val since = changedAt ?: return null
        val left = (if (on) minRun else minRest).minus(Duration.between(since, now))
        return left.takeIf { !it.isNegative && !it.isZero }
    }
}
