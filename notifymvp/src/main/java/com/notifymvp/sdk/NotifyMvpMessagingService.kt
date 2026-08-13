package com.notifymvp.sdk

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Firebase Messaging Service for NotifyMVP.
 *
 * Register this in your app's AndroidManifest.xml:
 *
 * ```xml
 * <service
 *     android:name="com.notifymvp.sdk.NotifyMvpMessagingService"
 *     android:exported="false">
 *     <intent-filter>
 *         <action android:name="com.google.firebase.MESSAGING_EVENT" />
 *     </intent-filter>
 * </service>
 * ```
 *
 * Override [onNotifyMessageReceived] in your Application class via
 * [NotifyMVP.setMessageListener] to handle foreground messages.
 */
class NotifyMvpMessagingService : FirebaseMessagingService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * Called when FCM delivers a token for the first time,
     * or when the token is refreshed (app reinstall, data clear, etc.)
     */
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        val logger = NotifyMVP.loggerInternal

        logger?.info("FCM token refreshed — re-registering")

        // Re-register with NotifyMVP backend in the background
        serviceScope.launch {
            try {
                NotifyMVP.onTokenRefreshed(token)
            } catch (e: Exception) {
                logger?.error("Token refresh re-registration failed", e)
            }
        }
    }

    /**
     * Called when a data/notification message arrives while the app is
     * in the FOREGROUND.
     *
     * Background messages are handled automatically by FCM and shown
     * as system notifications — no code needed for those.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val notif = remoteMessage.notification
        val data  = remoteMessage.data

        val title = notif?.title ?: data["title"] ?: data["name"] ?: ""
        val body  = notif?.body  ?: data["body"]  ?: data["message"] ?: ""

        NotifyMVP.loggerInternal?.debug("Foreground message: $title")

        // Deliver to app-level listener (set via NotifyMVP.setMessageListener)
        NotifyMVP.messageListenerInternal?.onMessage(title, body, data)

        // Show system heads-up notification (high-priority pop-up)
        if (title.isNotBlank()) {
            showSystemHeadsUpNotification(title, body, data)
        }
    }

    private fun showSystemHeadsUpNotification(title: String, body: String, data: Map<String, String>) {
        try {
            val notificationManager =
                getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

            val channelId = "notifymvp_heads_up_v4"

            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                val soundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
                val audioAttributes = android.media.AudioAttributes.Builder()
                    .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .setUsage(android.media.AudioAttributes.USAGE_NOTIFICATION)
                    .build()

                val channel = android.app.NotificationChannel(
                    channelId,
                    "NotifyMVP Push Notifications",
                    android.app.NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "High priority push notifications"
                    enableLights(true)
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 250, 250, 250)
                    setSound(soundUri, audioAttributes)
                    setShowBadge(true)
                }
                notificationManager.createNotificationChannel(channel)
            }

            val launchUrl = data["url"] ?: data["link"] ?: data["storyId"]
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                if (!launchUrl.isNullOrBlank()) {
                    putExtra("url", launchUrl)
                    putExtra("storyId", launchUrl)
                }
            }

            val notifId = (System.currentTimeMillis() and 0x7FFFFFFF).toInt()
            val pendingIntent = if (intent != null) {
                android.app.PendingIntent.getActivity(
                    this,
                    notifId,
                    intent,
                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                )
            } else null

            val soundUri = android.media.RingtoneManager.getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            val notif = androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(title)
                .setContentText(body)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                .setSound(soundUri)
                .setVibrate(longArrayOf(0, 250, 250, 250))
                .apply { if (pendingIntent != null) setContentIntent(pendingIntent) }
                .build()

            notificationManager.notify(notifId, notif)
        } catch (e: Exception) {
            NotifyMVP.loggerInternal?.error("Failed to post heads-up notification", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }
}

/**
 * Interface for receiving foreground push notifications.
 */
interface NotifyMessageListener {
    fun onMessage(title: String, body: String, data: Map<String, String>)
}

/**
 * Called when the user taps a notification (app opened from tray / cold start).
 * [url] is the launch URL / deep link from the dashboard (`data.url`), if any.
 */
fun interface NotifyNotificationOpenedListener {
    fun onOpened(title: String, body: String, url: String?, data: Map<String, String>)
}
