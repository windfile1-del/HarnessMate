package dev.wind.agent.phone

import org.json.JSONObject

/** A target belongs to a call, never to a chat or its capability grants. */
internal object PhoneScreenTarget {
    fun resolve(args: JSONObject, default: String): String {
        val explicit=args.optString("screen")
        require(explicit.isEmpty() || explicit in setOf("main","virtual")) { "PHONE_SCREEN_INVALID" }
        require(!(explicit=="main" && args.has("display_session_id"))) { "PHONE_SCREEN_SCOPE_CONFLICT" }
        return if(args.has("display_session_id")) "virtual" else explicit.ifEmpty { default }
    }
}
