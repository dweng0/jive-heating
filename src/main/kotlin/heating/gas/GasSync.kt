package heating.gas

import heating.Periodic
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Fetches the smart meter's gas readings at start and every 30 minutes after, off the tick's thread on [executor]:
 * only half-hours not kept yet, from the meter's first on, ten days at a time. A fetch that fails is logged and tried
 * again at the next 30 minutes; what it had already kept stays.
 */
class GasSync(
    private val meter: GasMeter,
    private val readings: GasReadings,
    private val executor: Executor,
) {
    private var nextAt: Instant? = null
    private val running = AtomicBoolean(false)

    val tick = Periodic { now ->
        val at = now.toInstant()
        if (nextAt?.let { at < it } == true) return@Periodic
        nextAt = at.plus(EVERY)
        if (!running.compareAndSet(false, true)) return@Periodic
        executor.execute {
            try {
                sync()
            } catch (e: Exception) {
                log.warn("gas meter fetch failed: ${e.message}")
            } finally {
                running.set(false)
            }
        }
    }

    private fun sync() {
        val reported = meter.reported() ?: return
        var from = readings.last()?.plus(GasReadings.HALF_HOUR) ?: reported.start
        while (from <= reported.endInclusive) {
            val to = minOf(from.plus(CHUNK).minus(GasReadings.HALF_HOUR), reported.endInclusive)
            readings.put(meter.readings(from, to))
            from = to.plus(GasReadings.HALF_HOUR)
        }
    }

    private companion object {
        val EVERY: Duration = Duration.ofMinutes(30)

        /** Glowmarkt gives half-hourly readings at most ten days at a time; a little under keeps clear of its limit. */
        val CHUNK: Duration = Duration.ofDays(9)
        val log = LoggerFactory.getLogger(GasSync::class.java)
    }
}
