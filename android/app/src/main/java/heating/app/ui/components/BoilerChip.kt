package heating.app.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.HourglassBottom
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import heating.app.data.ActivityEvent
import heating.app.data.ConnectionState
import heating.app.data.HomeState
import heating.app.data.Status
import heating.app.ui.BoilerState
import heating.app.ui.boilerChipLabel
import heating.app.ui.boilerExplanation
import heating.app.ui.boilerState
import heating.app.ui.secondsUntil
import heating.app.ui.theme.HeatingTheme
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * The boiler's state with a live countdown while it finishes a run or rests. Tap for a tray: why the
 * boiler is in that state, then what changed in the heating lately ([loadActivity], with [latestActivity] pushed live).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BoilerChip(
    status: Status?,
    loadActivity: suspend (limit: Int, before: Long?) -> List<ActivityEvent>,
    latestActivity: ActivityEvent?,
    modifier: Modifier = Modifier,
) {
    val colors = HeatingTheme.colors
    val state = boilerState(status)
    val until = status?.boiler?.until?.takeIf { state == BoilerState.CYCLING || state == BoilerState.RESTING }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(until) {
        while (until != null) {
            now = Instant.now()
            delay(1_000L)
        }
    }
    val label = boilerChipLabel(state, secondsUntil(until, now))
    var explaining by rememberSaveable { mutableStateOf(false) }

    val active = state == BoilerState.HEATING || state == BoilerState.CYCLING
    val bg = if (active) colors.heatContainer else colors.off
    val fg = if (active) colors.heatText else colors.onOff
    val icon = when (state) {
        BoilerState.HEATING -> Icons.Rounded.LocalFireDepartment
        BoilerState.CYCLING -> Icons.Rounded.HourglassBottom
        BoilerState.RESTING -> Icons.Rounded.Bedtime
        BoilerState.IDLE -> Icons.Rounded.CheckCircle
    }
    Surface(
        onClick = { explaining = true },
        shape = CircleShape,
        color = bg,
        modifier = modifier.semantics {
            contentDescription = "Boiler: $label. Tap for details"
            role = Role.Button
        },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
    if (explaining) {
        // A bottom sheet: dragging down or tapping outside closes it.
        ModalBottomSheet(onDismissRequest = { explaining = false }) {
            Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 16.dp)) {
                Text("Boiler: $label", style = MaterialTheme.typography.titleLarge)
                Text(boilerExplanation(state), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 8.dp))
                ActivityTray(loadActivity, latestActivity, Modifier.fillMaxHeight(0.6f))
            }
        }
    }
}

/**
 * The header chip: while the app is connected, the boiler's state; otherwise whether the app is
 * checking the service ("Connecting…") or can't reach it ("Offline"), with the reason on tap.
 */
@Composable
fun StatusChip(
    state: HomeState,
    loadActivity: suspend (limit: Int, before: Long?) -> List<ActivityEvent>,
    modifier: Modifier = Modifier,
) {
    when (state.connection) {
        // The boiler is the whole house's: a member's chip only says the app is connected.
        ConnectionState.LIVE -> if (state.admin) BoilerChip(state.status, loadActivity, state.latestActivity, modifier) else InfoChip(
            icon = Icons.Rounded.CheckCircle,
            label = "Live",
            title = "Connected",
            text = "Your rooms are up to date and change here as soon as they change in the house.",
            modifier = modifier,
        )
        ConnectionState.CONNECTING -> InfoChip(
            icon = Icons.Rounded.Sync,
            label = "Connecting…",
            title = "Checking the heating service",
            text = "The app is connecting to the heating service to get the latest temperatures. " +
                "This usually takes a moment after the phone wakes up or changes network. " +
                "Your heating keeps running on its own schedule meanwhile.",
            modifier = modifier,
        )
        ConnectionState.OFFLINE -> InfoChip(
            icon = Icons.Rounded.CloudOff,
            label = "Offline",
            title = "Can't reach the heating service",
            text = (state.connectionProblem ?: "The heating service isn't answering.") +
                "\n\nThe app keeps trying in the background. Your heating keeps running on its own " +
                "schedule; what you see here may be out of date until the app reconnects.",
            warning = true,
            modifier = modifier,
        )
    }
}

@Composable
private fun InfoChip(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    warning: Boolean = false,
) {
    val colors = HeatingTheme.colors
    var explaining by rememberSaveable { mutableStateOf(false) }
    val bg = if (warning) MaterialTheme.colorScheme.errorContainer else colors.off
    val fg = if (warning) MaterialTheme.colorScheme.onErrorContainer else colors.onOff
    Surface(
        onClick = { explaining = true },
        shape = CircleShape,
        color = bg,
        modifier = modifier.semantics {
            contentDescription = "$label. Tap for details"
            role = Role.Button
        },
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, tint = fg, modifier = Modifier.size(16.dp))
            Spacer(Modifier.size(6.dp))
            Text(label, style = MaterialTheme.typography.labelLarge, color = fg)
        }
    }
    if (explaining) {
        AlertDialog(
            onDismissRequest = { explaining = false },
            icon = { Icon(icon, contentDescription = null) },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { explaining = false }) { Text("OK") } },
        )
    }
}
