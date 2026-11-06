package heating.app.ui.energy

import heating.app.data.EnergyTarget
import java.util.Locale
import kotlin.math.abs

/**
 * Today, against where use should be by now: "1.0 kWh under pace" or "1.0 kWh over pace" (over pace is not over the
 * day's target). A week or month: "1.0 kWh under target" or "1.0 kWh over target".
 */
fun targetDelta(deltaKwh: Double, today: Boolean = false): String =
    String.format(Locale.UK, "%.1f kWh %s %s", abs(deltaKwh), if (deltaKwh <= 0) "under" else "over", if (today) "pace" else "target")

/** What is left of today's target: "7.0 kWh left today", or "1.0 kWh past the target" once it is used up. */
fun targetLeft(t: EnergyTarget): String =
    if (t.usedKwh <= t.kwh) String.format(Locale.UK, "%.1f kWh left today", t.kwh - t.usedKwh)
    else String.format(Locale.UK, "%.1f kWh past the target", t.usedKwh - t.kwh)

/** "5.0 of 12.0 kWh, 7.0 kWh left today, on track for 10.0 kWh"; for a week or month only the first part. */
fun targetProgress(t: EnergyTarget, today: Boolean): String {
    val used = String.format(Locale.UK, "%.1f of %.1f kWh", t.usedKwh, t.kwh)
    if (!today) return used
    val projected = t.projectedKwh
    return "$used, ${targetLeft(t)}" + (projected?.let { String.format(Locale.UK, ", on track for %.1f kWh", it) } ?: "")
}

const val SET_TARGET = "Set a daily target"
const val READINGS_MISSING = "Some readings are missing"
const val REVERSED = "This clamp reads backwards. Its use is still counted."
