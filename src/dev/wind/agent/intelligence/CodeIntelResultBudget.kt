package dev.wind.agent.intelligence

import org.json.JSONArray
import org.json.JSONObject

/**
 * The single exit gate every P5 result passes through.
 *
 * Counts are not a byte budget: one long signature, message or dependency label
 * can push a small result list past the limit. Whatever the JSON shape —
 * results, diagnostics, graph, report, error — the serialised UTF-8 payload
 * ends at or below [CodeIntelLimits.MAX_TOOL_RESULT_BYTES], truncation is
 * stated rather than silent, and a payload that cannot be shrunk is replaced by
 * an explicit failure instead of being sent anyway.
 */
internal object CodeIntelResultBudget {

    fun enforce(json: JSONObject): JSONObject {
        if (size(json) <= CodeIntelLimits.MAX_TOOL_RESULT_BYTES) return json
        shrinkArray(json, "results")?.let { return it }
        shrinkArray(json, "diagnostics")?.let { return it }
        shrinkGraph(json)?.let { return it }
        shrinkReport(json)?.let { return it }
        return JSONObject()
            .put("ok", false)
            .put("error", "RESULT_BUDGET_EXCEEDED")
            .put("truncated", true)
    }

    fun size(json: JSONObject): Int = json.toString().toByteArray(Charsets.UTF_8).size

    private fun shrinkArray(json: JSONObject, key: String): JSONObject? {
        val array = json.optJSONArray(key) ?: return null
        var keep = array.length() - 1
        while (keep >= 0) {
            json.put(key, head(array, keep))
            json.put("truncated", true)
            if (size(json) <= CodeIntelLimits.MAX_TOOL_RESULT_BYTES) return json
            keep--
        }
        return null
    }

    private fun shrinkGraph(json: JSONObject): JSONObject? {
        val graph = json.optJSONObject("graph") ?: return null
        graph.optJSONArray("edges")?.let { graph.put("edges", head(it, minOf(it.length(), 50))) }
        graph.optJSONArray("nodes")?.let { graph.put("nodes", head(it, minOf(it.length(), 50))) }
        graph.put("truncated", true)
        return if (size(json) <= CodeIntelLimits.MAX_TOOL_RESULT_BYTES) json else null
    }

    private fun shrinkReport(json: JSONObject): JSONObject? {
        val report = json.optJSONObject("report") ?: return null
        report.put("rawTail", "")
        report.optJSONArray("errors")?.let { report.put("errors", head(it, minOf(it.length(), 5))) }
        report.put("warnings", JSONArray())
        report.put("truncated", true)
        json.put("truncated", true)
        return if (size(json) <= CodeIntelLimits.MAX_TOOL_RESULT_BYTES) json else null
    }

    private fun head(array: JSONArray, count: Int): JSONArray {
        val subset = JSONArray()
        for (index in 0 until count) subset.put(array.get(index))
        return subset
    }
}
