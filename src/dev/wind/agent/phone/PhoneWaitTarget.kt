package dev.wind.agent.phone

import org.json.JSONObject

/** Shared selector semantics for UI and screenshot waits, independent of Android. */
internal data class PhoneWaitTarget(val text: String = "", val desc: String = "", val resource: String = "",
    val cls: String = "", val exact: Boolean = false, val clickable: Boolean? = null, val editable: Boolean? = null) {
    fun matches(node: NodeFacts): Boolean {
        fun match(value: String, expected: String) = expected.isEmpty() ||
            if (exact) value.equals(expected, true) else value.contains(expected, true)
        return node.visible && !node.password && node.area > 0 &&
            (match(node.text, text) || desc.isEmpty() && match(node.desc, text)) && match(node.desc, desc) &&
            (resource.isEmpty() || resource == node.resId) && (cls.isEmpty() || cls == node.cls) &&
            (clickable == null || clickable == node.clickable) && (editable == null || editable == node.editable)
    }
    fun matches(target: PhoneVisual.Target) = resource.isEmpty() && cls.isEmpty() && desc.isEmpty() &&
        clickable == null && editable == null && (text.isEmpty() || PhoneVisual.queryMatches(target, text, exact))
    fun validateVisual() = require(text.isNotBlank() && resource.isEmpty() && cls.isEmpty() && desc.isEmpty() &&
        clickable == null && editable == null) { "PHONE_VISUAL_SELECTOR_REQUIRES_TEXT: OCR cannot establish resource/class/interactivity" }
    companion object {
        fun parse(args: JSONObject): PhoneWaitTarget {
            val target = args.opt("target")
            val value = when (target) {
                null, JSONObject.NULL -> JSONObject().put("text", args.optString("text"))
                is String -> JSONObject().put("text", target)
                is JSONObject -> target
                else -> error("PHONE_WAIT_TARGET_INVALID")
            }
            val allowed = setOf("text", "description", "resource_id", "class", "exact", "clickable", "editable")
            require(value.keys().asSequence().all { it in allowed }) { "PHONE_WAIT_TARGET_INVALID" }
            fun string(name: String): String {
                if (!value.has(name)) return ""
                require(value.get(name) is String && value.getString(name).length in 1..350) { "PHONE_WAIT_TARGET_INVALID" }
                return value.getString(name)
            }
            fun boolean(name: String): Boolean? {
                if (!value.has(name)) return null
                require(value.get(name) is Boolean) { "PHONE_WAIT_TARGET_INVALID" }; return value.getBoolean(name)
            }
            val selector = PhoneWaitTarget(string("text"), string("description"), string("resource_id"), string("class"),
                boolean("exact") ?: false, boolean("clickable"), boolean("editable"))
            require(selector.text.isNotBlank() || selector.desc.isNotBlank() || selector.resource.isNotBlank() || selector.cls.isNotBlank()) {
                "PHONE_WAIT_TARGET_REQUIRED"
            }
            return selector
        }
    }
}
