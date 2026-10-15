package dev.wind.agent.phone

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import dev.wind.agent.CancellationToken

/** Only switches already user-enabled keyboards. No secure-settings write or root. */
internal object PhoneKeyboard {
    private fun current(context: Context) = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD).orEmpty()
    private fun aiId(context: Context): String? = (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
        .enabledInputMethodList.firstOrNull { it.packageName == context.packageName && it.serviceName == PhoneInputService::class.java.name }?.id

    fun <T> withEditor(service: PhoneControlService, expectedPackage: String, token: CancellationToken,
        ticket: PhoneApprovalPolicy.Ticket, operation: () -> T): T {
        return withFocusedEditor(service, expectedPackage, token, { PhoneApprovalPolicy.check(ticket) }, operation)
    }
    fun <T> withFocusedEditor(service: PhoneControlService, expectedPackage: String, token: CancellationToken,
        authority: () -> Unit, operation: () -> T): T {
        var previous: String? = null
        try {
            service.onMain(token) {
                authority()
                if (PhoneInputService.instance?.accepts(expectedPackage) != true) {
                    check(Build.VERSION.SDK_INT >= 30) { "PHONE_IME_MANUAL_REQUIRED: Android below 11 needs Vega AI input selected, or use UI set_text and tap Send" }
                    val id = aiId(service) ?: error("PHONE_IME_ENABLE_REQUIRED: enable Vega AI input once in system settings")
                    previous = current(service).takeIf { it.isNotBlank() && it != id }
                    previous?.let { service.getSharedPreferences("phone_automation", Context.MODE_PRIVATE).edit().putString("previous_keyboard", it).apply() }
                    check(service.softKeyboardController.switchToInputMethod(id)) { "PHONE_IME_SWITCH_REFUSED" }
                }
            }
            val until = android.os.SystemClock.elapsedRealtime() + 2500
            while (service.onMain(token) { PhoneInputService.instance?.accepts(expectedPackage) } != true) {
                token.throwIfCancelled()
                check(android.os.SystemClock.elapsedRealtime() < until) { "PHONE_IME_NO_TARGET_EDITOR: focus target input then observe again" }
                token.sleep(50)
            }
            authority(); token.throwIfCancelled(); return operation()
        } finally {
            val original = previous
            if (original != null) Handler(Looper.getMainLooper()).post {
                // Restore even after cancellation, but never override a keyboard the user chose meanwhile.
                try {
                    if (Build.VERSION.SDK_INT >= 30 && current(service) == aiId(service))
                        service.softKeyboardController.switchToInputMethod(original)
                } catch (_: Exception) { }
            }
        }
    }

    fun restoreForUser(ime: PhoneInputService): Boolean {
        if (Build.VERSION.SDK_INT >= 30) {
            val previous = ime.getSharedPreferences("phone_automation", Context.MODE_PRIVATE).getString("previous_keyboard", null)
            val service = PhoneAutomation.service
            if (previous != null && service != null) try {
                if (service.softKeyboardController.switchToInputMethod(previous)) return true
            } catch (_: Exception) { }
        }
        return try {
            @Suppress("DEPRECATION")
            (ime.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager)
                .switchToLastInputMethod(ime.window?.window?.attributes?.token)
        } catch (_: Exception) { false }
    }
}
