package com.notifymvp.sdk

/**
 * Sealed result type returned by all public SDK operations.
 */
sealed class NotifyResult {

    /** Operation succeeded. */
    data class Success(
        val deviceId: String = "",
        val platform: String = "",
        val appVersion: String = "",
    ) : NotifyResult()

    /** Operation failed with an error message. */
    data class Failure(val error: String) : NotifyResult()

    val isSuccess: Boolean get() = this is Success
}
