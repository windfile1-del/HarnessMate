package dev.wind.agent.phone

import android.content.Context
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.View
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.LinearLayout
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent

/** Optional automation IME: visual typing without reading the accessibility tree or the clipboard. */
class PhoneInputService : InputMethodService() {
    @Volatile internal var inputActive = false
        private set
    override fun onCreate() { super.onCreate(); instance = this }
    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting); inputActive = true
        // Includes the desktop-pet composer, whose editor belongs to this app.
        if (attribute?.packageName == packageName) Handler(Looper.getMainLooper()).post {
            if (currentInputEditorInfo?.packageName == packageName) PhoneKeyboard.restoreForUser(this)
        }
    }
    override fun onFinishInput() { inputActive = false; super.onFinishInput() }
    override fun onDestroy() { if (instance === this) instance = null; super.onDestroy() }
    override fun onCreateInputView(): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        val height = (48 * resources.displayMetrics.density).toInt()
        fun row(keys: List<String>, press: (String) -> Unit) {
            addView(LinearLayout(this@PhoneInputService).apply {
                orientation = LinearLayout.HORIZONTAL
                keys.forEach { key -> addView(Button(this@PhoneInputService).apply {
                    text = key; textSize = 14f; minWidth = 0; minimumWidth = 0
                    setPadding(0, 0, 0, 0); setOnClickListener { press(key) }
                }, LinearLayout.LayoutParams(0, height, 1f)) }
            })
        }
        var capitals = false
        row(listOf("常用键盘", "切换", "隐藏")) { key -> when (key) {
            "常用键盘" -> if (!PhoneKeyboard.restoreForUser(this@PhoneInputService))
                (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            "切换" -> (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            else -> requestHideSelf(0)
        } }
        listOf("1234567890", "qwertyuiop", "asdfghjkl", "zxcvbnm").forEach { letters ->
            row(letters.map { it.toString() }) { key -> currentInputConnection?.commitText(if (capitals) key.uppercase() else key, 1) }
        }
        row(listOf("⇧", ",", "空格", ".", "⌫", "↵")) { key -> when (key) {
            "⇧" -> capitals = !capitals
            "空格" -> currentInputConnection?.commitText(" ", 1)
            "⌫" -> currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DEL)).also {
                currentInputConnection?.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DEL))
            }
            "↵" -> {
                val action = (currentInputEditorInfo?.imeOptions ?: 0) and EditorInfo.IME_MASK_ACTION
                if (action != EditorInfo.IME_ACTION_NONE && action != EditorInfo.IME_ACTION_UNSPECIFIED)
                    currentInputConnection?.performEditorAction(action) else currentInputConnection?.commitText("\n", 1)
            }
            else -> currentInputConnection?.commitText(key, 1)
        } }
    }

    internal fun accepts(expectedPackage: String): Boolean = inputActive && currentInputEditorInfo?.packageName == expectedPackage

    internal fun commit(text: String, expectedPackage: String): Boolean {
        requireEditor(expectedPackage)
        require(text.length <= 8000) { "PHONE_TEXT_TOO_LONG" }
        return currentInputConnection?.commitText(text, 1) == true
    }
    internal fun paste(text: String, expectedPackage: String, replace: Boolean = false): Boolean {
        requireEditor(expectedPackage)
        require(text.isNotEmpty() && text.length <= 8000) { "PHONE_TEXT_TOO_LONG" }
        val connection = currentInputConnection ?: return false
        connection.beginBatchEdit()
        try {
            if (replace) {
                val existing = connection.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0)
                check(existing != null && existing.startOffset == 0 && existing.partialStartOffset < 0) { "PHONE_EDITOR_TEXT_UNAVAILABLE: do not overwrite an unknown draft" }
                check(connection.setSelection(0, existing.text?.length ?: 0)) { "PHONE_EDITOR_SELECTION_REFUSED" }
            }
            PhoneClipboard.copy(this, text)
            // No fallback after accepted submission; an asynchronous input connection can have unknown completion.
            return connection.performContextMenuAction(android.R.id.paste)
        } finally { connection.endBatchEdit() }
    }
    internal fun editorText(expectedPackage: String): String? {
        requireEditor(expectedPackage)
        val extracted = currentInputConnection?.getExtractedText(android.view.inputmethod.ExtractedTextRequest(), 0) ?: return null
        return extracted.takeIf { it.startOffset == 0 && it.partialStartOffset < 0 }?.text?.toString()
    }

    internal fun editorAction(expectedPackage: String): String? {
        val info = currentInputEditorInfo ?: return null
        if (!inputActive || info.packageName != expectedPackage) return null
        return actions.entries.firstOrNull { it.value == (info.imeOptions and EditorInfo.IME_MASK_ACTION) }?.key
    }

    internal fun performAction(expectedPackage: String, action: String): Boolean {
        val info = requireEditor(expectedPackage)
        val requested = if (action == "auto") {
            (info.imeOptions and EditorInfo.IME_MASK_ACTION).also { check(it in actions.values) { "PHONE_IME_NO_ACTION: tap the observed Send button instead" } }
        } else actions[action] ?: error("PHONE_IME_ACTION_INVALID")
        check((info.imeOptions and EditorInfo.IME_MASK_ACTION) == requested) { "PHONE_IME_ACTION_CHANGED: observe the editor action again" }
        return currentInputConnection?.performEditorAction(requested) == true
    }

    private fun requireEditor(expectedPackage: String): EditorInfo {
        check(inputActive) { "PHONE_IME_NOT_ACTIVE" }
        val info = currentInputEditorInfo ?: error("PHONE_IME_NO_EDITOR")
        val variation = info.inputType and InputType.TYPE_MASK_VARIATION
        val clazz = info.inputType and InputType.TYPE_MASK_CLASS
        val password = clazz == InputType.TYPE_CLASS_TEXT && variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD, InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD) ||
            clazz == InputType.TYPE_CLASS_NUMBER && variation == InputType.TYPE_NUMBER_VARIATION_PASSWORD
        check(!password) { "PHONE_PASSWORD_INPUT_BLOCKED" }
        check(info.packageName != packageName && !info.packageName.orEmpty().contains("permissioncontroller")) { "PHONE_IME_PROTECTED_EDITOR" }
        check(info.packageName == expectedPackage) { "PHONE_IME_EDITOR_CHANGED" }
        return info
    }

    companion object {
        @Volatile internal var instance: PhoneInputService? = null
        private val actions = mapOf("search" to EditorInfo.IME_ACTION_SEARCH, "done" to EditorInfo.IME_ACTION_DONE,
            "go" to EditorInfo.IME_ACTION_GO, "next" to EditorInfo.IME_ACTION_NEXT, "send" to EditorInfo.IME_ACTION_SEND)
    }
}
