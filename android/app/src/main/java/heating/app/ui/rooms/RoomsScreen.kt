package heating.app.ui.rooms

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
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.text.appendInlineContent
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import heating.app.data.Group
import heating.app.data.GroupControlRequest
import heating.app.data.HomeState
import heating.app.data.Mode
import heating.app.data.Room
import heating.app.ui.formatTemp
import heating.app.ui.components.StatusChip
import heating.app.ui.components.SensorInfo
import heating.app.ui.headline
import heating.app.ui.noRoomsText
import heating.app.ui.group.GroupEditor
import heating.app.ui.group.groupSummary
import heating.app.ui.theme.HeatingTheme
import heating.app.ui.zoneSummary
import kotlinx.coroutines.launch

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun RoomsScreen(
    state: HomeState,
    onRefresh: suspend () -> Unit,
    onOpenRoom: (String) -> Unit,
    onCreateRoom: (String) -> Unit,
    onOpenGroup: (String) -> Unit,
    onCreateGroup: (String, List<String>) -> Unit,
    onHouseControl: (GroupControlRequest) -> Unit,
    onCancelHouseBoosts: () -> Unit,
    contentPadding: PaddingValues,
    /** Admins: new devices waiting to be set up. */
    waitingDevices: (@Composable () -> Unit)? = null,
    /** The boiler chip's activity tray reads the feed through this. */
    loadActivity: suspend (limit: Int, before: Long?) -> List<heating.app.data.ActivityEvent> = { _, _ -> emptyList() },
    onBoilerOff: () -> Unit = {},
    onBoilerOn: () -> Unit = {},
) {
    heating.app.ui.components.BoilerOverrideNotice(state.boilerOverride, state.admin, onBoilerOn)
    val scope = rememberCoroutineScope()
    var refreshing by remember { mutableStateOf(false) }
    var adding by rememberSaveable { mutableStateOf(false) }
    var addingGroup by rememberSaveable { mutableStateOf(false) }

    PullToRefreshBox(
        isRefreshing = refreshing,
        onRefresh = { scope.launch { refreshing = true; onRefresh(); refreshing = false } },
        modifier = Modifier.fillMaxSize().padding(contentPadding),
    ) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, bottom = 20.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(2) }) { Header(state, loadActivity, onBoilerOff, onBoilerOn) }
            // A member sees only the rooms an admin gave them: no whole house, no groups, no setup.
            if (state.admin) {
                item(span = { GridItemSpan(2) }) { WholeHouseCard(state, onHouseControl, onCancelHouseBoosts) }
                waitingDevices?.let { card -> item(span = { GridItemSpan(2) }) { card() } }
            }
            if (!state.loading && state.rooms.isEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    Text(
                        noRoomsText(state.admin),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 32.dp),
                    )
                }
            } else if (!state.admin) {
                item(span = { GridItemSpan(2) }) { SectionLabel("${state.rooms.size} ROOMS") }
            } else if (state.groups.isEmpty()) {
                item(span = { GridItemSpan(2) }) { SectionLabel("${state.rooms.size} ROOMS") }
            } else {
                item(span = { GridItemSpan(2) }) { SectionLabel("${state.groups.size} GROUPS") }
                items(state.groups, key = { "group:" + it.id }) { group ->
                    GroupTile(group, group.rooms.mapNotNull { id -> state.rooms.firstOrNull { it.id == id } }) { onOpenGroup(group.id) }
                }
                item(span = { GridItemSpan(2) }) { SectionLabel("${state.rooms.size} ROOMS") }
            }
            items(state.rooms, key = { it.id }) { room -> RoomTile(room) { onOpenRoom(room.id) } }
            if (state.admin) item(span = { GridItemSpan(2) }) {
                TextButton(onClick = { adding = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                    Icon(Icons.Rounded.Add, contentDescription = null)
                    Spacer(Modifier.size(6.dp))
                    Text("Add a room")
                }
            }
            if (state.admin && state.rooms.isNotEmpty()) {
                item(span = { GridItemSpan(2) }) {
                    TextButton(onClick = { addingGroup = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp)) {
                        Icon(Icons.Rounded.Add, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("Add a group")
                    }
                }
            }
        }
    }

    if (adding) {
        var name by rememberSaveable { mutableStateOf("") }
        AlertDialog(
            onDismissRequest = { adding = false },
            title = { Text("New room") },
            text = { OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { onCreateRoom(name.trim()); adding = false }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { adding = false }) { Text("Cancel") } },
        )
    }

    if (addingGroup) {
        GroupEditor(
            title = "New group",
            rooms = state.rooms,
            initialName = "",
            initialRooms = emptyList(),
            onDismiss = { addingGroup = false },
            onSave = { name, rooms -> onCreateGroup(name, rooms); addingGroup = false },
        )
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private const val FLAME = "flame"

@Composable
private fun GroupTile(group: Group, members: List<heating.app.data.Room>, onClick: () -> Unit) {
    val colors = HeatingTheme.colors
    val (bg, fg) = when {
        group.heating -> colors.heat to colors.onHeat
        group.mode == "off" -> colors.off to colors.onOff
        else -> MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
    }
    val summary = groupSummary(group)
    // The card only lights up when every room heats; otherwise a flame marks the rooms that are.
    val heatingNames = members.filter { it.id in group.heatingRooms }.map { it.name }
    val roomList = androidx.compose.ui.text.buildAnnotatedString {
        members.forEachIndexed { i, room ->
            if (i > 0) append(", ")
            append(room.name)
            if (room.id in group.heatingRooms && !group.heating) {
                append(" ")
                appendInlineContent(FLAME, "heating")
            }
        }
        if (members.isEmpty()) append("No rooms")
    }
    val heatingSaid = if (heatingNames.isEmpty() || group.heating) "" else ", ${heatingNames.joinToString(", ")} heating"
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .heightIn(min = 148.dp)
            .semantics { contentDescription = "Group ${group.name}, ${formatTemp(group.current)}, $summary$heatingSaid" },
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(group.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (group.heating) Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.size(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(formatTemp(group.current), style = MaterialTheme.typography.displayMedium)
                Text(summary, style = MaterialTheme.typography.labelLarge)
                Text(
                    roomList, style = MaterialTheme.typography.labelSmall, maxLines = 2,
                    inlineContent = mapOf(
                        FLAME to androidx.compose.foundation.text.InlineTextContent(
                            androidx.compose.ui.text.Placeholder(1.1.em, 1.1.em, androidx.compose.ui.text.PlaceholderVerticalAlign.TextCenter),
                        ) { Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null, tint = colors.heat, modifier = Modifier.fillMaxSize()) },
                    ),
                )
            }
        }
    }
}

@Composable
private fun Header(
    state: HomeState,
    loadActivity: suspend (limit: Int, before: Long?) -> List<heating.app.data.ActivityEvent>,
    onBoilerOff: () -> Unit,
    onBoilerOn: () -> Unit,
) {
    val colors = HeatingTheme.colors
    Column(Modifier.padding(top = 8.dp, bottom = 4.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        // The chip says what the boiler is doing, or that the app is connecting / offline.
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            StatusChip(state, loadActivity, onBoilerOff = onBoilerOff, onBoilerOn = onBoilerOn)
        }
        Text(headline(state.rooms, state.groups, state.status), style = MaterialTheme.typography.headlineMedium, modifier = Modifier.fillMaxWidth())
        state.status?.holiday?.let { holiday ->
            Surface(shape = MaterialTheme.shapes.medium, color = colors.cool) {
                Text(
                    "Holiday until ${holiday.until.take(10)} · ${formatTemp(holiday.target)}",
                    style = MaterialTheme.typography.titleSmall, color = colors.onCool,
                    modifier = Modifier.fillMaxWidth().padding(14.dp),
                )
            }
        }
    }
}

@Composable
private fun RoomTile(room: Room, onClick: () -> Unit) {
    val colors = HeatingTheme.colors
    val zone = room.zones.firstOrNull()
    val off = room.zones.isNotEmpty() && room.zones.all { it.mode == Mode.OFF && it.boost == null }
    val (bg, fg) = when {
        room.heating -> colors.heat to colors.onHeat
        off -> colors.off to colors.onOff
        else -> MaterialTheme.colorScheme.surface to MaterialTheme.colorScheme.onSurface
    }
    val summary = when {
        room.zones.size > 1 -> "${room.zones.size} radiators · separately"
        zone != null -> zoneSummary(zone)
        else -> "No valves yet"
    }
    Card(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = bg, contentColor = fg),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier
            .heightIn(min = 148.dp)
            .semantics { contentDescription = "${room.name}, ${formatTemp(room.current)}, $summary" },
    ) {
        Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(room.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (room.heating) Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null, modifier = Modifier.size(20.dp))
            }
            Spacer(Modifier.size(14.dp))
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(formatTemp(room.current), style = MaterialTheme.typography.displayMedium)
                Text(summary, style = MaterialTheme.typography.labelLarge, color = fg.copy(alpha = 0.85f).takeIf { fg != Color.Unspecified } ?: fg)
                SensorInfo(room, color = fg)
                room.notices.firstOrNull { !(room.sensorOffline && it == "${room.sensor} is offline") }?.let {
                    Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
                }
            }
        }
    }
}
