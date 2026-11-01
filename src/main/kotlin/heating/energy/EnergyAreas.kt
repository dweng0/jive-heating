package heating.energy

import heating.ApiException
import heating.heatingJson
import heating.store.Store
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/**
 * A named thing with meters and no valves, such as "Lights" or "Garage/Office". It is not a room: it has no heating.
 * Its [id] comes from the name it was created with and stays the same through a rename.
 *
 * openapi: EnergyArea
 */
@Serializable
data class EnergyArea(val id: String, val name: String)

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

    @Synchronized
    fun delete(id: String) {
        if (!areas.removeIf { it.id == id }) throw ApiException.notFound("area not found")
        save()
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
