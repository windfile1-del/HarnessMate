package dev.wind.agent

import dev.wind.agent.phone.*
import java.io.File
import org.json.JSONObject

/** Pure JVM scenarios; no Android stub API is invoked. */
object PhoneHarnessTests {
    private var checks = 0
    private fun verify(value: Boolean, reason: String) { check(value) { reason }; checks++ }
    private fun node(path: List<Int> = listOf(0), text: String = "取消", id: String = "button", x: Int = 100, y: Int = 100,
        clickable: Boolean = true, password: Boolean = false, enabled: Boolean = true) = NodeFacts(path, "Button", id, text, "", x, y, x + 200, y + 100,
            clickable, false, false, enabled, password)
    @JvmStatic fun main(args: Array<String>) {
        freshness(); matching(); risk(); settle(); gestures(); interruptions(); vision(); waits(); recovery(); sourceContracts(); editorPanel(); replyFormat(); foregroundChat(); sendCaptureScope()
        println("PASS PhoneHarnessTests: $checks assertions")
    }
    private fun freshness() {
        val stamp = PhoneFreshness.Stamp("task-A", "com.taobao.taobao", 41, 1080, 2400, 5, 7, 1000)
        verify(PhoneFreshness.evaluate(stamp, stamp.copy(at = 2000)) == PhoneFreshness.Verdict.Fresh, "static page fresh")
        repeat(200) { index ->
            verify(PhoneFreshness.evaluate(stamp, stamp.copy(content = 8L + index, at = 9000)) is PhoneFreshness.Verdict.Drifted,
                "Taobao content updates must not block node or coordinate action")
        }
        val cases = listOf("owner" to stamp.copy(owner = "task-B"), "expired" to stamp.copy(at = 91001),
            "expired" to stamp.copy(at = 999), "package_changed" to stamp.copy(pkg = "com.video.app"),
            "window_changed" to stamp.copy(windowId = 57), "display_changed" to stamp.copy(width = 2400, height = 1080),
            "structural_change" to stamp.copy(structural = 6))
        cases.forEach { (reason, now) -> verify((PhoneFreshness.evaluate(stamp, now) as? PhoneFreshness.Verdict.Stale)?.reason == reason, "stale reason=$reason") }
        verify((PhoneFreshness.evaluate(stamp, stamp.copy(content = 999), true) as? PhoneFreshness.Verdict.Stale)?.reason == "content_changed", "strict rollback")
        verify(PhoneFreshness.evaluate(stamp, stamp.copy(at = 91000)) is PhoneFreshness.Verdict.Fresh, "TTL inclusive")
        verify((PhoneFreshness.evaluate(stamp, stamp.copy(rotation = 2)) as? PhoneFreshness.Verdict.Stale)?.reason == "display_changed", "180 degree rotation invalidates even when dimensions stay identical")
    }
    private fun foregroundChat() {
        val header = ChatPageParser.Line("小 卤 蛋", 300, 80, 620, 115)
        verify(ChatPageParser.titleMatches(listOf(header), "小卤蛋"), "OCR Chinese spacing cannot block a verified title")
        verify(!ChatPageParser.titleMatches(listOf(header.copy(top = 600)), "小卤蛋"), "a name in the message body is not conversation identity")
        verify(!ChatPageParser.titleMatches(listOf(header, header.copy(left = 500)), "小卤蛋"), "ambiguous headers reject identity")
        verify(!ChatPageParser.titleMatches(listOf(header.copy(confidence = 40f)), "小卤蛋"), "low confidence title cannot authorize sending")
        val incoming = ChatPageParser.Line("今天有空吗", 90, 260, 350, 290)
        val wrapped = ChatPageParser.Line("一起吃饭吧", 90, 300, 350, 330)
        val outgoing = ChatPageParser.Line("可以", 760, 400, 890, 430)
        val timestamp = ChatPageParser.Line("20:30", 100, 450, 240, 470)
        val fullWidth = ChatPageParser.Line("无法确认方向", 0, 510, 1000, 540)
        val turns = ChatPageParser.turns(listOf(header, incoming, wrapped, outgoing, timestamp, fullWidth), "小卤蛋", 900)
        verify(turns.first() == ChatPageParser.Turn("peer", "今天有空吗\n一起吃饭吧"), "wrapped OCR lines form one peer message with literal LF")
        verify(turns[1].role == "self" && turns.last().role == "unknown", "self and uncertain messages are never labeled peer")
        verify(turns.none { it.text == "20:30" }, "timestamps do not wake the model")
        val old = listOf(ChatPageParser.Turn("peer", "好"))
        verify(ChatPageParser.appended(emptyList(), old).isEmpty(), "opening old history seeds baseline")
        verify(ChatPageParser.appended(old, old).isEmpty(), "unchanged frame cannot loop")
        verify(ChatPageParser.appended(old, old + ChatPageParser.Turn("peer", "好")).size == 1, "two identical actual message rows still count as a new arrival")
        verify(ChatPageParser.appended(old, old + ChatPageParser.Turn("self", "好")).isEmpty(), "outgoing echo does not wake the model")
        verify(ChatPageParser.appended(old, listOf(ChatPageParser.Turn("peer", "别的旧消息"))).isEmpty(), "scrolling to unrelated history cannot manufacture arrivals")
        verify(ChatPageParser.appended(listOf(ChatPageParser.Turn("peer", "一起\n吃饭")), listOf(ChatPageParser.Turn("peer", "一起吃饭"))).isEmpty(), "UI/OCR wrapping difference is not a new message")
        val marker = Message("user", "第三方数据").apply { chatAssistEventId = "event-1" }
        verify(Message.fromJson(marker.toJson()).chatAssistEventId == "event-1", "visible incoming turn preserves its untrusted-data marker")
        val root = File(System.getProperty("user.dir"))
        val ime = File(root, "src/dev/wind/agent/phone/PhoneInputService.kt").readText().substringAfter("internal fun paste(").substringBefore("internal fun editorText")
        verify(ime.contains("android.R.id.paste") && !ime.contains("sendKeyEvent") && !ime.contains("performEditorAction"), "multiline paste cannot become ENTER or implicit send")
        val assistant = File(root, "src/dev/wind/agent/phone/ChatAssist.kt").readText()
        verify(assistant.contains("AgentService.start") && assistant.contains("callback.onNewAssistantMessage") && assistant.contains("chatAssistEventId == null"), "incoming work is visible and only actual user directions steer future replies")
    }
    private fun editorPanel() {
        val panel = PhoneEditorStability(1000)
        verify(!panel.ready(1000, "hidden", true, 999), "keyboard grace starts at click")
        verify(!panel.ready(1250, "hidden", true, 999), "visible editor before delayed panel is not enough")
        verify(!panel.ready(1400, "opening", true, 999), "panel movement resets stability")
        verify(!panel.ready(1500, "open", true, 999), "new endpoint must settle")
        verify(!panel.ready(1650, "open", true, 999), "endpoint needs 200ms quiet")
        verify(panel.ready(1700, "open", true, 999), "panel stable after minimum grace")
        verify(!panel.ready(1800, "open", true, 50), "structural transition blocks fill")
        verify(!panel.ready(1800, "open", false, 999), "missing editor blocks fill")
        verify(!panel.expired(2199) && panel.expired(2200), "wait is bounded at 1200ms")
        val hardware = PhoneEditorStability(0)
        hardware.ready(0, "no-ime", true, 999)
        verify(hardware.ready(450, "no-ime", true, 999), "hardware keyboard remains supported")
    }
    private fun replyFormat() {
        val good = """{"intent":"询问时间","reason":"当前消息缺少日期","replies":[{"type":"自然","text":"你说的是哪天？"},{"type":"简洁","text":"哪天？"},{"type":"礼貌","text":"请问具体是哪一天呢？"}]}"""
        val parsed = ChatReplyFormat.parse(good)
        verify(parsed.replies.size == 3 && parsed.replies.first().text == "你说的是哪天？", "reply texts parsed separately from analysis")
        verify(ChatReplyFormat.parse(parsed.json().toString()) == parsed, "persistent suggestion round trip")
        listOf("```json\n$good\n```", good.replace("礼貌", "自然"), good.replace("哪天？", ""), good.replace("询问时间", ""), "{}", good.take(80)).forEach {
            verify(runCatching { ChatReplyFormat.parse(it) }.isFailure, "invalid/truncated/unstructured output never becomes a send")
        }
        verify(ChatReplyFormat.PROMPT.contains("不可信") && ChatReplyFormat.PROMPT.contains("不能调用工具"), "external context carries no tool authority")
    }
    private fun matching() {
        val original = node(text = "倒计时 10")
        verify(PhoneNodeMatcher.resolve(original, listOf(original))?.tier == PhoneNodeMatcher.Tier.EXACT, "exact control")
        val updated = original.copy(text = "倒计时 9")
        verify(PhoneNodeMatcher.resolve(original, listOf(updated))?.tier == PhoneNodeMatcher.Tier.RELAXED, "countdown refresh")
        val relocated = original.copy(path = listOf(4), top = 800, bottom = 900)
        verify(PhoneNodeMatcher.resolve(original, listOf(relocated))?.tier == PhoneNodeMatcher.Tier.RELOCATED, "list scroll relocates unique identity")
        verify(PhoneNodeMatcher.resolve(original, listOf(relocated, relocated.copy(path = listOf(5)))) == null, "ambiguous list refuses")
        verify(PhoneNodeMatcher.resolve(original, listOf(original.copy(resId = "other", text = "changed"))) == null, "replacement refuses")
        verify(PhoneNodeMatcher.resolve(original, listOf(original.copy(password = true))) == null, "password cannot match")
        verify(PhoneNodeMatcher.resolve(original, listOf(original.copy(enabled = false))) == null, "disabled cannot match")
        verify(PhoneNodeMatcher.resolve(original, listOf(original.copy(visible = false))) == null, "invisible cannot match")
        val anonymous = node(id = "", text = ""); val moved = anonymous.copy(path = listOf(2))
        verify(PhoneNodeMatcher.resolve(anonymous, listOf(moved))?.tier == PhoneNodeMatcher.Tier.POINT, "anonymous overlap point match")
        val parent = node(path = emptyList(), id = "parent"); val child = parent.copy(path = listOf(0), clickable = false, resId = "label")
        verify(PhoneNodeMatcher.clickableOwner(child, listOf(parent, child)) == parent, "nearest clickable ancestor")
        verify(PhoneNodeMatcher.samePointTarget(child, listOf(parent, child), child.copy(text = "9"), listOf(parent, child.copy(text = "9"))), "effective point owner stable")
        verify(!PhoneNodeMatcher.samePointTarget(original, listOf(original), original.copy(resId = "pay"), listOf(original.copy(resId = "pay"))), "changed coordinate target refuses")
    }
    private fun categories(n: NodeFacts, action: String = "click", declared: String = "normal") =
        PhoneRisk.categories("com.example.store", action, declared, "auto", n, n.text, false)
    private fun risk() {
        val cancel = node(); val pay = cancel.copy(text = "立即支付", resId = "button")
        verify(PhoneRisk.extra(categories(cancel), categories(pay)).contains("payment"), "Cancel turning into Pay must require new authority")
        verify(categories(pay, declared = "normal").contains("payment"), "normal cannot downgrade local risk")
        verify(categories(pay, declared = "send").containsAll(setOf("send", "payment")), "declared other category cannot hide payment")
        verify(PhoneRisk.categories("com.example", "tap", "normal", "auto", null, "", true).contains("unclassified_interaction"), "visual unknown scope")
        verify(categories(node(text = "发送")).contains("send"), "send control recognized")
        verify(PhoneRisk.categories("com.example", "click", "normal", "auto", node(text = "搜索"), "发送", false).isEmpty(), "Send elsewhere does not taint ordinary navigation")
        verify(PhoneRisk.categories("com.example", "ime_action", "normal", "auto", null, "", true).contains("submit"), "auto IME submission sensitive")
        verify(PhoneRisk.extra(setOf("payment", "send"), setOf("payment")).isEmpty(), "risk can narrow without escalation")
        verify(PhoneRisk.extra(emptySet(), setOf("payment", "account", "destructive", "send"), fullyManaged = true).isEmpty(), "user-enabled Full delegates all risk categories without extra approvals")
    }
    private fun settle() {
        val fixed = node(path = listOf(0)); val carousel = node(path = listOf(1), id = "banner")
        verify(PhoneSettle.fingerprint(listOf(fixed)) == PhoneSettle.fingerprint(listOf(fixed.copy(text = "999"))), "countdown ignored in layout hash")
        val stability = PhoneSettle.LayoutStability()
        verify(!stability.sample(listOf(fixed, carousel)), "one sample not stable")
        verify(!stability.sample(listOf(fixed, carousel.copy(left = 120, right = 320))), "second moving sample unstable")
        verify(stability.sample(listOf(fixed, carousel.copy(left = 140, right = 340))) && "1" in stability.hotSpots, "three-sample carousel hotspot excluded")
        val changed = PhoneSettle.LayoutStability(); changed.sample(listOf(fixed))
        verify(!changed.sample(listOf(fixed.copy(top = 300, bottom = 400))), "real layout transition detected")
        val landmarks = listOf(node(text = "A", y = 800), node(path = listOf(1), text = "B", y = 1000))
        verify(PhoneSettle.movement(landmarks, landmarks.map { it.copy(top = it.top - 400, bottom = it.bottom - 400) }, false).movedPx == -400, "scroll displacement median")
        verify(PhoneSettle.movement(landmarks, landmarks, false).reachedEnd == true, "unchanged landmarks estimated end")
        verify(PhoneSettle.movement(emptyList(), landmarks, false).reachedEnd == null, "unmeasured not end")
    }
    private fun gestures() {
        for (direction in listOf("up", "down", "left", "right")) for (amount in listOf("small", "half", "page")) {
            val plan = PhoneGesturePlanner.scroll(1080, 2400, direction, amount, null, 35)
            verify(plan.startX in 0f..1079f && plan.endX in 0f..1079f && plan.startY >= 2400 * .12 && plan.endY <= 2400 * .9, "viewport edges safe")
            verify(when (direction) { "down" -> plan.endY < plan.startY; "up" -> plan.endY > plan.startY; "right" -> plan.endX < plan.startX; else -> plan.endX > plan.startX }, "semantic scroll direction $direction")
            verify(plan.continued && plan.holdMs == 120L, "API26+ release dwell")
        }
        val old = PhoneGesturePlanner.scroll(1080, 2400, "down", "half", null, 24)
        verify(!old.continued && old.durationMs == 900L, "API24-25 slow fallback")
        val small = PhoneGesturePlanner.scroll(1080, 2400, "down", "small", null, 35)
        val page = PhoneGesturePlanner.scroll(1080, 2400, "down", "page", null, 35)
        verify(page.startY - page.endY > small.startY - small.endY, "amount controls distance")
        val region = node(x = 200, y = 500)
        val inside = PhoneGesturePlanner.scroll(1080, 2400, "down", "page", region, 35)
        verify(inside.startX in region.left.toFloat()..region.right.toFloat() && inside.endY >= region.top, "node bounded gesture")
    }
    private fun interruptions() {
        for (context in listOf("发现新版本更新", "青少年模式", "是否切换全球站", "允许获取定位权限", "广告活动")) {
            val nodes = listOf(node(text = context, clickable = false), node(path = listOf(1)), node(path = listOf(2), text = "同意"), node(path = listOf(3), text = "立即支付"))
            val candidates = PhoneInterruptions.detect(nodes, true)
            verify(candidates.size == 1 && candidates.single().text == "取消", "negative only: $context")
        }
        listOf("允许", "同意", "确定", "立即更新", "去开启", "领取", "立即支付", "取消订单", "取消并支付").forEach {
            verify(!PhoneInterruptions.negativeButton(node(text = it)), "positive/destructive text never selected: $it")
        }
        verify(!PhoneInterruptions.negativeButton(node(password = true)), "protected dismiss forbidden")
        verify(!PhoneInterruptions.negativeButton(node(text = "同意").copy(desc = "关闭")), "negative description cannot override affirmative visible label")
        verify(PhoneInterruptions.detect(listOf(node()), false).isEmpty(), "ordinary Cancel not a popup")
        verify(PhoneInterruptions.detect(listOf(node(text = "×").copy(right = 130, bottom = 130)), true).size == 1, "small close glyph")
    }
    private fun sendCaptureScope() {
        for (pet in listOf(false,true)) for (visible in listOf(false,true)) for (active in listOf(0L,7L,8L)) {
            verify(dev.wind.agent.screen.ScreenShareSession.allowsAutomaticCapture(7L,active,pet,visible)==(pet && !visible && active==7L), "automatic capture requires matching session, pet mode and external foreground")
        }
        verify(!dev.wind.agent.screen.ScreenShareSession.allowsAutomaticCapture(0L,0L,true,false),"no grant cannot bind auto capture")
        val normal=Message("user","in-app send").also { it.screenSessionId=7; it.screenContextNote="old screen" }
        dev.wind.agent.screen.ScreenShareSession.bind(normal)
        verify(normal.screenSessionId==0L && normal.screenContextNote==null,"ordinary sends clear screen binding even when projection was enabled")
        normal.requestedSkillPaths=listOf("skills://example/SKILL.md")
        verify(!normal.toJson().has("requestedSkillPaths") && !normal.toJson().has("screenSessionId"),"send preparation and screen authority remain transient")
        val service=java.io.File("src/dev/wind/agent/AgentService.kt").readText()
        val start=service.substringAfter("override fun onStartCommand").substringBefore("private fun initializeRun")
        verify(listOf("store.load(","store.saveNow(","AgentEngine(","SkillStore(","ProjectManager(").none { it in start },"service main callback does not load/serialize chat or initialize runtime")
        val send=java.io.File("src/dev/wind/agent/MainActivity.kt").readText().substringAfter("private fun onSendOrStop()").substringBefore("private fun beginRun()")
        verify(!send.contains("store.save(") && !send.contains("SkillStore("),"send handlers have no full-chat serialization or skill directory scan")
    }
    private fun sourceContracts() {
        val root = File(System.getProperty("user.dir")); val phone = File(root, "src/dev/wind/agent/phone")
        listOf("PhoneFacts", "PhoneFreshness", "PhoneNodeMatcher", "PhoneRisk", "PhoneSettle", "PhoneGesturePlanner", "PhoneInterruptions").forEach {
            verify(!File(phone, "$it.kt").readText().contains("import android."), "$it must be Android-free")
        }
        val tools = File(phone, "PhoneTools.kt").readText()
        val observe = tools.substringAfter("private fun observe(").substringBefore("private fun visualCoordinates")
        val wait = tools.substringAfter("private fun waitFor(").substringBefore("private fun apps")
        verify(!observe.contains("service.act(") && !wait.contains("service.act("), "READ paths never inject actions")
        verify(observe.contains("content_events_during_capture"), "dynamic capture reports drift")
        verify(tools.contains("post_action_error") && tools.contains("observation_error"), "attached failures preserve action status")
        val authority = File(phone, "PhoneApprovalPolicy.kt").readText().substringAfter("fun authorize(").substringBefore("fun nativeScope")
        verify(authority.contains("permissions.policy == dev.wind.agent.permissions.ActionPolicy.AUTONOMOUS") &&
            authority.indexOf("ActionPolicy.AUTONOMOUS") < authority.indexOf("AgentBus.awaitApprovalDetailed"), "Autonomous Action Policy bypasses per-operation prompts within granted capability")
        verify(authority.contains("AgentPermissionGate(context).error") &&
            File(phone, "PhoneApprovalPolicy.kt").readText().contains("ticket.permissions.revision == ticket.permissionRevision"), "phone commit rechecks permission revocation")
        val service = File(phone, "PhoneControlService.kt").readText()
        val events = service.substringAfter("override fun onAccessibilityEvent").substringBefore("private fun structural")
        verify(!events.contains("rootInActiveWindow") && !events.contains("getRoot("), "event handlers never read UI tree")
        verify(service.contains("mode == PhoneAutomation.Mode.VISUAL) null else liveFacts") && service.contains("o.mode == PhoneAutomation.Mode.VISUAL) null else liveFacts"), "visual sampling/action never read UI nodes")
        verify(service.contains("consumeAll()") && service.contains("reason=") && service.contains("consumed; observe again"), "freshness diagnoses and consumed IDs")
        val engine = File(root, "src/dev/wind/agent/AgentEngine.kt").readText()
        verify(engine.contains("setOf(\"phone_observe\", \"phone_action\", \"phone_wait\", \"phone_launch\", \"phone_sequence\", \"phone_flow\")"), "action/wait/launch/sequence/flow image bridge wired")
    }
    private fun vision() {
        val target = requireNotNull(PhoneVisual.normalized("v0", "暂 不 更新", intArrayOf(100, 200, 300, 260), 1000, 1000, 90f))
        verify(target.text == "暂不更新" && target.x == 200 && target.y == 230, "Chinese OCR spacing and full-screen mapping")
        val scaled = requireNotNull(PhoneVisual.normalized("v1", "Search", intArrayOf(50, 100, 150, 130), 500, 500, 80f))
        verify(scaled.left == 100 && scaled.bottom == 260, "OCR scaling independent of screenshot dimensions")
        verify(PhoneVisual.normalized("v0", "search", intArrayOf(0, 0, 10, 10), 10, 10, 20f) == null, "low confidence discarded")
        verify(PhoneVisual.normalized("v0", "search", intArrayOf(0, 0, 10, 10), 10, 10, Float.NaN) == null, "nonfinite confidence discarded")
        verify(PhoneVisual.normalized("v0", "", intArrayOf(0, 0, 10, 10), 10, 10, 90f) == null, "nontext boxes discarded")
        verify(PhoneVisual.normalized("v0", "search", intArrayOf(10, 10, 0, 0), 10, 10, 90f) == null, "inverted bounds discarded")
        verify(PhoneVisual.resolve(target, listOf(target.copy(id = "v3", left = 110))) != null, "small OCR geometry drift accepted")
        verify(PhoneVisual.resolve(target, listOf(target.copy(left = 600, right = 800))) == null, "moved OCR target rejected")
        verify(PhoneVisual.resolve(target, listOf(target, target.copy(id = "v2", left = 600, right = 800))) == null, "duplicate identity anywhere rejects click")
        verify(PhoneVisual.resolve(target, listOf(target.copy(text = "立即更新"))) == null, "changed visual label rejected")
        val context = target.copy(id = "v2", text = "新版本更新")
        verify(PhoneVisual.interruptions(listOf(target, context)) == listOf(target), "OCR negative popup context")
        verify(PhoneVisual.interruptions(listOf(target.copy(text = "同意"), context)).isEmpty(), "OCR never accepts agreement")
        verify(PhoneVisual.interruptions(listOf(target.copy(text = "取消"))).isEmpty(), "ordinary visual Cancel is not a popup")
        verify(PhoneInterruptions.automatic(listOf(node(), node(path = listOf(1), text = "广告商品")), false, 2000) == null,
            "banner plus search Cancel never triggers automatic recovery")
        verify(PhoneInterruptions.automatic(listOf(node(y = 1000), node(path = listOf(1), text = "发现新版本")), false, 2000) != null,
            "strong update popup with negative button can recover")
        verify(PhoneInterruptions.automatic(listOf(node(y = 1000), node(path = listOf(1), text = "发现新版本"),
            node(path = listOf(2), text = "稍后")), false, 2000) == null, "ambiguous negative choices do not recover automatically")
    }
    private fun waits() {
        val text = PhoneWaitTarget.parse(JSONObject().put("target", "取消"))
        verify(text.matches(node()), "visible string selector")
        verify(!text.matches(node(text = "确定")), "different UI text")
        verify(!text.matches(node(password = true)), "wait excludes password")
        verify(!text.matches(node().copy(visible = false)), "wait excludes invisible nodes")
        verify(text.matches(node(enabled = false)), "disabled but visible label still meets visibility wait")
        verify(text.matches(node(text = "").copy(desc = "取消")), "legacy text also matches accessible description")
        val resource = PhoneWaitTarget.parse(JSONObject().put("target", JSONObject().put("resource_id", "button").put("clickable", true)))
        verify(resource.matches(node()), "resource selector and clickable requirement")
        verify(!resource.matches(node(clickable = false)), "selector conjunction")
        verify(PhoneWaitTarget(text = "取", exact = false).matches(node()), "substring selector")
        verify(!PhoneWaitTarget(text = "取", exact = true).matches(node()), "exact selector")
        val anchor = PhoneVisual.Target("v0", "搜 索", 0, 0, 100, 100, 90f)
        verify(PhoneWaitTarget(text = "搜索", exact = true).matches(anchor), "visual whitespace matching")
        verify(!resource.matches(anchor), "OCR cannot fake resource identity")
        var refused = false; try { resource.validateVisual() } catch (_: IllegalArgumentException) { refused = true }
        verify(refused, "visual nontext selector is explicitly refused")
        listOf(JSONObject(), JSONObject().put("target", 3), JSONObject().put("target", JSONObject().put("unknown", true)),
            JSONObject().put("target", JSONObject().put("text", "x").put("clickable", "yes"))).forEach {
            var invalid = false; try { PhoneWaitTarget.parse(it) } catch (_: IllegalArgumentException) { invalid = true }
            catch (_: IllegalStateException) { invalid = true }
            verify(invalid, "invalid selector rejected before waiting")
        }
    }
    private fun recovery() {
        val budget = PhoneRecoveryBudget()
        verify(budget.reserve("first", 0), "first recovery allowed")
        verify(!budget.reserve("first", 3000), "unknown/attempted interruption is never replayed")
        verify(!budget.reserve("second", 1499), "cooldown enforced")
        verify(budget.reserve("second", 1500), "new interruption after cooldown")
        repeat(18) { verify(budget.reserve("next$it", 3000L + it * 1500), "bounded recovery $it") }
        verify(!budget.reserve("overflow", 999999), "task recovery limit")
        verify(budget.attempts == 20, "suppressed attempts do not inflate budget")
        val root = File(System.getProperty("user.dir"))
        val service = File(root, "src/dev/wind/agent/phone/PhoneControlService.kt").readText()
        val manager = File(root, "src/dev/wind/agent/phone/PhoneManagedRecovery.kt").readText()
        val tools = File(root, "src/dev/wind/agent/phone/PhoneTools.kt").readText()
        verify(manager.contains("tryRecovery") && manager.contains("current.token.isCancelled"), "worker shares serialization and cancellation")
        verify(manager.contains("PhoneApprovalPolicy.Mode.FULL") && manager.contains("recoveryEnabled"), "background recovery requires Full and opt-in setting")
        verify(!service.substringAfter("TYPE_WINDOWS_CHANGED) {").substringBefore("val pkg =").contains("PhoneManagedRecovery.signal"), "overlay capture events cannot self-trigger OCR")
        verify(tools.contains("vision.truncated") && tools.contains("PhoneVisual.resolve"), "visual click rechecks unique complete OCR results")
        verify(File(root, "src/dev/wind/agent/screen/ScreenShareService.kt").readText().contains("if (request.done.get()) return@post"), "expired capture cannot hide overlay later")
    }
}
