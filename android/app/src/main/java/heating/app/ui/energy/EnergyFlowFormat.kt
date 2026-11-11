package heating.app.ui.energy

import heating.app.data.EnergyFlow
import heating.app.data.FlowLink
import heating.app.data.FlowNode
import java.util.Locale

/** The flow's periods, as the dashboard's. */
enum class FlowPeriod(val query: String, val label: String) { TODAY("today", "Today"), YESTERDAY("yesterday", "Yesterday"), WEEK("week", "Week"), MONTH("month", "Month") }

const val NO_HOUSE_METER_FLOW = "Choose a house meter to see where electricity goes"

/** A node laid out: its column, top-left and height; [source] is the source it flows from. */
data class PlacedNode(val node: FlowNode, val column: Int, val x: Float, val y: Float, val height: Float, val source: String)

/** A band laid out: where it leaves its node ([x0], [y0]) and reaches the next ([x1], [y1]), and how wide it is. */
data class PlacedLink(val link: FlowLink, val x0: Float, val y0: Float, val x1: Float, val y1: Float, val width: Float, val source: String)

/**
 * Lays the flow out as a Sankey diagram [width] by [height]: sources in the first column, each node a column after
 * what flows into it, stacked with [gap] between; every node and band as tall as its kWh on one scale.
 */
fun layoutFlow(flow: EnergyFlow, width: Float, height: Float, nodeWidth: Float = 12f, gap: Float = 10f): Pair<List<PlacedNode>, List<PlacedLink>> {
    val column = mutableMapOf<String, Int>()
    val source = mutableMapOf<String, String>()
    flow.nodes.filter { it.kind == "source" }.forEach { column[it.id] = 0; source[it.id] = it.id }
    var moved = true
    var guard = 0
    while (moved && guard++ < 10) {
        moved = false
        flow.links.forEach { l ->
            val c = column[l.from] ?: return@forEach
            if ((column[l.to] ?: -1) < c + 1) {
                column[l.to] = c + 1
                source[l.to] = source.getValue(l.from)
                moved = true
            }
        }
    }
    val columns = maxOf(1, (column.values.maxOrNull() ?: 0) + 1)
    val byColumn = (0 until columns).map { c -> flow.nodes.filter { column[it.id] == c } }
    val scale = byColumn.minOf { nodes ->
        val kwh = nodes.sumOf { it.kwh }
        if (kwh > 0) ((height - gap * maxOf(0, nodes.size - 1)) / kwh).toFloat() else Float.POSITIVE_INFINITY
    }.let { if (it.isFinite()) it else 0f }
    val step = if (columns > 1) (width - nodeWidth) / (columns - 1) else 0f
    val placed = linkedMapOf<String, PlacedNode>()
    byColumn.forEachIndexed { c, nodes ->
        var y = 0f
        nodes.forEach { n ->
            val h = maxOf(1f, (n.kwh * scale).toFloat())
            placed[n.id] = PlacedNode(n, c, c * step, y, h, source[n.id] ?: n.id)
            y += h + gap
        }
    }
    val out = mutableMapOf<String, Float>()
    val into = mutableMapOf<String, Float>()
    val links = flow.links.mapNotNull { l ->
        val a = placed[l.from] ?: return@mapNotNull null
        val b = placed[l.to] ?: return@mapNotNull null
        val w = (l.kwh * scale).toFloat()
        val y0 = a.y + (out[a.node.id] ?: 0f)
        val y1 = b.y + (into[b.node.id] ?: 0f)
        out[a.node.id] = (out[a.node.id] ?: 0f) + w
        into[b.node.id] = (into[b.node.id] ?: 0f) + w
        PlacedLink(l, a.x + nodeWidth, y0, b.x, y1, w, a.source)
    }
    return placed.values.toList() to links
}

/** "Kitchen · 3.0 kWh · £0.72 · 25% of electricity" */
fun flowReadout(name: String, kwh: Double, cost: Double, source: FlowNode?, currency: String): String = buildList {
    add(name)
    add(String.format(Locale.UK, "%.1f kWh", kwh))
    add(formatCost(cost, currency))
    if (source != null && source.kwh > 0 && source.name != name) add("${Math.round(kwh / source.kwh * 100)}% of ${source.name.lowercase(Locale.UK)}")
}.joinToString(" · ")
