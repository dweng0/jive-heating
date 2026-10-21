package heating.app.ui.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisGuidelineComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberAxisLabelComponent
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberColumnCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.layer.stacked
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.common.component.rememberLineComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.columnSeries
import com.patrykandpatrick.vico.core.cartesian.layer.ColumnCartesianLayer
import heating.app.data.HeatingApi
import heating.app.data.RoomEnergyHistory
import kotlinx.coroutines.delay
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Shown beside a faded bar: a plug in the room had no reading for some of that hour or day. */
const val SOME_MISSING = "Some readings missing"

/** One hour's or day's bar: its use (null when nothing was recorded) and whether a reading was missing. */
internal data class Bar(val label: String, val kwh: Double?, val incomplete: Boolean)

/** Every hour (24 hours) or day (7 or 30 days) in [h]'s range in [zone], so one with nothing recorded is a gap. */
internal fun energyBars(h: RoomEnergyHistory, range: String, zone: ZoneId): List<Bar> {
    val unit = if (range == "24h") ChronoUnit.HOURS else ChronoUnit.DAYS
    val byStart = h.points.associateBy { OffsetDateTime.parse(it.at).toInstant() }
    val to = OffsetDateTime.parse(h.to).toInstant()
    val labels = DateTimeFormatter.ofPattern(if (range == "24h") "HH:mm" else if (range == "7d") "EEE" else "d MMM", Locale.UK)
    var t: ZonedDateTime = OffsetDateTime.parse(h.from).atZoneSameInstant(zone).truncatedTo(unit)
    return buildList {
        while (t.toInstant() < to) {
            val p = byStart[t.toInstant()]
            add(Bar(t.format(labels), p?.kwh, p?.incomplete ?: false))
            t = t.plus(1, unit)
        }
    }
}

/** "0.09 kWh" for an hour's small use, "1.6 kWh" for a day's. */
internal fun barKwh(kwh: Double): String = String.format(Locale.UK, if (kwh < 1) "%.2f kWh" else "%.1f kWh", kwh)

/**
 * The room's plugs' electricity use as bars over 24 hours (one an hour), 7 or 30 days (one a day), under its heating
 * history. Admin only; not shown for a room with no plug use, but once shown a range with none says so, so the range can
 * be changed back.
 */
@Composable
fun RoomElectricity(api: HeatingApi, roomId: String, modifier: Modifier = Modifier) {
    var range by rememberSaveable { mutableStateOf("24h") }
    var history by remember { mutableStateOf<RoomEnergyHistory?>(null) }
    var shown by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(range) {
        while (true) {
            runCatching { api.roomEnergyHistory(roomId, range) }.onSuccess { history = it; if (it.points.isNotEmpty()) shown = true }
            delay(REFRESH_MS)
        }
    }
    if (!shown) return

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val color = (if (dark) SERIES_DARK else SERIES_LIGHT)[0]
    Column(modifier.fillMaxWidth().padding(top = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("Electricity", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        RangeButtons(range) { range = it }
        val h = history
        if (h == null || h.points.isEmpty()) {
            Text("No plug use in this time", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            val bars = energyBars(h, range, ZoneId.systemDefault())
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.padding(horizontal = 8.dp)) { EnergyBars(bars, range, color) }
                    if (bars.any { it.incomplete }) {
                        Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(12.dp).background(color.copy(alpha = 0.4f), RoundedCornerShape(3.dp)))
                            Spacer(Modifier.width(6.dp))
                            Text(SOME_MISSING, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

/** The bars (Vico): complete ones solid, incomplete ones faded, stacked into the same slot so each hour has one bar. */
@Composable
private fun EnergyBars(bars: List<Bar>, range: String, color: androidx.compose.ui.graphics.Color) {
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(bars) {
        producer.runTransaction {
            columnSeries {
                series(bars.indices.toList(), bars.map { if (it.incomplete) 0.0 else it.kwh ?: 0.0 })
                series(bars.indices.toList(), bars.map { if (it.incomplete) it.kwh ?: 0.0 else 0.0 })
            }
        }
    }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val thickness = if (range == "30d") 5.dp else 8.dp
    // Labels only every six hours, every day, or every week.
    val labelled = bars.indices.filter { if (range == "24h") bars[it].label.endsWith(":00") && bars[it].label.take(2).toInt() % 6 == 0 else range == "7d" || it % 7 == 0 }.toSet()
    val chart = rememberCartesianChart(
        rememberColumnCartesianLayer(
            columnProvider = ColumnCartesianLayer.ColumnProvider.series(
                rememberLineComponent(fill = fill(color), thickness = thickness),
                rememberLineComponent(fill = fill(color.copy(alpha = 0.4f)), thickness = thickness),
            ),
            mergeMode = { ColumnCartesianLayer.MergeMode.stacked() },
        ),
        startAxis = VerticalAxis.rememberStart(
            label = rememberAxisLabelComponent(color = muted, textSize = 11.sp),
            valueFormatter = { _, v, _ -> barKwh(v) },
            line = null,
            tick = null,
            guideline = rememberAxisGuidelineComponent(fill = fill(MaterialTheme.colorScheme.outlineVariant)),
        ),
        bottomAxis = HorizontalAxis.rememberBottom(
            label = rememberAxisLabelComponent(color = muted, textSize = 11.sp),
            valueFormatter = remember(bars, labelled) { CartesianValueFormatter { _, v, _ -> v.toInt().let { if (it in labelled) bars[it].label else " " } } },
            guideline = null,
            tick = null,
        ),
    )
    CartesianChartHost(
        chart = chart,
        modelProducer = producer,
        modifier = Modifier.fillMaxWidth().height(220.dp).semantics {
            contentDescription = "Electricity used by the room's plugs, one bar per ${if (range == "24h") "hour" else "day"}"
        },
        animationSpec = null,
        animateIn = false,
    )
}
