package com.notifymvp.sdk

/**
 * Configuration for the NotifyMVP SDK.
 *
 * @param appId        Your project App ID — from NotifyMVP dashboard → Projects
 * @param apiKey       Your project API Key — from NotifyMVP dashboard → Projects
 * @param baseUrl      Base URL of your NotifyMVP deployment (no trailing slash)
 * @param debugLogging Enable verbose console logs (disable in production)
 * @param timeoutMs    HTTP request timeout in milliseconds
 * @param maxRetries   Number of retry attempts on transient failure
 */
data class NotifyConfig(
    val appId: String,
    val apiKey: String,
    val baseUrl: String,
    val debugLogging: Boolean = false,
    val timeoutMs: Long = 15_000L,
    val maxRetries: Int = 3,
) {
    /** Full device registration endpoint URL */
    val registerEndpoint: String
        get() = "${baseUrl.trimEnd('/')}/api/device/register"

    init {
        require(appId.isNotBlank())  { "appId must not be blank" }
        require(apiKey.isNotBlank()) { "apiKey must not be blank" }
        require(baseUrl.isNotBlank()){ "baseUrl must not be blank" }
    }
}
