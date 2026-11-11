package heating.app.data

import kotlinx.serialization.Serializable

/** openapi: FlowNode. A source (electricity, gas), a place (room, area, heating) or a device (plug). */
@Serializable
data class FlowNode(val id: String, val name: String, val kind: String, val kwh: Double, val cost: Double, val estimated: Boolean = false)

/** openapi: FlowLink. kWh from one node to another; [estimated] when shared out rather than measured. */
@Serializable
data class FlowLink(val from: String, val to: String, val kwh: Double, val estimated: Boolean = false)

/** openapi: EnergyFlow. Where the house's energy went over a dashboard period. */
@Serializable
data class EnergyFlow(val currency: String = "GBP", val from: String = "", val to: String = "", val nodes: List<FlowNode> = emptyList(), val links: List<FlowLink> = emptyList())
