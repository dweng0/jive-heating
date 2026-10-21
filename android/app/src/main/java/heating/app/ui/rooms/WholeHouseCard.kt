package heating.app.ui.rooms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bolt
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import heating.app.data.GroupControlRequest
import heating.app.data.HomeState
import heating.app.data.Mode
import heating.app.ui.room.BoostSheet
import heating.app.ui.theme.HeatingTheme

/** One control for every room: boost everything, or end every boost. */
@Composable
fun WholeHouseCard(
    state: HomeState,
    onControl: (GroupControlRequest) -> Unit,
    onCancelBoosts: () -> Unit,
) {
    val zones = state.rooms.flatMap { it.zones }.filter { it.valves.isNotEmpty() }
    if (zones.size < 2) return

    var boosting by rememberSaveable { mutableStateOf(false) }
    val colors = HeatingTheme.colors

    val boosted = zones.count { it.boost != null }
    val modes = zones.map { it.mode }.toSet()
    val summary = when {
        boosted == zones.size -> "Every room is boosted"
        modes.size == 1 -> when (modes.single()) {
            Mode.SCHEDULE -> "Every room is on its schedule"
            Mode.MANUAL -> "Every room is on manual"
            Mode.OFF -> "Every room is off"
        }
        else -> "Rooms are set differently"
    }
    val holiday = state.status?.holiday?.active == true

    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = colors.heatContainer),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = MaterialTheme.shapes.medium, color = colors.heat, contentColor = colors.onHeat) {
                    Icon(Icons.Rounded.Home, contentDescription = null, modifier = Modifier.padding(8.dp).size(24.dp))
                }
                Spacer(Modifier.size(12.dp))
                Column {
                    Text("Whole house", style = MaterialTheme.typography.titleMedium)
                    Text(summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            // Boost only: boost every room, or end every boost.
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(
                    onClick = { boosting = true },
                    enabled = !holiday,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Boost all", maxLines = 1)
                }
                OutlinedButton(
                    onClick = onCancelBoosts,
                    enabled = boosted > 0,
                    modifier = Modifier.weight(1f).heightIn(min = 48.dp),
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Boost off", maxLines = 1)
                }
            }
        }
    }

    if (boosting) {
        BoostSheet(
            roomName = "every room",
            initialTarget = state.boostTarget,
            onDismiss = { boosting = false },
            onStart = { minutes, t -> onControl(GroupControlRequest(boost = minutes, target = t)); boosting = false },
        )
    }
}
