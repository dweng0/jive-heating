package heating.app

import heating.app.notify.unregisterFromPush
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import heating.app.data.AccountApi
import heating.app.data.HeatingApi
import heating.app.data.HeatingStore
import heating.app.data.KtorHeatingApi
import heating.app.data.ServerConfig
import heating.app.data.ServerSettings
import heating.app.data.Session
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.launch

/** Holds the connection for the whole app. No connection until someone has signed in. */
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val settings = ServerSettings(app)

    var connection by mutableStateOf<Connection?>(null)
        private set

    /** Why the user was sent back to sign-in (session ended elsewhere), shown once on that screen. */
    var signedOutReason by mutableStateOf<String?>(null)
        private set

    init {
        settings.load()?.let(::connect)
    }

    fun lastUrl(): String? = settings.lastUrl()
    fun lastUsername(): String? = settings.lastUsername()

    fun signedIn(baseUrl: String, session: Session) {
        signedOutReason = null
        val config = ServerConfig(baseUrl.trimEnd('/'), session.token)
        settings.save(config, session.user.username)
        connect(config)
    }

    /** After changing password the server issues a fresh token for this device. */
    fun replaceSession(session: Session) {
        val current = connection ?: return
        signedIn(current.baseUrl, session)
    }

    private fun connect(config: ServerConfig) {
        viewModelScope.coroutineContext.cancelChildren()
        val api = KtorHeatingApi(config, onUnauthorized = { sessionEnded() })
        connection = Connection(
            baseUrl = config.baseUrl,
            api = api,
            store = HeatingStore(api, viewModelScope).also { it.start() },
            account = AccountApi(config.baseUrl, config.token, onUnauthorized = { sessionEnded() }),
        )
    }

    private fun sessionEnded() = viewModelScope.launch {
        if (connection == null) return@launch
        signedOutReason = "Your session has ended. Please sign in again."
        signOut()
    }

    fun signOut() {
        // Stop this phone getting the person's notifications; outlives the cancelled view-model work.
        settings.load()?.let { config ->
            val app = getApplication<Application>()
            CoroutineScope(Dispatchers.IO).launch { unregisterFromPush(app, AccountApi(config.baseUrl, config.token)) }
        }
        viewModelScope.coroutineContext.cancelChildren()
        settings.signOut()
        connection = null
    }

    fun clearSignedOutReason() {
        signedOutReason = null
    }
}

data class Connection(val baseUrl: String, val api: HeatingApi, val store: HeatingStore, val account: AccountApi)
