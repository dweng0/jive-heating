package heating.energy

import heating.rooms.Room
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** A time a plug had no reading, from the start of its first stale five minutes to the end of its last. */
@Serializable
data class OfflinePeriod(val from: String, val to: String)

/**
 * One plug in a room: its power now (null while offline or stale) and its use and cost today. Cost is
 * use at electricityPencePerKwh alone; standing charges are never split onto plugs. When [incomplete],
 * [kwh] and [cost] are only so far and [offline] says when. [offlineSince] is set while the plug is offline
 * now, which also makes it incomplete. [totalStuckSince] is set while the plug is using power but its energy
 * total is not going up (see [MeterRegistry.totalStuckSince]); it is then incomplete too.
 *
 * openapi: RoomPlug
 */
@Serializable
data class RoomPlug(
    val meter: String,
    val power: Double?,
    val kwh: Double,
    val cost: Double,
    val missingMinutes: Int,
    val incomplete: Boolean,
    val offline: List<OfflinePeriod>,
    val offlineSince: String? = null,
    val totalStuckSince: String? = null,
)

/**
 * A room's plugs for its settings page: today so far, the room's sum above its plugs. Like the
 * Dashboard's rooms, it is "of which" the house meter's use, never added to anything.
 *
 * openapi: RoomPlugs
 */
@Serializable
data class RoomPlugs(
    val room: String,
    val currency: String,
    val kwh: Double,
    val cost: Double,
    val missingMinutes: Int,
    val incomplete: Boolean,
    val plugs: List<RoomPlug>,
)

/**
 * Works out a room's [RoomPlugs] from the per-device samples recorded in the room today and each plug's live
 * reading. The room's figures include plugs moved out since; each plug listed counts only its use here.
 */
class RoomPlugsReport(
    private val energy: EnergyHistory,
    private val settings: EnergySettingsRepository,
    private val monitored: MonitoredMeters,
    private val meters: MeterRegistry,
    private val now: () -> ZonedDateTime,
) {
    fun of(room: Room): RoomPlugs {
        val now = now()
        val from = now.toLocalDate().atStartOfDay(now.zone).toInstant()
        val prices = settings.get()
        // Use counts in the room the plug was in when it was recorded, as on the Dashboard. A sample covers
        // the five minutes before it, so one at midnight is yesterday's.
        val samples = monitored.all().associateWith { meter ->
            energy.deviceSamples(meter, from, now.toInstant()).filter { it.at > from && it.room == room.id }
        }
        val here = samples.values.flatten()
        val kwh = here.sumOf { it.kwh ?: 0.0 }
        // Plugs are sampled together, so time two plugs were stale at once counts once.
        val missing = minutes(here.filter { it.kwh == null }.map { it.at }.toSet())
        // Listed are the plugs in the room now; one moved out today still counts in the room's total.
        val plugs = samples.filterKeys { monitored.roomOf(it) == room.id }.map { (meter, samples) ->
            Plug(meter, samples.sumOf { it.kwh ?: 0.0 }, samples.filter { it.kwh == null }.map { it.at })
        }
        val listed = plugs.map { plug ->
            val missing = minutes(plug.staleAt.toSet())
            val meter = meters[plug.meter]
            val offlineSince = meter?.let(meters::offlineSince)
            val totalStuckSince = meter?.let(meters::totalStuckSince)
            RoomPlug(
                meter = plug.meter,
                power = meter?.takeIf { offlineSince == null }?.power,
                kwh = round(plug.kwh, 2),
                cost = round(plug.kwh * prices.electricityPencePerKwh, 1),
                missingMinutes = missing,
                incomplete = missing > 0 || offlineSince != null || totalStuckSince != null,
                offline = offline(plug.staleAt, now),
                offlineSince = offlineSince?.let { format(it, now) },
                totalStuckSince = totalStuckSince?.let { format(it, now) },
            )
        }
        return RoomPlugs(
            room = room.id,
            currency = prices.currency,
            kwh = round(kwh, 2),
            cost = round(kwh * prices.electricityPencePerKwh, 1),
            missingMinutes = missing,
            // A plug offline now, or whose total is stuck, makes the room's figures so far too.
            incomplete = missing > 0 || listed.any { it.offlineSince != null || it.totalStuckSince != null },
            plugs = listed,
        )
    }

    private class Plug(val meter: String, val kwh: Double, val staleAt: List<Instant>)

    private fun minutes(staleAt: Set<Instant>) = staleAt.size * MeterRecorder.SAMPLE_INTERVAL.toMinutes().toInt()

    /** Back-to-back stale samples joined into one period each. */
    private fun offline(staleAt: List<Instant>, now: ZonedDateTime): List<OfflinePeriod> {
        val periods = mutableListOf<Pair<Instant, Instant>>()
        staleAt.sorted().forEach { at ->
            val start = at.minus(MeterRecorder.SAMPLE_INTERVAL)
            val last = periods.lastOrNull()
            if (last != null && last.second >= start) periods[periods.size - 1] = last.first to at
            else periods += start to at
        }
        return periods.map { (from, to) -> OfflinePeriod(format(from, now), format(to, now)) }
    }

    private fun format(at: Instant, now: ZonedDateTime) = at.atZone(now.zone).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME)

    private fun round(v: Double, places: Int) = BigDecimal.valueOf(v).setScale(places, RoundingMode.HALF_UP).toDouble()
}
