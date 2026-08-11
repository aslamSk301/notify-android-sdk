package com.notifymvp.sdk

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()

/**
 * OkHttp-based HTTP client for the NotifyMVP REST API.
 * Uses coroutines + exponential backoff retry.
 */
internal class NotifyHttpClient(
    private val config: NotifyConfig,
    private val logger: NotifyLogger,
) {
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(config.timeoutMs, TimeUnit.MILLISECONDS)
        .apply {
            if (config.debugLogging) {
                addInterceptor(
                    HttpLoggingInterceptor { msg -> logger.debug(msg) }
                        .apply { level = HttpLoggingInterceptor.Level.BODY }
                )
            }
        }
        .build()

    /**
     * POST /api/device/register
     * Throws [NotifyException] on failure after all retries exhausted.
     */
    @Throws(NotifyException::class)
    suspend fun registerDevice(
        appId: String,
        apiKey: String,
        fcmToken: String?,
        platform: String,
        deviceId: String,
        appVersion: String,
        permissionStatus: String = "unknown",
        optedIn: Boolean = true,
        externalUserId: String? = null,
    ) {
        val body = JSONObject().apply {
            put("appId", appId)
            put("apiKey", apiKey)
            put("platform", platform)
            put("deviceId", deviceId)
            put("appVersion", appVersion)
            put("permissionStatus", permissionStatus)
            put("optedIn", optedIn)
            if (!fcmToken.isNullOrBlank()) put("fcmToken", fcmToken)
            if (!externalUserId.isNullOrBlank()) put("externalUserId", externalUserId)
        }.toString()

        postWithRetry(config.registerEndpoint, body, "registerDevice")
    }

    /**
     * POST /api/topics/subscribe
     */
    @Throws(NotifyException::class)
    suspend fun subscribeToTopic(fcmToken: String, topic: String) {
        val body = JSONObject().apply {
            put("appId",    config.appId)
            put("apiKey",   config.apiKey)
            put("fcmToken", fcmToken)
            put("topic",    topic)
        }.toString()
        postWithRetry("${config.baseUrl.trimEnd('/')}/api/topics/subscribe", body, "subscribeToTopic")
    }

    /**
     * POST /api/topics/unsubscribe
     */
    @Throws(NotifyException::class)
    suspend fun unsubscribeFromTopic(fcmToken: String, topic: String) {
        val body = JSONObject().apply {
            put("appId",    config.appId)
            put("apiKey",   config.apiKey)
            put("fcmToken", fcmToken)
            put("topic",    topic)
        }.toString()
        postWithRetry("${config.baseUrl.trimEnd('/')}/api/topics/unsubscribe", body, "unsubscribeFromTopic")
    }

    /**
     * GET /api/topics?appId=xxx&apiKey=xxx
     * Fetch available topics from the backend.
     */
    @Throws(NotifyException::class)
    suspend fun fetchTopics(): List<NotifyTopic> = withContext(Dispatchers.IO) {
        val url = "${config.baseUrl.trimEnd('/')}/api/topics" +
                  "?appId=${config.appId}&apiKey=${config.apiKey}"

        val request = Request.Builder()
            .url(url)
            .get()
            .header("Accept", "application/json")
            .header("X-SDK-Platform", "android")
            .header("X-SDK-Version", "1.1.0")
            .build()

        val response = client.newCall(request).execute()
        val body     = response.body?.string() ?: "{}"
        val json     = runCatching { JSONObject(body) }.getOrDefault(JSONObject())

        if (response.code == 401) throw NotifyException("Invalid appId or apiKey", code = NotifyException.Code.AUTH, httpStatus = 401)
        if (!response.isSuccessful) throw NotifyException("fetchTopics HTTP ${response.code}", code = NotifyException.Code.NETWORK, httpStatus = response.code)

        val arr    = json.optJSONArray("topics") ?: return@withContext emptyList()
        val result = mutableListOf<NotifyTopic>()
        for (i in 0 until arr.length()) result.add(NotifyTopic.fromJson(arr.getJSONObject(i)))
        result
    }

    // ── Internal ──────────────────────────────────────────────────────────────

    @Throws(NotifyException::class)
    private suspend fun postWithRetry(
        url: String,
        jsonBody: String,
        operation: String,
    ): JSONObject {
        var attempt = 0
        var lastException: Exception? = null

        while (attempt < config.maxRetries) {
            attempt++
            logger.debug("$operation attempt $attempt → $url")

            try {
                return execute(url, jsonBody, operation)
            } catch (e: NotifyException) {
                lastException = e
                // Auth errors and 4xx (except 429) are not retryable
                if (e.code == NotifyException.Code.AUTH ||
                    (e.httpStatus != null && e.httpStatus in 400..499 && e.httpStatus != 429)
                ) throw e

                if (attempt < config.maxRetries) {
                    val backoffMs = 500L * attempt
                    logger.warn("$operation failed (attempt $attempt), retrying in ${backoffMs}ms: ${e.message}")
                    delay(backoffMs)
                }
            } catch (e: IOException) {
                lastException = NotifyException(
                    "Network error: ${e.message}",
                    code = NotifyException.Code.NETWORK,
                    cause = e,
                )
                if (attempt < config.maxRetries) {
                    delay(500L * attempt)
                }
            }
        }

        throw lastException ?: NotifyException(
            "$operation failed after $attempt attempts",
            code = NotifyException.Code.NETWORK,
        )
    }

    @Throws(NotifyException::class, IOException::class)
    private fun execute(url: String, jsonBody: String, operation: String): JSONObject {
        val request = Request.Builder()
            .url(url)
            .post(jsonBody.toRequestBody(JSON_MEDIA_TYPE))
            .header("Content-Type", "application/json")
            .header("Accept", "application/json")
            .header("X-SDK-Platform", "android")
            .header("X-SDK-Version", "1.0.0")
            .build()

        val response = client.newCall(request).execute()
        val responseBody = response.body?.string() ?: "{}"
        val json = runCatching { JSONObject(responseBody) }.getOrDefault(JSONObject())

        if (response.code == 401) {
            throw NotifyException(
                "Invalid appId or apiKey",
                code = NotifyException.Code.AUTH,
                httpStatus = 401,
            )
        }

        if (!response.isSuccessful) {
            val errorMsg = json.optString("error", "HTTP ${response.code} from $operation")
            throw NotifyException(
                errorMsg,
                code = NotifyException.Code.NETWORK,
                httpStatus = response.code,
            )
        }

        return json
    }
}

/**
 * SDK-specific exception with error classification.
 */
class NotifyException(
    message: String,
    val code: Code,
    val httpStatus: Int? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    enum class Code {
        AUTH,           // Invalid credentials
        NETWORK,        // HTTP or IO failure
        NOT_INITIALIZED, // SDK used before initialize()
        UNKNOWN,
    }
}
