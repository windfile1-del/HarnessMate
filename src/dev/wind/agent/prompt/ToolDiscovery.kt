package dev.wind.agent.prompt

import dev.wind.agent.ToolRegistry
import org.json.JSONArray
import org.json.JSONObject

/** Bounded local metadata. No permissions, execution or user-file inspection. */
object ToolDiscovery {
    fun search(args: JSONObject): String {
        val query = args.optString("query").trim().take(200).lowercase()
        val words = query.split(Regex("\\s+")).filter { it.isNotEmpty() }
        val limit = args.optInt("limit", 5).coerceIn(1, 5)
        val matches = ToolRegistry.allEnabledTools().filter { spec ->
            words.isEmpty() || words.any { it in spec.name.lowercase() || it in spec.promptDoc.lowercase() }
        }.sortedBy { it.name }
        return JSONObject().put("tools", JSONArray(matches.take(limit).map {
            JSONObject().put("name", it.name).put("contract", PromptComposer.shortContract(it.promptDoc))
                .put("mutating", it.mutating).put("authority", "CHECKED_AT_EXECUTION")
        })).put("has_more", matches.size > limit).put("matched", matches.size).toString()
    }
    fun describe(args: JSONObject): String {
        val names = args.optJSONArray("names") ?: throw IllegalArgumentException("TOOL_NAMES_REQUIRED")
        require(names.length() in 1..5) { "TOOL_DESCRIPTION_LIMIT" }
        var remaining = 16000
        val output = JSONArray()
        for (i in 0 until names.length()) {
            val name = names.getString(i)
            val spec = ToolRegistry.spec(name)
            if (spec == null || !spec.enabled) output.put(JSONObject().put("name", name).put("status", "UNKNOWN_OR_DISABLED"))
            else {
                val body = spec.promptDoc.take(remaining); remaining -= body.length
                output.put(JSONObject().put("name", name).put("contract", body).put("truncated", body.length < spec.promptDoc.length)
                    .put("effect", spec.effect.name).put("mutating", spec.mutating))
            }
        }
        return JSONObject().put("tools", output).toString()
    }
}
