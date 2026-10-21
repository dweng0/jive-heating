package heating.app.ui.group

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import heating.app.data.Group
import heating.app.data.GroupControlRequest
import heating.app.data.HomeState
import heating.app.data.Mode
import heating.app.data.Room
import heating.app.ui.boostStart
import heating.app.ui.formatTemp
import heating.app.ui.room.BoostSheet
import heating.app.ui.stepTarget
import heating.app.ui.theme.HeatingTheme

/** Actions the group screen can take; each fans out to every room in the group. */
interface GroupActions {
    fun control(groupId: String, request: GroupControlRequest)
    fun cancelBoost(groupId: String)
    fun update(groupId: String, name: String, rooms: List<String>)
    fun delete(groupId: String)
}

/** Text for a group's mode and target, e.g. "Manual · 21°" or "Mixed". */
fun groupSummary(group: Group): String = when (group.mode) {
    "manual" -> "Manual · ${formatTemp(group.target)}"
    "schedule" -> if (group.target != null) "Schedule · ${formatTemp(group.target)}" else "Schedule"
    "off" -> "Off"
    else -> "Mixed"
}

@Composable
fun GroupScreen(
    group: Group,
    state: HomeState,
    actions: GroupActions,
    onOpenRoom: (String) -> Unit,
    onDeleted: () -> Unit,
    contentPadding: PaddingValues,
) {
    val members = state.rooms.filter { it.id in group.rooms }
    val boosted = members.flatMap { it.zones }.any { it.boost != null }
    var target by rememberSaveable(group.id) { mutableDoubleStateOf(group.target ?: 21.0) }
    var boosting by rememberSaveable { mutableStateOf(false) }
    var editing by rememberSaveable { mutableStateOf(false) }
    var deleting by rememberSaveable { mutableStateOf(false) }

    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column {
                Text(formatTemp(group.current), style = MaterialTheme.typography.displayMedium)
                Text(groupSummary(group), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (group.heating) Icon(Icons.Rounded.LocalFireDepartment, "Heating", tint = HeatingTheme.colors.heat, modifier = Modifier.size(28.dp))
        }

        val modes = listOf(Mode.SCHEDULE to "Schedule", Mode.MANUAL to "Manual", Mode.OFF to "Off")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            modes.forEachIndexed { i, (mode, label) ->
                SegmentedButton(
                    selected = group.mode == mode.name.lowercase(),
                    onClick = {
                        actions.control(group.id, GroupControlRequest(mode = mode, target = if (mode == Mode.MANUAL) target else null))
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                    modifier = Modifier.heightIn(min = 48.dp),
                ) { Text(label) }
            }
        }

        Text("Set every room to", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            StepButton(Icons.Rounded.Remove, "Lower temperature") { target = stepTarget(target, -1) }
            Text(formatTemp(target), style = MaterialTheme.typography.displaySmall)
            StepButton(Icons.Rounded.Add, "Raise temperature") { target = stepTarget(target, +1) }
        }
        Button(
            onClick = { actions.control(group.id, GroupControlRequest(mode = Mode.MANUAL, target = target)) },
            enabled = members.isNotEmpty(),
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) { Text("Set all to ${formatTemp(target)}") }

        if (boosted) {
            OutlinedButton(
                onClick = { actions.cancelBoost(group.id) },
                border = BorderStroke(2.dp, HeatingTheme.colors.heat),
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) { Text("Cancel boost", color = HeatingTheme.colors.heatText) }
        } else {
            OutlinedButton(
                onClick = { boosting = true },
                enabled = members.isNotEmpty() && state.status?.holiday?.active != true,
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
            ) {
                Icon(Icons.Rounded.Bolt, contentDescription = null)
                Spacer(Modifier.size(8.dp))
                Text("Boost all")
            }
        }

        Text("${members.size} ROOMS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        members.forEach { room ->
            TextButton(onClick = { onOpenRoom(room.id) }, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) {
                Text(room.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                Text(formatTemp(room.current), style = MaterialTheme.typography.titleMedium)
            }
        }
        HorizontalDivider()
        Row(Modifier.fillMaxWidth().padding(bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { editing = true }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) { Text("Edit group") }
            TextButton(onClick = { deleting = true }, modifier = Modifier.weight(1f).heightIn(min = 52.dp)) {
                Text("Delete group", color = MaterialTheme.colorScheme.error)
            }
        }
    }

    if (boosting) {
        BoostSheet(
            roomName = group.name,
            initialTarget = boostStart(state.boostTarget, group.target),
            onDismiss = { boosting = false },
            onStart = { minutes, t -> actions.control(group.id, GroupControlRequest(boost = minutes, target = t)); boosting = false },
        )
    }
    if (editing) {
        GroupEditor(
            title = "Edit group",
            rooms = state.rooms,
            initialName = group.name,
            initialRooms = group.rooms,
            onDismiss = { editing = false },
            onSave = { name, rooms -> actions.update(group.id, name, rooms); editing = false },
        )
    }
    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text("Delete ${group.name}?") },
            text = { Text("The rooms stay as they are.") },
            confirmButton = { TextButton(onClick = { actions.delete(group.id); deleting = false; onDeleted() }) { Text("Delete") } },
            dismissButton = { TextButton(onClick = { deleting = false }) { Text("Cancel") } },
        )
    }
}

/** Name plus a tick list of rooms. Used to create and to edit a group. */
@Composable
fun GroupEditor(
    title: String,
    rooms: List<Room>,
    initialName: String,
    initialRooms: List<String>,
    onDismiss: () -> Unit,
    onSave: (String, List<String>) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    var chosen by rememberSaveable { mutableStateOf(initialRooms) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                androidx.compose.material3.OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true)
                Spacer(Modifier.size(8.dp))
                rooms.forEach { room ->
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                        androidx.compose.material3.Checkbox(
                            checked = room.id in chosen,
                            onCheckedChange = { on -> chosen = if (on) chosen + room.id else chosen - room.id },
                        )
                        Text(room.name)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim(), rooms.map { it.id }.filter { it in chosen }) }) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        modifier = Modifier.size(64.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) { Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp)) }
}
