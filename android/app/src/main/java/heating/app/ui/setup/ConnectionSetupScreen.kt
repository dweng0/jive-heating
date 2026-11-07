package heating.app.ui.setup

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import heating.app.data.BridgeFound
import heating.app.data.BrokerView
import heating.app.data.HeatingApi
import heating.app.data.friendlyError
import kotlinx.coroutines.launch

private val STEP_NAMES = listOf("Broker", "Zigbee2MQTT", "Test notification")

/**
 * The connection setup steps (Broker, Zigbee2MQTT, Test notification): shown instead of the Rooms screen on a new
 * install, and from Account > Connection. [onFinish] runs once "Finish" has marked setup complete.
 */
@Composable
fun ConnectionSetupScreen(api: HeatingApi, onFinish: () -> Unit, contentPadding: PaddingValues = PaddingValues()) {
    var step by rememberSaveable { mutableStateOf(0) }
    Column(
        Modifier.padding(contentPadding).verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text("Connect your home", style = MaterialTheme.typography.headlineSmall)
        Text(
            "Point the app at your MQTT broker and Zigbee2MQTT. Each step can be tested before it is saved.",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("Step ${step + 1} of ${STEP_NAMES.size}: ${STEP_NAMES[step]}", style = MaterialTheme.typography.titleMedium)
        when (step) {
            0 -> BrokerStep(api, onSaved = { step = 1 })
            1 -> ZigbeeStep(api, onBack = { step = 0 }, onSaved = { step = 2 })
            else -> NotificationStep(api, onBack = { step = 1 }, onFinish = onFinish)
        }
    }
}

@Composable
private fun FieldError(message: String?) {
    if (message != null) Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
}

@Composable
private fun BrokerStep(api: HeatingApi, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var saved by remember { mutableStateOf<BrokerView?>(null) }
    var form by remember { mutableStateOf(BrokerForm()) }
    var failed by remember { mutableStateOf<BrokerCheck.Failed?>(null) }
    var tested by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        runCatching { api.savedBroker() }.getOrNull()?.let { b ->
            saved = b
            form = BrokerForm(b.host, b.port.toString(), b.tls, b.username.orEmpty())
        }
    }

    fun change(next: BrokerForm) {
        form = next
        tested = false
        failed = null
    }

    fun run(thenSave: Boolean) {
        busy = true
        scope.launch {
            when (val check = api.checkBroker(form)) {
                BrokerCheck.Ok -> {
                    tested = true
                    failed = null
                    if (thenSave) {
                        runCatching { api.saveBroker(form.toRequest()) }
                            .onSuccess { saved = it; onSaved() }
                            .onFailure { failed = BrokerCheck.Failed(BrokerField.Host, friendlyError(it)) }
                    }
                }
                is BrokerCheck.Failed -> failed = check
            }
            busy = false
        }
    }

    fun error(field: BrokerField) = failed?.takeIf { it.field == field }?.message

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("The MQTT broker Zigbee2MQTT publishes to, such as Mosquitto.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedTextField(form.host, { change(form.copy(host = it)) }, label = { Text("Host") }, singleLine = true, isError = error(BrokerField.Host) != null, modifier = Modifier.fillMaxWidth())
            FieldError(error(BrokerField.Host))
            OutlinedTextField(
                form.port, { change(form.copy(port = it)) }, label = { Text("Port") }, singleLine = true,
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = error(BrokerField.Port) != null, modifier = Modifier.fillMaxWidth(),
            )
            FieldError(error(BrokerField.Port))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Secure connection (TLS)", Modifier.weight(1f))
                Switch(form.tls, { tls ->
                    val port = when {
                        tls && form.port == "1883" -> "8883"
                        !tls && form.port == "8883" -> "1883"
                        else -> form.port
                    }
                    change(form.copy(tls = tls, port = port))
                })
            }
            FieldError(error(BrokerField.Tls))
            OutlinedTextField(form.username, { change(form.copy(username = it)) }, label = { Text("Username (leave empty for none)") }, singleLine = true, isError = error(BrokerField.Username) != null, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                form.password, { change(form.copy(password = it)) }, label = { Text("Password") }, singleLine = true,
                placeholder = { if (saved?.passwordSet == true && !form.clearPassword) Text("Saved. Type to change.") },
                enabled = !form.clearPassword, visualTransformation = PasswordVisualTransformation(),
                isError = error(BrokerField.Password) != null, modifier = Modifier.fillMaxWidth(),
            )
            FieldError(error(BrokerField.Password))
            if (saved?.passwordSet == true) {
                Row(Modifier.selectable(form.clearPassword, role = Role.Checkbox, onClick = { change(form.copy(clearPassword = !form.clearPassword, password = "")) }), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(form.clearPassword, null)
                    Text("Remove the saved password")
                }
            }
            if (tested) Text("Connected", color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { run(thenSave = false) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Test connection") }
                Button(onClick = { run(thenSave = true) }, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp)) { Text("Save and continue") }
            }
        }
    }
}

@Composable
private fun ZigbeeStep(api: HeatingApi, onBack: () -> Unit, onSaved: () -> Unit) {
    val scope = rememberCoroutineScope()
    var found by remember { mutableStateOf<List<BridgeFound>?>(null) }
    var chosen by remember { mutableStateOf<String?>(null) }
    var typed by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var looks by remember { mutableStateOf(0) }

    LaunchedEffect(looks) {
        found = null
        found = runCatching { api.discoverZigbee2mqtt() }.getOrDefault(emptyList())
    }
    val base = typed.trim().ifEmpty { chosen }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Which Zigbee2MQTT on the broker runs your valves and meters.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            val list = found
            when {
                list == null -> Text("Looking for Zigbee2MQTT on the broker…")
                list.isEmpty() -> Text(NO_BRIDGE_TEXT)
                else -> list.forEach { b ->
                    // Nothing is chosen for her, even with one found.
                    Row(
                        Modifier.fillMaxWidth().selectable(chosen == b.baseTopic && typed.isBlank(), role = Role.RadioButton, onClick = { chosen = b.baseTopic; typed = "" }),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(chosen == b.baseTopic && typed.isBlank(), null)
                        Text(bridgeLabel(b), Modifier.padding(start = 8.dp))
                    }
                }
            }
            OutlinedTextField(typed, { typed = it; error = null }, label = { Text("Or type its base topic") }, singleLine = true, isError = error != null, modifier = Modifier.fillMaxWidth())
            FieldError(error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack) { Text("Back") }
                OutlinedButton(onClick = { looks++ }) { Text("Look again") }
                Button(
                    onClick = {
                        val topic = base ?: return@Button
                        busy = true
                        scope.launch {
                            runCatching { api.saveBaseTopic(topic) }.onSuccess { onSaved() }.onFailure { error = friendlyError(it) }
                            busy = false
                        }
                    },
                    enabled = base != null && !busy,
                ) { Text("Save and continue") }
            }
        }
    }
}

@Composable
private fun NotificationStep(api: HeatingApi, onBack: () -> Unit, onFinish: () -> Unit) {
    val scope = rememberCoroutineScope()
    var result by remember { mutableStateOf<heating.app.data.TestNotificationResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }

    Card {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Check that your phones and browsers hear from your home.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            result?.let { r ->
                if (r.sent) Text("Sent. Look for “Test notification” on your phones and browsers.", color = MaterialTheme.colorScheme.primary)
                if (!r.registered) Text("You have no phone or browser registered for notifications yet. This phone registers when you allow notifications for the app; turn them on in Android's settings for this app, then send the test again.")
            }
            FieldError(error)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = onBack) { Text("Back") }
                OutlinedButton(
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching { api.sendTestNotification() }.onSuccess { result = it; error = null }.onFailure { error = friendlyError(it) }
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) { Text("Send test notification") }
                Button(
                    onClick = {
                        busy = true
                        scope.launch {
                            runCatching { api.completeSetup() }.onSuccess { onFinish() }.onFailure { error = friendlyError(it) }
                            busy = false
                        }
                    },
                    enabled = !busy,
                ) { Text("Finish") }
            }
        }
    }
}

/** "Lost connection to your broker since 18:04", tapping through to Account > Connection. */
@Composable
fun BrokerBanner(since: String?, onOpen: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Icon(Icons.Rounded.CloudOff, null, tint = MaterialTheme.colorScheme.error)
        Text(lostText(since), Modifier.weight(1f), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
        TextButton(onClick = onOpen) { Text("Connection") }
    }
}

/** Account > Connection: opens the setup steps again to change the broker or Zigbee2MQTT. */
@Composable
fun ConnectionCard(onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Connection", style = MaterialTheme.typography.titleMedium)
            Text("The MQTT broker and Zigbee2MQTT this app talks to.", color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = onOpen, modifier = Modifier.heightIn(min = 48.dp)) { Text("Change connection") }
        }
    }
}
