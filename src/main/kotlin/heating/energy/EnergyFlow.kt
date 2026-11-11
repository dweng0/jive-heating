package heating.energy

import heating.history.HistoryRepository
import heating.rooms.Room
import kotlinx.serialization.Serializable
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime

/** One node of the energy flow: a source (electricity, gas), a place (room, area, heating) or a device (plug). openapi: FlowNode */
@Serializable
data class FlowNode(val id: String, val name: String, val kind: String, val kwh: Double, val cost: Double, val estimated: Boolean = false)

/** kWh flowing from one node to another; [estimated] when it is shared out rather than measured. openapi: FlowLink */
@Serializable
data class FlowLink(val from: String, val to: String, val kwh: Double, val estimated: Boolean = false)

/**
 * Where the house's energy went over a dashboard period, for a Sankey diagram: electricity from the house meter to each
 * room, area, plug in neither and "Everything else", then to their plugs; gas to "Heating", then to the rooms by their
 * hours calling for heat while the boiler burned. openapi: EnergyFlow
 */
@Serializable
data class EnergyFlow(val currency: String, val from: String, val to: String, val nodes: List<FlowNode>, val links: List<FlowLink>)

/** Works out [EnergyFlow] from the dashboard's breakdown and the rooms' temperatures while the boiler burned. */
class EnergyFlowReport(
    private val dashboards: EnergyDashboards,
    private val history: HistoryRepository,
    private val rooms: () -> List<Room>,
    private val label: (String) -> String,
) {
    fun of(period: DashboardPeriod): EnergyFlow {
        val d = dashboards.of(period)
        val nodes = mutableListOf<FlowNode>()
        val links = mutableListOf<FlowLink>()
        val ePrice = d.electricityKwh?.takeIf { it > 0 }?.let { (d.electricityCost ?: 0.0) / it } ?: 0.0
        val gPrice = d.gasKwh.takeIf { it > 0 }?.let { d.gasCost / it } ?: 0.0
        fun node(id: String, name: String, kind: String, kwh: Double, price: Double, estimated: Boolean = false) {
            nodes += FlowNode(id, name, kind, round(kwh), round(kwh * price, 1), estimated)
        }
        fun link(from: String, to: String, kwh: Double, estimated: Boolean = false) {
            if (kwh > 0.0005) links += FlowLink(from, to, round(kwh), estimated)
        }

        d.electricityKwh?.let { house ->
            node(ELECTRICITY, "Electricity", "source", house, ePrice)
            d.rooms.filter { it.kwh > 0 }.forEach { room ->
                val id = "room:${room.room}"
                node(id, room.name, "place", room.kwh, ePrice)
                link(ELECTRICITY, id, room.kwh)
                d.devices.filter { it.room == room.room && it.kwh > 0 }.forEach { device ->
                    node("device:${device.meter}", label(device.meter), "device", device.kwh, ePrice)
                    link(id, "device:${device.meter}", device.kwh)
                }
            }
            val inAreas = d.areas.flatMap { a -> a.plugs.map { it.meter } }.toSet()
            d.areas.filter { it.kwh > 0 }.forEach { area ->
                val id = "area:${area.area}"
                node(id, area.name, "place", area.kwh, ePrice)
                link(ELECTRICITY, id, area.kwh)
                area.plugs.filter { it.kwh > 0 }.forEach { plug ->
                    node("device:${plug.meter}", label(plug.meter), "device", plug.kwh, ePrice)
                    link(id, "device:${plug.meter}", plug.kwh)
                }
            }
            // A plug in no room or area is a place of its own.
            d.devices.filter { it.room == null && it.meter !in inAreas && it.kwh > 0 }.forEach { device ->
                val id = "device:${device.meter}"
                node(id, label(device.meter), "place", device.kwh, ePrice)
                link(ELECTRICITY, id, device.kwh)
            }
            d.everythingElse?.takeIf { it.kwh > 0 }?.let { rest ->
                node(ELSE, "Everything else", "place", rest.kwh, ePrice)
                link(ELECTRICITY, ELSE, rest.kwh)
            }
        }

        if (d.gasKwh > 0) {
            node(GAS, "Gas", "source", d.gasKwh, gPrice, estimated = d.gasEstimatedKwh > 0)
            node(HEATING, "Heating", "place", d.gasKwh, gPrice, estimated = d.gasEstimatedKwh > 0)
            link(GAS, HEATING, d.gasKwh)
            val from = OffsetDateTime.parse(d.from).toInstant()
            val to = OffsetDateTime.parse(d.to).toInstant()
            val weights = callingMinutes(from, to)
            val total = weights.values.sum()
            if (total > 0) {
                val names = rooms().associate { it.id to it.name }
                weights.filterValues { it > 0 }.forEach { (room, minutes) ->
                    val kwh = d.gasKwh * minutes / total
                    val id = if (room == null) "heat:none" else "heat:$room"
                    node(id, if (room == null) "Heating, no room calling" else names[room] ?: room, "place", kwh, gPrice, estimated = true)
                    link(HEATING, id, kwh, estimated = true)
                }
            }
        }
        return EnergyFlow(d.currency, d.from, d.to, nodes, links)
    }

    /**
     * For each room, the minutes it was below its target while the boiler burned between [from] and [to]; under null
     * the burning minutes when no room was. A room's last reading holds for up to [STALE] after it.
     */
    private fun callingMinutes(from: Instant, to: Instant): Map<String?, Int> {
        val burning = history.boilerStates(from, to).filter { it.state in BURNING }
        if (burning.isEmpty()) return emptyMap()
        val points = history.points(from.minus(STALE), to).groupBy { it.roomId }.mapValues { (_, list) -> list.sortedBy { it.at } }
        val cursor = points.keys.associateWith { 0 }.toMutableMap()
        val minutes = mutableMapOf<String?, Int>()
        burning.forEach { period ->
            var t = maxOf(period.from, from)
            val end = minOf(period.to ?: to, to)
            while (t < end) {
                val calling = points.mapNotNull { (room, list) ->
                    var i = cursor.getValue(room)
                    while (i + 1 < list.size && !list[i + 1].at.isAfter(t)) i++
                    cursor[room] = i
                    val p = list[i]
                    val holds = !p.at.isAfter(t) && Duration.between(p.at, t) <= STALE
                    room.takeIf { holds && p.current != null && p.target != null && p.current < p.target }
                }
                if (calling.isEmpty()) minutes.merge(null, 1, Int::plus) else calling.forEach { minutes.merge(it, 1, Int::plus) }
                t = t.plus(MINUTE)
            }
        }
        return minutes
    }

    private fun round(v: Double, places: Int = 3) = BigDecimal.valueOf(v).setScale(places, RoundingMode.HALF_UP).toDouble()

    private companion object {
        const val ELECTRICITY = "electricity"
        const val GAS = "gas"
        const val HEATING = "heating"
        const val ELSE = "else"
        val BURNING = setOf("heating", "cycling")
        val STALE: Duration = Duration.ofMinutes(15)
        val MINUTE: Duration = Duration.ofMinutes(1)
    }
}
