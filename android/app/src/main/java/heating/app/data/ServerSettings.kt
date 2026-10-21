package heating.app.data

import android.content.Context

/** Server address, session token and who signed in, kept in app-private storage. */
class ServerSettings(context: Context) {
    private val prefs = context.getSharedPreferences("server", Context.MODE_PRIVATE)

    fun load(): ServerConfig? {
        val url = prefs.getString(URL, null) ?: return null
        val token = prefs.getString(TOKEN, null) ?: return null
        return ServerConfig(url, token)
    }

    fun save(config: ServerConfig, username: String? = null) = prefs.edit()
        .putString(URL, config.baseUrl)
        .putString(TOKEN, config.token)
        .apply { username?.let { putString(USERNAME, it) } }
        .apply()

    /** The address and username of the last sign-in, to prefill the sign-in screen. */
    fun lastUrl(): String? = prefs.getString(URL, null)
    fun lastUsername(): String? = prefs.getString(USERNAME, null)

    /** Forget the session but keep the address and username. */
    fun signOut() = prefs.edit().remove(TOKEN).apply()

    private companion object {
        const val URL = "url"
        const val TOKEN = "token"
        const val USERNAME = "username"
    }
}
