package heating.app.ui.energy

import androidx.compose.material3.OutlinedButton
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
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
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewmodel.compose.viewModel
import heating.app.data.EnergyDashboard
import heating.app.data.HeatingApi
import heating.app.data.LivePower
import heating.app.data.PowerBands
import heating.app.data.TodayPeak
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.OffsetDateTime
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin

/** Today's kWh and cost are recorded every five minutes; there is no point asking more often. */
private const val TODAY_EVERY_MS = 5 * 60_000L


/** Same colours as the web's --power-*: aqua, yellow, red and blue from the validated palette. */
internal fun zoneColor(zone: PowerZone, dark: Boolean): Color = when (zone) {
    PowerZone.LOW -> Color(if (dark) 0xFF199E70 else 0xFF1BAF7A)
    PowerZone.MID -> Color(if (dark) 0xFFC98500 else 0xFFEDA100)
    PowerZone.HIGH -> Color(if (dark) 0xFFE66767 else 0xFFE34948)
    PowerZone.NEUTRAL -> Color(if (dark) 0xFF3987E5 else 0xFF2A78D6)
}

/** What the section last showed. It outlives leaving for a room's dashboard, so coming back lays out the same and the Energy tab keeps its scroll. */
class LivePowerMemory : ViewModel() {
    var live by mutableStateOf<LivePower?>(null)
    var bands by mutableStateOf<PowerBands?>(null)
    var peak by mutableStateOf<TodayPeak?>(null)
    var today by mutableStateOf<EnergyDashboard?>(null)
    var busiest by mutableStateOf(Busiest())
}

/**
 * The top of the Energy tab: the dials (whole house, busiest room or area, today's peak, the daily target), the history
 * chart, then a live card per room, area and "Everything else". Admins and people with energy access; only admins' cards
 * open their energy pages and only admins can add an area.
 */
@Composable
fun LivePowerSection(
    api: HeatingApi,
    onOpenRoom: (String) -> Unit,
    onOpenEverythingElse: () -> Unit,
    onOpenArea: (String) -> Unit = {},
    onAddArea: () -> Unit = {},
    admin: Boolean = true,
) {
    val memory = viewModel<LivePowerMemory>()
    var live by memory::live
    var bands by memory::bands
    var peak by memory::peak
    var today by memory::today
    var busiest by memory::busiest

    LaunchedEffect(Unit) {
        launch {
            while (true) {
                runCatching { api.powerSummary() }.onSuccess { s ->
                    bands = s.bands
                    peak = s.todayPeak
                }
                runCatching { api.energyDashboard("today") }.onSuccess { today = it }
                delay(TODAY_EVERY_MS)
            }
        }
        // Reconnect while the Energy tab is open.
        var backoff = 1_000L
        while (true) {
            runCatching {
                api.livePower().collect { p ->
                    backoff = 1_000L
                    live = p
                    busiest = followBusiest(busiest, places(p), System.currentTimeMillis())
                }
            }
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(30_000L)
        }
    }

    // No house meter chosen: nothing to show live.
    if (today?.electricityMeter == null && today != null) return

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val onDial = places(live).firstOrNull { it.id == busiest.current }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        EnergyDials(live?.watts, connected = live != null, bands = bands, busiest = onDial, peak = peak, today = today, dark = dark, onSetTarget = if (admin) onAddArea else null)
        HouseHistoryChart(api, dark)
        live?.let { p ->
            val cards = liveCards(p, today)
            val (areaCards, others) = cards.partition { it.key.startsWith("area-") }
            others.filter { it.key != "rest" }.forEach { card ->
                val open: (() -> Unit)? = if (admin && card.key.startsWith("room-")) ({ onOpenRoom(card.key.removePrefix("room-")) }) else null
                UseCard(card, zoneColor(PowerZone.NEUTRAL, dark), open)
            }
            // "Other areas", below the rooms and above everything else; with none, only the action to add one.
            if (areaCards.isEmpty()) {
                if (admin) OutlinedButton(onClick = onAddArea) { Text("Add area") }
            } else {
                Text("Other areas", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
                areaCards.forEach { card -> UseCard(card, zoneColor(PowerZone.NEUTRAL, dark), if (admin) ({ onOpenArea(card.key.removePrefix("area-")) }) else null) }
            }
            others.filter { it.key == "rest" }.forEach { UseCard(it, zoneColor(PowerZone.NEUTRAL, dark), if (admin) onOpenEverythingElse else null) }
        }
    }
}

@Composable
private fun UseCard(card: LiveCard, color: Color, onOpen: (() -> Unit)?) {
    val share by animateFloatAsState((card.share ?: 0.0).toFloat(), tween(700), label = "share")
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth().then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open energy dashboard", onClick = onOpen) else Modifier),
    ) {
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
