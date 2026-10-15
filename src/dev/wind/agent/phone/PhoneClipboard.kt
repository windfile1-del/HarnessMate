package dev.wind.agent.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.os.PersistableBundle

/** Native text transport. LF is text, never a KEYCODE_ENTER or an editor action. */
internal object PhoneClipboard {
    fun copy(context: Context, text: String) {
        require(text.isNotEmpty() && text.length <= 16000) { "PHONE_CLIPBOARD_TEXT_INVALID" }
        val clip = ClipData.newPlainText("Vega reply", text)
        if (Build.VERSION.SDK_INT >= 24) clip.description.extras = PersistableBundle().apply {
            putBoolean("android.content.extra.IS_SENSITIVE", true)
        }
        (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(clip)
    }
    fun clear(context: Context) {
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        if (Build.VERSION.SDK_INT >= 28) manager.clearPrimaryClip() else manager.setPrimaryClip(ClipData.newPlainText("", ""))
    }
}
