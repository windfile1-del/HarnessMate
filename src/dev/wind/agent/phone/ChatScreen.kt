package dev.wind.agent.phone

import dev.wind.agent.CancellationToken
import dev.wind.agent.screen.ScreenShareSession
import org.json.JSONObject

/** Explicitly enabled chat screen reader. Ordinary phone observation stays image-free by default. */
internal object ChatScreen {
    data class Page(val pkg: String, val title: String, val window: Int, val rotation: Int, val source: String,
                    val lines: List<ChatPageParser.Line>, val turns: List<ChatPageParser.Turn>, val composerY: Int, val panelTop: Int)
    fun read(service: PhoneControlService, binding: JSONObject, token: CancellationToken): Page {
        val pkg = binding.getString("package"); val title = binding.getString("title")
        val meta = service.sample(PhoneAutomation.Mode.VISUAL, token)
        check(meta.pkg == pkg) { "CHAT_TARGET_NOT_FOREGROUND" }
        val geometry = service.chatGeometry(token)
        if (PhoneAutomation.mode(service) in setOf(PhoneAutomation.Mode.UI, PhoneAutomation.Mode.HYBRID)) {
            val sample = runCatching { service.sample(PhoneAutomation.Mode.UI, token) }.getOrNull()
            if (sample != null && !sample.truncated && sample.pkg == pkg) {
                val nodes = sample.nodes.filter { it.visible && !it.password && !it.editable && (it.text.isNotBlank() || it.desc.isNotBlank()) }
                fun label(f: NodeFacts) = f.text.ifBlank { f.desc }
                val leaves = nodes.filter { parent -> nodes.none { child -> parent.isAncestorOf(child) &&
                    label(child).isNotBlank() && (label(child) == label(parent) || label(parent).contains(label(child))) } }
                    .filter { it.text.isNotBlank() || PhoneVisual.key(it.desc) == PhoneVisual.key(title) || it.desc.trim() in setOf("发送", "Send") }
                val lines = leaves.map { f -> ChatPageParser.Line(f.text.ifBlank { f.desc }, f.left * 1000 / geometry[0], f.top * 1000 / geometry[1],
                    f.right * 1000 / geometry[0], f.bottom * 1000 / geometry[1]) }
                if (ChatPageParser.titleMatches(lines, title)) return Page(pkg, title, sample.window, geometry[2], "ui", lines,
                    ChatPageParser.turns(lines, title, geometry[3]), geometry[3], geometry[4])
            }
        }
        check(binding.optBoolean("visual_fallback", true)) { "CHAT_SCREEN_VISUAL_DISABLED" }
        val sharing = ScreenShareSession.sessionId
        check(sharing != 0L) { "CHAT_SCREEN_SHARE_REQUIRED: 从聊天助手开启屏幕共享，供节点缺失时识别当前聊天" }
        val capture = ScreenShareSession.service?.captureBlocking(sharing, token, phoneObservation = true) ?: error("CHAT_CAPTURE_UNAVAILABLE")
        val image = capture.image ?: error("CHAT_CAPTURE_FAILED: ${capture.error}")
        val ocr = PhoneVision.recognize(service, image, token)
        check(ocr.status == "OK" && !ocr.truncated) { "CHAT_OCR_UNAVAILABLE: ${ocr.status}" }
        val lines = ocr.targets.map { ChatPageParser.Line(it.text, it.left, it.top, it.right, it.bottom, it.confidence) }
        check(ChatPageParser.titleMatches(lines, title)) { "CHAT_VISUAL_PEER_NOT_VERIFIED: 当前画面未识别出唯一目标标题 $title" }
        check(lines.none { it.top > 500 && it.text.trim() in setOf("按住说话", "搜索表情", "搜索我的表情", "表情商店", "管理表情") }) {
            "CHAT_TEXT_COMPOSER_REQUIRED: 请切回文字输入并关闭表情面板；可使用复制兜底"
        }
        check(lines.count { it.top > 550 && it.text.trim() in setOf("照片", "拍摄", "位置", "视频通话", "文件") } < 3) {
            "CHAT_ATTACHMENT_PANEL_OPEN: 请关闭聊天功能面板再填入或监测"
        }
        val after = service.sample(PhoneAutomation.Mode.VISUAL, token)
        check(after.pkg == pkg && after.window == meta.window && service.chatGeometry(token)[2] == geometry[2]) { "CHAT_SCREEN_CHANGED_DURING_CAPTURE" }
        return Page(pkg, title, meta.window, geometry[2], "ocr", lines, ChatPageParser.turns(lines, title, geometry[3]), geometry[3], geometry[4])
    }
    fun fill(service: PhoneControlService, binding: JSONObject, text: String, token: CancellationToken, authority: () -> Unit = {}, afterFill: (() -> Unit)? = null) {
        val page = read(service, binding, token)
        authority(); token.throwIfCancelled()
        if (page.source == "ui" && text.length < 350) {
            // UI ACTION_SET_TEXT is already literal text; node rejection alone can use the IME route below.
            val result = runCatching { service.fillChat(page.pkg, page.title, text, token) }
            if (result.isSuccess) {
                val until = android.os.SystemClock.elapsedRealtime() + 1200
                var verified = false
                do {
                    token.sleep(80); authority()
                    val sample = service.sample(PhoneAutomation.Mode.UI, token)
                    if (sample.pkg == page.pkg && !sample.truncated && sample.nodes.count {
                            it.editable && it.actionable && it.text.replace("\r\n", "\n") == text.replace("\r\n", "\n")
                        } == 1) { verified = true; break }
                } while (android.os.SystemClock.elapsedRealtime() < until)
                check(verified) { "CHAT_FILL_RESULT_UNKNOWN: 未确认完整文本，请手动检查，不重复填入" }
                afterFill?.invoke(); return
            }
            val reason = result.exceptionOrNull()?.message.orEmpty()
            check(!reason.contains("RESULT_UNKNOWN") && !reason.contains("cancelled")) { reason }
            if (!reason.contains("EDITOR") && !reason.contains("FILL_REFUSED") && !reason.contains("CONVERSATION_NOT_VERIFIED"))
                throw requireNotNull(result.exceptionOrNull())
        }
        val fresh = read(service, binding, token)
        authority()
        service.chatTap(fresh.pkg, fresh.window, 500, fresh.composerY, token)
        token.sleep(450)
        PhoneKeyboard.withFocusedEditor(service, fresh.pkg, token, authority) {
            settleComposer(service, fresh.pkg, token)
            read(service, binding, token) // Verify the visible conversation again after the keyboard transition.
            service.onMain(token) {
                authority()
                check(PhoneInputService.instance?.paste(text, fresh.pkg, replace = true) == true) { "CHAT_PASTE_REFUSED: 可使用复制按钮手动粘贴" }
            }
            val until = android.os.SystemClock.elapsedRealtime() + 1200
            var verified = false
            do {
                token.sleep(80); authority()
                val actual = service.onMain(token) { PhoneInputService.instance?.editorText(fresh.pkg) }
                if (actual?.replace("\r\n", "\n") == text.replace("\r\n", "\n")) { verified = true; break }
            } while (android.os.SystemClock.elapsedRealtime() < until)
            check(verified) { "CHAT_PASTE_RESULT_UNKNOWN: 未确认完整文本，禁止自动发送或重复粘贴；可手动检查" }
            afterFill?.invoke()
        }
    }
    private fun settleComposer(service: PhoneControlService, pkg: String, token: CancellationToken) {
        val start = android.os.SystemClock.elapsedRealtime()
        val stability = PhoneEditorStability(start)
        do {
            token.throwIfCancelled()
            val sample = service.sample(PhoneAutomation.Mode.VISUAL, token)
            check(sample.pkg == pkg) { "CHAT_EDITOR_PACKAGE_CHANGED" }
            val geometry = service.chatGeometry(token)
            val active = service.onMain(token) { PhoneInputService.instance?.accepts(pkg) == true }
            val now = android.os.SystemClock.elapsedRealtime()
            if (stability.ready(now, "${sample.window}:${geometry.joinToString()}", active, sample.quietMs)) return
            check(!stability.expired(now)) { "CHAT_EDITOR_PANEL_NOT_STABLE: 请关闭动画或使用复制手动粘贴" }
            token.sleep(70)
        } while (true)
    }
    fun reply(service: PhoneControlService, binding: JSONObject, text: String, token: CancellationToken, authority: () -> Unit) {
        fill(service, binding, text, token, authority) {
        token.sleep(350)
        val page = read(service, binding, token)
        if (page.source == "ui" && text.length < 350) {
            val sample = service.sample(PhoneAutomation.Mode.UI, token)
            check(sample.nodes.count { it.editable && it.actionable && it.text == text } == 1) { "CHAT_INPUT_TEXT_NOT_VERIFIED: 不发送" }
        } else check(service.onMain(token) { PhoneInputService.instance?.editorText(page.pkg) }?.replace("\r\n", "\n") == text.replace("\r\n", "\n")) { "CHAT_EDITOR_TEXT_CHANGED: 不发送" }
        val send = page.lines.filter { it.text.trim() in setOf("发送", "Send") && it.confidence >= 65 && it.left > 650 &&
            it.top >= page.composerY - 55 && it.bottom < page.panelTop + 5 }.singleOrNull()
            ?: error("CHAT_SEND_BUTTON_NOT_VERIFIED: 已填入，请手动发送；不会重试发送")
        authority(); token.throwIfCancelled()
        service.chatTap(page.pkg, page.window, (send.left + send.right) / 2, (send.top + send.bottom) / 2, token)
        }
    }
}
