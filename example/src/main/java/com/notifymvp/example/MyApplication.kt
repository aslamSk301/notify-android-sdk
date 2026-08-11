package com.notifymvp.example

import android.app.Application
import android.util.Log
import com.notifymvp.sdk.NotifyConfig
import com.notifymvp.sdk.NotifyMVP
import com.notifymvp.sdk.NotifyMessageListener
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MyApplication : Application() {

    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onCreate() {
        super.onCreate()

        // ── 1. Initialize NotifyMVP SDK ──────────────────────────────────────
        appScope.launch {
            val result = NotifyMVP.initialize(
                context = this@MyApplication,
                config  = NotifyConfig(
                    appId        = "app_e1eba64d4fdbd13c",      // ← your App ID
                    apiKey       = "60e84f652dcb2ad016edba27fdd91af26005750d8d9ddad30134a5fa9e6ef702", // ← your API Key
                    baseUrl      = "https://notyfy.vercel.app",
                    debugLogging = BuildConfig.DEBUG,
                ),
            )

            if (result.isSuccess) {
                Log.i("NotifyMVP", "Device registered ✓ token=${NotifyMVP.fcmToken?.takeLast(8)}")
            } else {
                Log.e("NotifyMVP", "Registration failed: ${(result as? com.notifymvp.sdk.NotifyResult.Failure)?.error}")
            }
        }

        // ── 2. Foreground message listener ───────────────────────────────────
        NotifyMVP.setMessageListener(object : NotifyMessageListener {
            override fun onMessage(
                title: String,
                body: String,
                data: Map<String, String>,
            ) {
                Log.d("NotifyMVP", "Foreground message: $title — $body")
                // Deliver to the active Activity via a shared ViewModel or EventBus
                NotifyEventBus.emit(title, body)
            }
        })

        // ── 3. Notification tap → route (Launch URL / deep link) ─────────────
        NotifyMVP.setNotificationOpenedListener { title, body, url, data ->
            Log.d("NotifyMVP", "Opened: $title url=$url data=$data")
            NotifyEventBus.emit(title, if (url != null) "$body → $url" else body)
        }
    }
}
