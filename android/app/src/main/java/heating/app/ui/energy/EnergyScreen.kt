package heating.app.ui.energy

import androidx.compose.ui.Alignment
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import heating.app.data.EnergyState
import heating.app.data.HeatingApi

/** What the house uses: electricity now first, then use and cost for today, the week or the month. Admin only. */
@Composable
fun EnergyScreen(
    api: HeatingApi,
    state: EnergyState,
    onPeriod: (String) -> Unit,
    onOpenRoom: (String) -> Unit,
    onOpenEverythingElse: () -> Unit,
    contentPadding: PaddingValues,
    onOpenArea: (String) -> Unit = {},
    onManage: () -> Unit = {},
) {
    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Energy", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            OutlinedButton(onClick = onManage) { Text("Manage") }
        }
        LivePowerSection(api, onOpenRoom, onOpenEverythingElse, onOpenArea = onOpenArea, onAddArea = onManage)
        EnergySection(state, onPeriod, onSetTarget = onManage)
    }
}
