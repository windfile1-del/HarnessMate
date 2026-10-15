package dev.wind.agent.phone

import android.content.Context
import dev.wind.agent.AgentBus
import dev.wind.agent.CancellationToken
import org.json.JSONObject

/** Chat consent and exact peer verification stay independent from general phone action approvals. */
internal object ChatPhoneScreen {
    private fun owner() = AgentBus.activeTaskId.orEmpty()
    fun read(c: Context, binding: JSONObject, token: CancellationToken): ChatScreen.Page {
        val task = owner()
        if (!PhoneBackends.wantsAdb(c, JSONObject(), task)) return ChatScreen.read(PhoneAutomation.requireEnabled(c), binding, token)
        val backend = PhoneBackends.backend(c, task, token)
        return readAdb(c, task, backend, binding, token).first
    }
    private fun readAdb(c: Context, task: String, backend: PhoneBackend, binding: JSONObject, token: CancellationToken): Pair<ChatScreen.Page, PhoneObservation> {
        val pkg = binding.getString("package"); val title = binding.getString("title")
        val tools = PhoneAdbTools(c, task)
        val ui = runCatching { tools.observe(backend, JSONObject().put("mode", "ui").put("fresh",true), token) }.getOrNull()
        token.throwIfCancelled()
        val meta = ui ?: tools.observe(backend, JSONObject().put("mode", "visual"), token)
        check(meta.pkg == pkg) { "CHAT_TARGET_NOT_FOREGROUND" }
        val w = meta.json.getInt("width"); val h = meta.json.getInt("height")
        val editor = ui?.nodes?.map { it.second }?.filter { it.editable && it.actionable }?.singleOrNull()
        val composer = editor?.let { it.centerY*1000/h } ?: if (meta.json.optBoolean("ime_visible")) 580 else 870
        val panel = editor?.let { (it.bottom*1000/h+50).coerceAtMost(1000) } ?: if (meta.json.optBoolean("ime_visible")) 650 else 1000
        fun page(source: String, lines: List<ChatPageParser.Line>) = ChatScreen.Page(pkg,title,meta.json.getString("window").hashCode(),meta.json.getInt("rotation"),source,lines,
            ChatPageParser.turns(lines,title,composer),composer,panel)
        if (ui != null && !ui.json.optBoolean("truncated")) {
            val facts = ui.nodes.map { it.second }.filter { it.visible && !it.password && !it.editable && (it.text.isNotBlank() || it.desc.isNotBlank()) }
            fun label(f: NodeFacts) = f.text.ifBlank { f.desc }
            val leaves = facts.filter { parent -> facts.none { child -> parent.isAncestorOf(child) && label(parent).contains(label(child)) } }
            val lines = leaves.map { f -> ChatPageParser.Line(label(f),f.left*1000/w,f.top*1000/h,f.right*1000/w,f.bottom*1000/h) }
            if (ChatPageParser.titleMatches(lines,title)) return page("ui",lines) to ui
        }
        check(binding.optBoolean("visual_fallback", true)) { "CHAT_SCREEN_VISUAL_DISABLED" }
        val image = PhoneBackends.image(c,task,backend,token)
        val ocr = PhoneVision.recognize(c,image,token)
        check(ocr.status == "OK" && !ocr.truncated) { "CHAT_OCR_UNAVAILABLE" }
        val lines = ocr.targets.map { ChatPageParser.Line(it.text,it.left,it.top,it.right,it.bottom,it.confidence) }
        check(ChatPageParser.titleMatches(lines,title)) { "CHAT_VISUAL_PEER_NOT_VERIFIED" }
        check(lines.none { it.top>500 && it.text.trim() in setOf("按住说话","搜索表情","搜索我的表情","表情商店","管理表情") }) { "CHAT_TEXT_COMPOSER_REQUIRED" }
        check(lines.count { it.top>550 && it.text.trim() in setOf("照片","拍摄","位置","视频通话","文件") }<3) { "CHAT_ATTACHMENT_PANEL_OPEN" }
        val after=backend.observe("visual",token)
        PhoneBackends.samePage(meta.json,after)
        if(meta.json.has("event_epoch") && after.has("event_epoch")) check(meta.json.getLong("event_epoch")==after.getLong("event_epoch")) { "CHAT_SCREEN_CHANGED_DURING_OCR" }
        return page("ocr",lines) to meta
    }
    fun fill(c: Context, binding: JSONObject, text: String, token: CancellationToken, authority: () -> Unit = {}, send: Boolean = false) {
        val task = owner()
        if (!PhoneBackends.wantsAdb(c,JSONObject(),task)) {
            val service = PhoneAutomation.requireEnabled(c)
            if (send) ChatScreen.reply(service,binding,text,token,authority) else ChatScreen.fill(service,binding,text,token,authority)
            return
        }
        PhoneCoordinator.run(token) {
            val backend = PhoneBackends.backend(c,task,token); val tools = PhoneAdbTools(c,task)
            val initial = readAdb(c,task,backend,binding,token)
            val editors = initial.second.nodes.filter { it.second.editable && it.second.actionable }
            authority(); token.throwIfCancelled()
            if (editors.size==1) {
                val args = JSONObject().put("observation_id",initial.second.id).put("action","set_text").put("node_id",editors.single().first).put("text",text).put("backend","adb")
                val r = tools.action(backend,args,token,authority=authority,beforeText={
                    val fresh=readAdb(c,task,backend,binding,token)
                    peerGuard(fresh,binding)
                })
                check(r.optBoolean("text_verified") && r.optString("status") in setOf("COMPLETED","DISPATCHED")) { "CHAT_FILL_RESULT_UNKNOWN: inspect; do not repeat" }
                if (send) send(c,task,backend,binding,text,token,authority, false)
            } else {
                val tap = JSONObject().put("observation_id",initial.second.id).put("action","tap").put("x",500).put("y",initial.first.composerY).put("backend","adb")
                val tapped = tools.action(backend,tap,token,authority=authority)
                check(tapped.optString("status") in setOf("COMPLETED","DISPATCHED")) { "CHAT_EDITOR_FOCUS_UNKNOWN" }
                PhoneAdbIme.focused(c,task,binding.getString("package"),token,authority) {
                    readAdb(c,task,backend,binding,token) // exact peer again after keyboard transition
                    var accepted = false
                    PhoneOverlayScope.main(token) { authority(); accepted = PhoneInputService.instance?.paste(text,binding.getString("package"),true)==true }
                    check(accepted) { "CHAT_PASTE_RESULT_UNKNOWN" }
                    val until = android.os.SystemClock.elapsedRealtime()+1200; var verified = false
                    do {
                        token.sleep(80); authority(); var actual: String? = null
                        PhoneOverlayScope.main(token) { actual = PhoneInputService.instance?.editorText(binding.getString("package")) }
                        if (actual?.replace("\r\n","\n")==text.replace("\r\n","\n")) { verified=true; break }
                    } while (android.os.SystemClock.elapsedRealtime()<until)
                    check(verified) { "CHAT_PASTE_RESULT_UNKNOWN" }
                    if (send) send(c,task,backend,binding,text,token,authority,true)
                }
            }
        }
    }
    private fun send(c: Context, task: String, backend: PhoneBackend, binding: JSONObject, text: String, token: CancellationToken, authority: () -> Unit, ime: Boolean) {
        token.sleep(200); authority()
        val fresh = readAdb(c,task,backend,binding,token)
        if (ime) {
            var actual: String? = null; PhoneOverlayScope.main(token) { actual = PhoneInputService.instance?.editorText(binding.getString("package")) }
            check(actual?.replace("\r\n","\n")==text.replace("\r\n","\n")) { "CHAT_EDITOR_TEXT_CHANGED" }
        } else check(fresh.second.nodes.count { (_,f) -> f.editable && f.actionable && f.text.replace("\r\n","\n")==text.replace("\r\n","\n") }==1) { "CHAT_EDITOR_TEXT_CHANGED" }
        val button = fresh.first.lines.filter { it.text.trim() in setOf("发送","Send") && it.confidence>=65 && it.left>650 && it.top>=fresh.first.composerY-55 && it.bottom<fresh.first.panelTop+5 }.singleOrNull()
            ?: error("CHAT_SEND_BUTTON_NOT_VERIFIED: text filled; send manually")
        val args = JSONObject().put("observation_id",fresh.second.id).put("action","tap").put("x",(button.left+button.right)/2).put("y",(button.top+button.bottom)/2).put("backend","adb").put("sensitivity","send")
            .put("peer_guard",peerGuard(fresh,binding))
        val r = PhoneAdbTools(c,task).action(backend,args,token,authority=authority)
        check(r.optString("status") in setOf("DISPATCHED","COMPLETED")) { "CHAT_SEND_RESULT_UNKNOWN: never resend" }
    }
    private fun peerGuard(page:Pair<ChatScreen.Page,PhoneObservation>,binding:JSONObject):JSONObject = JSONObject()
        .put("title",binding.getString("title")).put("source",page.first.source).put("package",binding.getString("package"))
        .put("event_epoch",page.second.json.optLong("event_epoch",-1))
}
