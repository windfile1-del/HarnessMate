package dev.wind.agent.context

import org.json.JSONObject

/** Provider input totals include cache reads; never add cached tokens twice. */
data class TokenUsage(val inputTokens: Long, val outputTokens: Long, val cachedTokens: Long,
                      val cacheWriteTokens: Long, val cacheKnown: Boolean) {
    companion object {
        fun parse(event: JSONObject, previous: TokenUsage? = null): TokenUsage? {
            val root = event.optJSONObject("response") ?: event.optJSONObject("message") ?: event
            val u = root.optJSONObject("usage") ?: root.optJSONObject("usageMetadata") ?: return null
            val details = u.optJSONObject("prompt_tokens_details") ?: u.optJSONObject("input_tokens_details")
            val known = u.has("cache_read_input_tokens") || u.has("prompt_cache_hit_tokens") ||
                u.has("cachedContentTokenCount") || details?.has("cached_tokens") == true
            val read = (when {
                u.has("cache_read_input_tokens") -> u.optLong("cache_read_input_tokens")
                u.has("prompt_cache_hit_tokens") -> u.optLong("prompt_cache_hit_tokens")
                u.has("cachedContentTokenCount") -> u.optLong("cachedContentTokenCount")
                else -> details?.optLong("cached_tokens") ?: 0
            }).coerceAtLeast(0)
            val write = (if (u.has("cache_creation_input_tokens")) u.optLong("cache_creation_input_tokens")
                else details?.optLong("cache_write_tokens") ?: 0).coerceAtLeast(0)
            val input = when {
                u.has("prompt_tokens") -> u.optLong("prompt_tokens")
                u.has("promptTokenCount") -> u.optLong("promptTokenCount")
                // Anthropic's input_tokens excludes both cache read and creation.
                u.has("cache_read_input_tokens") || u.has("cache_creation_input_tokens") -> u.optLong("input_tokens") + read + write
                else -> u.optLong("input_tokens")
            }.coerceAtLeast(0)
            // Anthropic message_delta can contain output usage only. Do not erase message_start input.
            val output = (if (u.has("completion_tokens")) u.optLong("completion_tokens") else if (u.has("candidatesTokenCount"))
                u.optLong("candidatesTokenCount") + u.optLong("thoughtsTokenCount") else u.optLong("output_tokens")).coerceAtLeast(0)
            if (!u.has("prompt_tokens") && !u.has("input_tokens") && !u.has("promptTokenCount") && !known)
                return previous?.copy(outputTokens = output)
            return TokenUsage(input, output, read.coerceAtMost(input), write, known)
        }
    }
}
