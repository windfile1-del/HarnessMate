package dev.wind.agent.phone

import android.content.Context
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import dev.wind.agent.CancellationToken
import dev.wind.agent.adb.AdbCommands
import dev.wind.agent.adb.AdbManager

/** Only already-enabled keyboards; literal InputConnection text, never shell input text. */
internal object PhoneAdbIme {
    fun <T> focused(context: Context, owner: String, pkg: String, token: CancellationToken, authority: () -> Unit, operation: () -> T): T {
        val manager = AdbManager.get(context)
        val enabled = (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).enabledInputMethodList
        val ai = enabled.singleOrNull { it.packageName == context.packageName && it.serviceName == PhoneInputService::class.java.name }?.id
            ?: error("PHONE_IME_ENABLE_REQUIRED: enable Vega AI keyboard once; copy remains available")
        val original = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
        var changed = false
        try {
            authority(); PhoneBackends.guard(context, owner, token)
            if (PhoneInputService.instance?.accepts(pkg) != true) {
                require(ai.matches(Regex("[A-Za-z0-9_.$/]+"))) { "PHONE_IME_ID_INVALID" }
                val r = manager.execute("ime set " + AdbCommands.quote(ai), owner, token, beforeSend = authority)
                changed = original != ai
                check(r.status == "COMPLETED") { "PHONE_IME_SWITCH_RESULT_UNKNOWN" }
            }
            val until = android.os.SystemClock.elapsedRealtime() + 2500
            while (PhoneInputService.instance?.accepts(pkg) != true) {
                authority(); token.throwIfCancelled(); check(android.os.SystemClock.elapsedRealtime() < until) { "PHONE_IME_NO_TARGET_EDITOR" }; token.sleep(50)
            }
            token.sleep(450); authority(); return operation()
        } finally {
            // Separate short cleanup token restores after user cancellation, but never after capability revocation.
            if (changed && enabled.any { it.id == original } && Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD) == ai &&
                dev.wind.agent.permissions.AgentPermissions.get(context).enabled(dev.wind.agent.permissions.AgentCapability.LOCAL_ADB, owner)) {
                val cleanup = CancellationToken()
                runCatching { manager.execute("ime set " + AdbCommands.quote(original), owner, cleanup, 3000, 1024) }
            }
        }
    }
    fun paste(context: Context, owner: String, pkg: String, text: String, replace: Boolean, token: CancellationToken, authority: () -> Unit): Boolean =
        focused(context, owner, pkg, token, authority) {
            var accepted = false
            PhoneOverlayScope.main(token) { authority(); accepted = PhoneInputService.instance?.paste(text, pkg, replace) == true }
            check(accepted) { "PHONE_IME_PASTE_COMPLETION_UNKNOWN" }
            val until = android.os.SystemClock.elapsedRealtime()+1200
            var verified = false
            do {
                token.sleep(80); authority()
                var actual: String? = null
                PhoneOverlayScope.main(token) { actual = PhoneInputService.instance?.editorText(pkg) }
                if (actual?.replace("\r\n", "\n") == text.replace("\r\n", "\n")) { verified = true; break }
            } while (android.os.SystemClock.elapsedRealtime()<until)
            verified
        }
}
