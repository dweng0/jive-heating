package heating.energy

import heating.Change
import heating.ChangeNotifier
import kotlinx.serialization.Serializable
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** Power moving from one node to another right now: [from] and [to] are solar, battery, grid or home; [watts] is above 0. */
@Serializable
data class PowerFlow(val from: String, val to: String, val watts: Double)

/**
 * The inverter's latest reading: [solarWatts] generated, [batteryWatts] into the battery (below 0 while it discharges),
 * [batteryPct] its charge, and when it was read.
 */
data class InverterReading(val solarWatts: Double, val batteryWatts: Double, val batteryPct: Double?, val at: Instant)

/**
 * The solar inverter and battery. Nothing reports to it until the inverter is installed and connected, and until then
 * live power has no solar or battery.
 */
class Inverter(private val changes: ChangeNotifier, private val clock: Clock) {
    @Volatile private var latest: InverterReading? = null

    fun report(solarWatts: Double, batteryWatts: Double, batteryPct: Double?) {
        latest = InverterReading(solarWatts, batteryWatts, batteryPct, clock.instant())
        changes.emit(Change.LivePowerChanged)
    }

    /** The latest reading, or null when there is none from the last [LIVE_FOR]. */
    fun live(): InverterReading? = latest?.takeIf { Duration.between(it.at, clock.instant()) < LIVE_FOR }

    companion object {
        val LIVE_FOR: Duration = LivePower.LIVE_FOR
    }
}

/**
 * Where the power goes, given [solar] generated, [battery] into the battery (below 0 while discharging) and [home] used.
 * Solar serves the home first, then the battery, and the rest is exported. A discharging battery serves the home before
 * anything is exported, and the grid makes up whatever is left. Flows of 0 W are left out.
 */
fun powerFlows(solar: Double, battery: Double, home: Double): List<PowerFlow> {
    val sun = maxOf(0.0, solar)
    val use = maxOf(0.0, home)
    val charge = maxOf(0.0, battery)
    val discharge = maxOf(0.0, -battery)
    val solarToHome = minOf(sun, use)
    val solarToBattery = minOf(sun - solarToHome, charge)
    val solarToGrid = sun - solarToHome - solarToBattery
    val batteryToHome = minOf(discharge, use - solarToHome)
    val batteryToGrid = discharge - batteryToHome
    val gridToHome = use - solarToHome - batteryToHome
    val gridToBattery = charge - solarToBattery
    return listOf(
        PowerFlow(SOLAR, HOME, solarToHome),
        PowerFlow(SOLAR, BATTERY, solarToBattery),
        PowerFlow(SOLAR, GRID, solarToGrid),
        PowerFlow(BATTERY, HOME, batteryToHome),
        PowerFlow(BATTERY, GRID, batteryToGrid),
        PowerFlow(GRID, HOME, gridToHome),
        PowerFlow(GRID, BATTERY, gridToBattery),
    ).filter { it.watts > 0.0 }
}

private const val SOLAR = "solar"
private const val BATTERY = "battery"
private const val GRID = "grid"
private const val HOME = "home"
