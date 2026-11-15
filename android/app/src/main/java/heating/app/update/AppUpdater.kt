package heating.app.update

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable
import java.io.File

/** The Android app the service holds (GET /api/app); version is null when the service does not know it. */
@Serializable
data class AndroidAppInfo(val size: Long = 0, val versionCode: Int? = null, val versionName: String? = null)

@Serializable
data class AppsInfo(val android: AndroidAppInfo? = null)

/** Where updates come from: the service's copy of the APK. */
interface UpdateSource {
    suspend fun appInfo(): AndroidAppInfo?
    /**
     * Downloads the APK into [to], telling [progress] bytes so far and the total when known. With [from] above 0, [to]
     * already holds that many bytes from an interrupted try and only the rest is asked for.
     */
    suspend fun downloadApk(to: File, from: Long, progress: (Long, Long?) -> Unit)
}

/** Android's package installer, behind an interface so the update flow can be tested without a device. */
interface Installer {
    fun canInstall(): Boolean
    /** Opens the setting that lets this app install apps. */
    fun requestPermission()
    fun install(apk: File)
}

/** When the service was last asked for its version, in epoch milliseconds. */
interface LastCheck { var at: Long }

sealed interface UpdateState {
    /** Not checked yet, or the service does not know its version: nothing to offer. */
    data object Unknown : UpdateState
    data object UpToDate : UpdateState
    data class Available(val versionName: String) : UpdateState
    data class Downloading(val versionName: String, val fraction: Float?) : UpdateState
    data class NeedsPermission(val versionName: String) : UpdateState
    data class Installing(val versionName: String) : UpdateState
    data class Failed(val versionName: String) : UpdateState
}

/** The banner's words for [state], or null for no banner. */
fun updateBanner(state: UpdateState): String? = when (state) {
    is UpdateState.Available -> "Version ${state.versionName} is ready"
    is UpdateState.Downloading -> "Downloading version ${state.versionName}" + (state.fraction?.let { " · ${(it * 100).toInt()}%" } ?: "")
    is UpdateState.NeedsPermission -> "Allow Heating to install apps to update"
    is UpdateState.Installing -> "Installing version ${state.versionName}"
    is UpdateState.Failed -> "Update failed"
    UpdateState.Unknown, UpdateState.UpToDate -> null
}

/** The line under the version on Account. */
fun accountUpdateLine(state: UpdateState): String? = if (state == UpdateState.UpToDate) "Up to date" else updateBanner(state)

/**
 * Checks the service for a newer app than [installedCode] (at most once an hour) and, when asked, downloads it into
 * [dir] and hands it to the [installer]. The installed app keeps working whatever happens here.
 */
class AppUpdater(
    private val installedCode: Int,
    private val source: UpdateSource,
    private val installer: Installer,
    private val dir: File,
    private val now: () -> Long,
    private val lastCheck: LastCheck,
) {
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Unknown)
    val state: StateFlow<UpdateState> = _state

    private var offered: String? = null

    suspend fun check(force: Boolean = false) {
        val t = now()
        if (!force && t - lastCheck.at < CHECK_EVERY_MS) return
        lastCheck.at = t
        val info = runCatching { source.appInfo() }.getOrNull() ?: return
        val code = info.versionCode ?: return
        if (code > installedCode) {
            offered = info.versionName ?: code.toString()
            if (_state.value !is UpdateState.Downloading && _state.value !is UpdateState.Installing) _state.value = UpdateState.Available(offered!!)
        } else {
            offered = null
            _state.value = UpdateState.UpToDate
        }
    }

    suspend fun update(onState: (UpdateState) -> Unit = {}) {
        val name = offered ?: return
        fun set(s: UpdateState) { _state.value = s; onState(s) }
        if (!installer.canInstall()) {
            set(UpdateState.NeedsPermission(name))
            installer.requestPermission()
            return
        }
        val apk = File(dir.apply { mkdirs() }, "heating.apk")
        apk.delete()
        set(UpdateState.Downloading(name, 0f))
        // A dropped or stalled connection carries on from where the file got to, rather than failing the update.
        var tries = 0
        while (true) {
            try {
                source.downloadApk(apk, if (apk.exists()) apk.length() else 0L) { done, total ->
                    set(UpdateState.Downloading(name, total?.takeIf { it > 0 }?.let { done.toFloat() / it }))
                }
                break
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                if (++tries >= DOWNLOAD_TRIES) {
                    apk.delete()
                    set(UpdateState.Failed(name))
                    return
                }
                kotlinx.coroutines.delay(RETRY_AFTER_MS)
            }
        }
        try {
            set(UpdateState.Installing(name))
            installer.install(apk)
        } catch (e: Exception) {
            if (e is kotlinx.coroutines.CancellationException) throw e
            apk.delete()
            set(UpdateState.Failed(name))
        }
    }

    /** Back in the app, perhaps from the install setting: carry on if permission was the only thing missing. */
    suspend fun resumed() {
        if (_state.value is UpdateState.NeedsPermission && installer.canInstall()) update()
    }

    companion object {
        const val CHECK_EVERY_MS = 60 * 60_000L
        /** Tries at the download, each carrying on from the last, before saying "Update failed". */
        const val DOWNLOAD_TRIES = 5
        const val RETRY_AFTER_MS = 2_000L
    }
}
