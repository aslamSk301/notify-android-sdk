# NotifyMVP Android SDK (Jetpack / Kotlin)

Official Android SDK for NotifyMVP. Uses Kotlin Coroutines + OkHttp.

---

## Installation

### Option A — JitPack (Recommended)

**Step 1:** Add JitPack to your root `settings.gradle.kts`
```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }  // ← add this
    }
}
```

**Step 2:** Add the SDK dependency in `app/build.gradle.kts`
```kotlin
dependencies {
    implementation("com.github.aslamSk301:notify-android-sdk:1.1.0")
    // Firebase (required)
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
}
```

---

### Option B — Local Module

```kotlin
// settings.gradle.kts
include(":notifymvp")
project(":notifymvp").projectDir = File("../notify_android_sdk/notifymvp")

// app/build.gradle.kts
dependencies {
    implementation(project(":notifymvp"))
}
```

```xml
<service
    android:name="com.notifymvp.sdk.NotifyMvpMessagingService"
    android:exported="false">
    <intent-filter>
        <action android:name="com.google.firebase.MESSAGING_EVENT" />
    </intent-filter>
</service>
```

---

## Usage

### `Application.onCreate()` — initialize once
```kotlin
class MyApp : Application() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        scope.launch {
            val result = NotifyMVP.initialize(
                context = this@MyApp,
                config  = NotifyConfig(
                    appId        = "app_xxxxxxxx",           // Dashboard → App ID
                    apiKey       = "your_api_key",           // Dashboard → API Key
                    baseUrl      = "https://notyfy.vercel.app",
                    debugLogging = BuildConfig.DEBUG,
                )
            )
            Log.d("NotifyMVP", if (result.isSuccess) "Registered ✓" else "Failed: $result")
        }

        // Listen for foreground messages
        NotifyMVP.setMessageListener(object : NotifyMessageListener {
            override fun onMessage(title: String, body: String, data: Map<String, String>) {
                // Show in-app notification or update UI
            }
        })
    }
}
```

### Manual re-register (e.g. after user login)
```kotlin
lifecycleScope.launch {
    val result = NotifyMVP.register()
    if (result.isSuccess) {
        Log.d("NotifyMVP", "Re-registered! token=${NotifyMVP.fcmToken}")
    }
}
```

---

## How it works

```
App starts
    ↓
NotifyMVP.initialize()
    ↓
FirebaseMessaging.getToken()     ← gets FCM token from Google
    ↓
POST /api/device/register        ← registers token with NotifyMVP backend
    ↓
Device saved in DB ✓

Token refreshes?
    ↓
NotifyMvpMessagingService.onNewToken()   ← Firebase calls this automatically
    ↓
NotifyMVP.onTokenRefreshed()             ← re-registers with backend
    ↓
DB updated ✓

Notification arrives (foreground)?
    ↓
NotifyMvpMessagingService.onMessageReceived()
    ↓
NotifyMessageListener.onMessage()        ← delivered to your app
```

---

## API Reference

| Method | Description |
|--------|-------------|
| `NotifyMVP.initialize(context, config)` | Init SDK + register device |
| `NotifyMVP.register()` | Manually re-register |
| `NotifyMVP.setMessageListener(listener)` | Receive foreground messages |
| `NotifyMVP.setNotificationOpenedListener(listener)` | Tap → route (`url` from dashboard) |
| `NotifyMVP.handleIntent(intent)` | Call from Activity onCreate / onNewIntent |
| `NotifyMVP.fcmToken` | Current FCM token |
| `NotifyMVP.isInitialized` | SDK ready? |
| `NotifyMVP.activeConfig` | Current config |

### Deep link / Launch URL (OneSignal-style)

Dashboard **Launch URL** is sent as FCM `data.url`. Wire it in your launcher Activity:

```kotlin
NotifyMVP.setNotificationOpenedListener { title, body, url, data ->
    // navigate with url: https://…, myapp://…, or /orders/123
}

override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    NotifyMVP.handleIntent(intent) // http(s) opens in browser by default
}

override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    NotifyMVP.handleIntent(intent)
}
```
