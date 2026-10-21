package heating.boost

import heating.ApiException
import heating.heatingJson
import heating.rooms.ZoneControl
import heating.store.Store
import kotlinx.serialization.Serializable

/**
 * The household's boost temperature: what a boost heats to when no target is given, and where the apps
 * start their boost picker. Users can still choose another temperature for each boost.
 *
 * openapi: BoostSettings
 */
@Serializable
data class BoostSettings(
    val defaultTarget: Double = 22.0,
)

/** Throws a 400 unless the default is a user target: 5.0 to 30.0 in 0.5 steps. */
fun BoostSettings.validated(): BoostSettings {
    val inRange = defaultTarget.isFinite() && defaultTarget in ZoneControl.MIN_TARGET..ZoneControl.MAX_TARGET
    if (!inRange || defaultTarget * 2 != Math.round(defaultTarget * 2).toDouble()) {
        throw ApiException.badRequest(
            "defaultTarget must be ${ZoneControl.MIN_TARGET} to ${ZoneControl.MAX_TARGET} in steps of 0.5",
        )
    }
    return this
}

/** The household's [BoostSettings], kept as one document in the store. */
class BoostSettingsRepository(private val store: Store) {
    private var current: BoostSettings =
        store.get(KEY)?.let { heatingJson.decodeFromString(BoostSettings.serializer(), it) } ?: BoostSettings()

    @Synchronized
    fun get(): BoostSettings = current

    @Synchronized
    fun update(change: (BoostSettings) -> BoostSettings): BoostSettings {
        val updated = change(current)
        if (updated != current) {
            store.put(KEY, heatingJson.encodeToString(BoostSettings.serializer(), updated))
            current = updated
        }
        return updated
    }

    companion object {
        private const val KEY = "boost"
    }
}
