package com.notifymvp.sdk

import android.content.Context
import android.os.Build
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

private const val PREFS_FILE = "notifymvp_prefs"
private const val KEY_DEVICE_ID = "device_id"

/**
 * Provides stable, encrypted device metadata for registration.
 */
internal class DeviceInfoService(context: Context) {

    // Use application context to avoid Activity leaks
    private val appContext = context.applicationContext

    /**
     * Returns a stable UUID for this device.
     * Stored in EncryptedSharedPreferences — persists across app restarts.
     * A fresh UUID is generated on first install (or after app data clear).
     */
    fun getDeviceId(): String {
        val prefs = buildEncryptedPrefs()
        val existing = prefs.getString(KEY_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) return existing

        val newId = UUID.randomUUID().toString()
        prefs.edit().putString(KEY_DEVICE_ID, newId).apply()
        return newId
    }

    fun getAppVersion(): String = try {
        appContext.packageManager
            .getPackageInfo(appContext.packageName, 0)
            .versionName ?: "0.0.0"
    } catch (_: Exception) { "0.0.0" }

    fun getPlatform(): String = "android"

    fun getDeviceModel(): String =
        "${Build.MANUFACTURER} ${Build.MODEL}"

    fun getOsVersion(): String =
        "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

    fun getLanguage(): String = try {
        java.util.Locale.getDefault().language
    } catch (_: Exception) { "en" }

    fun getTimezone(): String = try {
        java.util.TimeZone.getDefault().id
    } catch (_: Exception) { "UTC" }

    // ── Internal ──────────────────────────────────────────────────────────────

    private fun buildEncryptedPrefs(): android.content.SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            EncryptedSharedPreferences.create(
                appContext,
                PREFS_FILE,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        } catch (_: Exception) {
            appContext.getSharedPreferences(PREFS_FILE, Context.MODE_PRIVATE)
        }
    }
}
