package heating.app.ui.energy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import heating.app.data.HeatingApi
import heating.app.data.HouseHistoryPoint
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** While the chart shows the present it moves on with each five-minute sample. */
private const val FOLLOW_EVERY_MS = 5 * 60_000L

/** What has been loaded for one range: points by their start (ms), and the stretches of time asked for. */
private data class Loaded(val points: Map<Long, HouseHistoryPoint> = emptyMap(), val covered: List<LongRange> = emptyList())

private fun ms(at: String) = OffsetDateTime.parse(at).toInstant().toEpochMilli()

/**
 * The house's use under the dials: a line of power over a day or week, bars of kWh over a month or year. Drag it to go
 * back in time; it loads more as it needs it, stops at the first sample and never goes past now. Tap a point to read it.
 */
@Composable
fun HouseHistoryChart(api: HeatingApi, dark: Boolean, now: () -> Long = System::currentTimeMillis) {
    var range by remember { mutableStateOf(HistoryRange.DAY) }
    var end by remember { mutableStateOf(now()) }
    var follow by remember { mutableStateOf(true) }
    val loaded = remember { mutableStateMapOf<HistoryRange, Loaded>() }
    var earliest by remember { mutableStateOf<Long?>(null) }
    var currency by remember { mutableStateOf("GBP") }
    var selected by remember { mutableStateOf<Long?>(null) }

    suspend fun load(r: HistoryRange, to: Long?) {
        val asked = to ?: now()
        runCatching { api.houseHistory(r.key, to?.let { Instant.ofEpochMilli(it).toString() }) }.onSuccess { h ->
            currency = h.currency
            earliest = h.earliest?.let(::ms)
            val was = loaded[r] ?: Loaded()
            loaded[r] = Loaded(was.points + h.points.associateBy { ms(it.at) }, was.covered + listOf(ms(h.from)..maxOf(ms(h.to), asked)))
        }
    }

    // Whatever the window shows and has not been loaded yet.
    LaunchedEffect(range, end, loaded[range], earliest) {
        val from = end - range.span
        val lacking = uncovered(loaded[range]?.covered.orEmpty(), maxOf(from, earliest ?: from) - range.step, end) ?: return@LaunchedEffect
        if (earliest != null && lacking <= earliest!!) return@LaunchedEffect
        load(range, if (lacking >= now() - 1000) null else lacking)
    }
    // Following the present: the newest points again every five minutes.
    LaunchedEffect(follow, range) {
        while (follow) {
            delay(FOLLOW_EVERY_MS)
            end = now()
            load(range, null)
        }
    }

    fun moveTo(next: Long) {
        val t = now()
        end = clampEnd(next, range.span, t, earliest)
        follow = end >= t - range.step
    }

    val from = end - range.span
    val rows = loaded[range]?.points.orEmpty().filterKeys { it >= from - range.step * 2 && it <= end + range.step }.toSortedMap()
    val shownPoints = rows.count { it.key in from..end }
    val currentEnd by rememberUpdatedState(end)
    val currentRange by rememberUpdatedState(range)
    val line = zoneColor(PowerZone.NEUTRAL, dark)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val density = LocalDensity.current
    val labelPaint = remember(muted, density) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textAlign = android.graphics.Paint.Align.CENTER
            textSize = with(density) { 10.dp.toPx() }
            color = muted.toArgb()
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("The whole house. Drag to go back in time.", style = MaterialTheme.typography.bodySmall, color = muted)
                }
                if (!follow) OutlinedButton(onClick = { moveTo(now()); selected = null }) { Text("Now") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                HistoryRange.entries.forEach { r ->
                    FilterChip(
                        selected = range == r,
                        onClick = {
                            range = r
                            selected = null
                            // Keep the time at the right edge.
                            val t = now()
                            end = if (follow) t else clampEnd(end, r.span, t, earliest)
                        },
                        label = { Text(r.label) },
                    )
                }
            }
            Canvas(
                Modifier.fillMaxWidth().height(200.dp).testTag("history-chart")
                    .semantics { contentDescription = "History chart, ${range.label}, $shownPoints points" }
                    .pointerInput(Unit) {
                        var startEnd = 0L
                        var dragged = 0f
                        detectHorizontalDragGestures(
                            onDragStart = { startEnd = currentEnd; dragged = 0f },
                        ) { change, dx ->
                            change.consume()
                            dragged += dx
                            // Dragging right shows earlier times.
                            moveTo(startEnd - (dragged / size.width * currentRange.span).toLong())
                        }
                    }
                    .pointerInput(rows.keys.toList()) {
                        detectTapGestures { pos ->
                            val t = currentEnd - currentRange.span + (pos.x / size.width * currentRange.span).toLong()
                            selected = rows.keys.minByOrNull { kotlin.math.abs(it + (if (currentRange.bars) currentRange.step / 2 else 0) - t) }
                        }
                    },
            ) {
                val bottom = size.height - labelPaint.textSize * 1.8f
                fun x(t: Long) = ((t - from).toFloat() / range.span) * size.width
                val values = rows.values.mapNotNull { if (range.bars) maxOf(it.kwh ?: 0.0, it.targetKwh ?: 0.0) else it.watts }
                val top = (values.maxOrNull() ?: 0.0).takeIf { it > 0 } ?: 1.0
                fun y(v: Double) = bottom - (v / top).toFloat() * bottom * 0.92f
                listOf(0.0, 0.5, 1.0).forEach { f -> drawLine(grid, Offset(0f, y(top * f)), Offset(size.width, y(top * f)), strokeWidth = 1f) }

                if (range.bars) {
                    val w = (size.width / (range.span.toFloat() / range.step)) * 0.7f
                    rows.forEach { (at, p) ->
                        val k = p.kwh ?: return@forEach
                        val cx = x(at + range.step / 2)
                        drawRoundRect(
                            line.copy(alpha = if (p.incomplete) 0.4f else if (selected == at) 0.75f else 1f),
                            topLeft = Offset(cx - w / 2, y(k)),
                            size = Size(w, bottom - y(k)),
                            cornerRadius = CornerRadius(3.dp.toPx()),
                        )
                    }
                    val target = Path()
                    var started = false
                    rows.forEach { (at, p) ->
                        val t = p.targetKwh ?: run { started = false; return@forEach }
                        val cx = x(at + range.step / 2)
                        if (!started) target.moveTo(cx, y(t)) else target.lineTo(cx, y(t))
                        started = true
                    }
                    drawPath(target, muted, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))))
                } else {
                    // A missing reading breaks the line.
                    val runs = mutableListOf<MutableList<Pair<Long, Double>>>()
                    rows.forEach { (at, p) -> if (p.watts == null) runs += mutableListOf<Pair<Long, Double>>() else (runs.lastOrNull() ?: mutableListOf<Pair<Long, Double>>().also { runs += it }).add(at to p.watts) }
                    runs.filter { it.size > 1 }.forEach { run ->
                        val path = Path().apply { run.forEachIndexed { i, (at, v) -> if (i == 0) moveTo(x(at), y(v)) else lineTo(x(at), y(v)) } }
                        val fill = Path().apply { addPath(path); lineTo(x(run.last().first), bottom); lineTo(x(run.first().first), bottom); close() }
                        drawPath(fill, Brush.verticalGradient(listOf(line.copy(alpha = 0.3f), Color.Transparent)))
                        drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
                selected?.let { at ->
                    val cx = x(if (range.bars) at + range.step / 2 else at)
                    drawLine(muted, Offset(cx, 0f), Offset(cx, bottom), strokeWidth = 1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f)))
                }
                axisTicks(from, end, range).forEach { t ->
                    drawContext.canvas.nativeCanvas.drawText(axisLabel(t, range), x(t), size.height - labelPaint.textSize * 0.3f, labelPaint)
                }
            }
            val readout = selected?.let { rows[it] }?.let { pointReadout(it, range, currency) }
            if (readout != null) {
                Column(Modifier.fillMaxWidth().semantics { contentDescription = readout.joinToString(", ") }) {
                    Text(readout.first(), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                    Text(readout.drop(1).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = muted)
                }
            } else {
                Text("Tap the chart to read a point", style = MaterialTheme.typography.bodySmall, color = muted)
            }
        }
    }
}

/** Marks along the bottom: every 6 hours over a day, each day over a week, each week over a month, each month over a year. */
private fun axisTicks(from: Long, to: Long, range: HistoryRange, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
    var t = Instant.ofEpochMilli(from).atZone(zone).withMinute(0).withSecond(0).withNano(0)
    t = when (range) {
        HistoryRange.DAY -> t.withHour(t.hour / 6 * 6).plusHours(6)
        HistoryRange.WEEK -> t.withHour(0).plusDays(1)
        HistoryRange.MONTH -> t.withHour(0).plusDays(((8 - t.dayOfWeek.value) % 7).toLong().takeIf { it > 0 } ?: 7)
        HistoryRange.YEAR -> t.withHour(0).withDayOfMonth(1).plusMonths(1)
    }
    return generateSequence(t) {
        when (range) {
            HistoryRange.DAY -> it.plusHours(6)
            HistoryRange.WEEK -> it.plusDays(1)
            HistoryRange.MONTH -> it.plusDays(7)
            HistoryRange.YEAR -> it.plusMonths(2)
        }
    }.map { it.toInstant().toEpochMilli() }.takeWhile { it < to }.take(20).toList()
}

private fun axisLabel(t: Long, range: HistoryRange, zone: ZoneId = ZoneId.systemDefault()): String {
    val d = Instant.ofEpochMilli(t).atZone(zone)
    val pattern = when (range) {
        HistoryRange.DAY -> "HH:mm"
        HistoryRange.WEEK -> "EEE"
        HistoryRange.MONTH -> "d MMM"
        HistoryRange.YEAR -> "MMM"
    }
    return d.format(DateTimeFormatter.ofPattern(pattern, Locale.UK))
}
