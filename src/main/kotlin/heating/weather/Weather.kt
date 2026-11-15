package heating.weather

import heating.Change
import heating.ChangeNotifier
import heating.Periodic
import kotlinx.serialization.Serializable
import org.slf4j.LoggerFactory
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/** What the sky is doing, as every weather source reports it. */
enum class Condition { CLEAR, PARTLY_CLOUDY, CLOUDY, RAIN, SNOW }

/**
 * The weather outside the house: the [condition], and when the source knows them, [cloudCover] (0 to 100), the
 * temperature and when it was observed.
 */
data class WeatherReading(
    val condition: Condition,
    val cloudCover: Int? = null,
    val temperatureC: Double? = null,
    val observedAt: Instant? = null,
)

/**
 * The contract every weather adapter meets (Home Assistant first; a weather API or a garden station later). Called off
 * the request path, about every five minutes; it may block, and throws when the source cannot be reached. Null means
 * the source has nothing to say yet.
 */
fun interface WeatherSource {
    fun current(): WeatherReading?
}

/** The weather as the Live picture draws it, sent on /ws/power. [at] is when it was observed (or fetched). */
@Serializable
data class LiveWeather(val look: String, val temperatureC: Double?, val at: String)

/** The Live picture's look for [r]: clear, cloudy, overcast, rain, snow, or frost (freezing under a clear or partly cloudy sky). */
fun lookOf(r: WeatherReading): String {
    val freezing = r.temperatureC != null && r.temperatureC <= 0.0
    return when (r.condition) {
        Condition.CLEAR -> if (freezing) "frost" else "clear"
        Condition.PARTLY_CLOUDY -> if (freezing) "frost" else "cloudy"
        Condition.CLOUDY -> "overcast"
        Condition.RAIN -> "rain"
        Condition.SNOW -> "snow"
    }
}

/**
 * Asks the configured [source] for the weather every [EVERY], on [executor], and keeps the latest. Tells [changes]
 * when the look changes so live power is sent at once. A reading older than [FRESH_FOR] is no longer shown.
 */
class Weather(
    private val source: WeatherSource?,
    private val executor: Executor,
    private val clock: Clock,
    private val changes: ChangeNotifier,
) {
    private data class Fetched(val reading: WeatherReading, val at: Instant)

    @Volatile private var latest: Fetched? = null
    @Volatile private var nextAt: Instant? = null
    private val running = AtomicBoolean(false)

    /** Whether the last ask failed; the failure is logged once until the source works again. */
    @Volatile var failing = false
        private set

    fun now(): LiveWeather? {
        val f = latest ?: return null
        if (Duration.between(f.at, clock.instant()) >= FRESH_FOR) return null
        return LiveWeather(lookOf(f.reading), f.reading.temperatureC, (f.reading.observedAt ?: f.at).toString())
    }

    val tick = Periodic { now ->
        val src = source ?: return@Periodic
        val at = now.toInstant()
        if (nextAt?.let { at < it } == true) return@Periodic
        nextAt = at.plus(EVERY)
        if (!running.compareAndSet(false, true)) return@Periodic
        executor.execute {
            try {
                val was = now()?.look
                val reading = src.current()
                if (reading != null) latest = Fetched(reading, clock.instant())
                if (failing) log.info("weather source working again")
                failing = false
                if (now()?.look != was) changes.emit(Change.LivePowerChanged)
            } catch (e: Exception) {
                if (!failing) log.warn("weather source failed: ${e.message}")
                failing = true
            } finally {
                running.set(false)
            }
        }
    }

    companion object {
        val EVERY: Duration = Duration.ofMinutes(5)
        val FRESH_FOR: Duration = Duration.ofMinutes(30)
        private val log = LoggerFactory.getLogger(Weather::class.java)
    }
}
