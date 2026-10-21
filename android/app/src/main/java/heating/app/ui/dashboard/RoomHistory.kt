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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import heating.app.data.HeatingApi
import heating.app.data.History
import heating.app.ui.theme.HeatingTheme
import kotlinx.coroutines.delay

/**
 * A room's temperature (bold) and target (dashed) as two series, in minutes since the start of the range;
 * null when the room has no history yet.
 */
internal fun roomSeries(h: History, roomId: String, temperatureColor: Color, targetColor: Color): Pair<Series, Series>? {
    val points = h.rooms.firstOrNull { it.id == roomId }?.points?.takeIf { it.isNotEmpty() } ?: return null
    val from = ms(h.from)
    fun minutes(at: String) = (ms(at) - from) / 60_000.0
    val temperature = Series("temperature", "Temperature", temperatureColor, points.mapNotNull { p -> p.current?.let { minutes(p.at) to it } })
    val target = Series("target", "Target", targetColor, points.mapNotNull { p -> p.target?.let { minutes(p.at) to it } })
    return temperature to target
}

/** The room's temperature and target over 24 hours, 7 days or 30 days, under its controls. */
@Composable
fun RoomHistory(api: HeatingApi, roomId: String, modifier: Modifier = Modifier) {
    var range by rememberSaveable { mutableStateOf("24h") }
    var history by remember { mutableStateOf<History?>(null) }
    var failed by remember { mutableStateOf(false) }

    LaunchedEffect(range) {
        while (true) {
            runCatching { api.history(range) }
                .onSuccess { history = it; failed = false }
                .onFailure { failed = true }
            delay(REFRESH_MS)
        }
    }

    val dark = MaterialTheme.colorScheme.background.luminance() < 0.5f
    val temperatureColor = (if (dark) SERIES_DARK else SERIES_LIGHT)[0]
    val targetColor = MaterialTheme.colorScheme.onSurfaceVariant
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("History", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        RangeButtons(range) { range = it }
        val h = history
        val series = h?.let { roomSeries(it, roomId, temperatureColor, targetColor) }
        when {
            h == null && failed -> Text("Couldn't load the history. It will try again in a few minutes.", color = muted)
            h == null -> Text("Loading…", color = muted)
            series == null -> EmptyHistory()
            else -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
                Column(Modifier.padding(vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.padding(horizontal = 8.dp)) { HistoryChart(h, range, series.first, reference = series.second) }
                    Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Key({ Box(Modifier.width(18.dp).height(3.dp).background(temperatureColor, CircleShape)) }, "Temperature")
                        Key({ Box(Modifier.width(18.dp).height(2.dp).background(targetColor.copy(alpha = 0.55f), CircleShape)) }, "Target")
                        if (h.boiler.isNotEmpty()) {
                            Key({ Box(Modifier.size(12.dp).background(HeatingTheme.colors.heat.copy(alpha = 0.4f), RoundedCornerShape(3.dp))) }, "Boiler on")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun Key(swatch: @Composable () -> Unit, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        swatch()
        Spacer(Modifier.width(6.dp))
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
