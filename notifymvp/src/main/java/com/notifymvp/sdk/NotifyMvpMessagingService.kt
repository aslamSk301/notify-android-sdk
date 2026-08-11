package com.notifymvp.sdk

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

        val title = remoteMessage.notification?.title ?: return
        val body  = remoteMessage.notification?.body  ?: ""
        val data  = remoteMessage.data

        NotifyMVP.loggerInternal?.debug("Foreground message: $title")

        // Deliver to app-level listener (set via NotifyMVP.setMessageListener)
        NotifyMVP.messageListenerInternal?.onMessage(title, body, data)
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.coroutineContext[SupervisorJob]?.cancel()
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
