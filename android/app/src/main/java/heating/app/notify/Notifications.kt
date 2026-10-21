package heating.app.notify

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.google.android.gms.tasks.Task
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import heating.app.data.AccountApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** The channel every heating notification uses; the service names it in each message. */
const val CHANNEL_ID = "heating"

private const val PREFS = "notifications"
private const val ASKED = "asked"

/** Ask for permission once, and only while the app may not notify yet. */
fun shouldAskToNotify(granted: Boolean, askedBefore: Boolean): Boolean = !granted && !askedBefore

fun canNotify(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

fun askedToNotify(context: Context): Boolean = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED, false)

fun markAskedToNotify(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED, true).apply()

fun createChannel(context: Context) {
    val channel = NotificationChannel(CHANNEL_ID, "Heating", NotificationManager.IMPORTANCE_DEFAULT).apply {
        description = "When someone turns the heating on, and when it has finished"
    }
    context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
}

/** This phone's Firebase token, or null when the app was built without Firebase config. */
suspend fun pushToken(context: Context): String? {
    if (FirebaseApp.getApps(context).isEmpty() && FirebaseApp.initializeApp(context) == null) return null
    return runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull()
}

/** Tell the service to send this person's notifications here. Failures are retried on the next start. */
suspend fun registerForPush(context: Context, account: AccountApi) {
    val token = pushToken(context) ?: return
    runCatching { account.registerDevice(token) }
}

/** Before signing out: stop this phone getting the person's notifications. */
suspend fun unregisterFromPush(context: Context, account: AccountApi) {
    val token = pushToken(context) ?: return
    runCatching { account.unregisterDevice(token) }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { cont ->
    addOnCompleteListener { task ->
        val error = task.exception
        if (error != null) cont.resumeWithException(error) else cont.resume(task.result)
    }
}
