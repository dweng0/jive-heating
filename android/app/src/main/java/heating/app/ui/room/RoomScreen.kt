package heating.app.ui.room

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.Surface
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import heating.app.data.HomeState
import heating.app.data.Mode
import heating.app.data.Room
import heating.app.data.Zone
import heating.app.ui.BOOST_MINUTES
import heating.app.ui.boostLabel
import heating.app.ui.boostStart
import heating.app.ui.clockTime
import heating.app.ui.formatTemp
import heating.app.ui.stepTarget
import heating.app.ui.components.Dial
import heating.app.ui.components.SensorInfo
import heating.app.ui.theme.HeatingTheme
import heating.app.ui.zoneSummary

/** Actions the room screen can take. */
interface RoomActions {
    fun setMode(zoneId: String, mode: Mode, target: Double? = null)
    fun nudgeTarget(zoneId: String, target: Double)
    fun boost(zoneId: String, minutes: Int, target: Double)
    fun cancelBoost(zoneId: String)
}

@Composable
fun RoomScreen(
    room: Room,
    state: HomeState,
    actions: RoomActions,
    onEditSchedule: (zoneId: String) -> Unit,
    contentPadding: PaddingValues,
    history: @Composable () -> Unit = {},
) {
    var zoneIndex by rememberSaveable(room.id) { mutableIntStateOf(0) }
    val zone = room.zones.getOrNull(zoneIndex) ?: room.zones.firstOrNull()
    var boosting by rememberSaveable { mutableStateOf(false) }

    if (zone == null) {
        Column(Modifier.fillMaxSize().padding(contentPadding).padding(24.dp)) {
            Text("No valves in ${room.name} yet.", style = MaterialTheme.typography.titleMedium)
            Text(
                if (state.admin) "Add one from the room's settings." else "An admin adds them from the room's settings.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        return
    }

    val pending = state.pendingTargets[zone.id]
    val shown = pending ?: zone.boost?.target ?: zone.target
    val off = zone.mode == Mode.OFF && zone.boost == null && pending == null

    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        if (room.zones.size > 1) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                room.zones.forEachIndexed { i, z ->
                    FilterChip(
                        selected = i == zoneIndex,
                        onClick = { zoneIndex = i },
                        label = { Text(z.valves.firstOrNull() ?: "Radiator ${i + 1}") },
                    )
                }
            }
        }

        SensorInfo(room)

        ModeSwitch(zone) { mode -> actions.setMode(zone.id, mode, if (mode == Mode.MANUAL) shown ?: zone.manualTarget else null) }

        Dial(
            target = if (off) null else shown,
            current = zone.current,
            label = when {
                zone.boost != null -> "Boost"
                off -> "Heating"
                else -> "Target"
            },
            heating = zone.heating,
            enabled = zone.boost == null,
            onTargetChange = { actions.nudgeTarget(zone.id, it) },
        )

        Row(horizontalArrangement = Arrangement.spacedBy(40.dp)) {
            StepButton(Icons.Rounded.Remove, "Lower target", enabled = zone.boost == null) {
                actions.nudgeTarget(zone.id, stepTarget(shown ?: zone.manualTarget, -1))
            }
            StepButton(Icons.Rounded.Add, "Raise target", enabled = zone.boost == null) {
                actions.nudgeTarget(zone.id, stepTarget(shown ?: zone.manualTarget, +1))
            }
        }

        if (zone.boost == null || pending != null) Text(
            if (pending != null) "Setting ${formatTemp(pending)}…" else zoneSummary(zone),
            style = MaterialTheme.typography.titleSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Column(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (zone.boost != null) {
                BoostingBanner(zone.boost.target, zone.boost.endsAt) { actions.cancelBoost(zone.id) }
            } else {
                Button(
                    onClick = { boosting = true },
                    enabled = state.status?.holiday?.active != true,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                ) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null)
                    Spacer(Modifier.size(8.dp))
                    Text("Boost")
                }
            }
            OutlinedButton(
                onClick = { onEditSchedule(zone.id) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
            ) { Text("Edit schedule") }
        }

        Box(Modifier.padding(bottom = 24.dp)) { history() }
    }

    if (boosting) {
        BoostSheet(
            roomName = room.name,
            initialTarget = boostStart(state.boostTarget, zone.target),
            onDismiss = { boosting = false },
            onStart = { minutes, target -> actions.boost(zone.id, minutes, target); boosting = false },
        )
    }
}

/** A clear "boost is on" state with its cancel right next to it. */
@Composable
private fun BoostingBanner(target: Double, endsAt: String, onCancel: () -> Unit) {
    val colors = HeatingTheme.colors
    val until = runCatching { clockTime(endsAt) }.getOrDefault(endsAt)
    Surface(shape = MaterialTheme.shapes.large, color = colors.heat, contentColor = colors.onHeat, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 10.dp, top = 12.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Rounded.Bolt, contentDescription = null)
            Spacer(Modifier.size(10.dp))
            Text(
                "Boosting to ${formatTemp(target)} until $until",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            OutlinedButton(
                onClick = onCancel,
                border = BorderStroke(2.dp, colors.onHeat),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text("Cancel boost", color = colors.onHeat) }
        }
    }
}

@Composable
private fun ModeSwitch(zone: Zone, onMode: (Mode) -> Unit) {
    val modes = listOf(Mode.SCHEDULE to "Schedule", Mode.MANUAL to "Manual", Mode.OFF to "Off")
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        modes.forEachIndexed { i, (mode, label) ->
            SegmentedButton(
                selected = zone.mode == mode,
                onClick = { if (zone.mode != mode) onMode(mode) },
                shape = SegmentedButtonDefaults.itemShape(i, modes.size),
                modifier = Modifier.heightIn(min = 48.dp),
            ) { Text(label) }
        }
    }
}

@Composable
private fun StepButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    FilledIconButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.size(64.dp),
        colors = IconButtonDefaults.filledIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
        ),
    ) { Icon(icon, contentDescription = label, modifier = Modifier.size(28.dp)) }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BoostSheet(roomName: String, initialTarget: Double, onDismiss: () -> Unit, onStart: (Int, Double) -> Unit) {
    var target by rememberSaveable { mutableDoubleStateOf(stepTarget(initialTarget, 0)) }
    var minutes by rememberSaveable { mutableIntStateOf(60) }
    val colors = HeatingTheme.colors

    // Open fully so "Start boost" is never below the fold.
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Boost $roomName", style = MaterialTheme.typography.headlineSmall)
                Text(formatTemp(target), style = MaterialTheme.typography.displaySmall, color = colors.heatText)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                FilledIconButton(onClick = { target = stepTarget(target, -1) }) { Icon(Icons.Rounded.Remove, "Lower boost temperature") }
                Text("Temperature", Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FilledIconButton(onClick = { target = stepTarget(target, +1) }) { Icon(Icons.Rounded.Add, "Raise boost temperature") }
            }
            LazyVerticalGrid(
                columns = GridCells.Fixed(3),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.heightIn(max = 120.dp),
                userScrollEnabled = false,
            ) {
                items(BOOST_MINUTES) { m ->
                    FilterChip(
                        selected = m == minutes,
                        onClick = { minutes = m },
                        label = { Text(boostLabel(m), Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center) },
                        modifier = Modifier.heightIn(min = 48.dp),
                    )
                }
            }
            Button(
                onClick = { onStart(minutes, target) },
                modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
                colors = ButtonDefaults.buttonColors(containerColor = colors.heat, contentColor = colors.onHeat),
            ) { Text("Start boost") }
            TextButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("Not now") }
        }
    }
}
