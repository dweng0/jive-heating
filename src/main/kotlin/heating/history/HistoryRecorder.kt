package heating.history

import heating.Change
import heating.ChangeNotifier
import heating.Periodic
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant

/**
 * Records every room's temperature and target every five minutes, each boiler state change, and
 * deletes history older than a year once a day. History is a nice-to-have: a failed write is logged
 * and never stops the heating.
 */
class HistoryRecorder(
    private val history: HistoryRepository,
    private val changes: ChangeNotifier,
    private val now: () -> Instant,
    /** Each room's id, current temperature and effective target right now. */
    private val rooms: () -> List<Triple<String, Double?, Double?>>,
    /** The boiler's state name right now: heating, cycling, resting or idle. */
    private val boilerState: () -> String,
) {
    private var lastSample: Instant? = null
    private var lastCleanup: Instant? = null
    private var lastState: String? = null

    val tick = Periodic { time ->
        val at = time.toInstant()
        safely("boiler state") { recordState(at) }
        if (due(lastSample, at, SAMPLE_INTERVAL)) {
            lastSample = at
            safely("room temperatures") {
                history.addPoints(rooms().filter { it.second != null }.map { (id, current, target) -> RoomPoint(at, id, current, target) })
                history.seen(at)
            }
        }
        if (due(lastCleanup, at, CLEANUP_INTERVAL)) {
            lastCleanup = at
            safely("clean-up") { history.deleteBefore(at.minus(KEEP)) }
        }
    }

    fun start() {
        val at = now()
        lastSample = at
        lastCleanup = at
        safely("restart") { history.closeOpenState() }
        safely("boiler state") { recordState(at) }
        changes.subscribe { if (it is Change.StatusChanged) safely("boiler state") { recordState(now()) } }
    }

    @Synchronized
    private fun recordState(at: Instant) {
        val state = boilerState()
        if (state == lastState) return
        history.boilerChanged(state, at)
        lastState = state
    }

    private fun due(last: Instant?, at: Instant, every: Duration) = last == null || Duration.between(last, at) >= every

    /** Kinds of write that are failing; each is logged once until it works again. */
    private val failing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>()

    private fun safely(what: String, block: () -> Unit) {
        try {
            block()
            if (failing.remove(what)) log.info("history: recording {} again", what)
        } catch (e: Exception) {
            if (failing.add(what)) log.warn("history: could not record {}: {}", what, e.message)
        }
    }

    companion object {
        private val log = LoggerFactory.getLogger(HistoryRecorder::class.java)
        val SAMPLE_INTERVAL: Duration = Duration.ofMinutes(5)
        private val CLEANUP_INTERVAL = Duration.ofDays(1)
        private val KEEP = Duration.ofDays(365)
    }
}
