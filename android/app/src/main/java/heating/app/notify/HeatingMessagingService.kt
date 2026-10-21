package heating.app.notify

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import heating.app.MainActivity
import heating.app.R
import heating.app.data.AccountApi
import heating.app.data.ServerSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Shows heating notifications while the app is open (Android shows them itself when it is not), and
 * hands a new Firebase token to the service when the signed-in phone gets one.
 */
class HeatingMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val config = ServerSettings(this).load() ?: return
        scope.launch { runCatching { AccountApi(config.baseUrl, config.token).registerDevice(token) } }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val n = message.notification ?: return
        createChannel(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(n.title)
            .setContentText(n.body)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        getSystemService(NotificationManager::class.java).notify(message.messageId.hashCode(), notification)
    }
}
