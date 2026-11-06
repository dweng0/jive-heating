package heating.app.ui.energy

import heating.app.data.EnergyTarget
import java.util.Locale
import kotlin.math.abs

/** "1.0 kWh under target" or "1.0 kWh over target". */
fun targetDelta(deltaKwh: Double): String =
    String.format(Locale.UK, "%.1f kWh %s target", abs(deltaKwh), if (deltaKwh <= 0) "under" else "over")

/** "5.0 of 12.0 kWh, on track for 10.0 kWh"; for a week or month only the first part. */
fun targetProgress(t: EnergyTarget, today: Boolean): String {
    val used = String.format(Locale.UK, "%.1f of %.1f kWh", t.usedKwh, t.kwh)
    val projected = t.projectedKwh
    return if (today && projected != null) used + String.format(Locale.UK, ", on track for %.1f kWh", projected) else used
}

const val SET_TARGET = "Set a daily target"
const val READINGS_MISSING = "Some readings are missing"
const val REVERSED = "This clamp reads backwards. Its use is still counted."
