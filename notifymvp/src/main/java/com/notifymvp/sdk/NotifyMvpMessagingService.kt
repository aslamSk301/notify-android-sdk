package com.notifymvp.sdk

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

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
     * Data + high-priority messages (including NotifyMVP rich / `notifymvp_rich=1`)
     * are delivered here in foreground and background. The SDK posts the system
     * notification (Big Picture, actions) so rich push works without a notification payload.
     */
    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)

        val notif = remoteMessage.notification
        val data  = remoteMessage.data

        val title = notif?.title ?: data["title"] ?: data["name"] ?: ""
        val body  = notif?.body  ?: data["body"]  ?: data["message"] ?: ""

        NotifyMVP.loggerInternal?.debug("FCM message: $title")

        // Deliver to app-level listener (set via NotifyMVP.setMessageListener)
        NotifyMVP.messageListenerInternal?.onMessage(title, body, data)

        // Show system heads-up notification (high-priority pop-up / rich Big Picture)
        if (title.isNotBlank()) {
            serviceScope.launch {
                showSystemHeadsUpNotification(title, body, data, notif?.imageUrl)
            }
        }
    }

    private suspend fun showSystemHeadsUpNotification(
        title: String,
        body: String,
        data: Map<String, String>,
        notificationImageUrl: String?,
    ) {
        try {
            val notificationManager =
                getSystemService(android.content.Context.NOTIFICATION_SERVICE) as android.app.NotificationManager

            val channelId = DEFAULT_CHANNEL_ID
            createNotificationChannel(this, channelId)

            val launchUrl = data["url"] ?: data["link"] ?: data["storyId"]
            val intent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                for ((k, v) in data) {
                    putExtra(k, v)
                }
                if (!launchUrl.isNullOrBlank()) {
                    putExtra("url", launchUrl)
                    putExtra("storyId", launchUrl)
                    if (launchUrl.startsWith("storycean://")) {
                        try {
                            setData(android.net.Uri.parse(launchUrl))
                        } catch (_: Exception) {}
                    }
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
            val iconRes = resolveSmallIconRes()

            val imageUrl = data["imageUrl"]
                ?: data["image"]
                ?: notificationImageUrl
            val iconUrl = data["iconUrl"] ?: data["icon"] ?: data["largeIcon"]

            val bigPicture = downloadBitmap(imageUrl)
            val largeIcon = downloadBitmap(iconUrl) ?: bigPicture

            val style = if (bigPicture != null) {
                androidx.core.app.NotificationCompat.BigPictureStyle()
                    .bigPicture(bigPicture)
                    .bigLargeIcon(null as Bitmap?)
                    .setSummaryText(body)
            } else {
                androidx.core.app.NotificationCompat.BigTextStyle().bigText(body)
            }

            val builder = androidx.core.app.NotificationCompat.Builder(this, channelId)
                .setSmallIcon(iconRes)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(style)
                .setAutoCancel(true)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_MAX)
                .setCategory(androidx.core.app.NotificationCompat.CATEGORY_MESSAGE)
                .setDefaults(androidx.core.app.NotificationCompat.DEFAULT_ALL)
                .setSound(soundUri)
                .setVibrate(longArrayOf(0, 250, 250, 250))
                .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)

            if (largeIcon != null) {
                builder.setLargeIcon(largeIcon)
            }
            if (pendingIntent != null) {
                builder.setContentIntent(pendingIntent)
            }

            for (action in RichPushHelper.parseActions(data)) {
                val actionIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or
                        android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP
                    putExtra("notifymvp_action_id", action.id)
                    for ((k, v) in data) putExtra(k, v)
                    if (!launchUrl.isNullOrBlank()) putExtra("url", launchUrl)
                }
                val actionPending = if (actionIntent != null) {
                    android.app.PendingIntent.getActivity(
                        this,
                        (notifId + action.id.hashCode()) and 0x7FFFFFFF,
                        actionIntent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE,
                    )
                } else null
                if (actionPending != null) {
                    builder.addAction(
                        androidx.core.app.NotificationCompat.Action.Builder(
                            0,
                            action.title,
                            actionPending,
                        ).build(),
                    )
                }
            }

            withContext(Dispatchers.Main) {
                notificationManager.notify(notifId, builder.build())
            }
        } catch (e: Exception) {
            NotifyMVP.loggerInternal?.error("Failed to post heads-up notification", e)
        }
    }

    private fun downloadBitmap(urlString: String?): Bitmap? {
        if (urlString.isNullOrBlank()) return null
        return try {
            val connection = (URL(urlString).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8_000
                readTimeout = 8_000
                instanceFollowRedirects = true
                doInput = true
            }
            connection.connect()
            if (connection.responseCode !in 200..299) {
                connection.disconnect()
                return null
            }
            connection.inputStream.use { stream ->
                BitmapFactory.decodeStream(stream)
            }.also { connection.disconnect() }
        } catch (e: Exception) {
            NotifyMVP.loggerInternal?.warn("Failed to download notification image: $urlString — ${e.message}")
            null
        }
    }

    private fun resolveSmallIconRes(): Int {
        return try {
            val appIcon = packageManager.getApplicationInfo(packageName, 0).icon
            if (appIcon != 0) appIcon else android.R.drawable.ic_popup_reminder
        } catch (_: Exception) {
            android.R.drawable.ic_popup_reminder
        }
    }

    companion object {
        const val DEFAULT_CHANNEL_ID = "notifymvp_heads_up_channel"

        /**
         * Ensures high-priority NotificationChannel exists on Android 8.0+ (API 26+).
         * Call eagerly on SDK initialization so background pushes are never dropped by OS.
         */
        fun createNotificationChannel(context: android.content.Context, channelId: String = DEFAULT_CHANNEL_ID) {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                try {
                    val notificationManager =
                        context.getSystemService(android.content.Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                            ?: return

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
                        lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
                    }
                    notificationManager.createNotificationChannel(channel)
                } catch (e: Exception) {
                    NotifyMVP.loggerInternal?.error("Failed to create notification channel", e)
                }
            }
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
