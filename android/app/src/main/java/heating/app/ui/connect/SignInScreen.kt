package heating.app.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import heating.app.data.AccountApi
import heating.app.data.HeatingApiException
import heating.app.data.Session
import heating.app.ui.components.PasswordField
import heating.app.ui.components.ShowPasswordsToggle
import kotlinx.coroutines.launch

private enum class Step { Address, SignIn, Setup }

/**
 * Where the heating service is, then sign in. If the service has no users yet, first-run setup
 * creates the admin account instead, which needs the service token from the server's .env once.
 */
@Composable
fun SignInScreen(
    initialUrl: String?,
    initialUsername: String?,
    notice: String?,
    onSignedIn: (baseUrl: String, Session) -> Unit,
) {
    var step by rememberSaveable { mutableStateOf(if (initialUrl != null) Step.SignIn else Step.Address) }
    var url by rememberSaveable { mutableStateOf(initialUrl ?: "https://") }
    var username by rememberSaveable { mutableStateOf(initialUsername ?: "") }
    var password by rememberSaveable { mutableStateOf("") }
    var confirm by rememberSaveable { mutableStateOf("") }
    var serviceToken by rememberSaveable { mutableStateOf("") }
    var busy by rememberSaveable { mutableStateOf(false) }
    var showPasswords by rememberSaveable { mutableStateOf(false) }
    var error by rememberSaveable { mutableStateOf(notice) }
    val scope = rememberCoroutineScope()
    val baseUrl = url.trim().trimEnd('/')

    fun run(action: suspend (AccountApi) -> Unit) {
        busy = true
        error = null
        scope.launch {
            runCatching { action(AccountApi(baseUrl)) }
                .onFailure { e ->
                    error = when {
                        e is HeatingApiException -> e.message
                        else -> "Can't reach the heating service at that address."
                    }
                }
            busy = false
        }
    }

    Column(
        Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            when (step) {
                Step.Address -> "Connect to your heating"
                Step.SignIn -> "Sign in"
                Step.Setup -> "Set up your heating"
            },
            style = MaterialTheme.typography.headlineLarge,
            modifier = Modifier.padding(top = 32.dp),
        )
        Text(
            when (step) {
                Step.Address -> "Enter the address of the heating service."
                Step.SignIn -> baseUrl.removePrefix("https://").removePrefix("http://")
                Step.Setup -> "No one has an account yet. Create the admin account; you'll need the service token from the server's .env file this once."
            },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        when (step) {
            Step.Address -> {
                OutlinedTextField(
                    value = url, onValueChange = { url = it.trim() },
                    label = { Text("Service address") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            Step.SignIn, Step.Setup -> {
                if (step == Step.Setup) {
                    PasswordField(serviceToken, { serviceToken = it.trim() }, "Service token", showPasswords)
                }
                OutlinedTextField(
                    value = username, onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                PasswordField(password, { password = it }, "Password", showPasswords)
                if (step == Step.Setup) {
                    PasswordField(confirm, { confirm = it }, "Confirm password", showPasswords)
                    Text(
                        "At least ${AccountApi.MIN_PASSWORD} characters. A few random words work well.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                ShowPasswordsToggle(showPasswords, { showPasswords = it }, if (step == Step.Setup) "Show passwords" else "Show password")
            }
        }

        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        val ready = when (step) {
            Step.Address -> baseUrl.startsWith("http") && baseUrl.length > 10
            Step.SignIn -> username.isNotBlank() && password.isNotEmpty()
            Step.Setup -> serviceToken.isNotBlank() && username.isNotBlank() &&
                password.length >= AccountApi.MIN_PASSWORD && password == confirm
        }
        Button(
            onClick = {
                when (step) {
                    Step.Address -> run { api -> step = if (api.status().setupRequired) Step.Setup else Step.SignIn }
                    Step.SignIn -> run { api -> onSignedIn(baseUrl, api.login(username.trim(), password)) }
                    Step.Setup -> run { api -> onSignedIn(baseUrl, api.setup(serviceToken, username.trim(), password)) }
                }
            },
            enabled = ready && !busy,
            modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp),
        ) {
            if (busy) {
                CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
            } else {
                Text(
                    when (step) {
                        Step.Address -> "Continue"
                        Step.SignIn -> "Sign in"
                        Step.Setup -> "Create admin account"
                    },
                )
            }
        }
        if (step != Step.Address) {
            TextButton(onClick = { step = Step.Address; error = null }, modifier = Modifier.fillMaxWidth()) {
                Text("Use a different address")
            }
        }
    }
}
