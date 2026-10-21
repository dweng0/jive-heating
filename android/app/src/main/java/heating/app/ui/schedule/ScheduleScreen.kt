package heating.app.ui.schedule

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import heating.app.data.Block
import heating.app.data.Day
import heating.app.data.HeatingApi
import heating.app.data.WeekSchedule
import heating.app.ui.formatTemp
import heating.app.ui.scheduleSegments
import heating.app.ui.theme.HeatingColors
import heating.app.ui.theme.HeatingTheme
import kotlinx.coroutines.launch
import java.time.LocalDate

private val DAY_NAMES = Day.entries.map { it.name.lowercase().replaceFirstChar(Char::uppercase) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScheduleScreen(api: HeatingApi, zoneId: String, onDone: () -> Unit, onError: (String) -> Unit, contentPadding: PaddingValues) {
    val scope = rememberCoroutineScope()
    var week by remember { mutableStateOf<WeekSchedule?>(null) }
    var day by rememberSaveable { mutableStateOf(Day.entries[LocalDate.now().dayOfWeek.value - 1]) }
    var edits by remember { mutableStateOf<Map<Day, List<Block>>>(emptyMap()) }
    var copying by rememberSaveable { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }

    LaunchedEffect(zoneId) {
        runCatching { api.schedule(zoneId) }.onSuccess { week = it }.onFailure { onError(heating.app.data.friendlyError(it)) }
    }

    val loaded = week ?: run {
        Box(Modifier.fillMaxSize().padding(contentPadding), contentAlignment = Alignment.Center) { Text("Loading schedule…") }
        return
    }
    val blocks = edits[day] ?: loaded[day].blocks
    fun edit(change: (List<Block>) -> List<Block>) { edits = edits + (day to change(blocks)) }
    val colors = HeatingTheme.colors

    Column(Modifier.fillMaxSize().padding(contentPadding)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Day.entries.forEachIndexed { i, d ->
                val selected = d == day
                Surface(
                    onClick = { day = d },
                    shape = CircleShape,
                    color = if (selected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.surface,
                    contentColor = if (selected) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.weight(1f).height(44.dp),
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(DAY_NAMES[i].take(1) + if (d in edits) "•" else "", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
        }

        Timeline(blocks, colors, Modifier.padding(horizontal = 20.dp, vertical = 16.dp))

        LazyColumn(
            Modifier.weight(1f),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 4.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            itemsIndexed(blocks) { i, block ->
                BlockRow(
                    block = block,
                    until = blocks.getOrNull(i + 1)?.start ?: "midnight",
                    removable = i > 0,
                    colors = colors,
                    onEarlier = { edit { DayEditor.move(it, i, -15) } },
                    onLater = { edit { DayEditor.move(it, i, +15) } },
                    onCooler = { edit { DayEditor.cooler(it, i) } },
                    onWarmer = { edit { DayEditor.warmer(it, i) } },
                    onRemove = { edit { DayEditor.remove(it, i) } },
                )
            }
            item {
                OutlinedButton(
                    onClick = { edit(DayEditor::add) },
                    enabled = DayEditor.canAdd(blocks),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                ) {
                    Text(if (DayEditor.canAdd(blocks)) "+ Add a change" else "${DayEditor.MAX_CHANGES} of ${DayEditor.MAX_CHANGES} changes used")
                }
            }
        }

        Row(
            Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = { copying = true }, modifier = Modifier.weight(1f).heightIn(min = 56.dp)) { Text("Copy day…") }
            Button(
                enabled = !saving && edits.values.all { DayEditor.problem(it) == null },
                onClick = {
                    scope.launch {
                        saving = true
                        runCatching { edits.forEach { (d, b) -> week = api.saveDay(zoneId, d, b) } }
                            .onSuccess { edits = emptyMap(); onDone() }
                            .onFailure { onError(heating.app.data.friendlyError(it)) }
                        saving = false
                    }
                },
                modifier = Modifier.weight(1f).heightIn(min = 56.dp),
            ) { Text(if (edits.isEmpty()) "Done" else "Save") }
        }
    }

    if (copying) {
        var targets by rememberSaveable { mutableStateOf(Day.entries.filter { it != day && it.ordinal < 5 == day.ordinal < 5 }.toSet()) }
        ModalBottomSheet(onDismissRequest = { copying = false }) {
            Column(Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Copy ${DAY_NAMES[day.ordinal]} to…", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(bottom = 8.dp))
                Day.entries.filter { it != day }.forEach { d ->
                    Row(
                        Modifier.fillMaxWidth().heightIn(min = 48.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(checked = d in targets, onCheckedChange = { targets = if (it) targets + d else targets - d })
                        Text(DAY_NAMES[d.ordinal], style = MaterialTheme.typography.bodyLarge)
                    }
                }
                Button(
                    enabled = targets.isNotEmpty(),
                    onClick = {
                        // Copy the (possibly edited) day locally; Save sends every changed day.
                        edits = edits + targets.associateWith { blocks }
                        copying = false
                    },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(top = 8.dp),
                ) { Text("Copy to ${targets.size} days") }
                TextButton(onClick = { copying = false }, modifier = Modifier.fillMaxWidth()) { Text("Cancel") }
            }
        }
    }
}

private fun warmth(target: Double, colors: HeatingColors): Pair<Color, Color> = when {
    target >= 20.0 -> colors.heat to colors.onHeat
    target >= 18.0 -> colors.warm to colors.onHeat
    else -> colors.cool to colors.onCool
}

@Composable
private fun Timeline(blocks: List<Block>, colors: HeatingColors, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().height(40.dp).clip(RoundedCornerShape(12.dp))) {
            scheduleSegments(blocks).forEach { seg ->
                val (bg, fg) = warmth(seg.target, colors)
                Box(Modifier.weight(seg.fraction.coerceAtLeast(0.001f)).fillMaxHeight().background(bg), contentAlignment = Alignment.Center) {
                    if (seg.fraction > 0.09f) Text(formatTemp(seg.target), style = MaterialTheme.typography.labelMedium, color = fg)
                }
            }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            listOf("00:00", "06:00", "12:00", "18:00", "24:00").forEach {
                Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun BlockRow(
    block: Block, until: String, removable: Boolean, colors: HeatingColors,
    onEarlier: () -> Unit, onLater: () -> Unit, onCooler: () -> Unit, onWarmer: () -> Unit, onRemove: () -> Unit,
) {
    Surface(shape = MaterialTheme.shapes.medium, color = MaterialTheme.colorScheme.surface, shadowElevation = 1.dp) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp, bottom = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(12.dp).clip(CircleShape).background(warmth(block.target, colors).first))
                Text(block.start, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 12.dp).width(72.dp))
                Text("until $until", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                if (removable) IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, "Remove change at ${block.start}") }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (removable) {
                    TextButton(onClick = onEarlier) { Text("−15m") }
                    TextButton(onClick = onLater) { Text("+15m") }
                }
                Box(Modifier.weight(1f))
                FilledTonalIconButton(onClick = onCooler) { Icon(Icons.Rounded.Remove, "Cooler from ${block.start}") }
                Text(formatTemp(block.target), style = MaterialTheme.typography.titleLarge, modifier = Modifier.width(60.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                FilledTonalIconButton(onClick = onWarmer) { Icon(Icons.Rounded.Add, "Warmer from ${block.start}") }
            }
        }
    }
}
