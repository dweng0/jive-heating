package heating.app.ui.dashboard

import android.graphics.DashPathEffect
import android.graphics.Paint
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.cartesianLayerPadding
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.CartesianDrawingContext
import com.patrykandpatrick.vico.core.cartesian.CartesianMeasuringContext
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianLayerRangeProvider
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.decoration.Decoration
import com.patrykandpatrick.vico.core.cartesian.layer.CartesianLayerDimensions
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarker
import com.patrykandpatrick.vico.core.cartesian.marker.CartesianMarkerVisibilityListener
import heating.app.data.BoilerPeriod
import heating.app.data.History
import heating.app.ui.formatTemp
import heating.app.ui.theme.HeatingTheme
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Spacing of points per range in minutes; a gap longer than three of these breaks a line. */
private val STEP_MINUTES = mapOf("24h" to 5.0, "7d" to 30.0, "30d" to 120.0)

/** A room's (or the house's) points as minutes since the start of the range. */
internal data class Series(val id: String, val name: String, val color: Color, val points: List<Pair<Double, Double>>)

/**
 * One series as a bold line (the whole-house median, or a room) over an optional dashed reference line
 * (the house, for comparison), with the boiler's heating and finishing-run periods shaded behind (Vico).
 * The time axis starts where the data does, so a few hours of history isn't squashed into a corner.
 * Press and drag for the values at a time.
 */
@Composable
internal fun HistoryChart(h: History, range: String, main: Series, reference: Series? = null) {
    val from = ms(h.from)
    val to = ms(h.to)
    val span = (to - from) / 60_000.0
    val step = STEP_MINUTES.getValue(range)
    val start = chartStart(listOfNotNull(main, reference), span, range)
    val shown = listOfNotNull(reference, main)
    val values = shown.flatMap { s -> s.points.map { it.second } }
    val lo = floor(minOf(values.minOrNull() ?: 18.0, 18.0) - 0.5)
    val hi = ceil(maxOf(values.maxOrNull() ?: 21.0, 21.0) + 0.5)
    val every = if (hi - lo > 12) 4.0 else if (hi - lo > 6) 2.0 else 1.0

    // Vico draws one line per series, so a line with a gap becomes several series in its colour.
    val segments = shown.flatMap { s -> split(s.points, step).map { s to it } }

    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(segments) {
        if (segments.isNotEmpty()) {
            producer.runTransaction { lineSeries { segments.forEach { (_, pts) -> series(pts.map { it.first }, pts.map { it.second }) } } }
        }
    }

    val heat = HeatingTheme.colors.heat
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    var touched by remember { mutableStateOf<Double?>(null) }

    val lineProvider = LineCartesianLayer.LineProvider.series(
        segments.map { (s, _) ->
            LineCartesianLayer.Line(
                fill = LineCartesianLayer.LineFill.single(fill(if (s === main) s.color else s.color.copy(alpha = 0.55f))),
                stroke = if (s === main) LineCartesianLayer.LineStroke.Continuous(2.5f, Paint.Cap.ROUND)
                else LineCartesianLayer.LineStroke.Dashed(1.5f, Paint.Cap.ROUND, 4f, 4f),
            )
        },
    )
    val chart = rememberCartesianChart(
        rememberLineCartesianLayer(
            lineProvider = lineProvider,
            rangeProvider = CartesianLayerRangeProvider.fixed(minX = start, maxX = span, minY = lo, maxY = hi),
        ),
        startAxis = VerticalAxis.rememberStart(
            label = rememberAxisLabelComponent(color = muted, textSize = 11.sp),
            valueFormatter = { _, v, _ -> "${v.toInt()}°" },
            itemPlacer = remember(every) { VerticalAxis.ItemPlacer.step({ every }) },
            line = null,
            tick = null,
            guideline = rememberAxisGuidelineComponent(fill = fill(MaterialTheme.colorScheme.outlineVariant)),
        ),
        bottomAxis = HorizontalAxis.rememberBottom(
            label = rememberAxisLabelComponent(color = muted, textSize = 11.sp),
            valueFormatter = remember(from, range) { CartesianValueFormatter { _, v, _ -> tickLabel(from + (v * 60_000).toLong(), range) } },
            itemPlacer = remember(from, to, range, start) { TimeTicks(timeTicks(from + (start * 60_000).toLong(), to, range).map { (it - from) / 60_000.0 }) },
            guideline = null,
            tick = null,
        ),
        decorations = listOf(BoilerBands(h.boiler, from, from + (start * 60_000).toLong(), to, heat)),
        marker = remember(muted) { Guide(muted) },
        markerVisibilityListener = remember {
            object : CartesianMarkerVisibilityListener {
                override fun onShown(marker: CartesianMarker, targets: List<CartesianMarker.Target>) { touched = targets.firstOrNull()?.x }
                override fun onUpdated(marker: CartesianMarker, targets: List<CartesianMarker.Target>) { touched = targets.firstOrNull()?.x }
                override fun onHidden(marker: CartesianMarker) { touched = null }
            }
        },
        layerPadding = { cartesianLayerPadding() },
        getXStep = { step },
    )

    Box {
        CartesianChartHost(
            chart = chart,
            modelProducer = producer,
            modifier = Modifier.fillMaxWidth().height(240.dp),
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content, minZoom = Zoom.Content, maxZoom = Zoom.Content),
            animationSpec = null,
            animateIn = false,
        )
        touched?.let { x ->
            val t = from + (x * 60_000).toLong()
            val period = h.boiler.firstOrNull { ms(it.from) <= t && (it.to == null || ms(it.to) > t) }
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                modifier = Modifier.align(Alignment.TopEnd).padding(4.dp),
            ) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text(readoutTime(t, range), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                    listOfNotNull(main, reference).forEach { s ->
                        valueAt(s.points, x, step)?.let {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).background(s.color, CircleShape))
                                Spacer(Modifier.width(5.dp))
                                Text(
                                    "${s.name} ${formatTemp(it)}",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = if (s === main) FontWeight.SemiBold else null,
                                )
                            }
                        }
                    }
                    Text("Boiler ${boilerWords(period?.state)}", style = MaterialTheme.typography.labelSmall, color = muted)
                }
            }
        }
    }
}

/** Splits points into runs with no gap longer than three steps. */
private fun split(points: List<Pair<Double, Double>>, step: Double): List<List<Pair<Double, Double>>> {
    val out = mutableListOf<MutableList<Pair<Double, Double>>>()
    points.forEach { p ->
        val last = out.lastOrNull()?.lastOrNull()
        if (last == null || p.first - last.first > step * 3) out += mutableListOf(p) else out.last() += p
    }
    return out
}

/** Shades the boiler's heating (stronger) and finishing-run (lighter) periods behind the lines. */
private class BoilerBands(private val periods: List<BoilerPeriod>, private val from: Long, private val shownFrom: Long, private val to: Long, color: Color) : Decoration {
    private val paint = Paint().apply { this.color = color.toArgb() }

    override fun drawUnderLayers(context: CartesianDrawingContext) {
        with(context) {
            fun px(minutes: Double) =
                layerBounds.left + layerDimensions.startPadding +
                    ((minutes - ranges.minX) / ranges.xStep * layerDimensions.xSpacing).toFloat() - scroll
            periods.forEach { b ->
                val alpha = when (b.state) { "heating" -> 56; "cycling" -> 26; else -> return@forEach }
                val start = (maxOf(shownFrom, ms(b.from)) - from) / 60_000.0
                val end = (minOf(to, b.to?.let(::ms) ?: to) - from) / 60_000.0
                if (end <= start) return@forEach
                paint.alpha = alpha
                canvas.drawRect(px(start), layerBounds.top, maxOf(px(end), px(start) + 1f), layerBounds.bottom, paint)
            }
        }
    }
}

/** A dashed vertical line at the pressed time; the values show in a card above the chart. */
private class Guide(color: Color) : CartesianMarker {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        this.color = color.toArgb()
        style = Paint.Style.STROKE
        strokeWidth = 3f
        pathEffect = DashPathEffect(floatArrayOf(10f, 10f), 0f)
    }

    override fun drawOverLayers(context: CartesianDrawingContext, targets: List<CartesianMarker.Target>) {
        val x = targets.firstOrNull()?.canvasX ?: return
        context.canvas.drawLine(x, context.layerBounds.top, x, context.layerBounds.bottom, paint)
    }
}

/** Bottom-axis labels at fixed times (every 6 hours, each day, or each week). */
private class TimeTicks(private val values: List<Double>) : HorizontalAxis.ItemPlacer {
    override fun getLabelValues(
        context: CartesianDrawingContext,
        visibleXRange: ClosedFloatingPointRange<Double>,
        fullXRange: ClosedFloatingPointRange<Double>,
        maxLabelWidth: Float,
    ) = values

    override fun getWidthMeasurementLabelValues(
        context: CartesianMeasuringContext,
        layerDimensions: CartesianLayerDimensions,
        fullXRange: ClosedFloatingPointRange<Double>,
    ) = values

    override fun getHeightMeasurementLabelValues(
        context: CartesianMeasuringContext,
        layerDimensions: CartesianLayerDimensions,
        fullXRange: ClosedFloatingPointRange<Double>,
        maxLabelWidth: Float,
    ) = values

    override fun getStartLayerMargin(context: CartesianMeasuringContext, layerDimensions: CartesianLayerDimensions, tickThickness: Float, maxLabelWidth: Float) = 0f

    override fun getEndLayerMargin(context: CartesianMeasuringContext, layerDimensions: CartesianLayerDimensions, tickThickness: Float, maxLabelWidth: Float) = 0f
}

private fun valueAt(points: List<Pair<Double, Double>>, x: Double, step: Double) =
    points.minByOrNull { abs(it.first - x) }?.takeIf { abs(it.first - x) < step / 2 }?.second

private fun boilerWords(state: String?) = when (state) {
    "heating" -> "heating"
    "cycling" -> "finishing its run"
    "resting" -> "resting"
    "idle" -> "idle"
    else -> "not recorded"
}

/**
 * Minutes into the range where the chart starts: the first reading, rounded down to the hour (24 hours)
 * or the day, so a new install shows its few hours across the whole width. A full range starts at 0.
 */
internal fun chartStart(series: List<Series>, span: Double, range: String): Double {
    val first = series.mapNotNull { s -> s.points.firstOrNull()?.first }.minOrNull() ?: return 0.0
    val unit = if (range == "24h") 60.0 else 24 * 60.0
    val start = floor(first / unit) * unit
    // Keep at least two units on screen so a single reading isn't a lone dot at the edge.
    return start.coerceIn(0.0, maxOf(0.0, span - 2 * unit))
}

private fun timeTicks(from: Long, to: Long, range: String): List<Long> {
    val start = Instant.ofEpochMilli(from).atZone(ZoneId.systemDefault())
    return if (range == "24h") {
        val hours = (to - from) / 3_600_000.0
        val every = if (hours <= 6) 1L else if (hours <= 12) 3L else 6L
        var t = start.truncatedTo(ChronoUnit.HOURS).let { it.withHour(0).plusHours(((it.hour / every) + 1) * every) }
        buildList { while (t.toInstant().toEpochMilli() <= to) { add(t.toInstant().toEpochMilli()); t = t.plusHours(every) } }
    } else {
        val every = if (range == "7d") 1L else 7L
        var t = start.truncatedTo(ChronoUnit.DAYS).plusDays(1)
        buildList { while (t.toInstant().toEpochMilli() <= to) { add(t.toInstant().toEpochMilli()); t = t.plusDays(every) } }
    }
}

private fun tickLabel(t: Long, range: String): String =
    Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (range == "24h") "HH:mm" else if (range == "7d") "EEE" else "d MMM"))

private fun readoutTime(t: Long, range: String): String =
    Instant.ofEpochMilli(t).atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern(if (range == "24h") "HH:mm" else "EEE d MMM HH:mm"))
