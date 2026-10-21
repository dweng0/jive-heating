package heating.app.ui.energy

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import heating.app.data.EnergyPricesRequest
import heating.app.data.EnergySettings

/** Gas and electricity prices and the household's currency for the dashboard's costs. Shown to admins only. */
@Composable
fun EnergyPricesCard(settings: EnergySettings?, onSave: (EnergyPricesRequest) -> Unit) {
    var draft by remember { mutableStateOf<PriceDraft?>(null) }
    var currency by remember { mutableStateOf("GBP") }

    Text("Energy prices", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, top = 8.dp))
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (settings == null) {
                Text("Loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            settings.notices.forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium) }
            PriceField.entries.forEach { field ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Text(field.label, Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(formatPrice(field, settings), style = MaterialTheme.typography.bodyLarge)
                }
            }
            Text(currencyLine(settings), color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                "Changing a price recalculates past costs too.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { draft = priceDraft(settings); currency = settings.currency }, modifier = Modifier.align(Alignment.End)) { Text("Edit prices") }
        }
    }

    draft?.let { current ->
        val request = current.toRequest(currency, settings?.currency)
        AlertDialog(
            onDismissRequest = { draft = null },
            title = { Text("Energy prices") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    PriceField.entries.forEach { field ->
                        OutlinedTextField(
                            value = current[field].orEmpty(),
                            onValueChange = { draft = current + (field to it) },
                            label = { Text(field.label) },
                            suffix = { Text(field.unit(currency)) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        )
                    }
                    CurrencyPicker(currency, currencyChoices(settings?.currency ?: "GBP")) { currency = it }
                    if (currency != settings?.currency) Text(NOT_CONVERTED, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = {
                TextButton(enabled = request != null, onClick = { request?.let(onSave); draft = null }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { draft = null }) { Text("Cancel") } },
        )
    }
}

/** "Currency" with a button showing the choice; tap for the list. */
@Composable
private fun CurrencyPicker(currency: String, choices: List<Pair<String, String>>, onChoose: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("Currency", Modifier.weight(1f))
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.semantics { contentDescription = "Currency" }) {
                Text(currencyLabel(currency))
            }
            DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
                choices.forEach { (code, label) ->
                    DropdownMenuItem(text = { Text(label) }, onClick = { open = false; onChoose(code) })
                }
            }
        }
    }
}
