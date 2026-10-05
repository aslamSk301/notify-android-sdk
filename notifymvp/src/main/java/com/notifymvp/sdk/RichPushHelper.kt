package com.notifymvp.sdk

import org.json.JSONArray

internal object RichPushHelper {

    fun isRichData(data: Map<String, String>): Boolean {
        if (data["notifymvp_rich"] == "1") return true
        return !data["imageUrl"].isNullOrBlank() ||
            !data["image"].isNullOrBlank() ||
            !data["iconUrl"].isNullOrBlank() ||
            hasActions(data)
    }

    fun hasActions(data: Map<String, String>): Boolean {
        if (!data["actions"].isNullOrBlank()) return true
        return (1..3).any { n ->
            !data["action${n}_title"].isNullOrBlank() || !data["action${n}_label"].isNullOrBlank()
        }
    }

    data class ParsedAction(val id: String, val title: String)

    fun parseActions(data: Map<String, String>, max: Int = 3): List<ParsedAction> {
        val out = mutableListOf<ParsedAction>()
        val raw = data["actions"]
        if (!raw.isNullOrBlank()) {
            try {
                val arr = JSONArray(raw)
                for (i in 0 until arr.length()) {
                    if (out.size >= max) break
                    val obj = arr.optJSONObject(i) ?: continue
                    val title = obj.optString("title")
                        .ifBlank { obj.optString("label") }
                        .ifBlank { obj.optString("name") }
                    if (title.isBlank()) continue
                    val id = obj.optString("id")
                        .ifBlank { obj.optString("action") }
                        .ifBlank { title }
                        .ifBlank { "action_$i" }
                    out.add(ParsedAction(id, title))
                }
            } catch (_: Exception) {
                /* ignore malformed JSON */
            }
        }
        for (n in 1..max) {
            if (out.size >= max) break
            val title = data["action${n}_title"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: data["action${n}_label"]?.trim()?.takeIf { it.isNotEmpty() }
            if (title == null) continue
            val id = data["action${n}_id"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: data["action${n}_action"]?.trim()?.takeIf { it.isNotEmpty() }
                ?: "action_$n"
            if (out.any { it.id == id }) continue
            out.add(ParsedAction(id, title))
        }
        return out
    }
}
