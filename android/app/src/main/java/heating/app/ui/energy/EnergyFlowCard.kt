package heating.app.ui.energy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import heating.app.data.EnergyFlow
import heating.app.data.HeatingApi
import java.util.Locale

/**
 * "Where it went": the house's energy as a Sankey diagram, from electricity and gas through rooms, areas and heating
 * to plugs, each band as wide as its kWh. Tapping a node or band says its kWh, cost and share of its source; estimated
 * bands (gas shared between rooms) are dashed.
 */
@Composable
fun EnergyFlowCard(api: HeatingApi) {
    var period by rememberSaveable { mutableStateOf(FlowPeriod.TODAY) }
    var flow by remember { mutableStateOf<EnergyFlow?>(null) }
    var readout by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(period) {
        readout = null
        runCatching { api.energyFlow(period.query) }.onSuccess { flow = it }
    }
    val electricity = zoneColor(PowerZone.NEUTRAL, MaterialTheme.colorScheme.background.luminanceDark())
    val gas = heating.app.ui.theme.HeatingTheme.colors.heat
    val onSurface = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    fun colour(source: String) = when (source) { "electricity" -> electricity; "gas" -> gas; else -> muted }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Where it went", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FlowPeriod.entries.forEach { p -> FilterChip(selected = period == p, onClick = { period = p }, label = { Text(p.label) }) }
            }
            val f = flow ?: return@Column
            if (f.nodes.none { it.id == "electricity" }) Text(NO_HOUSE_METER_FLOW, style = MaterialTheme.typography.bodyMedium, color = muted)
            Text(readout ?: "Tap a band to see what it used.", style = MaterialTheme.typography.bodySmall, color = muted)
            if (f.nodes.isEmpty()) return@Column
            val tallest = listOf("source", "place", "device").maxOf { kind -> f.nodes.count { it.kind == kind } }.coerceAtLeast(1)
            val heightDp = (tallest * 30).coerceAtLeast(220).dp
            val labelRoom = with(density) { 96.dp.toPx() }
            val nodeWidth = with(density) { 10.dp.toPx() }
            val pad = with(density) { 10.dp.toPx() }
            // The last layout drawn, for taps to find what they hit; not state, as drawing must not recompose.
            val laidOut = remember { LaidOut() }
            val paint = remember(onSurface, density) {
                android.graphics.Paint().apply { isAntiAlias = true; textSize = with(density) { 11.dp.toPx() }; color = onSurface.toArgb() }
            }
            Canvas(
                Modifier.fillMaxWidth().height(heightDp).semantics { contentDescription = "Where the house's energy went" }
                    .pointerInput(f) {
                        detectTapGestures { tap ->
                            val (nodes, links) = laidOut.value ?: return@detectTapGestures
                            val p = Offset(tap.x - labelRoom, tap.y - pad)
                            val node = nodes.firstOrNull { p.x in it.x - 8f..it.x + nodeWidth + 8f && p.y in it.y..it.y + it.height }
                            val src = { id: String -> f.nodes.firstOrNull { it.id == id } }
                            readout = if (node != null) flowReadout(node.node.name, node.node.kwh, node.node.cost, src(node.source), f.currency)
                            else links.firstOrNull { l ->
                                if (p.x < l.x0 || p.x > l.x1) return@firstOrNull false
                                val t = (p.x - l.x0) / (l.x1 - l.x0)
                                val top = l.y0 + (l.y1 - l.y0) * (3 * t * t - 2 * t * t * t)
                                p.y in top..top + maxOf(l.width, 6f)
                            }?.let { l ->
                                val to = f.nodes.first { it.id == l.link.to }
                                val from = f.nodes.first { it.id == l.link.from }
                                val cost = if (to.kwh > 0) to.cost * l.link.kwh / to.kwh else 0.0
                                flowReadout("${from.name} → ${to.name}", l.link.kwh, cost, src(l.source), f.currency) + if (l.link.estimated) " · estimated" else ""
                            }
                        }
                    },
            ) {
                val result = layoutFlow(f, size.width - labelRoom * 2, size.height - pad * 2, nodeWidth)
                laidOut.value = result
                val (nodes, links) = result
                translate(labelRoom, pad) {
                    links.forEach { l ->
                        val mid = (l.x0 + l.x1) / 2
                        val w = maxOf(l.width, 1f)
                        val band = Path().apply {
                            moveTo(l.x0, l.y0)
                            cubicTo(mid, l.y0, mid, l.y1, l.x1, l.y1)
                            lineTo(l.x1, l.y1 + w)
                            cubicTo(mid, l.y1 + w, mid, l.y0 + w, l.x0, l.y0 + w)
                            close()
                        }
                        val c = colour(l.source)
                        drawPath(band, c.copy(alpha = if (l.link.estimated) 0.15f else 0.3f))
                        if (l.link.estimated) drawPath(band, c.copy(alpha = 0.6f), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
                    }
                    nodes.forEach { n ->
                        drawRect(colour(n.source), Offset(n.x, n.y), Size(nodeWidth, n.height))
                        val text = "${n.node.name} ${String.format(Locale.UK, "%.1f", n.node.kwh)} kWh"
                        val y = n.y + n.height / 2 + paint.textSize / 3
                        if (n.column == 0) {
                            paint.textAlign = android.graphics.Paint.Align.RIGHT
                            drawContext.canvas.nativeCanvas.drawText(text, n.x - 6f, y, paint)
                        } else {
                            paint.textAlign = android.graphics.Paint.Align.LEFT
                            drawContext.canvas.nativeCanvas.drawText(text, n.x + nodeWidth + 6f, y, paint)
                        }
                    }
                }
            }
        }
    }
}

private class LaidOut { var value: Pair<List<PlacedNode>, List<PlacedLink>>? = null }

private fun Color.luminanceDark(): Boolean = (0.299f * red + 0.587f * green + 0.114f * blue) < 0.5f
