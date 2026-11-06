package heating.energy

import heating.Periodic
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Records the house meter and every monitored device every five minutes: the kWh used since the previous sample. The house
 * meter's is counted from its power readings ([MeterRegistry.powerTotal]), with its average power, since some clamps' energy
 * totals jump up and down; a device's is worked out from its running total, with its average power from its power readings
 * (its total moves in 0.01 kWh steps, too coarse to chart five minutes by). A total that drops (the device reset it) counts on from the new
 * total rather than going negative. A stale or offline meter is recorded as unknown, not as 0 kWh;
 * when it reports again the use since its last reading is counted (a catch-up). A catch-up on the day the device went
 * stale covers its stale samples, so nothing is missing; a device back with a lower total (reset) loses the use while
 * offline and counts on from the new total. Like room history this is a
 * nice-to-have: a failed write is logged and never stops the heating.
 */
class MeterRecorder(
    private val history: EnergyHistory,
    private val meters: MeterRegistry,
    private val settings: EnergySettingsRepository,
    private val monitored: MonitoredMeters,
    private val now: () -> Instant,
    private val areas: EnergyAreas? = null,
) {
    /** Each whole-area meter's power total at its last sample that had one. */
    private val lastAreaPower = mutableMapOf<String, Double>()

    private var lastSample: Instant? = null
    private var lastCleanup: Instant? = null

    /** The house meter and its total at the last sample that had a reading. */
    private var last: Pair<String, Double>? = null

    /** Each monitored device's total at its last sample that had a reading. */
    private val lastDevices = mutableMapOf<String, Double>()

    /** Each monitored device's power total at its last sample that had one. */
    private val lastDevicePower = mutableMapOf<String, Double>()
    private var failing = false

    val tick = Periodic { time ->
        val at = time.toInstant()
        if (due(lastSample, at, SAMPLE_INTERVAL)) {
            val previous = lastSample
            lastSample = at
            safely { record(at, previous, time.zone) }
        }
        if (due(lastCleanup, at, CLEANUP_INTERVAL)) {
            lastCleanup = at
            safely { history.deleteBefore(at.minus(KEEP)) }
        }
    }

    /** The first sample comes five minutes after a start, so time the service was down is never counted as covered. */
    fun start() {
        lastSample = now()
        lastCleanup = now()
    }

    @Synchronized
    private fun record(at: Instant, previousSample: Instant?, zone: ZoneId) {
        val meter = settings.get().electricityMeter
        val counter = meter?.let(meters::powerTotal)
        val kwh = if (meter == null || counter == null) null else {
            // The power total starts at 0 with this run. Its first reading since the last sample is all new use; a meter read
            // for longer (just made the house meter) starts counting from now.
            val fresh = meters[meter]?.powerSince?.let { previousSample == null || !it.isBefore(previousSample) } == true
            // A power total never resets within a run: one lower than last time means nothing more was used.
            (last?.takeIf { it.first == meter }?.second ?: if (fresh) 0.0 else null)?.let { (counter - it).coerceAtLeast(0.0) } ?: 0.0
        }
        val hours = Duration.between(previousSample ?: at.minus(SAMPLE_INTERVAL), at).toMillis() / 3_600_000.0
        history.add(MeterSample(at, meter, counter, kwh, watts = kwh?.takeIf { hours > 0 }?.let { it * 1000 / hours }))
        last = when {
            meter != null && counter != null -> meter to counter
            // Gone stale: the power total drops the last power it held past its last reading, so count on from what it
            // had counted at that reading.
            meter != null && meters[meter]?.powerSince != null -> meter to meters[meter]!!.powerKwh
            else -> last?.takeIf { it.first == meter }
        }

        for (device in monitored.all()) {
            val watts = deviceWatts(device, previousSample, hours)
            val reading = meters.reading(device)
            if (reading == null) {
                history.addDevice(MeterSample(at, device, null, null, monitored.roomOf(device), area = monitored.areaOf(device)))
                continue
            }
            val previous = lastDevices[device] ?: history.lastDeviceCounter(device)
            val staleSince = history.deviceStaleSince(device)
            val use = when {
                // Back after being offline with a lower total (it was reset): the use while offline is lost.
                staleSince != null && previous != null && reading < previous -> 0.0
                // More than a plug can pass in the time since the last sample: its total jumped, nothing was used.
                staleSince == null && previous != null && hours > 0 && reading - previous > MAX_PLUG_KW * hours -> 0.0
                else -> used(reading, previous)
            }
            history.addDevice(MeterSample(at, device, reading, use, monitored.roomOf(device), watts, monitored.areaOf(device)))
            // A catch-up on the day it went offline covers the stale samples: nothing is missing. One past midnight
            // lands on the day it came back, so both days keep their stale samples.
            if (staleSince != null && previous != null && reading >= previous && day(staleSince, zone) == day(at, zone)) {
                history.coverDeviceStale(device, staleSince, at)
            }
            // A total that dipped a little keeps counting from the higher one, so a step back is never counted again.
            lastDevices[device] = if (previous != null && dipped(reading, previous)) previous else reading
        }

        recordAreas(at, previousSample, hours)
    }

    /**
     * Each energy area's whole-area meter, counted from its power like the house meter: some clamps' energy totals jump up and
     * down, and some report no energy at all. A meter with no reading is recorded as unknown, never 0 kWh.
     */
    private fun recordAreas(at: Instant, previousSample: Instant?, hours: Double) {
        val measuring = areas?.list().orEmpty().mapNotNull { area -> area.wholeMeter?.let { area.id to it } }
        lastAreaPower.keys.retainAll(measuring.map { it.second }.toSet())
        for ((area, meter) in measuring) {
            val total = meters.powerTotal(meter)
            if (total == null) {
                // The power total counts nothing for the gap, and drops the last power it was holding past the last
                // reading: count on from what it had counted at that reading.
                meters[meter]?.takeIf { it.powerSince != null }?.let { lastAreaPower[meter] = it.powerKwh }
                history.addArea(MeterSample(at, meter, null, null, area))
                continue
            }
            // Its first reading this run is all new use; a meter read for longer (just chosen) starts counting from now.
            val fresh = meters[meter]?.powerSince?.let { previousSample == null || !it.isBefore(previousSample) } == true
            // A power total never resets within a run, so one lower than last time is the last power held past its last
            // reading, then dropped when it went stale: nothing more was used.
            val kwh = (lastAreaPower[meter] ?: if (fresh) 0.0 else null)?.let { (total - it).coerceAtLeast(0.0) } ?: 0.0
            lastAreaPower[meter] = total
            history.addArea(MeterSample(at, meter, total, kwh, area, kwh.takeIf { hours > 0 }?.let { it * 1000 / hours }))
        }
    }

    /** [device]'s average power since the last sample, like the house meter's; null while it has no reading. */
    private fun deviceWatts(device: String, previousSample: Instant?, hours: Double): Double? {
        val total = meters.powerTotal(device)
        if (total == null) {
            lastDevicePower.remove(device)
            return null
        }
        val fresh = meters[device]?.powerSince?.let { previousSample == null || !it.isBefore(previousSample) } == true
        val previous = lastDevicePower[device] ?: if (fresh) 0.0 else null
        lastDevicePower[device] = total
        return previous?.takeIf { hours > 0 }?.let { (total - it).coerceAtLeast(0.0) * 1000 / hours }
    }

    /** The household day a sample counts in: it covers the five minutes before it, so one at midnight is the day before. */
    private fun day(at: Instant, zone: ZoneId) = at.minusMillis(1).atZone(zone).toLocalDate()

    /**
     * The kWh since [previous]. A total that dipped a little (some plugs' totals step back) used nothing; one that dropped
     * further (a reset) counts on from the new total.
     */
    private fun used(counter: Double, previous: Double?): Double = when {
        previous == null -> 0.0
        counter >= previous -> counter - previous
        dipped(counter, previous) -> 0.0
        else -> counter
    }

    /** A total below [previous] by at most 1 kWh, and at least half of it: a step back, not a reset. */
    private fun dipped(counter: Double, previous: Double) = counter < previous && previous - counter <= 1.0 && counter >= previous / 2

    private fun due(last: Instant?, at: Instant, every: Duration) = last == null || Duration.between(last, at) >= every

    private fun safely(block: () -> Unit) {
        try {
            block()
            if (failing) log.info("energy: recording the house meter again")
            failing = false
        } catch (e: Exception) {
            if (!failing) log.warn("energy: could not record the house meter: {}", e.message)
            failing = true
        }
    }

    companion object {
        /** The most a plug can pass: a 16 A socket at 240 V is 3.84 kW. */
        private const val MAX_PLUG_KW = 4.0
        private val log = LoggerFactory.getLogger(MeterRecorder::class.java)
        val SAMPLE_INTERVAL: Duration = Duration.ofMinutes(5)
        private val CLEANUP_INTERVAL = Duration.ofDays(1)
        private val KEEP = Duration.ofDays(365)
    }
}
