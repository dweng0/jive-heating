package heating.energy

import heating.Periodic
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * Records the house meter and every monitored device every five minutes: the kWh used since the previous sample. The house
 * meter's is counted from its power readings ([MeterRegistry.powerTotal]), with its average power, since some clamps' energy
 * totals jump up and down; a device's is worked out from its running total. A total that drops (the device reset it) counts on from the new
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
) {
    private var lastSample: Instant? = null
    private var lastCleanup: Instant? = null

    /** The house meter and its total at the last sample that had a reading. */
    private var last: Pair<String, Double>? = null

    /** Each monitored device's total at its last sample that had a reading. */
    private val lastDevices = mutableMapOf<String, Double>()
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
            used(counter, last?.takeIf { it.first == meter }?.second ?: if (fresh) 0.0 else null)
        }
        val hours = Duration.between(previousSample ?: at.minus(SAMPLE_INTERVAL), at).toMillis() / 3_600_000.0
        history.add(MeterSample(at, meter, counter, kwh, watts = kwh?.takeIf { hours > 0 }?.let { it * 1000 / hours }))
        last = if (meter != null && counter != null) meter to counter else last?.takeIf { it.first == meter }

        for (device in monitored.all()) {
            val reading = meters.reading(device)
            if (reading == null) {
                history.addDevice(MeterSample(at, device, null, null, monitored.roomOf(device)))
                continue
            }
            val previous = lastDevices[device] ?: history.lastDeviceCounter(device)
            val staleSince = history.deviceStaleSince(device)
            val use = when {
                // Back after being offline with a lower total (it was reset): the use while offline is lost.
                staleSince != null && previous != null && reading < previous -> 0.0
                else -> used(reading, previous)
            }
            history.addDevice(MeterSample(at, device, reading, use, monitored.roomOf(device)))
            // A catch-up on the day it went offline covers the stale samples: nothing is missing. One past midnight
            // lands on the day it came back, so both days keep their stale samples.
            if (staleSince != null && previous != null && reading >= previous && day(staleSince, zone) == day(at, zone)) {
                history.coverDeviceStale(device, staleSince, at)
            }
            lastDevices[device] = reading
        }
    }

    /** The household day a sample counts in: it covers the five minutes before it, so one at midnight is the day before. */
    private fun day(at: Instant, zone: ZoneId) = at.minusMillis(1).atZone(zone).toLocalDate()

    /** The kWh since [previous]; a total that dropped (a reset) counts on from the new total. */
    private fun used(counter: Double, previous: Double?): Double = when {
        previous == null -> 0.0
        counter >= previous -> counter - previous
        else -> counter
    }

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
        private val log = LoggerFactory.getLogger(MeterRecorder::class.java)
        val SAMPLE_INTERVAL: Duration = Duration.ofMinutes(5)
        private val CLEANUP_INTERVAL = Duration.ofDays(1)
        private val KEEP = Duration.ofDays(365)
    }
}
