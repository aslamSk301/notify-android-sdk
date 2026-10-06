package com.notifymvp.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.tasks.await
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * NotifyMVP Android SDK — main entry point.
 *
 * ## Notification tap → route (OneSignal-style launch URL)
 * Dashboard "Launch URL" is delivered as `data["url"]`.
 * Call [handleIntent] from MainActivity.onCreate / onNewIntent:
 * ```kotlin
 * NotifyMVP.setNotificationOpenedListener { title, body, url, data ->
 *     // navigate using url: https://…, myapp://…, or /orders/123
 * }
 * override fun onCreate(savedInstanceState: Bundle?) {
 *     super.onCreate(savedInstanceState)
 *     NotifyMVP.handleIntent(intent)
 * }
 * override fun onNewIntent(intent: Intent) {
 *     super.onNewIntent(intent)
 *     setIntent(intent)
 *     NotifyMVP.handleIntent(intent)
 * }
 * ```
 */
object NotifyMVP {

    // ── State ─────────────────────────────────────────────────────────────────
    @Volatile private var config: NotifyConfig? = null
    @Volatile private var httpClient: NotifyHttpClient? = null
    @Volatile private var deviceInfo: DeviceInfoService? = null
    @Volatile private var logger: NotifyLogger? = null
    @Volatile private var appContext: Context? = null

    @Volatile private var _fcmToken: String? = null
    @Volatile private var _initialized = false
    @Volatile private var _optedIn = true
    @Volatile private var _permissionStatus = "unknown"
    @Volatile private var _externalUserId: String? = null

    // Internal — accessed by NotifyMvpMessagingService
    internal val loggerInternal: NotifyLogger? get() = logger
    internal var messageListenerInternal: NotifyMessageListener? = null
    internal var openedListenerInternal: NotifyNotificationOpenedListener? = null

    // ── Public API ────────────────────────────────────────────────────────────

    /** Current FCM token. Null until [initialize] completes. */
    val fcmToken: String? get() = _fcmToken

    /** True after [initialize] completes successfully. */
    val isInitialized: Boolean get() = _initialized

    /** Active SDK configuration. */
    val activeConfig: NotifyConfig? get() = config

    val isOptedIn: Boolean get() = _optedIn
    val permissionStatus: String get() = _permissionStatus
    val externalUserId: String? get() = _externalUserId
    val subscriptionStatus: String
        get() {
            val tokenOk = !_fcmToken.isNullOrBlank()
            val permOk = _permissionStatus == "granted" || _permissionStatus == "provisional"
            return if (tokenOk && permOk && _optedIn) "subscribed" else "unsubscribed"
        }

    /**
     * Link an external user ID (e.g. Firebase Auth UID, User Database ID) to this device subscription.
     * Allows sending targeted notifications to this user via `user:{userId}` target in the dashboard/API.
     */
    suspend fun setExternalUserId(externalUserId: String?): NotifyResult {
        _externalUserId = externalUserId
        if (!_initialized) {
            logger?.warn("NotifyMVP is not initialized yet. External User ID stored ($externalUserId) and will be registered upon initialize.")
            return NotifyResult.Failure("NotifyMVP is not initialized.")
        }
        logger?.info("External User ID set: $externalUserId")
        return registerDevice()
    }

    /**
     * Initialize the SDK and register this device.
     * Call from [android.app.Application.onCreate] — suspend-friendly.
     */
    suspend fun initialize(
        context: Context,
        config: NotifyConfig,
        autoRegister: Boolean = true,
    ): NotifyResult {
        val appCtx = context.applicationContext

        this.appContext = appCtx
        this.config     = config
        this.logger     = NotifyLogger(config.debugLogging)
        this.httpClient = NotifyHttpClient(config, logger!!)
        this.deviceInfo = DeviceInfoService(appCtx)
        this._initialized = true

        // Register high-priority notification channel immediately (critical for Android 8.0+ / API 26+)
        NotifyMvpMessagingService.createNotificationChannel(appCtx)

        logger!!.info("NotifyMVP SDK v1.1.6 initialized. appId=${config.appId}")

        return if (autoRegister) registerDevice() else NotifyResult.Success()
    }

    /** Manually register / re-register this device. */
    suspend fun register(): NotifyResult {
        if (!_initialized) return NotifyResult.Failure("NotifyMVP is not initialized.")
        return registerDevice()
    }

    /** OneSignal-style: user wants pushes again. */
    suspend fun optIn(): NotifyResult {
        if (!_initialized) return NotifyResult.Failure("NotifyMVP is not initialized.")
        _optedIn = true
        return registerDevice()
    }

    /** OneSignal-style: stop receiving pushes without deleting the device record. */
    suspend fun optOut(): NotifyResult {
        if (!_initialized) return NotifyResult.Failure("NotifyMVP is not initialized.")
        _optedIn = false
        return registerWithBackendResult()
    }

    /** Re-read OS permission + re-register (call on app resume). */
    suspend fun syncSubscription(): NotifyResult {
        if (!_initialized) return NotifyResult.Failure("NotifyMVP is not initialized.")
        return registerDevice()
    }

    /** Foreground push notification listener. */
    fun setMessageListener(listener: NotifyMessageListener?) {
        messageListenerInternal = listener
    }

    /**
     * Notification tap listener (background / terminated → open).
     * Pair with [handleIntent] in your launcher Activity.
     */
    fun setNotificationOpenedListener(listener: NotifyNotificationOpenedListener?) {
        openedListenerInternal = listener
    }

    /**
     * Process an Activity Intent after a notification tap.
     * FCM puts `data` extras (including `url`) on the Intent.
     *
     * @param openHttpInBrowser If true, http(s) launch URLs open in the browser.
     *                          Relative paths / custom schemes are only passed to the listener.
     * @return true if a notification-related payload was found
     */
    fun handleIntent(intent: Intent?, openHttpInBrowser: Boolean = true): Boolean {
        if (intent == null) return false
        val extras = intent.extras
        val dataUri = intent.dataString

        val data = mutableMapOf<String, String>()
        if (extras != null) {
            for (key in extras.keySet()) {
                val value = extras.get(key)?.toString() ?: continue
                if (key.startsWith("google.") || key == "from" || key == "collapse_key") continue
                data[key] = value
            }
        }

        val url = data["url"] ?: data["link"] ?: data["storyId"] ?: dataUri?.takeIf { it.isNotBlank() }
        val title = data["gcm.notification.title"] ?: data["title"] ?: ""
        val body  = data["gcm.notification.body"]  ?: data["body"]  ?: ""

        val looksLikeNotif = !url.isNullOrBlank() ||
            (extras != null && (extras.containsKey("google.message_id") || extras.containsKey("google.sent_time")))

        if (!looksLikeNotif) return false

        logger?.debug("Notification opened — url=$url title=$title")
        openedListenerInternal?.onOpened(title, body, url, data)

        val isAppDeepLink = !url.isNullOrBlank() && (
            (url.contains("://") && !url.startsWith("http://") && !url.startsWith("https://")) ||
            url.contains("storycean.com") ||
            data.containsKey("storyId")
        )

        if (openHttpInBrowser && !url.isNullOrBlank() && !isAppDeepLink &&
            (url.startsWith("http://") || url.startsWith("https://"))
        ) {
            try {
                val ctx = appContext
                if (ctx != null) {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                    )
                }
            } catch (e: Exception) {
                logger?.error("Failed to open launch URL", e)
            }
        }

        return true
    }

    suspend fun subscribeToTopic(topic: String): NotifyResult {
        if (!checkInitialized()) return NotifyResult.Failure("NotifyMVP is not initialized.")
        val name = topic.trim()
        if (name.isEmpty()) return NotifyResult.Failure("Topic name is empty")
        return try {
            FirebaseMessaging.getInstance().subscribeToTopic(name).await()
            logger?.info("Subscribed to Firebase topic: $name")
            val token = _fcmToken
            if (!token.isNullOrBlank()) {
                try {
                    httpClient!!.subscribeToTopic(token, name)
                } catch (e: Exception) {
                    logger?.warn("Backend topic sync failed (non-fatal): ${e.message}")
                }
            }
            NotifyResult.Success()
        } catch (e: Exception) {
            logger?.error("Subscribe failed: ${e.message}")
            NotifyResult.Failure(e.message ?: "Subscribe failed")
        }
    }

    suspend fun unsubscribeFromTopic(topic: String): NotifyResult {
        if (!checkInitialized()) return NotifyResult.Failure("NotifyMVP is not initialized.")
        val name = topic.trim()
        if (name.isEmpty()) return NotifyResult.Failure("Topic name is empty")
        return try {
            FirebaseMessaging.getInstance().unsubscribeFromTopic(name).await()
            logger?.info("Unsubscribed from Firebase topic: $name")
            val token = _fcmToken
            if (!token.isNullOrBlank()) {
                try {
                    httpClient!!.unsubscribeFromTopic(token, name)
                } catch (e: Exception) {
                    logger?.warn("Backend unsub sync failed (non-fatal): ${e.message}")
                }
            }
            NotifyResult.Success()
        } catch (e: Exception) {
            logger?.error("Unsubscribe failed: ${e.message}")
            NotifyResult.Failure(e.message ?: "Unsubscribe failed")
        }
    }

    suspend fun fetchTopics(): List<NotifyTopic> {
        if (!_initialized) return emptyList()
        return try {
            httpClient!!.fetchTopics()
        } catch (e: Exception) {
            logger?.error("fetchTopics failed", e)
            emptyList()
        }
    }

    fun reset() {
        config       = null
        httpClient   = null
        deviceInfo   = null
        logger       = null
        appContext   = null
        _fcmToken    = null
        _initialized = false
        _optedIn     = true
        _permissionStatus = "unknown"
        messageListenerInternal = null
        openedListenerInternal  = null
    }

    // ── Internal — called by NotifyMvpMessagingService ────────────────────────

    internal suspend fun onTokenRefreshed(newToken: String) {
        if (!_initialized) return
        _fcmToken = newToken
        val topics = registerWithBackend(newToken)
        if (_optedIn) {
            autoSubscribeSystemTopics(topics)
        }
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private fun readPermissionStatus(): String {
        val ctx = appContext ?: return "unknown"
        return try {
            val enabled = androidx.core.app.NotificationManagerCompat
                .from(ctx)
                .areNotificationsEnabled()
            if (enabled) "granted" else "denied"
        } catch (_: Exception) {
            "unknown"
        }
    }

    private suspend fun registerDevice(): NotifyResult {
        return try {
            _permissionStatus = readPermissionStatus()
            var token: String? = null
            try {
                token = getFcmToken()
                if (!token.isNullOrBlank()) {
                    _fcmToken = token
                    logger?.debug("FCM token: …${token.takeLast(8)}")
                }
            } catch (e: Exception) {
                logger?.warn("FCM token unavailable: ${e.message}")
            }

            val topics = registerWithBackend(_fcmToken ?: token)
            // FCM topic membership does not depend on the notification permission
            // dialog. Android 13 asks for that permission after Application.onCreate,
            // so waiting for "granted" left the device off every topic.
            if (_optedIn && !_fcmToken.isNullOrBlank()) {
                autoSubscribeSystemTopics(topics)
            }

            val devId = deviceInfo!!.getDeviceId()
            val ver   = deviceInfo!!.getAppVersion()
            val plat  = deviceInfo!!.getPlatform()

            logger?.info("Device registered ✓  deviceId=$devId status=$subscriptionStatus permission=$_permissionStatus")
            NotifyResult.Success(deviceId = devId, platform = plat, appVersion = ver)
        } catch (e: NotifyException) {
            logger?.error("Registration failed: ${e.message}")
            NotifyResult.Failure(e.message ?: "Unknown error")
        } catch (e: Exception) {
            logger?.error("Unexpected registration error", e)
            NotifyResult.Failure(e.message ?: "Unexpected error")
        }
    }

    private suspend fun registerWithBackendResult(): NotifyResult {
        return try {
            _permissionStatus = readPermissionStatus()
            registerWithBackend(_fcmToken)
            NotifyResult.Success(
                deviceId = deviceInfo!!.getDeviceId(),
                platform = deviceInfo!!.getPlatform(),
                appVersion = deviceInfo!!.getAppVersion(),
            )
        } catch (e: Exception) {
            NotifyResult.Failure(e.message ?: "Unexpected error")
        }
    }

    /**
     * Client-side FCM subscribe for system topics returned by register.
     * Server IID subscribe can fail; this is what actually puts the device on the topic.
     */
    private suspend fun autoSubscribeSystemTopics(fromServer: List<String>) {
        val appId = config?.appId?.trim().orEmpty()
        val topics = when {
            fromServer.isNotEmpty() -> fromServer
            appId.isNotEmpty() -> listOf("all_$appId")
            else -> return
        }
        for (topic in topics) {
            try {
                FirebaseMessaging.getInstance().subscribeToTopic(topic).await()
                logger?.debug("Auto-subscribed to system topic: $topic")
            } catch (e: Exception) {
                logger?.warn("Auto-subscribe to $topic failed: ${e.message}")
            }
        }
    }

    private suspend fun registerWithBackend(token: String?): List<String> {
        val client = httpClient ?: return emptyList()
        val info   = deviceInfo ?: return emptyList()
        val cfg    = config    ?: return emptyList()

        return client.registerDevice(
            appId = cfg.appId,
            apiKey = cfg.apiKey,
            fcmToken = token,
            platform = info.getPlatform(),
            deviceId = info.getDeviceId(),
            appVersion = info.getAppVersion(),
            deviceModel = info.getDeviceModel(),
            deviceOs = info.getOsVersion(),
            language = info.getLanguage(),
            timezone = info.getTimezone(),
            country = info.getCountry().ifBlank { null },
            sdkVersion = "1.1.6",
            permissionStatus = _permissionStatus,
            optedIn = _optedIn,
            externalUserId = _externalUserId,
        )
    }

    private suspend fun getFcmToken(): String =
        suspendCancellableCoroutine { cont ->
            FirebaseMessaging.getInstance().token
                .addOnSuccessListener { token ->
                    if (token.isNullOrBlank()) {
                        cont.resumeWithException(
                            NotifyException(
                                "FCM token is null or empty",
                                code = NotifyException.Code.UNKNOWN,
                            )
                        )
                    } else {
                        cont.resume(token)
                    }
                }
                .addOnFailureListener { e ->
                    cont.resumeWithException(
                        NotifyException(
                            "Failed to get FCM token: ${e.message}",
                            code = NotifyException.Code.UNKNOWN,
                            cause = e,
                        )
                    )
                }
        }

    private fun checkInitialized(): Boolean {
        if (!_initialized) {
            logger?.warn("NotifyMVP is not initialized yet.")
            return false
        }
        return true
    }
}
