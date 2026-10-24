package heating.app.ui.energy

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
fun EnergyScreen(api: HeatingApi, state: EnergyState, onPeriod: (String) -> Unit, contentPadding: PaddingValues) {
    Column(
        Modifier.fillMaxSize().padding(contentPadding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("Energy", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        LivePowerSection(api)
        EnergySection(state, onPeriod)
    }
}
