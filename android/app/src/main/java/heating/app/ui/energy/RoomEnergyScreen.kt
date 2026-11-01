package heating.app.ui.energy

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import heating.app.data.EnergyDashboard
import heating.app.data.EnergyUsage
import heating.app.data.HeatingApi
import heating.app.data.HeatingApiException
import heating.app.data.LivePower
import heating.app.data.RoomPlugs
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Recorded every five minutes; there is no point asking more often. */
private const val USAGE_EVERY_MS = 5 * 60_000L
/** A plug's power is not on the live feed, only its room's; this keeps the table close behind it. */
private const val PLUGS_EVERY_MS = 15_000L
private const val AXIS_WIDTH_DP = 56
private val TIME_LABEL = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val DAY_LABEL = DateTimeFormatter.ofPattern("d MMM", Locale.UK)
private val POINT_LABEL = DateTimeFormatter.ofPattern("EEE d MMM, HH:mm", Locale.UK)

/** Live power from /ws/power while the screen is open, reconnecting; null until the first message. */
@Composable
private fun rememberLivePower(api: HeatingApi): LivePower? {
    var live by remember { mutableStateOf<LivePower?>(null) }
    LaunchedEffect(Unit) {
        var backoff = 1_000L
        while (true) {
            runCatching {
                api.livePower().collect {
                    backoff = 1_000L
                    live = it
                }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }
    return live
}

/** One room's electricity: what it is using and costing, each plug over time, and its plugs side by side. Admin only. */
@Composable
fun RoomEnergyScreen(api: HeatingApi, roomId: String, roomName: String?, contentPadding: PaddingValues) {
    val live = rememberLivePower(api)
    var plugs by remember { mutableStateOf<RoomPlugs?>(null) }
    var missing by remember { mutableStateOf(false) }
    LaunchedEffect(roomId) {
        while (true) {
            runCatching { api.roomPlugs(roomId) }
                .onSuccess { plugs = it }
                .onFailure { if ((it as? HeatingApiException)?.status == 404) missing = true }
            delay(PLUGS_EVERY_MS)
        }
    }
    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        if (missing) {
            Text("Room not found", style = MaterialTheme.typography.titleMedium)
            return@Column
        }
        val current = plugs
        Figures(
            now = nowText(live != null, live?.rooms?.firstOrNull { it.room == roomId }?.watts),
            kwh = current?.kwh,
            cost = current?.cost,
            currency = current?.currency ?: "GBP",
            warning = current?.let(::roomWarning),
        )
        if (current != null) {
            val meters = current.plugs.map { it.meter }
            val slides = remember(meters, roomName) { usageSlides(roomName ?: "Room", meters) }
            val pager = rememberPagerState { slides.size }
            val scope = rememberCoroutineScope()
            UsageCarousel(slides, { scope.launch { pager.animateScrollToPage(it) } }, { window, to -> api.roomUsage(roomId, window, to) }) { chart ->
                HorizontalPager(pager, Modifier.fillMaxWidth(), verticalAlignment = Alignment.Top) { page -> chart(slides[page]) }
            }
            if (meters.isEmpty()) {
                Text("No plugs in this room", color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else {
                PlugTable(current) { meter ->
                    val page = slides.indexOfFirst { it.first == meter }
                    if (page >= 0) scope.launch { pager.animateScrollToPage(page) }
                }
            }
        }
    }
}

/** What no plug accounts for: the same dashboard as a room's, with one chart and no plugs to compare. Admin only. */
@Composable
fun EverythingElseEnergyScreen(api: HeatingApi, contentPadding: PaddingValues) {
    val live = rememberLivePower(api)
    var today by remember { mutableStateOf<EnergyDashboard?>(null) }
    LaunchedEffect(Unit) {
        while (true) {
            runCatching { api.energyDashboard("today") }.onSuccess { today = it }
            delay(USAGE_EVERY_MS)
        }
    }
    val rest = today?.everythingElse
    val noReading = live != null && live.elseWatts == null
    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("What no plug accounts for", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Figures(
            now = nowText(live != null, live?.elseWatts),
            kwh = rest?.kwh,
            cost = rest?.cost,
            currency = today?.currency ?: "GBP",
            warning = if (rest?.incomplete == true || noReading) "Some readings are missing, so this is so far." else null,
        )
        val single = listOf(WHOLE to "Everything else")
        UsageCarousel(single, {}, { window, to -> api.everythingElseUsage(window, to) }) { chart -> chart(single[0]) }
    }
}

@Composable
private fun Figures(now: String, kwh: Double?, cost: Double?, currency: String, warning: String?) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Figure("Now", now, null)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Figure("Today", formatKwh(kwh), warning, Modifier.weight(1f))
            Figure("Cost today", formatCost(cost, currency), warning, Modifier.weight(1f))
        }
    }
}

@Composable
private fun Figure(label: String, value: String, warning: String?, modifier: Modifier = Modifier.fillMaxWidth()) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = modifier) {
        Column(Modifier.padding(14.dp).height(64.dp), verticalArrangement = Arrangement.Center) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                warning?.let { CostWarning(it) }
            }
        }
    }
}

/** The usage read so far and the time being looked at, shared by every chart. A null [end] follows the latest readings. */
private class UsageView(val window: UsageWindowChoice, val loaded: LoadedUsage?, val end: Instant?, val moveTo: (Instant) -> Unit)

/**
 * Line charts of [slides] over time, one at a time, with arrows and dots to move between them, read with [load]. Time and range
 * are shared, so every chart shows the same time, and so are Power, Use and Cost. [pages] lays the charts out.
 */
@Composable
private fun UsageCarousel(
    slides: List<Pair<String, String>>,
    onPage: (Int) -> Unit,
    load: suspend (String, String?) -> EnergyUsage,
    pages: @Composable (chart: @Composable (Pair<String, String>) -> Unit) -> Unit,
) {
    var windowName by rememberSaveable { mutableStateOf(UsageWindowChoice.DAY.name) }
    var metricName by rememberSaveable { mutableStateOf(UsageMetric.WATTS.name) }
    var endMillis by rememberSaveable { mutableStateOf<Long?>(null) }
    val window = UsageWindowChoice.valueOf(windowName)
    val metric = UsageMetric.valueOf(metricName)
    var loaded by remember(windowName) { mutableStateOf<LoadedUsage?>(null) }
    val end = endMillis?.let(Instant::ofEpochMilli)

    LaunchedEffect(windowName) {
        while (true) {
            runCatching { load(window.value, null) }.onSuccess { loaded = withPage(loaded, it, window.step) }
            delay(USAGE_EVERY_MS)
        }
    }
    // Reads a window ahead of what is shown, so scrolling back does not run into nothing.
    val wanted = (end ?: Instant.now()).minus(window.length.multipliedBy(2))
    val loadedFrom = loaded?.loadedFrom
    val earliest = loaded?.earliest
    LaunchedEffect(windowName, loadedFrom, loadedFrom?.let { wanted < it }) {
        if (loadedFrom != null && earliest != null && wanted < loadedFrom && earliest < loadedFrom) {
            runCatching { load(window.value, loadedFrom.toString()) }.onSuccess { loaded = withPage(loaded, it, window.step) }
        }
    }
    val moveTo: (Instant) -> Unit = { t ->
        val now = Instant.now()
        val clamped = clampEnd(t, now, loaded?.earliest, window.length)
        endMillis = if (clamped >= now.minusSeconds(1)) null else clamped.toEpochMilli()
    }
    val view = UsageView(window, loaded, end, moveTo)

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Choice(UsageMetric.entries.map { it.name to it.label }, metricName) { metricName = it }
            Choice(UsageWindowChoice.entries.map { it.name to it.value }, windowName) { next ->
                // The end stays where it is, so the time being looked at stays in view.
                val nextWindow = UsageWindowChoice.valueOf(next)
                end?.let { endMillis = clampEnd(it, Instant.now(), loaded?.earliest, nextWindow.length).toEpochMilli() }
                windowName = next
            }
            if (end != null) TextButton(onClick = { endMillis = null }, modifier = Modifier.align(Alignment.End)) { Text("Now") }
            pages { slide -> Slide(slide, slides, onPage, view, metric) }
        }
    }
}

@Composable
private fun Choice(options: List<Pair<String, String>>, selected: String, onSelect: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { i, (value, label) ->
            SegmentedButton(selected = selected == value, onClick = { onSelect(value) }, shape = SegmentedButtonDefaults.itemShape(i, options.size)) {
                Text(label, maxLines = 1)
            }
        }
    }
}

@Composable
private fun Slide(slide: Pair<String, String>, slides: List<Pair<String, String>>, onPage: (Int) -> Unit, view: UsageView, metric: UsageMetric) {
    val many = slides.size > 1
    val page = slides.indexOf(slide)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            if (many) IconButton(onClick = { onPage(page - 1) }, enabled = page > 0) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Previous chart") }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(slide.second, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, textAlign = TextAlign.Center)
                if (many) {
                    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), modifier = Modifier.padding(top = 4.dp)) {
                        slides.indices.forEach { i ->
                            Box(Modifier.size(7.dp).background(if (i == page) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant, CircleShape))
                        }
                    }
                }
            }
            if (many) IconButton(onClick = { onPage(page + 1) }, enabled = page < slides.size - 1) { Icon(Icons.AutoMirrored.Rounded.ArrowForward, "Next chart") }
        }
        UsageChart(view, slide.first, slide.second, metric)
    }
}

/** One series over time, drawn by hand so it can break where there is no reading. Drag to move through time; touch and hold for a point. */
@Composable
private fun UsageChart(view: UsageView, seriesKey: String, name: String, metric: UsageMetric) {
    val window = view.window
    val end = view.end ?: Instant.now()
    val from = end.minus(window.length)
    val currency = view.loaded?.currency ?: "GBP"
    val rows = remember(view.loaded, seriesKey, end, window) { usageRows(view.loaded?.series?.get(seriesKey).orEmpty(), window.step, from, end) }
    val shown = rows.filter { it.at >= from && it.at <= end && it.value(metric) != null }
    var cursor by remember { mutableStateOf<Float?>(null) }
    val density = LocalDensity.current
    val axisWidth = with(density) { AXIS_WIDTH_DP.dp.toPx() }
    var width by remember { mutableStateOf(1f) }
    val plotWidth = (width - axisWidth).coerceAtLeast(1f)
    val zone = ZoneId.systemDefault()

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val line = zoneColor(PowerZone.NEUTRAL, dark)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val paint = remember(ink, density) {
        android.graphics.Paint().apply {
            isAntiAlias = true
            textSize = with(density) { 11.dp.toPx() }
            color = ink.toArgb()
        }
    }
    val cursorAt = cursor?.let { from.plusMillis((((it - axisWidth) / plotWidth).coerceIn(0f, 1f) * window.length.toMillis()).toLong()) }
    val pointRow = cursorAt?.let { rowAt(rows, it, window.step) }
    // Latest values for the gesture handlers, which outlive a recomposition.
    val latest = remember { mutableStateOf(end) }.also { it.value = end }
    val latestView = remember { mutableStateOf(view) }.also { it.value = view }
    val latestWidth = remember { mutableStateOf(plotWidth) }.also { it.value = plotWidth }

    Box(Modifier.fillMaxWidth().height(240.dp)) {
        Canvas(
            Modifier
                .fillMaxSize()
                .onSizeChanged { width = it.width.toFloat() }
                .semantics {
                    contentDescription = "$name, ${metric.label.lowercase()} over time, ${shown.size} points"
                    customActions = listOf(
                        CustomAccessibilityAction("Earlier") { view.moveTo(end.minus(window.length.dividedBy(4))); true },
                        CustomAccessibilityAction("Later") { view.moveTo(end.plus(window.length.dividedBy(4))); true },
                    )
                }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures { change, dx ->
                        if (cursor == null) {
                            // Used here, so the carousel does not move as well.
                            change.consume()
                            val v = latestView.value
                            val millis = (dx / latestWidth.value * v.window.length.toMillis()).toLong()
                            v.moveTo(latest.value.minusMillis(millis))
                        }
                    }
                }
                .pointerInput(Unit) {
                    detectDragGesturesAfterLongPress(
                        onDragStart = { cursor = it.x },
                        onDrag = { change, _ ->
                            change.consume()
                            cursor = change.position.x
                        },
                        onDragEnd = { cursor = null },
                        onDragCancel = { cursor = null },
                    )
                },
        ) {
            val bottom = size.height - 22.dp.toPx()
            val top = 8.dp.toPx()
            val high = shown.maxOfOrNull { it.value(metric)!! }?.coerceAtLeast(if (metric == UsageMetric.WATTS) 1.0 else 0.001) ?: 1.0
            fun x(at: Instant) = axisWidth + (at.toEpochMilli() - from.toEpochMilli()).toFloat() / window.length.toMillis() * (size.width - axisWidth)
            fun y(v: Double) = bottom - (v / high).toFloat() * (bottom - top)

            listOf(0.0, high / 2, high).forEach { v ->
                drawLine(grid, Offset(axisWidth, y(v)), Offset(size.width, y(v)), strokeWidth = 1.dp.toPx())
                if (shown.isNotEmpty() || v == 0.0) drawContext.canvas.nativeCanvas.drawText(metricValue(metric, v, currency), 0f, y(v) + paint.textSize / 3, paint)
            }
            listOf(0.0, 0.5, 1.0).forEach { f ->
                val at = from.plusMillis((window.length.toMillis() * f).toLong()).atZone(zone)
                val label = at.format(if (window == UsageWindowChoice.DAY) TIME_LABEL else DAY_LABEL)
                val w = paint.measureText(label)
                val px = axisWidth + (size.width - axisWidth) * f.toFloat()
                drawContext.canvas.nativeCanvas.drawText(label, (px - w / 2).coerceIn(axisWidth, size.width - w), size.height - 4.dp.toPx(), paint)
            }
            clipRect(left = axisWidth) {
                lineRuns(rows, metric).forEach { run ->
                    if (run.size == 1) {
                        // A reading alone between gaps has no line to be part of.
                        drawCircle(line, 3.dp.toPx(), Offset(x(run[0].at), y(run[0].value(metric)!!)))
                    } else {
                        val path = Path().apply {
                            run.forEachIndexed { i, r ->
                                val px = x(r.at)
                                val py = y(r.value(metric)!!)
                                if (i == 0) moveTo(px, py) else lineTo(px, py)
                            }
                        }
                        drawPath(path, line, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
                    }
                }
                cursor?.let {
                    val px = pointRow?.let { r -> x(r.at) } ?: it
                    drawLine(ink, Offset(px, top), Offset(px, bottom), strokeWidth = 1.dp.toPx())
                    pointRow?.value(metric)?.let { v -> drawCircle(line, 4.dp.toPx(), Offset(px, y(v))) }
                }
            }
        }
        if (shown.isEmpty() && view.loaded != null) {
            Text("Nothing recorded for this time", Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        }
        if (cursorAt != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.align(Alignment.TopEnd)) {
                Column(Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                    Text((pointRow?.at ?: cursorAt).atZone(zone).format(POINT_LABEL), style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.SemiBold)
                    pointLines(pointRow, currency).forEach { Text(it, style = MaterialTheme.typography.labelMedium) }
                }
            }
        }
    }
}

/** Each plug in a room, sortable by any heading, scrolling sideways with its name kept in view. Picking a name shows its chart. */
@Composable
private fun PlugTable(plugs: RoomPlugs, onPick: (String) -> Unit) {
    var columnName by rememberSaveable { mutableStateOf(PlugSort().column.name) }
    var descending by rememberSaveable { mutableStateOf(PlugSort().descending) }
    val sort = PlugSort(PlugColumn.valueOf(columnName), descending)
    fun pick(column: PlugColumn) {
        val next = nextSort(sort, column)
        columnName = next.column.name
        descending = next.descending
    }
    val scroll = rememberScrollState()
    val nameWidth = 116.dp

    @Composable
    fun Heading(column: PlugColumn, modifier: Modifier) {
        Row(modifier.clickable(onClickLabel = "Sort by ${column.label}") { pick(column) }.padding(vertical = 12.dp, horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(column.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (sort.column == column) {
                Icon(if (sort.descending) Icons.Rounded.ArrowDownward else Icons.Rounded.ArrowUpward, if (sort.descending) "biggest first" else "smallest first", Modifier.size(14.dp))
            }
        }
    }

    @Composable
    fun Cell(column: PlugColumn, text: String, content: (@Composable () -> Unit)? = null) {
        Row(Modifier.width(column.width.dp).padding(horizontal = 6.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            content?.invoke()
            Text(text, style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(vertical = 4.dp, horizontal = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Heading(PlugColumn.METER, Modifier.width(nameWidth))
                Row(Modifier.horizontalScroll(scroll)) {
                    PlugColumn.entries.drop(1).forEach { Heading(it, Modifier.width(it.width.dp)) }
                }
            }
            sortPlugs(plugs.plugs, sort).forEach { plug ->
                HorizontalDivider()
                Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        plug.meter,
                        Modifier.width(nameWidth).clickable(onClickLabel = "Show its chart") { onPick(plug.meter) }.padding(horizontal = 6.dp, vertical = 12.dp),
                        fontWeight = FontWeight.Medium,
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 1,
                    )
                    Row(Modifier.horizontalScroll(scroll), verticalAlignment = Alignment.CenterVertically) {
                        Cell(PlugColumn.STATE, plugState(plug))
                        Cell(PlugColumn.POWER, if (plug.offlineSince != null) "Offline" else formatPower(plug.power))
                        Cell(PlugColumn.SHARE, plugShare(plug.kwh, plugs))
                        Cell(PlugColumn.KWH, formatKwh(plug.kwh))
                        Cell(PlugColumn.COST, formatCost(plug.cost, plugs.currency)) { plugWarning(plug)?.let { CostWarning(it) } }
                    }
                }
            }
            // Use by plugs moved out stays last, so it all adds up.
            movedOut(plugs)?.let { left ->
                HorizontalDivider()
                Row(Modifier.height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Moved out", Modifier.width(nameWidth).padding(horizontal = 6.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(Modifier.horizontalScroll(scroll)) {
                        Cell(PlugColumn.STATE, "–")
                        Cell(PlugColumn.POWER, "–")
                        Cell(PlugColumn.SHARE, plugShare(left.kwh, plugs))
                        Cell(PlugColumn.KWH, formatKwh(left.kwh))
                        Cell(PlugColumn.COST, formatCost(left.cost, plugs.currency))
                    }
                }
            }
        }
    }
}
