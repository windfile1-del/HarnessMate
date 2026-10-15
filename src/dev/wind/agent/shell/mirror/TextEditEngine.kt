package dev.wind.agent.shell.mirror

import org.json.JSONObject

object TextEditEngine {
    fun apply(original: String, args: JSONObject): String {
        val edits = args.optJSONArray("edits")
        if (edits != null) {
            var text = original
            for (i in 0 until edits.length()) text = apply(text, edits.getJSONObject(i))
            return text
        }
        if (args.has("start_line")) {
            val lines = original.split('\n').toMutableList()
            val start = args.getInt("start_line")
            val end = args.optInt("end_line", start)
            require(start >= 1 && end >= start && start <= lines.size + 1) { "Invalid line range" }
            val replacement = args.optString("new_text", args.optString("content", ""))
            val replacementLines = if (replacement.isEmpty()) emptyList() else replacement.split('\n')
            return (lines.take(start - 1) + replacementLines + lines.drop(minOf(end, lines.size))).joinToString("\n")
        }
        val old = args.getString("old_string")
        require(old.isNotEmpty()) { "old_string must not be empty" }
        val at = original.indexOf(old)
        if (args.optBoolean("replace_all", false)) {
            require(at >= 0) { "old_string not found" }
            return original.replace(old, args.getString("new_string"))
        }
        require(at >= 0 && original.indexOf(old, at + 1) < 0) { "old_string must match exactly once" }
        return original.replaceRange(at, at + old.length, args.getString("new_string"))
    }
}
