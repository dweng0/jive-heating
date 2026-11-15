package heating.app.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import java.io.File

/** Hands a downloaded APK to Android's package installer. */
class AndroidInstaller(private val context: Context) : Installer {
    override fun canInstall(): Boolean = context.packageManager.canRequestPackageInstalls()

    override fun requestPermission() {
        context.startActivity(
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun install(apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.updates", apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** The last update check's time, kept across restarts. */
class PrefsLastCheck(context: Context) : LastCheck {
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    override var at: Long
        get() = prefs.getLong("lastCheck", 0)
        set(value) { prefs.edit().putLong("lastCheck", value).apply() }
}
