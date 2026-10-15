package dev.wind.agent.phone

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.Tools
import org.json.JSONArray
import org.json.JSONObject

/** Original backend remains available through the same short-sequence contract. */
internal class PhoneLegacySequence(private val context: Context, private val owner: String) {
    fun run(args: JSONObject, token: CancellationToken, observer: Tools.Observer?): String = PhoneCoordinator.run(token) {
        val steps = args.getJSONArray("steps"); require(steps.length() in 1..8) { "PHONE_SEQUENCE_LIMIT" }
        val service = PhoneAutomation.requireEnabled(context)
        val initial = service.actionObservation(owner, args, token)
        require(initial.mode == PhoneAutomation.Mode.UI) { "PHONE_SEQUENCE_LEGACY_UI_REQUIRED" }
        val validated = (0 until steps.length()).map { index -> JSONObject(steps.getJSONObject(index).toString()).also { a ->
            require(a.getString("action") in setOf("tap", "click", "set_text", "paste_text", "scroll", "swipe")) { "PHONE_SEQUENCE_ACTION_INVALID" }
            require(a.getString("expected_package") == initial.packageName) { "PHONE_SEQUENCE_PACKAGE_GUARD_REQUIRED" }
            if (a.getString("action") !in setOf("tap", "swipe")) require(a.optJSONObject("target")?.length()?.let { it > 0 } == true) { "PHONE_SEQUENCE_SELECTOR_REQUIRED" }
            PhoneAdbTools(context, owner).validateAction(JSONObject(a.toString()).put("node_id", "validate_only"))
        } }
        val results = JSONArray()
        for (a in validated) {
            PhoneAutomation.yieldToTarget(token)
            val fresh = service.observe(owner, PhoneAutomation.Mode.UI, token)
            if (fresh.packageName != initial.packageName || fresh.windowId != initial.windowId || fresh.width != initial.width || fresh.height != initial.height)
                return@run JSONObject().put("status", "STOPPED").put("error", "PHONE_SEQUENCE_WINDOW_CHANGED").put("steps", results).toString()
            a.optJSONObject("target")?.let { target ->
                val query = PhoneWaitTarget.parse(JSONObject().put("target", target))
                val candidates = fresh.nodes.filter { it.value.facts.actionable && query.matches(it.value.facts) }
                check(candidates.size == 1) { "PHONE_SEQUENCE_TARGET_NOT_UNIQUE" }
                a.put("node_id", candidates.keys.single())
            }
            a.put("observation_id", fresh.id).put("backend", "legacy")
            val output = PhoneTools(context, owner).run("phone_action", a, token, observer)
            val result = runCatching { JSONObject(output) }.getOrElse { JSONObject().put("status", "UNKNOWN").put("output", output) }
            results.put(result)
            if (result.optString("status") !in setOf("GESTURE_COMPLETED", "NODE_ACTION_ACCEPTED", "TEXT_INPUT_ACCEPTED", "COMPLETED"))
                return@run JSONObject().put("status", "STOPPED").put("steps", results).toString()
        }
        JSONObject().put("status", "DISPATCHED").put("steps", results).put("business_success_verified", false).toString()
    }
}
