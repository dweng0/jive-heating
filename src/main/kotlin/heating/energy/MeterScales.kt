package heating.energy

import heating.ApiException
import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A correction factor the admin gives a meter whose clamp reads too low or too high, such as 3 for a clamp reporting a third of
 * the real load. The meter's power and energy are multiplied by it as they arrive, so everything that uses the meter sees the
 * corrected reading. A meter with no factor (or 1) is read as it reports. A meter that leaves Zigbee2MQTT loses its factor.
 */
class MeterScales(private val store: Store) {
    private val scales: MutableMap<String, Double> =
        (store.get(KEY)?.let { heatingJson.decodeFromString(SERIALIZER, it) } ?: emptyMap()).toMutableMap()
    private val listeners = CopyOnWriteArrayList<(String, Double, Double) -> Unit>()

    /** [meter]'s factor, 1.0 when it has none. */
    @Synchronized
    fun of(meter: String): Double = scales[meter] ?: 1.0

    /** Sets [meter]'s factor; 1 clears it. 400 unless it is more than 0 and at most [MAX]. */
    fun set(meter: String, factor: Double) {
        if (!factor.isFinite() || factor <= 0.0 || factor > MAX) throw ApiException.badRequest("factor must be more than 0 and at most $MAX")
        change(meter) { if (factor == 1.0) scales.remove(meter) != null else scales.put(meter, factor) != factor }
    }

    fun clear(meter: String) = change(meter) { scales.remove(meter) != null }

    fun meterRemoved(meter: String) = clear(meter)

    /** Called with a meter, its old factor and its new one, after its factor has changed. */
    fun onChanged(listener: (String, Double, Double) -> Unit) {
        listeners += listener
    }

    private fun change(meter: String, apply: () -> Boolean) {
        var old = 1.0
        var new = 1.0
        val changed = synchronized(this) {
            old = scales[meter] ?: 1.0
            apply().also {
                new = scales[meter] ?: 1.0
                if (it) store.put(KEY, heatingJson.encodeToString(SERIALIZER, scales))
            }
        }
        if (changed) listeners.forEach { it(meter, old, new) }
    }

    companion object {
        const val MAX = 10.0
        private const val KEY = "meter-scales"
        private val SERIALIZER = MapSerializer(String.serializer(), Double.serializer())
    }
}
