package heating.app.ui.energy

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalGasStation
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import heating.app.data.EnergyDashboard
import heating.app.data.PowerBands
import heating.app.data.TodayPeak
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

private const val START = 150f
private const val SWEEP = 240f

/** The same ease as [sweep]: out-cubic, never past the end. */
private val SweepEasing = Easing { t -> 1 - (1 - t).pow(3) }

/**
 * The Energy tab's dashboard, like a car's: the whole house and the busiest room or area side by side, today's peak and
 * the daily target as a fuel gauge under them. The two dials share one scale, which only grows while the screen is open.
 */
@Composable
fun EnergyDials(
    watts: Double?,
    connected: Boolean,
    bands: PowerBands?,
    busiest: Place?,
    peak: TodayPeak?,
    today: EnergyDashboard?,
    dark: Boolean,
    onSetTarget: (() -> Unit)?,
    counters: (@Composable () -> Unit)? = null,
) {
    var max by remember { mutableStateOf(dialMax(maxOf(watts ?: 0.0, busiest?.watts ?: 0.0, peak?.watts ?: 0.0))) }
    val wanted = dialMax(maxOf(watts ?: 0.0, busiest?.watts ?: 0.0, peak?.watts ?: 0.0))
    if (wanted > max) max = wanted

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        BoxWithConstraints {
            val phone = maxWidth < PHONE_WIDTH
            val wide = maxWidth >= WIDE_WIDTH
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (phone) {
                    // On a phone only the whole-house dial: four gauges bunch up on a small screen.
                    Box(Modifier.widthIn(max = 300.dp).fillMaxWidth().align(Alignment.CenterHorizontally)) {
                        SpeedDial("Whole house", watts, bands, max, if (connected) "No reading" else "Connecting…", dark)
                    }
                } else if (wide) {
                    // Wide, like the browser: peak, the two dials and the fuel gauge in one row.
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
                        Box(Modifier.weight(1f)) { PeakGauge(peak, max, dark) }
                        Box(Modifier.weight(1.5f)) {
                            SpeedDial("Whole house", watts, bands, max, if (connected) "No reading" else "Connecting…", dark)
                        }
                        Box(Modifier.weight(1.5f)) {
                            SpeedDial(busiest?.name ?: "Busiest room", busiest?.watts, null, max, if (connected) NO_PLACES else "Connecting…", dark)
                        }
                        Box(Modifier.weight(1f)) { FuelGauge(today, dark, onSetTarget) }
                    }
                } else {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Box(Modifier.weight(1f)) {
                            SpeedDial("Whole house", watts, bands, max, if (connected) "No reading" else "Connecting…", dark)
                        }
                        Box(Modifier.weight(1f)) {
                            SpeedDial(busiest?.name ?: "Busiest room", busiest?.watts, null, max, if (connected) NO_PLACES else "Connecting…", dark)
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.Bottom) {
                        Box(Modifier.weight(1f)) { PeakGauge(peak, max, dark) }
                        Box(Modifier.weight(1f)) { FuelGauge(today, dark, onSetTarget) }
                    }
                }
                counters?.invoke()
            }
        }
    }
}

/** Narrower than this the dashboard shows only the whole-house dial. */
private val PHONE_WIDTH = 600.dp

private fun DrawScope.point(angle: Float, r: Float, c: Offset = center): Offset {
    val rad = Math.toRadians(angle.toDouble())
    return Offset(c.x + r * cos(rad).toFloat(), c.y + r * sin(rad).toFloat())
}

private fun DrawScope.arc(color: Color, from: Float, to: Float, r: Float, width: Float, cap: StrokeCap, c: Offset = center) {
    drawArc(color, from, to - from, useCenter = false, topLeft = Offset(c.x - r, c.y - r), size = Size(r * 2, r * 2), style = Stroke(width, cap = cap))
}

/** A big dial: a coloured arc up to the needle, colour bands as a thin outer ring, the watts in the middle and a label under it. */
@Composable
private fun SpeedDial(label: String, watts: Double?, bands: PowerBands?, max: Double, empty: String, dark: Boolean) {
    val needle = remember { Animatable((watts ?: 0.0).toFloat()) }
    LaunchedEffect(watts) { needle.animateTo((watts ?: 0.0).toFloat(), tween(SWEEP_MS, easing = SweepEasing)) }
    val shown = needle.value.toDouble()
    val zone = watts?.let { powerZone(shown, bands) }
    val color by animateColorAsState(zone?.let { zoneColor(it, dark) } ?: MaterialTheme.colorScheme.outline, tween(700), label = "dial colour")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val tickColor = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val tickPaint = remember(tickColor, density) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = with(density) { 9.dp.toPx() }
            this.color = tickColor.toArgb()
        }
    }
    val needleAlpha by animateFloatAsState(if (watts == null) 0.3f else 1f, label = "needle")
    val reading = if (watts != null) dialReading(shown) else empty
    fun angle(w: Double) = START + SWEEP * dialFraction(w, max).toFloat()

    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().semantics { contentDescription = "$label: $reading" }) {
        Box(Modifier.widthIn(max = 260.dp).fillMaxWidth().aspectRatio(1f), contentAlignment = Alignment.Center) {
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
                drawLine(ink.copy(alpha = needleAlpha), center, point(angle(shown), r * 0.8f), strokeWidth = size.minDimension * 0.013f, cap = StrokeCap.Round)
                drawCircle(ink, radius = size.minDimension * 0.028f, center = center)
            }
            Text(
                reading,
                style = if (watts == null) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                color = if (watts == null) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 18.dp, start = 8.dp, end = 8.dp),
            )
        }
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Today's peak on the house dial's scale; tapping it shows what was using the power then. */
@Composable
private fun PeakGauge(peak: TodayPeak?, max: Double, dark: Boolean) {
    var open by remember { mutableStateOf(false) }
    val track = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val high = zoneColor(PowerZone.HIGH, dark)
    val (reading, at) = peakReading(peak)
    fun angle(w: Double) = START + SWEEP * dialFraction(w, max).toFloat()

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth()
            .then(if (peak != null) Modifier.clickable(onClickLabel = "What was using the power") { open = true } else Modifier)
            .semantics { contentDescription = "Today's peak: $reading $at" },
    ) {
        Canvas(Modifier.widthIn(max = 150.dp).fillMaxWidth().aspectRatio(1.3f)) {
            val c = Offset(size.width / 2, size.height * 0.58f)
            val r = size.height * 0.42f
            val thick = size.height * 0.08f
            arc(track, START, START + SWEEP, r, thick, StrokeCap.Round, c)
            peak?.let { arc(high.copy(alpha = 0.85f), START, maxOf(START + 0.5f, angle(it.watts)), r, thick, StrokeCap.Round, c) }
            drawLine(ink.copy(alpha = if (peak == null) 0.3f else 1f), c, point(angle(peak?.watts ?: 0.0), r * 0.8f, c), strokeWidth = size.height * 0.02f, cap = StrokeCap.Round)
            drawCircle(ink, radius = size.height * 0.035f, center = c)
        }
        Text(reading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text("Today's peak", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(at, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }

    if (open && peak != null) {
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = { TextButton(onClick = { open = false }) { Text("Done") } },
            title = { Text("$reading $at") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    peak.parts.zip(peakLines(peak)).forEach { (part, line) ->
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(Modifier.fillMaxWidth()) {
                                Text(line.first, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (part.kind == "else") MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                                Text(line.second, style = MaterialTheme.typography.bodyMedium)
                            }
                            LinearProgressIndicator(
                                progress = { if (peak.watts > 0) (part.watts / peak.watts).toFloat() else 0f },
                                modifier = Modifier.fillMaxWidth().height(4.dp),
                                color = if (part.kind == "else") MaterialTheme.colorScheme.outline else zoneColor(PowerZone.NEUTRAL, dark),
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                                drawStopIndicator = {},
                            )
                        }
                    }
                }
            },
        )
    }
}

/** The daily target as a fuel gauge: full (F) at midnight, draining to E, with a tick where it should be by now. */
@Composable
private fun FuelGauge(today: EnergyDashboard?, dark: Boolean, onSetTarget: (() -> Unit)?) {
    val target = today?.target
    val f = target?.let(::fuel)
    val left by animateFloatAsState((f?.left ?: 0.0).toFloat(), tween(SWEEP_MS), label = "fuel")
    val track = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val high = zoneColor(PowerZone.HIGH, dark)
    val density = LocalDensity.current
    val letters = remember(muted, density) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = with(density) { 11.dp.toPx() }
            isFakeBoldText = true
            color = muted.toArgb()
        }
    }
    fun angle(v: Float) = 180f + 180f * v

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().semantics {
            contentDescription = when {
                f == null || target == null -> "Daily target: $NO_TARGET"
                f.overKwh > 0 -> "Daily target: ${fuelLeft(f)} ${fuelUsed(target)}"
                else -> "Daily target: ${fuelLeft(f)} ${fuelUsed(target)}, ${paceLine(f.paceKwh)}"
            }
        },
    ) {
        Canvas(Modifier.widthIn(max = 150.dp).fillMaxWidth().aspectRatio(1.6f)) {
            val c = Offset(size.width / 2, size.height * 0.85f)
            val r = size.width * 0.36f
            val thick = size.width * 0.055f
            arc(track, 180f, 360f, r, thick, StrokeCap.Round, c)
            if (f != null) {
                arc(high.copy(alpha = 0.7f), 180f, 180f + 36f, r, thick, StrokeCap.Round, c)
                drawLine(ink, point(angle(f.pace.toFloat()), r * 0.82f, c), point(angle(f.pace.toFloat()), r * 1.18f, c), strokeWidth = size.width * 0.02f, cap = StrokeCap.Round)
            }
            drawContext.canvas.nativeCanvas.drawText("E", c.x - r, c.y + letters.textSize * 1.3f, letters)
            drawContext.canvas.nativeCanvas.drawText("F", c.x + r, c.y + letters.textSize * 1.3f, letters)
            drawLine(ink.copy(alpha = if (f == null) 0.3f else 1f), c, point(angle(left), r * 0.85f, c), strokeWidth = size.width * 0.015f, cap = StrokeCap.Round)
            drawCircle(ink, radius = size.width * 0.025f, center = c)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 6.dp)) {
            Icon(
                Icons.Rounded.LocalGasStation,
                contentDescription = if (f?.low == true) "Low fuel" else null,
                tint = if (f?.low == true) zoneColor(PowerZone.MID, dark) else muted.copy(alpha = 0.4f),
                modifier = Modifier.size(16.dp),
            )
            if (f != null) Text(fuelLeft(f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        }
        if (f != null && target != null) {
            val pace = if (f.paceKwh <= 0) zoneColor(PowerZone.LOW, dark) else zoneColor(PowerZone.MID, dark)
            Text(
                buildAnnotatedString {
                    append(fuelUsed(target))
                    if (f.overKwh <= 0) {
                        append(" · ")
                        withStyle(SpanStyle(color = pace)) { append(paceLine(f.paceKwh)) }
                    }
                },
                style = MaterialTheme.typography.labelSmall,
                color = muted,
                textAlign = TextAlign.Center,
            )
        } else {
            Text(NO_TARGET, style = MaterialTheme.typography.labelMedium, color = muted)
            today?.let { Text(todayLine(it), style = MaterialTheme.typography.labelSmall, color = muted) }
            if (onSetTarget != null && today?.electricityMeter != null) TextButton(onClick = onSetTarget) { Text(SET_TARGET) }
        }
    }
}
