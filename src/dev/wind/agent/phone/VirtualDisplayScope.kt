package dev.wind.agent.phone

import org.json.JSONObject

/** Pure host guard, shared by RPC frames, observations, readiness and action preparation. */
internal object VirtualDisplayScope {
    val actions=setOf("tap","long_press","swipe","click","dismiss","set_text","type_text","paste_text","scroll","back")
    fun session(expected: String?, supplied: String, display: Int) {
        check(!expected.isNullOrBlank() && expected==supplied && display>0) { "VIRTUAL_DISPLAY_SESSION_CHANGED" }
    }
    fun frame(scope: JSONObject, frame: JSONObject) {
        session(scope.optString("display_session_id"),frame.optString("display_session_id"),frame.optInt("display_id",-1))
        check(frame.optString("display_epoch")==frame.getString("display_session_id") && frame.optLong("geometry_epoch")==1L &&
            frame.optInt("width")==720 && frame.optInt("height")==1280 && frame.optInt("rotation")==0) { "VIRTUAL_DISPLAY_GEOMETRY_CHANGED" }
        check(frame.optLong("frame_sequence")>0 && frame.optLong("frame_timestamp_ns")>0) { "VIRTUAL_DISPLAY_FRAME_INVALID" }
    }
    fun same(before: JSONObject, after: JSONObject) {
        for(key in listOf("display_session_id","display_id","display_epoch","geometry_epoch","package","window","width","height","rotation","structural_epoch"))
            check(before.has(key) && after.has(key) && before.get(key).toString()==after.get(key).toString()) { "PHONE_WINDOW_CHANGED" }
    }
    fun action(args: JSONObject) {
        check(args.optString("action") in actions) { "VIRTUAL_DISPLAY_ACTION_UNSUPPORTED" }
        check(!args.has("visual_id")) { "VIRTUAL_DISPLAY_OCR_SELECTOR_NOT_SUPPORTED" }
    }
    fun packageName(pkg: String) {
        require(pkg.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+"))) { "PHONE_PACKAGE_INVALID" }
    }
}
