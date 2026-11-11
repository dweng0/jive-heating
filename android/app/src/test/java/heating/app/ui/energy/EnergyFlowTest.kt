package heating.app.ui.energy

import heating.app.data.EnergyFlow
import heating.app.data.FlowLink
import heating.app.data.FlowNode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EnergyFlowTest {
    private fun node(id: String, name: String, kind: String, kwh: Double) = FlowNode(id, name, kind, kwh, kwh * 24)

    private val flow = EnergyFlow(
        nodes = listOf(
            node("electricity", "Electricity", "source", 11.8), node("room:kitchen", "Kitchen", "place", 3.0),
            node("device:Kettle", "Kettle", "device", 1.2), node("device:Oven", "Oven", "device", 1.8),
            node("area:lights", "Lights", "place", 0.9), node("else", "Everything else", "place", 7.9),
            node("gas", "Gas", "source", 20.0), node("heating", "Heating", "place", 20.0), node("heat:lounge", "Lounge", "place", 15.0),
        ),
        links = listOf(
            FlowLink("electricity", "room:kitchen", 3.0), FlowLink("electricity", "area:lights", 0.9), FlowLink("electricity", "else", 7.9),
            FlowLink("room:kitchen", "device:Kettle", 1.2), FlowLink("room:kitchen", "device:Oven", 1.8),
            FlowLink("gas", "heating", 20.0), FlowLink("heating", "heat:lounge", 15.0, estimated = true),
        ),
    )

    // BDD: The Energy tab shows where the energy went
    @Test
    fun `the flow is laid out sources first, places then devices, bands as wide as their kWh`() {
        val (nodes, links) = layoutFlow(flow, 600f, 400f)
        val byName = nodes.associateBy { it.node.name }
        assertEquals(0, byName.getValue("Electricity").column)
        assertEquals(1, byName.getValue("Kitchen").column)
        assertEquals(2, byName.getValue("Kettle").column)
        assertEquals(2, byName.getValue("Lounge").column)
        assertTrue(byName.getValue("Electricity").x < byName.getValue("Kitchen").x)
        // One scale: heights in proportion to kWh.
        assertEquals(7.9 / 0.9, (byName.getValue("Everything else").height / byName.getValue("Lights").height).toDouble(), 1e-3)
        val kitchen = links.first { it.link.to == "room:kitchen" }
        assertEquals(byName.getValue("Kitchen").height, kitchen.width, 1e-3f)
        assertEquals("gas", links.first { it.link.to == "heat:lounge" }.source)
        // Nothing runs off the bottom.
        assertTrue(nodes.all { it.y + it.height <= 400f + 1e-3f })
        assertEquals("Kitchen · 3.0 kWh · £0.72 · 25% of electricity", flowReadout("Kitchen", 3.0, 72.0, flow.nodes.first(), "GBP"))
        assertEquals(listOf("Today", "Yesterday", "Week", "Month"), FlowPeriod.entries.map { it.label })
    }
}
