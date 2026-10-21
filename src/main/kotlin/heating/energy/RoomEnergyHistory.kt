package heating.energy

import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit

/**
 * A room's use over one hour or day, from [at]. [incomplete] when a plug in the room had no reading for some of it: [kwh]
 * is then only what was recorded, and null when nothing was.
 */
@Serializable
data class EnergyBar(val at: String, val kwh: Double?, val incomplete: Boolean)

/**
 * A room's electricity over time for its page: the use of the plugs recorded in it as bars. Admin only, like the room's
 * plugs.
 *
 * openapi: RoomEnergyHistory
 */
@Serializable
data class RoomEnergyHistory(val room: String, val from: String, val to: String, val points: List<EnergyBar>)

/** Works out a [RoomEnergyHistory] from the per-device samples recorded in the room. */
class RoomEnergyHistoryReport(
    private val energy: EnergyHistory,
    private val zone: ZoneId,
    private val clock: Clock,
) {
    /** The last [length] as one bar per [bar] (hours, or days from the household's midnight), stamped with its start. */
    fun of(room: String, length: Duration, bar: ChronoUnit): RoomEnergyHistory {
        val to = clock.instant()
        val from = to.minus(length)
        // Use counts in the room the plug was in when it was recorded. A sample covers the five minutes before it,
        // so one on the hour counts in the hour (or day) it ends.
        val bars = energy.roomSamples(room, from, to)
            .groupBy { it.at.minusMillis(1).atZone(zone).truncatedTo(bar).toInstant() }
            .map { (at, samples) ->
                val known = samples.mapNotNull { it.kwh }
                EnergyBar(format(at), known.takeIf { it.isNotEmpty() }?.let { round(it.sum()) }, samples.any { it.kwh == null })
            }
            .sortedBy { it.at }
        return RoomEnergyHistory(room, format(from), format(to), bars)
    }

    private fun format(at: Instant) = at.atZone(zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun round(v: Double) = BigDecimal(v).setScale(3, RoundingMode.HALF_UP).toDouble()
}
