package heating.app.ui.energy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import heating.app.data.EnergyArea
import heating.app.data.Meter
import heating.app.data.MeterJob
import heating.app.data.Room
import heating.app.data.friendlyError
import kotlinx.coroutines.launch

private val JOBS = listOf(
    "house" to (MAIN_METER_BUTTON to "Measures all electricity coming in"),
    "area" to ("Measure an energy area" to "Measures all of an area, such as the lights"),
    "monitored" to ("Monitor one device" to "An appliance or circuit, such as the oven"),
    "none" to ("Nothing for now" to "Waits to be set up"),
)

/** The label a meter has of its own, or "" when it goes by its Zigbee2MQTT name. */
fun ownLabel(m: Meter): String = if (m.label != null && m.label != m.name) m.label else ""

/**
 * What [m] measures, chosen in one dialog: on Manage energy, and from a league row for a meter with no job yet. With
 * [withLabel] it also offers its name; [onSave] gets the name (null when unchanged or not offered) and the job.
 */
@Composable
fun MeterJobDialog(
    m: Meter,
    areas: List<EnergyArea>,
    rooms: List<Room>,
    withLabel: Boolean = false,
    onDismiss: () -> Unit,
    onSave: (label: String?, job: MeterJob) -> Unit,
) {
    var job by remember(m) { mutableStateOf(if (m.inUse) "house" else if (m.measures != null) "area" else if (m.monitored) "monitored" else "none") }
    var place by remember(m) { mutableStateOf(m.measures ?: m.area?.let { "area:$it" } ?: m.room ?: "") }
    var label by remember(m) { mutableStateOf(ownLabel(m)) }
    fun body(): MeterJob? = when (job) {
        "house", "none" -> MeterJob(job)
        "area" -> place.removePrefix("area:").takeIf { a -> areas.any { it.id == a } }?.let { MeterJob(job, area = it) }
        else -> when {
            place.startsWith("area:") -> MeterJob(job, area = place.removePrefix("area:"))
            place.isNotEmpty() -> MeterJob(job, room = place)
            else -> MeterJob(job)
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("What does ${m.shownAs} measure?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("What it recorded in its old job stays where it was recorded.", style = MaterialTheme.typography.bodySmall)
                if (withLabel) {
                    OutlinedTextField(label, { if (it.length <= 40) label = it }, label = { Text("Name") }, placeholder = { Text(m.name) }, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("meter-settings-label"))
                }
                JOBS.forEach { (value, words) ->
                    val disabled = !m.reportsEnergy && (value == "house" || value == "monitored")
                    Row(
                        Modifier.fillMaxWidth().selectable(selected = job == value, enabled = !disabled, role = Role.RadioButton) {
                            job = value
                            place = if (value == "area") areas.firstOrNull()?.id ?: "" else ""
                        },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = job == value, onClick = null, enabled = !disabled)
                        Column(Modifier.padding(start = 8.dp)) {
                            Text(words.first, fontWeight = FontWeight.Medium)
                            Text(if (disabled) "This meter does not report energy" else words.second, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                if (job == "area") {
                    val current = areas.firstOrNull { it.id == place.removePrefix("area:") }?.name ?: "Add an area first"
                    Picker("Area: $current", areas.map { it.id to it.name }) { place = it }
                }
                if (job == "monitored") {
                    val current = place.takeIf { it.startsWith("area:") }?.let { p -> areas.firstOrNull { "area:${it.id}" == p }?.name }
                        ?: rooms.firstOrNull { it.id == place }?.name ?: "No room"
                    Picker("Place: $current", listOf("" to "No room") + rooms.map { it.id to it.name } + areas.map { "area:${it.id}" to it.name }) { place = it }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = body() != null, onClick = {
                val b = body() ?: return@TextButton
                onSave(if (withLabel && label != ownLabel(m)) label else null, b)
            }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Opens a meter's settings by name from elsewhere (a league row): loads it and the areas, then shows [MeterJobDialog] with its name. */
class MeterSetUp internal constructor(private val api: heating.app.data.HeatingApi, private val scope: kotlinx.coroutines.CoroutineScope) {
    internal var rooms: List<Room> = emptyList()
    internal var onMessage: (String) -> Unit = {}
    private var meter by mutableStateOf<Meter?>(null)
    private var areas by mutableStateOf(emptyList<EnergyArea>())

    fun open(name: String) {
        scope.launch {
            runCatching { api.meters() to api.energyAreas() }
                .onSuccess { (ms, a) ->
                    areas = a.sortedBy { it.name.lowercase() }
                    meter = ms.firstOrNull { it.name == name }
                }
                .onFailure { onMessage(friendlyError(it)) }
        }
    }

    @Composable
    fun Dialog() {
        val m = meter ?: return
        MeterJobDialog(m, areas, rooms, withLabel = true, onDismiss = { meter = null }) { label, job ->
            meter = null
            scope.launch {
                runCatching {
                    if (label != null) api.labelMeter(m.name, label)
                    api.setMeterJob(m.name, job)
                }
                    .onSuccess { onMessage("${label?.ifEmpty { null } ?: m.shownAs} updated") }
                    .onFailure { onMessage(friendlyError(it)) }
            }
        }
    }
}

@Composable
fun rememberMeterSetUp(api: heating.app.data.HeatingApi, rooms: List<Room>, onMessage: (String) -> Unit): MeterSetUp {
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    return remember(api) { MeterSetUp(api, scope) }.also {
        it.rooms = rooms
        it.onMessage = onMessage
    }
}
