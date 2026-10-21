package heating.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.ShowChart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import heating.app.data.History
import heating.app.data.HeatingApi
import heating.app.data.friendlyError
import heating.app.ui.formatTemp
import heating.app.ui.theme.HeatingTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.OffsetDateTime

private val RANGES = listOf("24h" to "24 hours", "7d" to "7 days", "30d" to "30 days")

/** The validated categorical order (same as the web): blue, orange, aqua, yellow, magenta, green, violet, red. */
internal val SERIES_LIGHT = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4, 0xFF008300, 0xFF6250D6, 0xFFE34948).map(::Color)
internal val SERIES_DARK = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181, 0xFF008300, 0xFF9085E9, 0xFFE66767).map(::Color)

internal const val REFRESH_MS = 5 * 60_000L

internal fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

/** Each room's temperature and the whole-house median over 24 hours, 7 days or 30 days, with boiler heating shaded. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(
    api: HeatingApi,
    contentPadding: PaddingValues,
    /** Below the temperature chart: energy use and cost. */
    energy: (@Composable () -> Unit)? = null,
) {
    var range by rememberSaveable { mutableStateOf("24h") }
    var history by remember { mutableStateOf<History?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(range) {
        while (true) {
            runCatching { api.history(range) }
                .onSuccess { history = it; error = null }
                .onFailure { error = friendlyError(it) }
            delay(REFRESH_MS)
        }
    }

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val palette = if (dark) SERIES_DARK else SERIES_LIGHT
    val colorOf = { i: Int -> palette.getOrElse(i) { Color.Gray } }

    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Charts", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        RangeButtons(range) { range = it }

        val h = history
        when {
            h == null && error != null -> Text("Couldn't load the history. ${error}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            h == null -> Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            h.rooms.all { it.points.isEmpty() } -> EmptyHistory()
            else -> {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Stat(Icons.Rounded.LocalFireDepartment, "Boiler on", formatDuration(boilerOnMs(h)), Modifier.weight(1f))
                    Stat(Icons.Rounded.Home, "House average", formatTemp(houseAverage(h)), Modifier.weight(1f))
                }
                val from = ms(h.from)
                fun minutes(at: String) = (ms(at) - from) / 60_000.0
                val house = Series("house", "Whole house", MaterialTheme.colorScheme.onSurface, h.house.mapNotNull { p -> p.current?.let { minutes(p.at) to it } })
                val rooms = h.rooms.mapIndexed { i, r ->
                    Series(r.id, r.name, colorOf(i), r.points.mapNotNull { p -> p.current?.let { minutes(p.at) to it } })
                }
                ChartPager(h, range, house, rooms)
            }
        }
        energy?.invoke()
    }
}

/** The whole house first, then one page per room (with the house dashed behind); swipe between them. */
@Composable
private fun ChartPager(h: History, range: String, house: Series, rooms: List<Series>) {
    val pages = listOf(house) + rooms
    val pager = rememberPagerState { pages.size }
    val scope = rememberCoroutineScope()
    var explaining by rememberSaveable { mutableStateOf(false) }
    val page = pages.getOrElse(pager.currentPage) { house }

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(page.color, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(page.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f, fill = false))
                Spacer(Modifier.width(8.dp))
                Text(formatTemp(page.points.lastOrNull()?.second), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { explaining = true }) {
                    Icon(Icons.Outlined.Info, contentDescription = "How to read this chart", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                }
            }
            HorizontalPager(pager, beyondViewportPageCount = 1) { i ->
                Box(Modifier.padding(horizontal = 8.dp)) {
                    if (i == 0) HistoryChart(h, range, house) else HistoryChart(h, range, pages[i], reference = house)
                }
            }
            PageDots(pages, pager.currentPage) { scope.launch { pager.animateScrollToPage(it) } }
        }
    }
    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            title = { Text("Reading the charts") },
            text = { ChartKey(heat = HeatingTheme.colors.heat) },
            confirmButton = { TextButton(onClick = { explaining = false }) { Text("OK") } },
        )
    }
}

/** One dot per page in its line colour; the current one is wider. Tap a dot to jump there. */
@Composable
private fun PageDots(pages: List<Series>, current: Int, onPick: (Int) -> Unit) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
        pages.forEachIndexed { i, s ->
            Box(
                Modifier
                    .clickable(onClickLabel = "Show ${s.name}") { onPick(i) }
                    .padding(horizontal = 3.dp, vertical = 8.dp)
                    .size(width = if (i == current) 18.dp else 8.dp, height = 8.dp)
                    .background(if (i == current) s.color else s.color.copy(alpha = 0.35f), CircleShape),
            )
        }
    }
}

/** 24 hours / 7 days / 30 days. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RangeButtons(range: String, onRange: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        RANGES.forEachIndexed { i, (value, label) ->
            SegmentedButton(
                selected = range == value,
                onClick = { onRange(value) },
                shape = SegmentedButtonDefaults.itemShape(i, RANGES.size),
            ) { Text(label, maxLines = 1) }
        }
    }
}

@Composable
internal fun EmptyHistory() {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(28.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Rounded.ShowChart, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("No history yet", style = MaterialTheme.typography.titleMedium)
            Text(
                "Room temperatures are recorded every five minutes. The chart appears here within five minutes.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun Stat(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, value: String, modifier: Modifier) {
    val colors = HeatingTheme.colors
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = modifier) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(12.dp), color = colors.heatContainer, contentColor = colors.heatText) {
                Icon(icon, null, Modifier.padding(8.dp).size(20.dp))
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun ChartKey(heat: Color) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        KeyRow({ Box(Modifier.width(22.dp).height(3.dp).background(onSurface, CircleShape)) }, "The whole house: the middle (median) of every room's temperature.")
        KeyRow({ Box(Modifier.width(22.dp).height(2.dp).background(onSurface.copy(alpha = 0.5f), CircleShape)) }, "On a room's chart, the faint dashed line is the whole house, for comparison.")
        KeyRow({ Box(Modifier.size(14.dp).background(heat.copy(alpha = 0.4f), RoundedCornerShape(3.dp))) }, "The boiler was heating.")
        KeyRow({ Box(Modifier.size(14.dp).background(heat.copy(alpha = 0.15f), RoundedCornerShape(3.dp))) }, "The boiler was finishing its minimum run.")
        Text("Swipe sideways for each room. Press and drag on a chart to read the temperatures at a time.")
    }
}

@Composable
private fun KeyRow(swatch: @Composable () -> Unit, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(22.dp), contentAlignment = Alignment.Center) { swatch() }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

private fun boilerOnMs(h: History): Long {
    val from = ms(h.from)
    val to = ms(h.to)
    return h.boiler.filter { it.state == "heating" || it.state == "cycling" }
        .sumOf { maxOf(0L, minOf(to, it.to?.let(::ms) ?: to) - maxOf(from, ms(it.from))) }
}

private fun houseAverage(h: History): Double? = h.house.mapNotNull { it.current }.takeIf { it.isNotEmpty() }?.average()

private fun formatDuration(ms: Long): String {
    val minutes = Math.round(ms / 60_000.0)
    return if (minutes < 60) "$minutes min" else "${minutes / 60} h ${minutes % 60} min"
}
