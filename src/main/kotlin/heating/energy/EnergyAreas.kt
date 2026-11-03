package heating.energy

import heating.ApiException
import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * A named thing with meters and no valves, such as "Lights" or "Garage/Office". It is not a room: it has no heating.
 * Its [id] comes from the name it was created with and stays the same through a rename. [wholeMeter] is the meter (a
 * clamp) that measures all of it, null when it has none; a meter measures one area at most. [overlaps] are the ids of the
 * areas it shares a circuit with, both ways: some use is counted in both, and as it cannot be measured it is not guessed.
 *
 * openapi: EnergyArea
 */
@Serializable
data class EnergyArea(val id: String, val name: String, val wholeMeter: String? = null, val overlaps: List<String> = emptyList())

/** Each pair of [areas] that share a circuit, as their names in order, once per pair, sorted. */
fun sharedCircuits(areas: List<EnergyArea>): List<List<String>> {
    val names = areas.associate { it.id to it.name }
    return areas.flatMap { area -> area.overlaps.mapNotNull { other -> names[other]?.let { listOf(area.name, it).sortedBy(String::lowercase) } } }
        .distinct().sortedBy { it.joinToString("\u0000").lowercase() }
}

/** The names of the areas [area] shares a circuit with, by name. */
fun overlapNames(area: EnergyArea, areas: List<EnergyArea>): List<String> =
    areas.filter { it.id in area.overlaps }.map { it.name }.sortedBy(String::lowercase)

/** The household's [EnergyArea]s, sorted by name and kept as one document in the store. */
class EnergyAreas(private val store: Store) {
    private val areas: MutableList<EnergyArea> =
        (store.get(KEY)?.let { heatingJson.decodeFromString(SERIALIZER, it) } ?: emptyList()).toMutableList()

    @Synchronized
    fun list(): List<EnergyArea> = areas.sortedBy { it.name.lowercase() }

    @Synchronized
    fun create(name: String): EnergyArea {
        val clean = validName(name, exceptId = null)
        val area = EnergyArea(freeId(clean), clean)
        areas += area
        save()
        return area
    }

    @Synchronized
    fun rename(id: String, name: String): EnergyArea {
        val index = areas.indexOfFirst { it.id == id }
        if (index < 0) throw ApiException.notFound("area not found")
        val renamed = areas[index].copy(name = validName(name, exceptId = id))
        areas[index] = renamed
        save()
        return renamed
    }

    /** Deletes area [id]. Any area that shared a circuit with it no longer does. */
    @Synchronized
    fun delete(id: String) {
        if (!areas.removeIf { it.id == id }) throw ApiException.notFound("area not found")
        areas.replaceAll { area -> if (id in area.overlaps) area.copy(overlaps = area.overlaps - id) else area }
        save()
    }

    /** Areas [id] and [other] share a circuit, both ways. 404 for an unknown area, 400 for the area itself. */
    @Synchronized
    fun overlap(id: String, other: String): EnergyArea {
        val area = get(id)
        val with = get(other)
        if (id == other) throw ApiException.badRequest("an area cannot share a circuit with itself")
        replace(with.copy(overlaps = (with.overlaps + id).distinct().sorted()))
        return replace(area.copy(overlaps = (area.overlaps + other).distinct().sorted()))
    }

    /** Areas [id] and [other] no longer share a circuit. 404 if they did not. */
    @Synchronized
    fun stopOverlapping(id: String, other: String) {
        val area = get(id)
        if (other !in area.overlaps) throw ApiException.notFound("the areas do not share a circuit")
        replace(area.copy(overlaps = area.overlaps - other))
        areas.firstOrNull { it.id == other }?.let { replace(it.copy(overlaps = it.overlaps - id)) }
    }

    /** Each pair of areas that share a circuit, as their names in order, once per pair. */
    @Synchronized
    fun sharedCircuits(): List<List<String>> = sharedCircuits(list())

    @Synchronized
    fun get(id: String): EnergyArea = areas.firstOrNull { it.id == id } ?: throw ApiException.notFound("area not found")

    /** The id of the area [meter] measures as its whole-area meter; null when it measures none. */
    @Synchronized
    fun measuredBy(meter: String): String? = areas.firstOrNull { it.wholeMeter == meter }?.id

    /** Makes [meter] the whole-area meter of area [id], replacing any it had. 409 if it already measures another area. */
    @Synchronized
    fun measure(id: String, meter: String): EnergyArea {
        val area = get(id)
        val other = measuredBy(meter)
        if (other != null && other != id) throw ApiException.conflict("$meter already measures another area")
        return replace(area.copy(wholeMeter = meter))
    }

    /** Area [id] no longer has a whole-area meter. 404 if it had none. */
    @Synchronized
    fun stopMeasuring(id: String): EnergyArea {
        val area = get(id)
        if (area.wholeMeter == null) throw ApiException.notFound("area has no whole-area meter")
        return replace(area.copy(wholeMeter = null))
    }

    /** [meter] left Zigbee2MQTT: the area it measured, if any, no longer has a whole-area meter. Returns that area. */
    @Synchronized
    fun meterRemoved(meter: String): EnergyArea? {
        val area = areas.firstOrNull { it.wholeMeter == meter } ?: return null
        return replace(area.copy(wholeMeter = null))
    }

    private fun replace(area: EnergyArea): EnergyArea {
        areas[areas.indexOfFirst { it.id == area.id }] = area
        save()
        return area
    }

    private fun validName(name: String, exceptId: String?): String {
        val clean = name.trim()
        if (clean.isEmpty()) throw ApiException.badRequest("name is required")
        if (areas.any { it.id != exceptId && it.name.equals(clean, ignoreCase = true) }) {
            throw ApiException.conflict("an area called $clean already exists")
        }
        return clean
    }

    /** "Garage/Office" is "garage-office"; another name that reads the same is "garage-office-2". */
    private fun freeId(name: String): String {
        val base = name.lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-').ifEmpty { "area" }
        val taken = areas.map { it.id }.toSet()
        if (base !in taken) return base
        return generateSequence(2) { it + 1 }.map { "$base-$it" }.first { it !in taken }
    }

    private fun save() = store.put(KEY, heatingJson.encodeToString(SERIALIZER, areas.toList()))

    private companion object {
        const val KEY = "energy-areas"
        val SERIALIZER = ListSerializer(EnergyArea.serializer())
    }
}
