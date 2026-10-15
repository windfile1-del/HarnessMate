package dev.wind.agent.phone

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.Tools
import org.json.JSONArray
import org.json.JSONObject

/** Read-only readiness polling. Changing feed text does not extend a layout wait. */
internal object PhoneReady {
    fun nodeId(n: JSONObject): String = n.optString("node_id",n.optString("id"))
    fun key(o: JSONObject, target: JSONObject? = null): String {
        val identity = listOf("package", "window", "width", "height", "rotation", "display_id", "structural_epoch", "ime_visible")
            .joinToString("|") { (o.opt(it) ?: JSONObject.NULL).toString() }
        val nodes = o.optJSONArray("nodes") ?: JSONArray()
        val query = target?.let { PhoneWaitTarget.parse(JSONObject().put("target", it)) }
        return identity + (0 until nodes.length()).map { nodes.getJSONObject(it) }.filter {
            val f = PhoneObservation.facts(it)
            if (query != null) query.matches(f) else f.visible && (f.clickable || f.editable || f.scrollable)
        }.joinToString { it.optString("resource_id") + it.optString("class") + it.optJSONArray("bounds") + it.optBoolean("editable") }
    }
    fun resolve(o: JSONObject, target: JSONObject): String? {
        if (o.optBoolean("truncated")) return null
        val query = PhoneWaitTarget.parse(JSONObject().put("target", target))
        val nodes = o.optJSONArray("nodes") ?: JSONArray()
        val matches = (0 until nodes.length()).map { nodes.getJSONObject(it) }.filter {
            val f = PhoneObservation.facts(it); f.actionable && query.matches(f)
        }
        return matches.singleOrNull()?.let(::nodeId)
    }
    fun await(read: () -> JSONObject, sleep: (Long) -> Unit, clock: () -> Long, timeout: Long,
        pkg: String? = null, target: JSONObject? = null, minimum: Long = 180, stable: Long = 180): JSONObject {
        val start = clock(); var previous = ""; var since = start; var last: JSONObject?=null
        while (true) {
            val o = try { read() } catch(e: Exception) {
                if(!listOf("PHONE_WINDOW_CHANGED", "PHONE_STRUCTURAL_CHANGED", "PHONE_WINDOW_IDENTITY_PENDING", "PHONE_WINDOW_UNKNOWN", "PHONE_FOREGROUND_UNVERIFIED").any { e.message.orEmpty().startsWith(it) }) throw e
                val now=clock()
                if(now-start>=timeout) {
                    val prior=last ?: throw e
                    return JSONObject().put("status","TIMEOUT").put("waited_ms",now-start).put("observation",prior).put("observation_error",e.message)
                }
                previous=""; since=now; sleep(minOf(90,timeout-(now-start))); continue
            }
            last=o
            val now = clock(); val key = key(o, target)
            if (key != previous) { previous = key; since = now }
            val matched = (pkg == null || o.optString("package") == pkg) && (target == null || resolve(o, target) != null)
            if (matched && now-start >= minimum && now-since >= stable)
                return JSONObject().put("status", "MET").put("waited_ms", now-start).put("observation", o)
            if (now-start >= timeout) return JSONObject().put("status", "TIMEOUT").put("waited_ms", now-start).put("observation", o)
            sleep(minOf(90, timeout-(now-start)))
        }
    }
}

/** Validates the whole plan first, then only polls reads; never retries a submitted effect. */
internal class PhoneFlowRunner(private val read: () -> JSONObject, private val perform: (JSONObject) -> JSONObject,
    private val sleep: (Long) -> Unit, private val clock: () -> Long) {
    companion object {
        private val allowed = setOf("click", "long_press", "set_text", "paste_text", "type_text", "scroll", "back", "home", "launch", "wait")
        fun validate(args: JSONObject): List<JSONObject> {
            require(args.optString("mode", "ui") == "ui") { "PHONE_FLOW_UI_REQUIRED" }
            val steps = args.getJSONArray("steps"); require(steps.length() in 1..8) { "PHONE_FLOW_LIMIT" }
            require(args.optLong("timeout_ms", 20000) in 1000..30000) { "PHONE_FLOW_TIMEOUT_INVALID" }
            return (0 until steps.length()).map { JSONObject(steps.getJSONObject(it).toString()).also { s ->
                val action = s.getString("action"); require(action in allowed) { "PHONE_FLOW_ACTION_INVALID" }
                val keys = setOf("action", "expected_package", "package", "target", "text", "direction", "sensitivity", "timeout_ms", "duration_ms", "replace")
                require(s.keys().asSequence().all { it in keys }) { "PHONE_FLOW_UNKNOWN_FIELD" }
                val pkg = s.getString(if (action == "launch") "package" else "expected_package")
                require(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "PHONE_PACKAGE_INVALID" }
                require(s.optLong("timeout_ms", 2500) in 200..5000) { "PHONE_FLOW_STEP_TIMEOUT_INVALID" }
                if (action in setOf("click", "long_press", "set_text", "paste_text", "type_text", "scroll"))
                    PhoneWaitTarget.parse(JSONObject().put("target", s.getJSONObject("target")))
                else s.optJSONObject("target")?.let { PhoneWaitTarget.parse(JSONObject().put("target", it)) }
                if (action in setOf("set_text", "paste_text", "type_text")) require(s.getString("text").length in 1..16000 && !s.getString("text").contains('\u0000')) { "PHONE_TEXT_INVALID" }
                require(s.optInt("duration_ms", 650) in 1..1500) { "PHONE_DURATION_INVALID" }
                if (action == "scroll") require(s.optString("direction", "down") in setOf("up", "down", "left", "right", "forward", "backward")) { "PHONE_SCROLL_DIRECTION_INVALID" }
            } }
        }
        fun accepted(r: JSONObject) = r.optString("status") in setOf("DISPATCHED", "COMPLETED", "GESTURE_COMPLETED", "NODE_ACTION_ACCEPTED", "TEXT_INPUT_ACCEPTED")
    }
    fun run(args: JSONObject): JSONObject {
        val steps = validate(args); val start = clock(); val deadline = start+args.optLong("timeout_ms", 20000)
        val receipts = JSONArray(); var last: JSONObject? = null
        fun finish(status: String, error: String? = null) = JSONObject().put("status", status).put("steps", receipts)
            .put("completed_steps", (0 until receipts.length()).count { accepted(receipts.getJSONObject(it)) || receipts.getJSONObject(it).optString("status")=="MET" })
            .put("elapsed_ms", clock()-start).put("business_success_verified", false)
            .put("observation", last ?: JSONObject.NULL).put("retry_safe", false).also { if(error!=null) it.put("error", error) }
        for ((index, step) in steps.withIndex()) {
            if (clock() >= deadline) return finish("STOPPED", "PHONE_FLOW_DEADLINE")
            var performing=false
            try {
                if (step.getString("action") != "launch") {
                    val ready = PhoneReady.await(read, sleep, clock, minOf(step.optLong("timeout_ms",2500),deadline-clock()),
                        step.getString("expected_package"),step.optJSONObject("target"))
                    last = ready.getJSONObject("observation")
                    if (ready.getString("status") != "MET") return finish("STOPPED", "PHONE_FLOW_TARGET_NOT_READY: step=$index")
                    if (step.getString("action") == "wait") { receipts.put(JSONObject().put("status","MET").put("step",index)); continue }
                    step.put("observation_id", last!!.getString("observation_id"))
                    step.optJSONObject("target")?.let { target ->
                        val id = PhoneReady.resolve(last!!,target) ?: error("PHONE_FLOW_TARGET_NOT_UNIQUE")
                        step.put("node_id",id)
                        if(step.getString("action")=="long_press") {
                            val nodes=last!!.getJSONArray("nodes")
                            val f=PhoneObservation.facts((0 until nodes.length()).map { nodes.getJSONObject(it) }.single { PhoneReady.nodeId(it)==id })
                            step.put("x",f.centerX*1000.0/(last!!.getInt("width")-1)).put("y",f.centerY*1000.0/(last!!.getInt("height")-1))
                        }
                    }
                }
                step.remove("target"); step.put("observe_after",if(step.getString("action")=="launch") "ui" else "none")
                last=null // The submitted action consumes its input snapshot, even on an uncertain result.
                performing=true
                val result = perform(step); performing=false; receipts.put(result.put("step",index))
                result.optJSONObject("observation")?.let { last=it }
                if(!accepted(result)) {
                    if(last==null) runCatching { read() }.onSuccess { last=it }
                    return finish("STOPPED", "PHONE_FLOW_ACTION_STOPPED: step=$index")
                }
                if(result.optBoolean("restored_app")) {
                    last=null
                    return finish(if(index==steps.lastIndex) "DISPATCHED" else "STOPPED",if(index==steps.lastIndex) null else "PHONE_FLOW_SELF_CONTROL_FORBIDDEN")
                }
                // Any next page is allowed only through the next step's explicit package and unique selector.
            } catch(e: Exception) {
                if(performing) receipts.put(JSONObject().put("step",index).put("status","UNKNOWN").put("retry_safe",false).put("error",e.message))
                runCatching { read() }.onSuccess { last=it }
                return finish("STOPPED",e.message ?: "PHONE_FLOW_FAILED")
            }
        }
        try { last=PhoneReady.await(read,sleep,clock,minOf(1200,(deadline-clock()).coerceAtLeast(0))).getJSONObject("observation") }
        catch(e: Exception) { return finish("DISPATCHED", "PHONE_FLOW_FINAL_OBSERVATION_FAILED: ${e.message}") }
        return finish("DISPATCHED")
    }
}

internal class PhoneWorkflow(private val context: Context, private val owner: String) {
    fun run(args: JSONObject, token: CancellationToken, observer: Tools.Observer?): String = PhoneCoordinator.run(token) {
        check(PhoneAutomation.mode(context) != PhoneAutomation.Mode.VISUAL) { "PHONE_FLOW_UI_REQUIRED" }
        val tools=PhoneTools(context,owner); val backend=args.optString("backend","auto")
        PhoneFlowRunner(
            read={ JSONObject(tools.run("phone_observe",JSONObject().put("screen","main").put("backend",backend).put("mode","ui").put("fresh",true),token,null)) },
            perform={ a ->
                a.put("screen","main").put("backend",backend)
                val name=if(a.getString("action")=="launch") "phone_launch" else "phone_action"
                val output=tools.run(name,a,token,observer)
                runCatching { JSONObject(output) }.getOrElse { JSONObject().put("status","UNKNOWN").put("output",output) }
            }, sleep={ token.sleep(it) },clock={ android.os.SystemClock.elapsedRealtime() }
        ).run(args).toString()
    }
}
