package heating.app.ui.energy

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import heating.app.data.EnergyDashboard
import heating.app.data.HeatingApi
import heating.app.data.LivePower
import heating.app.data.PowerBands
import heating.app.data.PowerPoint
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private const val START = 150f
private const val SWEEP = 240f
/** Today's kWh and cost are recorded every five minutes; there is no point asking more often. */
private const val TODAY_EVERY_MS = 5 * 60_000L

/** The same ease as [sweep]: out-cubic, never past the end. */
private val SweepEasing = Easing { t -> 1 - (1 - t).pow(3) }

/** Same colours as the web's --power-*: aqua, yellow, red and blue from the validated palette. */
private fun zoneColor(zone: PowerZone, dark: Boolean): Color = when (zone) {
    PowerZone.LOW -> Color(if (dark) 0xFF199E70 else 0xFF1BAF7A)
    PowerZone.MID -> Color(if (dark) 0xFFC98500 else 0xFFEDA100)
    PowerZone.HIGH -> Color(if (dark) 0xFFE66767 else 0xFFE34948)
    PowerZone.NEUTRAL -> Color(if (dark) 0xFF3987E5 else 0xFF2A78D6)
}

/** The house's electricity now, on a dial coloured by the household's own use, with its rooms as cards. Admin only. */
@Composable
fun LivePowerSection(api: HeatingApi) {
    var live by remember { mutableStateOf<LivePower?>(null) }
    var bands by remember { mutableStateOf<PowerBands?>(null) }
    var recent by remember { mutableStateOf<List<PowerPoint>>(emptyList()) }
    var today by remember { mutableStateOf<EnergyDashboard?>(null) }

    LaunchedEffect(Unit) {
        launch {
            runCatching { api.powerSummary() }.onSuccess { s ->
                bands = s.bands
                recent = s.recent + recent.filter { p -> s.recent.lastOrNull()?.let { p.at > it.at } ?: true }
            }
        }
        launch {
            while (true) {
                runCatching { api.energyDashboard("today") }.onSuccess { today = it }
                delay(TODAY_EVERY_MS)
            }
        }
        // Reconnect while the Dashboard is open.
        var backoff = 1_000L
        while (true) {
            runCatching {
                api.livePower().collect { p ->
                    backoff = 1_000L
                    live = p
                    recent = withReading(recent, p)
                }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    // No house meter chosen: nothing to show live.
    if (today?.electricityMeter == null && today != null) return

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val watts = live?.watts
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.fillMaxWidth()) {
                    Text("Electricity now", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("The whole house, live from the meter", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                PowerDial(watts, bands, connected = live != null, dark = dark)
                today?.let { Text(todayLine(it), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.fillMaxWidth()) }
                Sparkline(recent, bands, zoneColor(PowerZone.NEUTRAL, dark))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("30 min ago", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("Now", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        live?.let { p -> liveCards(p, today).forEach { UseCard(it, zoneColor(PowerZone.NEUTRAL, dark)) } }
    }
}

private fun DrawScope.point(angle: Float, r: Float): Offset {
    val rad = Math.toRadians(angle.toDouble())
    return Offset(center.x + r * cos(rad).toFloat(), center.y + r * sin(rad).toFloat())
}

private fun DrawScope.arc(color: Color, from: Float, to: Float, r: Float, width: Float, cap: StrokeCap) {
    drawArc(color, from, to - from, useCenter = false, topLeft = Offset(center.x - r, center.y - r), size = Size(r * 2, r * 2), style = Stroke(width, cap = cap))
}

/** The gauge: a coloured arc up to the needle, the household's bands as a thin outer ring, big watts in the middle. */
@Composable
private fun PowerDial(watts: Double?, bands: PowerBands?, connected: Boolean, dark: Boolean) {
    // The scale only grows while the screen is open, so a big reading does not shrink everything after it.
    var max by remember { mutableStateOf(dialMax(watts)) }
    val wanted = dialMax(watts)
    if (wanted > max) max = wanted

    val needle = remember { Animatable((watts ?: 0.0).toFloat()) }
    LaunchedEffect(watts) { needle.animateTo((watts ?: 0.0).toFloat(), tween(SWEEP_MS, easing = SweepEasing)) }
    val shown = needle.value.toDouble()
    val zone = watts?.let { powerZone(shown, bands) }
    val color by animateColorAsState(zone?.let { zoneColor(it, dark) } ?: MaterialTheme.colorScheme.outline, tween(700), label = "dial colour")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = androidx.compose.ui.platform.LocalDensity.current
    val tickPaint = remember(tickColor, density) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = with(density) { 11.dp.toPx() }
            this.color = tickColor.toArgb()
        }
    }
    val needleAlpha by animateFloatAsState(if (watts == null) 0.3f else 1f, label = "needle")
    val label = when {
        watts != null -> dialReading(shown)
        connected -> "No reading"
        else -> "Connecting…"
    }
    fun angle(w: Double) = START + SWEEP * dialFraction(w, max).toFloat()

    Box(Modifier.widthIn(max = 320.dp).fillMaxWidth().aspectRatio(1f).semantics { contentDescription = "Electricity now: $label" }, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxWidth().aspectRatio(1f)) {
            val r = size.minDimension * 0.4f
            val thick = size.minDimension * 0.07f
            arc(track, START, START + SWEEP, r, thick, StrokeCap.Round)
            bands?.let { b ->
                val outer = r + thick * 0.95f
                val thin = size.minDimension * 0.015f
                arc(zoneColor(PowerZone.LOW, dark), START, angle(b.green), outer, thin, StrokeCap.Butt)
                arc(zoneColor(PowerZone.MID, dark), angle(b.green), angle(b.red), outer, thin, StrokeCap.Butt)
                arc(zoneColor(PowerZone.HIGH, dark), angle(b.red), START + SWEEP, outer, thin, StrokeCap.Butt)
            }
            if (watts != null && shown > 0) arc(color, START, maxOf(START + 0.5f, angle(shown)), r, thick, StrokeCap.Round)
            dialTicks(max).forEach { t ->
                val a = point(angle(t), r * 0.72f)
                drawContext.canvas.nativeCanvas.drawText(tickLabel(t), a.x, a.y + tickPaint.textSize / 3, tickPaint)
            }
            val tip = point(angle(shown), r * 0.8f)
            drawLine(ink.copy(alpha = needleAlpha), center, tip, strokeWidth = size.minDimension * 0.013f, cap = StrokeCap.Round)
            drawCircle(ink, radius = size.minDimension * 0.028f, center = center)
        }
        Text(
            label,
            style = if (watts == null) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.displaySmall,
            fontWeight = FontWeight.Bold,
            color = if (watts == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 36.dp),
        )
    }
}

/** The last 30 minutes of house watts, with a soft fill; a missing reading breaks the line. */
@Composable
private fun Sparkline(points: List<PowerPoint>, bands: PowerBands?, color: Color) {
    val known = points.filter { it.watts != null }
    Canvas(Modifier.fillMaxWidth().height(56.dp)) {
        if (known.size < 2) return@Canvas
        val newest = OffsetDateTime.parse(points.last().at).toInstant().toEpochMilli()
        val top = maxOf(known.maxOf { it.watts!! }, bands?.red ?: 0.0, 1.0)
        fun x(at: String) = size.width * (1 - (newest - OffsetDateTime.parse(at).toInstant().toEpochMilli()) / (30 * 60_000f))
        fun y(w: Double) = size.height * (1 - (w / top).toFloat() * 0.9f)
        val runs = mutableListOf<MutableList<PowerPoint>>()
        points.forEach { p -> if (p.watts == null) runs += mutableListOf<PowerPoint>() else (runs.lastOrNull() ?: mutableListOf<PowerPoint>().also { runs += it }).add(p) }
        runs.filter { it.size > 1 }.forEach { run ->
            val line = Path().apply { run.forEachIndexed { i, p -> if (i == 0) moveTo(x(p.at), y(p.watts!!)) else lineTo(x(p.at), y(p.watts!!)) } }
            val fill = Path().apply { addPath(line); lineTo(x(run.last().at), size.height); lineTo(x(run.first().at), size.height); close() }
            drawPath(fill, Brush.verticalGradient(listOf(color.copy(alpha = 0.3f), color.copy(alpha = 0f))))
            drawPath(line, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round))
        }
    }
}

@Composable
private fun UseCard(card: LiveCard, color: Color) {
    val share by animateFloatAsState((card.share ?: 0.0).toFloat(), tween(700), label = "share")
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.Top) {
                Column(Modifier.weight(1f)) {
                    Text(card.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    card.detail?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Text(card.now, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (card.share == null && card.now == "–") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
            }
            LinearProgressIndicator(
                progress = { share },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = if (card.key == "rest") MaterialTheme.colorScheme.outline else color,
                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                drawStopIndicator = {},
            )
            Row {
                Text(card.shareText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                Text(card.today, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
