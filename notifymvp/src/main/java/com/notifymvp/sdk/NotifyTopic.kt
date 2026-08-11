package com.notifymvp.sdk

import org.json.JSONObject

/**
 * A push notification topic available for subscription.
 * Fetched from the backend via [NotifyMVP.fetchTopics].
 */
data class NotifyTopic(
    /** Topic name, e.g. "cricket", "breaking_news" */
    val name: String,
    /** Human-readable description shown to users */
    val description: String?,
) {
    companion object {
        fun fromJson(json: JSONObject) = NotifyTopic(
            name        = json.getString("name"),
            description = if (json.has("description") && !json.isNull("description"))
                              json.getString("description") else null,
        )
    }
}
