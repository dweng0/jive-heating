package heating.app.ui.energy

import heating.app.data.EnergyTarget
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.TextButton
import androidx.compose.foundation.background
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.Role
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.material3.DropdownMenu
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import heating.app.data.EnergyState
import heating.app.ui.theme.HeatingTheme

private val PERIODS = listOf("today" to "Today", "week" to "Week", "month" to "Month")

/** Under the temperature chart: gas, electricity, standing charges and total, with a bar per day for week and month. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnergySection(state: EnergyState, onPeriod: (String) -> Unit, onSetTarget: (() -> Unit)? = {}) {
    LaunchedEffect(Unit) { onPeriod(state.period) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Use and cost", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 8.dp))
        Text(
            "Gas is estimated from the boiler's hours. Costs use today's prices.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            PERIODS.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = state.period == value,
                    onClick = { onPeriod(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, PERIODS.size),
                ) { Text(label, maxLines = 1) }
            }
        }
        val d = state.dashboard?.takeIf { it.period == state.period }
        if (d == null) {
            Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
            return@Column
        }
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                energyLines(d).forEach { line ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(line.label, fontWeight = if (line.label == "Total") FontWeight.Bold else FontWeight.Normal)
                            if (line.detail.isNotEmpty()) {
                                Text(line.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                        Text(line.cost, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    }
                }
                noMeterNote(d)?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        val target = d.target
        if (target != null) TargetCard(target, today = d.period == "today")
        else if (d.electricityMeter != null && onSetTarget != null) TextButton(onClick = onSetTarget) { Text(SET_TARGET) }
        val rooms = roomLines(d)
        if (rooms.isNotEmpty()) Devices("Electricity by room, of which", rooms)
        val devices = deviceLines(d)
        if (devices.isNotEmpty()) Devices("Electricity, of which", devices)
        val bars = dayBars(d)
        if (bars.isNotEmpty()) DayBars(bars)
    }
}

/** Rooms' or monitored plugs' use, "of which" under electricity: already in it, so never added to the total. */
@Composable
private fun Devices(title: String, devices: List<DeviceLine>) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            devices.forEach { line ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(line.name, maxLines = 1)
                        Text(line.detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(line.cost, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    line.warning?.let { if (line.tapToRead) CostWarning(it) else IncompleteWarning(it) }
                }
            }
        }
    }
}

/** The warning triangle on a plug's figures when some readings were missing. */
@Composable
fun IncompleteWarning(description: String) {
    Icon(Icons.Rounded.Warning, contentDescription = description, tint = HeatingTheme.colors.heat, modifier = Modifier.size(18.dp))
}

/**
 * The shared warning triangle, tappable to show [message]. A screen reader hears "Cost incomplete" and the
 * message straight away, without the tap.
 */
@Composable
fun CostWarning(message: String) {
    var open by remember { mutableStateOf(false) }
    Box(
        Modifier
            .size(48.dp)
            .clickable { open = true }
            .clearAndSetSemantics {
                contentDescription = COST_INCOMPLETE
                stateDescription = message
                role = Role.Button
                onClick { open = true; true }
            },
        contentAlignment = Alignment.Center,
    ) {
        IncompleteWarning(COST_INCOMPLETE)
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Text(message, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
        }
    }
}

/** Use so far against the daily target: green under, amber over, a bar with a mark where use should be by now. */
@Composable
fun TargetCard(target: EnergyTarget, today: Boolean) {
    val under = target.deltaKwh <= 0
    val green = Color(0xFF2E7D32)
    val amber = Color(0xFFB26A00)
    val scale = maxOf(target.kwh, target.usedKwh, target.projectedKwh ?: 0.0, 0.001)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface), modifier = Modifier.fillMaxWidth().testTag("energy-target")) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(targetDelta(target.deltaKwh, today), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = if (under) green else amber)
            Text(targetProgress(target, today), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.fillMaxWidth().height(14.dp)) {
                Box(Modifier.fillMaxWidth().height(8.dp).align(Alignment.CenterStart).background(MaterialTheme.colorScheme.surfaceVariant))
                Box(Modifier.fillMaxWidth((target.usedKwh / scale).toFloat().coerceIn(0f, 1f)).height(8.dp).align(Alignment.CenterStart).background(if (under) green else amber))
                Row(Modifier.fillMaxWidth()) {
                    val at = (target.expectedKwh / scale).toFloat().coerceIn(0.001f, 0.999f)
                    Spacer(Modifier.weight(at))
                    Box(Modifier.width(2.dp).fillMaxHeight().background(MaterialTheme.colorScheme.onSurface).semantics { contentDescription = "Where use should be by now" })
                    Spacer(Modifier.weight(1f - at))
                }
            }
            if (target.incomplete) Text(READINGS_MISSING, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private val amberDot = Color(0xFFE09000)

@Composable
private fun DayBars(bars: List<DayBar>) {
    val gas = HeatingTheme.colors.heat
    val electricity = MaterialTheme.colorScheme.primary
    val standing = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().height(140.dp), horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
                bars.forEach { bar ->
                    Box(Modifier.weight(1f).fillMaxHeight()) {
                    Column(
                        Modifier.fillMaxSize().semantics {
                            contentDescription = bar.date + (if (bar.incomplete) ", incomplete" else "") + (if (bar.overTarget) ", over target" else "")
                        },
                    ) {
                        // Weights stack the parts from the bottom, each as a share of the tallest day.
                        val empty = 1f - bar.total.coerceIn(0f, 1f)
                        if (empty > 0f) Spacer(Modifier.weight(empty))
                        val alpha = if (bar.incomplete) 0.4f else 1f
                        Part(bar.gas, gas.copy(alpha = alpha))
                        Part(bar.electricity, electricity.copy(alpha = alpha))
                        Part(bar.standing, standing)
                    }
                    // The daily target as a line across the bar, and a dot on a day over it.
                    bar.target?.let { t ->
                        Column(Modifier.fillMaxSize()) {
                            val above = 1f - t.coerceIn(0f, 1f)
                            if (above > 0f) Spacer(Modifier.weight(above))
                            Box(Modifier.fillMaxWidth().height(2.dp).background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)))
                            if (t > 0f) Spacer(Modifier.weight(t.coerceIn(0.0001f, 1f)))
                        }
                    }
                    if (bar.overTarget) Box(Modifier.align(Alignment.TopCenter).size(5.dp).background(amberDot, CircleShape))
                    }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Key(gas, "Gas")
                Key(electricity, "Electricity")
                Key(standing, "Standing")
            }
            if (bars.any { it.target != null }) Text("Line: the daily target. Dot: over target.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (bars.any { it.incomplete }) {
                Text(
                    "Faded days are incomplete: the service or the meter was not recording.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.ColumnScope.Part(fraction: Float, color: Color) {
    if (fraction > 0f) Box(Modifier.fillMaxWidth().weight(fraction).background(color))
}

@Composable
private fun Key(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(Modifier.size(10.dp).background(color))
        Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
