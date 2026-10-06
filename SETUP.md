# NotifyMVP Android setup

Use this guide in an Android/Kotlin app. You need a NotifyMVP project first: copy its **App ID** and **API key** from the NotifyMVP dashboard, and use the public URL of your NotifyMVP Worker as `baseUrl`.

## 1. Add the JitPack dependency

In `settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
    }
}
```

In `app/build.gradle.kts`:

```kotlin
dependencies {
    implementation("com.github.aslamSk301:notify-android-sdk:1.1.6")
    implementation(platform("com.google.firebase:firebase-bom:33.1.0"))
    implementation("com.google.firebase:firebase-messaging-ktx")
}
```

Download `google-services.json` from your Firebase project into `app/`, and enable the Google Services Gradle plugin in the host app.

## 2. Initialize once when the app starts

```kotlin
class MyApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            NotifyMVP.initialize(
                context = this@MyApp,
                config = NotifyConfig(
                    appId = "app_xxxxxxxx",
                    apiKey = "your_api_key",
                    baseUrl = "https://notify.yourdomain.com",
                    debugLogging = BuildConfig.DEBUG,
                ),
            )
        }
    }
}
```

The SDK obtains the FCM token, registers the device, refreshes it after token changes, and maintains system topics automatically. Do **not** call `NotifyMVP.register()` on every screen; use it only after a login/user change or a deliberate retry.

## 3. Confirm delivery

Run the app once, then open NotifyMVP dashboard → **Devices**. The device should be `Subscribed` and have its system topics. Send a test notification from the dashboard.

For foreground-message, rich-push, deep-link, and manual re-register examples, see [README.md](README.md).
