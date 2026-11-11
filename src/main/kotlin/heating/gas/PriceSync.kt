package heating.gas

import heating.Periodic
import heating.activity.ActivityLog
import heating.energy.EnergySettings
import heating.energy.EnergySettingsRepository
import kotlinx.serialization.json.JsonPrimitive
import org.slf4j.LoggerFactory
import java.time.Duration
import java.time.Instant
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Keeps the energy prices at the Bright account's tariff while syncPricesWithBright is on: at once when it is turned
 * on, then every day, off the tick's thread on [executor]. A fetch that fails keeps the prices and is tried again the
 * next day. Only the four prices change; a change is logged in the activity feed.
 */
class PriceSync(
    private val meter: GasMeter,
    private val settings: EnergySettingsRepository,
    private val activity: ActivityLog?,
    private val executor: Executor,
    private val now: () -> Instant,
) {
    private var nextAt: Instant? = null
    private val running = AtomicBoolean(false)

    val tick = Periodic { at ->
        if (!settings.get().syncPricesWithBright) return@Periodic
        val instant = at.toInstant()
        if (nextAt?.let { instant < it } == true) return@Periodic
        nextAt = instant.plus(EVERY)
        if (!running.compareAndSet(false, true)) return@Periodic
        executor.execute {
            try {
                sync()
            } catch (e: Exception) {
                log.warn("Bright tariff fetch failed: ${e.message}")
            } finally {
                running.set(false)
            }
        }
    }

    /** Fetch the tariff now (just turned on), and next in a day; a failure is logged and keeps the prices. */
    fun syncNow() {
        nextAt = now().plus(EVERY)
        try {
            sync()
        } catch (e: Exception) {
            log.warn("Bright tariff fetch failed: ${e.message}")
        }
    }

    private fun sync() {
        val tariff = meter.tariff() ?: return
        var before: EnergySettings? = null
        val after = settings.update { current ->
            before = current
            if (!current.syncPricesWithBright) current
            else current.copy(
                gasPencePerKwh = tariff.gasPencePerKwh,
                gasStandingPencePerDay = tariff.gasStandingPencePerDay,
                electricityPencePerKwh = tariff.electricityPencePerKwh ?: current.electricityPencePerKwh,
                electricityStandingPencePerDay = tariff.electricityStandingPencePerDay ?: current.electricityStandingPencePerDay,
            )
        }
        if (after != before) {
            val text = "gas ${after.gasPencePerKwh}p/kWh + ${after.gasStandingPencePerDay}p/day, " +
                "electricity ${after.electricityPencePerKwh}p/kWh + ${after.electricityStandingPencePerDay}p/day"
            log.info("prices from Bright: {}", text)
            activity?.record("prices", null, null, JsonPrimitive(text), reason = "Bright", adminOnly = true)
        }
    }

    private companion object {
        val EVERY: Duration = Duration.ofDays(1)
        val log = LoggerFactory.getLogger(PriceSync::class.java)
    }
}
