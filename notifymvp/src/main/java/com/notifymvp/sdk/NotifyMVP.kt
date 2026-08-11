package com.notifymvp.sdk

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.suspendCancellableCoroutine
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
    val subscriptionStatus: String
        get() {
            val tokenOk = !_fcmToken.isNullOrBlank()
            val permOk = _permissionStatus == "granted" || _permissionStatus == "provisional"
            return if (tokenOk && permOk && _optedIn) "subscribed" else "unsubscribed"
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

        logger!!.info("NotifyMVP SDK v1.1.0 initialized. appId=${config.appId}")

        return if (autoRegister) registerDevice() else NotifyResult.Success()
    }

    /** Manually register / re-register this device. */
    suspend fun register(): NotifyResult {
        checkInitialized()
        return registerDevice()
    }

    /** OneSignal-style: user wants pushes again. */
    suspend fun optIn(): NotifyResult {
        checkInitialized()
        _optedIn = true
        return registerDevice()
    }

    /** OneSignal-style: stop receiving pushes without deleting the device record. */
    suspend fun optOut(): NotifyResult {
        checkInitialized()
        _optedIn = false
        return registerWithBackendResult()
    }

    /** Re-read OS permission + re-register (call on app resume). */
    suspend fun syncSubscription(): NotifyResult {
        checkInitialized()
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
        val extras = intent.extras ?: return false

        val data = mutableMapOf<String, String>()
        for (key in extras.keySet()) {
            val value = extras.get(key)?.toString() ?: continue
            if (key.startsWith("google.") || key == "from" || key == "collapse_key") continue
            data[key] = value
        }

        val url = data["url"]?.takeIf { it.isNotBlank() }
        val title = data["gcm.notification.title"] ?: data["title"] ?: ""
        val body  = data["gcm.notification.body"]  ?: data["body"]  ?: ""

        val looksLikeNotif = url != null ||
            extras.containsKey("google.message_id") ||
            extras.containsKey("google.sent_time")

        if (!looksLikeNotif) return false

        logger?.debug("Notification opened — url=$url title=$title")
        openedListenerInternal?.onOpened(title, body, url, data)

        if (openHttpInBrowser && !url.isNullOrBlank() &&
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
        checkInitialized()
        val token = _fcmToken ?: return NotifyResult.Failure("Not registered yet")
        return try {
            httpClient!!.subscribeToTopic(token, topic)
            logger?.info("Subscribed to topic: $topic")
            NotifyResult.Success()
        } catch (e: NotifyException) {
            logger?.error("Subscribe failed: ${e.message}")
            NotifyResult.Failure(e.message ?: "Subscribe failed")
        } catch (e: Exception) {
            logger?.error("Subscribe error", e)
            NotifyResult.Failure(e.message ?: "Unknown error")
        }
    }

    suspend fun unsubscribeFromTopic(topic: String): NotifyResult {
        checkInitialized()
        val token = _fcmToken ?: return NotifyResult.Failure("Not registered yet")
        return try {
            httpClient!!.unsubscribeFromTopic(token, topic)
            logger?.info("Unsubscribed from topic: $topic")
            NotifyResult.Success()
        } catch (e: NotifyException) {
            logger?.error("Unsubscribe failed: ${e.message}")
            NotifyResult.Failure(e.message ?: "Unsubscribe failed")
        } catch (e: Exception) {
            logger?.error("Unsubscribe error", e)
            NotifyResult.Failure(e.message ?: "Unknown error")
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
        registerWithBackend(newToken)
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
            if (_permissionStatus == "granted" || _permissionStatus == "provisional") {
                try {
                    token = getFcmToken()
                    _fcmToken = token
                    logger?.debug("FCM token: …${token.takeLast(8)}")
                } catch (e: Exception) {
                    logger?.warn("FCM token unavailable: ${e.message}")
                }
            }

            registerWithBackend(token)

            val devId = deviceInfo!!.getDeviceId()
            val ver   = deviceInfo!!.getAppVersion()
            val plat  = deviceInfo!!.getPlatform()

            logger?.info("Device registered ✓  deviceId=$devId status=$subscriptionStatus")
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

    private suspend fun registerWithBackend(token: String?) {
        val client = httpClient ?: return
        val info   = deviceInfo ?: return
        val cfg    = config    ?: return

        client.registerDevice(
            appId = cfg.appId,
            apiKey = cfg.apiKey,
            fcmToken = token,
            platform = info.getPlatform(),
            deviceId = info.getDeviceId(),
            appVersion = info.getAppVersion(),
            permissionStatus = _permissionStatus,
            optedIn = _optedIn,
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

    private fun checkInitialized() {
        if (!_initialized) throw NotifyException(
            "NotifyMVP is not initialized. Call NotifyMVP.initialize() first.",
            code = NotifyException.Code.NOT_INITIALIZED,
        )
    }
}
