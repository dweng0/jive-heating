package heating.app.ui.energy

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import heating.app.data.GasDay
import heating.app.data.HeatingApi
import heating.app.ui.theme.HeatingTheme
import kotlinx.coroutines.launch

/**
 * A day of the smart gas meter as a bar per half-hour, with the day's kWh, cost and busiest half-hour, and buttons for the
 * day before and after. Admins also see the boiler's burn rate the meter measured, and can use it for the estimate.
 * Hidden while the meter has no readings.
 */
@Composable
fun GasCard(api: HeatingApi, admin: Boolean) {
    var date by remember { mutableStateOf<String?>(null) }
    var day by remember { mutableStateOf<GasDay?>(null) }
    LaunchedEffect(date) {
        runCatching { api.gasDay(date) }.onSuccess { if (it != null || date == null) day = it }
    }
    val d = day ?: return
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val heat = HeatingTheme.colors.heat
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("GAS", style = MaterialTheme.typography.labelSmall, color = muted)
                    Text(gasDayTitle(d.date), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(gasDayFigures(d), style = MaterialTheme.typography.bodySmall, color = muted)
                }
                OutlinedIconButton(onClick = { date = d.previous }, enabled = d.previous != null) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowLeft, contentDescription = "Day before")
                }
                OutlinedIconButton(onClick = { date = d.next }, enabled = d.next != null) {
                    Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = "Day after")
                }
            }
            Row(
                Modifier.fillMaxWidth().height(110.dp).semantics { contentDescription = "Gas each half-hour on ${gasDayTitle(d.date)}" },
                horizontalArrangement = Arrangement.spacedBy(1.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                gasBars(d).forEach { share ->
                    Column(Modifier.weight(1f).fillMaxHeight()) {
                        val empty = 1f - share.coerceIn(0f, 1f)
                        if (empty > 0f) Spacer(Modifier.weight(empty))
                        if (share > 0f) Box(Modifier.fillMaxWidth().weight(share.coerceIn(0.0001f, 1f)).background(heat, RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp)))
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = muted) }
            }
            if (admin) boilerMeasuredLine(d)?.let { line ->
                Column(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp)).padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(line, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}
