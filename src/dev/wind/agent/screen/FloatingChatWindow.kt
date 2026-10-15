package dev.wind.agent.screen

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.text.InputType
import android.text.TextUtils
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import dev.wind.agent.AgentBus
import dev.wind.agent.AgentService
import dev.wind.agent.Chat
import dev.wind.agent.ChatStore
import dev.wind.agent.Fa
import dev.wind.agent.MainActivity
import dev.wind.agent.Message
import dev.wind.agent.Prefs
import dev.wind.agent.Tools
import dev.wind.agent.capability.CapabilityDecision
import dev.wind.agent.capability.GrantScope

/** Independent compact overlays; transparent gaps never form a large touch-blocking rectangle. */
internal class FloatingChatWindow(private val context: Context, private val onClose: () -> Unit) {
    private class Overlay(val view: View, val layout: WindowManager.LayoutParams)
    private val wm = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private val prefs = Prefs(context)
    private val store = ChatStore(context)
    private val geometry = context.getSharedPreferences("screen_floating_window", Context.MODE_PRIVATE)
    private var pet: Overlay? = null
    private var composer: Overlay? = null
    private var reply: Overlay? = null
    private var history: Overlay? = null
    private var approval: Overlay? = null
    private var companion: Overlay? = null
    private var approvalBody: LinearLayout? = null
    private var approvalScroll: ScrollView? = null
    private var approvalTitle: TextView? = null
    private var approvalOwner: AgentBus.PendingApproval? = null
    private var approvalRunId = 0L
    private var approvalDeferredId = 0L
    private var approvalWanted = false
    private var companionScroll: ScrollView? = null
    private var companionTitle: TextView? = null
    private var companionVisible = false
    private var companionReady = false
    private var cat: CatgirlPetView? = null
    private var badge: TextView? = null
    private var field: EditText? = null
    private var send: Button? = null
    private var replyText: TextView? = null
    private var replyBody: LinearLayout? = null
    private var renderedReply = ""
    private var renderedReplyStreaming = true
    private var replyQuestion: TextView? = null
    private var replyScroll: ScrollView? = null
    private var playback: Button? = null
    private var historyItems: LinearLayout? = null
    private var historyTitle: TextView? = null
    private var historyScroll: ScrollView? = null
    private var historyVisible = false
    private var selectedQuestion: Message? = null
    private var selectedQuestionIndex = -1
    private var selectedQuestionDismissed = false
    private var historyKeys: List<Pair<Message, String>> = emptyList()
    private var historyChatId = ""
    private var inputVisible = false
    private var replyWanted = false
    private var hiddenForCapture = false
    private var hiddenForPhoneAction = false
    private var destroyed = false
    private var keyboardHeight = 0
    private var x = geometry.getInt("x", dp(12))
    private var y = geometry.getInt("y", dp(72))
    private var currentChatId = ""
    private var observedRun = 0L
    private var replyFloor = 0
    private var currentReply: Message? = null
    private var dismissedReply: Message? = null
    private var wasStreaming = false
    private var autoPaused = false
    private var userPaused = false
    private var replyBodyHeight = dp(36)
    private var replyOverhead = dp(36)
    private var inputHeight = dp(84)
    private var draft = ""
    private var suggestions: Overlay? = null
    private var suggestionList: LinearLayout? = null
    private var suggestionScroll: ScrollView? = null
    private var suggestionExpanded = false
    private var suggestionKey = ""
    private var suggestionsWanted = false
    private val pulse = object : Runnable {
        override fun run() {
            if (destroyed) return
            refreshApproval()
            refreshStatus()
            refreshReply()
            refreshSuggestions()
            if (historyVisible) refreshHistory()
            fitWindows()
            main.postDelayed(this, 250L) // UI observation only; never captures the phone screen.
        }
    }
    private val scrollPlayback = object : Runnable {
        override fun run() {
            if (destroyed) return
            val viewport = replyScroll
            if (!autoPaused && !inputVisible && replyWanted && !hiddenForCapture &&
                reply?.view?.visibility == View.VISIBLE && currentReply != null && !wasStreaming && viewport != null) {
                val remaining = ((viewport.getChildAt(0)?.height ?: 0) - viewport.height).coerceAtLeast(0)
                if (viewport.scrollY < remaining) {
                    val advance = (replyText?.lineHeight ?: dp(22)) * 2
                    viewport.smoothScrollTo(0, minOf(remaining, viewport.scrollY + advance))
                }
            }
            main.postDelayed(this, 2400L)
        }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density + 0.5f).toInt()

    fun show() {
        if (destroyed) return
        try {
            PetCompanion.initialize(context)
            if (pet == null) createWindows()
            main.removeCallbacks(pulse); main.post(pulse)
            main.removeCallbacks(scrollPlayback); main.postDelayed(scrollPlayback, 2400L)
            applyVisibility()
        } catch (error: Exception) {
            destroy()
            throw error
        }
    }

    private fun text(value: String, size: Float = 13f): TextView = TextView(context).apply {
        this.text = value
        textSize = size
        setTextColor(Color.WHITE)
        setShadowLayer(dp(1).toFloat(), 0f, dp(1).toFloat(), Color.BLACK)
        textDirection = View.TEXT_DIRECTION_FIRST_STRONG
    }

    private fun button(glyph: String, description: String, action: () -> Unit): Button = Button(context).apply {
        this.text = glyph
        textSize = 12f
        isAllCaps = false
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.TRANSPARENT)
        minimumWidth = 0; minWidth = 0; minimumHeight = 0; minHeight = 0
        setPadding(dp(4), 0, dp(4), 0)
        setSingleLine(true)
        ellipsize = TextUtils.TruncateAt.END
        contentDescription = description
        setOnClickListener { action() }
    }

    private fun consentButton(label: String, action: () -> Unit): Button = button(label, label, action).apply {
        textSize = 13f
        setSingleLine(false)
        setPadding(dp(6), dp(5), dp(6), dp(5))
        setFilterTouchesWhenObscured(true)
        setOnTouchListener { _, event ->
            event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or
                (if (Build.VERSION.SDK_INT >= 29) MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED else 0)) != 0
        }
    }

    /** Local masking behind text is intentional: fully transparent glyph backgrounds mix with page text. */
    private fun readablePlate() = GradientDrawable().apply {
        setColor(Color.argb(246, 22, 26, 35))
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), Color.argb(100, 255, 255, 255))
    }

    private fun attach(view: View, width: Int, height: Int): Overlay {
        val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
        val layout = WindowManager.LayoutParams(width, height, type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT)
        layout.gravity = Gravity.TOP or Gravity.LEFT
        layout.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        view.visibility = View.INVISIBLE
        wm.addView(view, layout)
        return Overlay(view, layout)
    }

    private fun createWindows() {
        val mascot = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.TRANSPARENT)
        }
        cat = CatgirlPetView(context).also {
            mascot.addView(it, LinearLayout.LayoutParams(dp(72), dp(72)))
        }
        badge = text(ScreenStrings.petIdle, 10f).also {
            it.gravity = Gravity.CENTER
            it.setSingleLine(true)
            it.ellipsize = TextUtils.TruncateAt.END
            it.background = readablePlate()
            mascot.addView(it, LinearLayout.LayoutParams(dp(72), dp(20)))
        }
        pet = attach(mascot, dp(72), dp(92))
        installPetGestures(mascot)

        val input = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = readablePlate()
            setPadding(dp(6), dp(2), dp(6), dp(2))
        }
        val grip = text("···", 10f).apply {
            gravity = Gravity.CENTER
            contentDescription = ScreenStrings.dragBubble
        }
        input.addView(grip, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(10)))
        installDrag(grip)
        val row = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        field = EditText(context).also {
            it.textSize = 15f
            it.setTextColor(Color.WHITE); it.setHintTextColor(Color.rgb(200, 208, 220))
            it.setBackgroundColor(Color.TRANSPARENT)
            it.hint = ScreenStrings.prompt
            it.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            it.minLines = 1; it.maxLines = 2
            it.setPadding(dp(4), dp(3), dp(4), dp(3))
            it.setText(draft)
            it.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) enableKeyboard()
                false
            }
            val rowHeight = maxOf(dp(48), it.lineHeight * 2 + dp(8))
            inputHeight = rowHeight + dp(36)
            row.addView(it, LinearLayout.LayoutParams(0, rowHeight, 1f))
        }
        send = button(ScreenStrings.send, ScreenStrings.send) { sendOrStop() }.also {
            row.addView(it, LinearLayout.LayoutParams(dp(48), ViewGroup.LayoutParams.MATCH_PARENT))
        }
        input.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, inputHeight - dp(36)))
        val actions = LinearLayout(context)
        actions.addView(button("聊", "聊天回复助手：选择联系人、隐私授权、回复建议或自动回复") {
            hideKeyboard(); dev.wind.agent.phone.ChatAssistActivity.open(context, selectedChat()?.id)
        }, LinearLayout.LayoutParams(0, dp(22), 1f))
        actions.addView(button("↗", ScreenStrings.expand) { openApp() }, LinearLayout.LayoutParams(0, dp(22), 1f))
        actions.addView(button("◉", ScreenStrings.settings) { openControls() }, LinearLayout.LayoutParams(0, dp(22), 1f))
        actions.addView(button("≡", ScreenStrings.history) { openHistory() }, LinearLayout.LayoutParams(0, dp(22), 1f))
        actions.addView(button("♡", ScreenStrings.companionTitle) { openCompanion() }, LinearLayout.LayoutParams(0, dp(22), 1f))
        actions.addView(button("◎", dev.wind.agent.webprovider.WebStrings.t(context, "Models / webpage", "模型 / 原网页")) {
            hideKeyboard()
            try { dev.wind.agent.modelprovider.ModelProvidersActivity.open(context, selectedChat()?.id) }
            catch (_: Exception) { Toast.makeText(context, "MODEL_UI_UNAVAILABLE", Toast.LENGTH_LONG).show() }
        }, LinearLayout.LayoutParams(0, dp(22), 1f))
        actions.addView(button("×", ScreenStrings.bubbleClose) { closeInput() }, LinearLayout.LayoutParams(0, dp(22), 1f))
        actions.addView(button("T", dev.wind.agent.webprovider.WebStrings.t(context, "Read visible screen text", "读取当前屏幕文字")) {
            val current = selectedChat()
            if (current?.webBinding == null) {
                Toast.makeText(context, "SELECT_WEB_PROVIDER_FIRST", Toast.LENGTH_LONG).show()
            } else try {
                val visibleText = dev.wind.agent.webprovider.WebScreenTextService.capture()
                dev.wind.agent.webprovider.WebContextInbox.add(current.id,
                    dev.wind.agent.webprovider.WebContextItem("screen_text", "Visible screen text", visibleText))
                Toast.makeText(context, dev.wind.agent.webprovider.WebStrings.t(context, "Screen text added to the next send", "已加入下一条问题的待发上下文"), Toast.LENGTH_LONG).show()
            } catch (failure: Exception) {
                Toast.makeText(context, failure.message ?: "SCREEN_TEXT_UNAVAILABLE", Toast.LENGTH_LONG).show()
                if (failure.message == "ENABLE_READ_ONLY_SCREEN_TEXT_SERVICE") try {
                    context.startActivity(android.content.Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                } catch (_: Exception) {}
            }
        }, LinearLayout.LayoutParams(0, dp(22), 1f))
        input.addView(actions)
        composer = attach(input, dp(284), inputHeight)
        input.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { closeInput(); true } else false
        }
        input.setOnApplyWindowInsetsListener { _, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val next = if (insets.isVisible(WindowInsets.Type.ime())) insets.getInsets(WindowInsets.Type.ime()).bottom else 0
                if (next != keyboardHeight) { keyboardHeight = next; main.post { fitWindows() } }
            }
            insets
        }
        if (Build.VERSION.SDK_INT < 30) {
            input.viewTreeObserver.addOnGlobalLayoutListener {
                if (destroyed || !inputVisible) return@addOnGlobalLayoutListener
                val visible = Rect()
                input.getWindowVisibleDisplayFrame(visible)
                val metrics = android.util.DisplayMetrics()
                @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(metrics)
                val occluded = (metrics.heightPixels - visible.bottom).coerceAtLeast(0)
                val next = if (occluded > dp(120)) occluded else 0
                if (next != keyboardHeight) { keyboardHeight = next; main.post { fitWindows() } }
            }
        }

        val bubble = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = readablePlate()
            setPadding(dp(8), dp(4), dp(8), dp(6))
        }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        val title = text("Vega", 11f).apply {
            setSingleLine(true); ellipsize = TextUtils.TruncateAt.END
            contentDescription = ScreenStrings.dragBubble
        }
        installDrag(title)
        top.addView(title, LinearLayout.LayoutParams(0, dp(26), 1f))
        playback = button("Ⅱ", ScreenStrings.scrollPause) { togglePlayback() }.also {
            top.addView(it, LinearLayout.LayoutParams(dp(32), dp(26)))
        }
        top.addView(button("≡", ScreenStrings.history) { openHistory() }, LinearLayout.LayoutParams(dp(32), dp(26)))
        top.addView(button("↻", ScreenStrings.latestAnswer) { showLatestAnswer() }, LinearLayout.LayoutParams(dp(32), dp(26)))
        top.addView(button("↗", ScreenStrings.expand) { openApp() }, LinearLayout.LayoutParams(dp(32), dp(26)))
        top.addView(button("×", ScreenStrings.bubbleClose) { dismissReply() }, LinearLayout.LayoutParams(dp(32), dp(26)))
        bubble.addView(top)
        replyQuestion = text("", 11f).apply {
            setTextColor(Color.rgb(184, 218, 235))
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            setPadding(0, 0, 0, dp(4))
        }.also { bubble.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)) }
        replyText = text("", 14f).apply {
            setLineSpacing(dp(3).toFloat(), 1f)
            setPadding(0, dp(3), 0, dp(3))
        }
        replyScroll = ScrollView(context).also {
            it.isFillViewport = false
            replyBody = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(replyText)
            }
            it.addView(replyBody, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            it.setOnTouchListener { _, event ->
                if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                    autoPaused = true; userPaused = true; refreshPlayback()
                }
                false
            }
            bubble.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, replyBodyHeight))
        }
        reply = attach(bubble, dp(244), replyBodyHeight + dp(36))
        bubble.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { dismissReply(); true } else false
        }
        createHistoryWindow()
        createApprovalWindow()
        createCompanionWindow()
        createSuggestionWindow()
        val current = selectedChat()
        currentChatId = current?.id ?: ""
        replyFloor = current?.let { synchronized(it.messages) {
            if (AgentBus.isBusy()) (it.messages.indexOfLast { turn -> turn.role == "user" } + 1).coerceAtLeast(0)
            else it.messages.size
        } } ?: 0
        fitWindows()
    }

    private fun createSuggestionWindow() {
        val plate = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; background = readablePlate(); setPadding(dp(6), dp(4), dp(6), dp(4)) }
        val top = LinearLayout(context)
        top.addView(text("建议回复 · 点击仅填字", 11f), LinearLayout.LayoutParams(0, dp(28), 1f))
        top.addView(button("↕", "展开或收起完整建议") { suggestionExpanded = !suggestionExpanded; fitWindows() }, LinearLayout.LayoutParams(dp(30), dp(28)))
        top.addView(button("×", "清除当前建议") {
            dev.wind.agent.phone.ChatAssist.dismissSuggestions(context); suggestionKey = ""; refreshSuggestions()
        }, LinearLayout.LayoutParams(dp(30), dp(28)))
        plate.addView(top)
        val scroll = ScrollView(context).also { suggestionScroll = it }
        suggestionList = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(suggestionList)
        plate.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(224)))
        suggestions = attach(plate, dp(280), dp(260)).also { it.layout.flags = it.layout.flags or WindowManager.LayoutParams.FLAG_SECURE; update(it) }
    }
    private fun refreshSuggestions() {
        val data = dev.wind.agent.phone.ChatAssist.suggestions(context)
        val key = data.toString()
        suggestionsWanted = data.optString("chat_id") == selectedChat()?.id && data.optJSONArray("replies")?.length() == 3
        if (suggestionKey != key) {
            suggestionKey = key; suggestionList?.removeAllViews()
            if (suggestionsWanted) {
                val choices = data.getJSONArray("replies")
                for (i in 0 until choices.length()) {
                    val choice = choices.getJSONObject(i)
                    val card = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL; setPadding(0, dp(6), 0, dp(8)) }
                    val body = text(choice.getString("text"), 14f).apply {
                        setSingleLine(false); maxLines = Int.MAX_VALUE; ellipsize = null
                        setTextIsSelectable(true); setLineSpacing(dp(3).toFloat(), 1f)
                        contentDescription = choice.getString("type") + "回复建议完整正文"
                    }
                    card.addView(body, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                    val actions = LinearLayout(context)
                    actions.addView(button("填入", "仅填入此建议，不发送") {
                        dev.wind.agent.phone.ChatAssist.fill(context, data.getString("event"), i) { message -> Toast.makeText(context, message, Toast.LENGTH_LONG).show() }
                    }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    actions.addView(button("复制", "复制完整建议，供手动粘贴") {
                        try { dev.wind.agent.phone.ChatAssist.copy(context, data.getString("event"), i); Toast.makeText(context, "已复制完整回复，可手动粘贴", Toast.LENGTH_SHORT).show() }
                        catch (failure: Exception) { Toast.makeText(context, failure.message, Toast.LENGTH_LONG).show() }
                    }, LinearLayout.LayoutParams(0, dp(40), 1f))
                    card.addView(actions)
                    suggestionList?.addView(card, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            }
        }
        applyVisibility()
    }
    private fun createApprovalWindow() {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; background = readablePlate()
            setPadding(dp(8), dp(5), dp(8), dp(7))
        }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        approvalTitle = text(Fa.APPROVE_TITLE, 12f).also {
            it.setSingleLine(true); it.ellipsize = TextUtils.TruncateAt.END
            installDrag(it)
            top.addView(it, LinearLayout.LayoutParams(0, dp(32), 1f))
        }
        top.addView(button("≡", ScreenStrings.history) {
            approvalDeferredId = approvalOwner?.id ?: 0L; approvalWanted = false; openHistory()
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        top.addView(button("♡", ScreenStrings.companionTitle) {
            approvalDeferredId = approvalOwner?.id ?: 0L; approvalWanted = false; openCompanion()
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        top.addView(button("×", ScreenStrings.approvalLater) {
            approvalDeferredId = approvalOwner?.id ?: 0L; approvalWanted = false; applyVisibility()
        }, LinearLayout.LayoutParams(dp(32), dp(32)))
        panel.addView(top)
        approvalBody = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        approvalScroll = ScrollView(context).also {
            it.addView(approvalBody, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            panel.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(240)))
        }
        approval = attach(panel, dp(284), dp(284)).also {
            it.layout.flags = it.layout.flags or WindowManager.LayoutParams.FLAG_SECURE
            update(it)
        }
        // Outside touches do not authorize or reject; × explicitly defers the unchanged queue entry.
    }

    private fun refreshApproval() {
        val pending = AgentBus.currentApproval()
        if (pending == null || pending.decided) {
            approvalOwner = null; approvalWanted = false; return
        }
        approvalTitle?.text = "${Fa.APPROVE_TITLE} (${AgentBus.outstandingApprovals()})"
        if (approvalOwner !== pending) {
            approvalOwner = pending; approvalRunId = AgentBus.runId(); approvalDeferredId = 0L
            val body = approvalBody ?: return
            body.removeAllViews()
            val request = pending.capabilityRequest
            val heading = listOf(pending.agent, Tools.actionLabel(pending.tool), pending.tool,
                Fa.riskLabel(dev.wind.agent.security.PolicyEngine.classifyRisk(pending.tool, pending.args ?: org.json.JSONObject())))
                .filter { it.isNotBlank() }.joinToString("\n")
            body.addView(text(heading, 13f).apply { setPadding(0, dp(4), 0, dp(8)) })
            val summary = request?.summary ?: pending.args?.optString("_capability_summary", "").orEmpty()
            if (summary.isNotBlank()) body.addView(text(summary, 12f).apply { setPadding(0, 0, 0, dp(8)) })
            if (pending.tool == Tools.ToolNames.EDIT_FILE || pending.tool == Tools.ToolNames.WRITE_FILE) {
                val payload = pending.args ?: org.json.JSONObject()
                body.addView(text(payload.optString("path", ""), 12f))
                val edits = payload.optJSONArray("edits")
                if (pending.tool == Tools.ToolNames.EDIT_FILE && edits != null && edits.length() > 0) {
                    for (index in 0 until edits.length()) {
                        val edit = edits.optJSONObject(index) ?: continue
                        body.addView(dev.wind.agent.MarkdownRenderer.buildDiffCard(context,
                            edit.optString("old_string", ""), edit.optString("new_string", ""), false))
                    }
                } else body.addView(dev.wind.agent.MarkdownRenderer.buildDiffCard(context,
                    payload.optString("old_string", ""),
                    payload.optString(if (pending.tool == Tools.ToolNames.WRITE_FILE) "content" else "new_string", ""),
                    pending.tool == Tools.ToolNames.WRITE_FILE))
            }
            // Preserve complete proposed arguments/diff/command. Details can scroll instead of silently truncating authority.
            body.addView(text(pending.args?.toString(2) ?: "{}", 12f).apply {
                setPadding(dp(3), dp(5), dp(3), dp(10))
            })
            if (request != null) {
                body.addView(text(Fa.CAP_SCOPE_NOTE, 11f).apply { setPadding(0, 0, 0, dp(6)) })
                listOf(GrantScope.ONCE to Fa.CAP_ALLOW_ONCE, GrantScope.TASK to Fa.CAP_ALLOW_TASK,
                    GrantScope.APP_SESSION to Fa.CAP_ALLOW_SESSION).forEach { (scope, label) ->
                    if (scope in request.allowedScopes) body.addView(consentButton(label) {
                        decideApproval(pending, CapabilityDecision.Allow(scope))
                    }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
                }
            } else body.addView(consentButton(if (pending.tool.startsWith("phone_") && pending.args?.has("authorization_scope") == true)
                if (Fa.chinese) "允许本任务内类似操作" else "Allow similar actions in this task" else Fa.APPROVE_RUN) {
                decideApproval(pending, CapabilityDecision.Allow(GrantScope.ONCE))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            body.addView(consentButton(Fa.APPROVE_REJECT) { decideApproval(pending, CapabilityDecision.Deny) },
                LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            body.addView(text(ScreenStrings.approvalLaterHint, 11f).apply { setPadding(0, dp(6), 0, dp(4)) })
            approvalScroll?.scrollTo(0, 0)
        }
        approvalWanted = approvalDeferredId != pending.id
        if (approvalWanted && !isAppVisible() && !hiddenForCapture && field?.hasFocus() == true) hideKeyboard()
    }

    private fun decideApproval(owner: AgentBus.PendingApproval, decision: CapabilityDecision) {
        if (destroyed || owner.decided || approvalRunId != AgentBus.runId() || AgentBus.currentApproval() !== owner) return
        if (decision is CapabilityDecision.Allow && owner.capabilityRequest?.allowedScopes?.contains(decision.scope) == false) return
        if (owner.capabilityRequest != null) owner.decideCapability(decision)
        else owner.decide(decision is CapabilityDecision.Allow)
        refreshApproval(); refreshStatus(); fitWindows(); applyVisibility()
    }

    private fun createCompanionWindow() {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; background = readablePlate()
            setPadding(dp(8), dp(4), dp(8), dp(6))
        }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        companionTitle = text(ScreenStrings.companionTitle, 12f).also {
            it.setSingleLine(true); it.ellipsize = TextUtils.TruncateAt.END; installDrag(it)
            top.addView(it, LinearLayout.LayoutParams(0, dp(30), 1f))
        }
        top.addView(button("×", ScreenStrings.bubbleClose) { companionVisible = false; applyVisibility() }, LinearLayout.LayoutParams(dp(32), dp(30)))
        panel.addView(top)
        val options = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        options.addView(button(ScreenStrings.phoneTools, ScreenStrings.phoneTools) {
            companionVisible = false; applyVisibility()
            dev.wind.agent.phone.PhoneControlsActivity.open(context)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        options.addView(text(ScreenStrings.appearance, 12f))
        PetAppearance.Character.values().forEach { character ->
            options.addView(button(character.label, character.label) {
                if (cat?.setAppearance(character) == true) {
                    PetAppearance.choose(context, character)
                    companionVisible = false; refreshStatus()
                } else Toast.makeText(context, ScreenStrings.appearanceFailed, Toast.LENGTH_SHORT).show()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        }
        options.addView(text(ScreenStrings.companionPrivacy, 12f).apply { setPadding(0, dp(6), 0, dp(8)) })
        options.addView(consentButton(ScreenStrings.companionAutomatic) {
            PetCompanion.choose("auto")
            if (PetCompanion.monitorConnected) {
                PetCompanionService.requestCurrentHint(); companionVisible = false; refreshStatus()
            } else {
                try { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                catch (error: Exception) { Toast.makeText(context, error.message, Toast.LENGTH_LONG).show() }
            }
            applyVisibility()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        options.addView(button(ScreenStrings.companionOff, ScreenStrings.companionOff) {
            PetCompanion.choose("off"); companionVisible = false; refreshStatus()
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        PetCompanion.states.forEach { state ->
            val label = PetActivity.label(PetActivity.Snapshot(0L, state))
            options.addView(button(label, label) {
                PetCompanion.choose(state.name); companionVisible = false; refreshStatus()
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(38)))
        }
        companionScroll = ScrollView(context).also {
            it.addView(options); panel.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(210)))
        }
        companion = attach(panel, dp(260), dp(250))
    }

    private fun openCompanion() {
        if (inputVisible) closeInput()
        historyVisible = false; companionVisible = true
        companionScroll?.scrollTo(0, 0)
        fitWindows(); applyVisibility()
    }

    private fun createHistoryWindow() {
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = readablePlate()
            setPadding(dp(8), dp(4), dp(8), dp(6))
        }
        val top = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        historyTitle = text(ScreenStrings.history, 12f).also {
            it.setSingleLine(true); it.ellipsize = TextUtils.TruncateAt.END
            installDrag(it)
            top.addView(it, LinearLayout.LayoutParams(0, dp(30), 1f))
        }
        top.addView(button("↻", ScreenStrings.latestAnswer) { showLatestAnswer() }, LinearLayout.LayoutParams(dp(32), dp(30)))
        top.addView(button("×", ScreenStrings.bubbleClose) { closeHistory() }, LinearLayout.LayoutParams(dp(32), dp(30)))
        panel.addView(top)
        historyItems = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        historyScroll = ScrollView(context).also {
            it.addView(historyItems, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            panel.addView(it, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(180)))
        }
        history = attach(panel, dp(260), dp(220))
        panel.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_OUTSIDE) { closeHistory(); true } else false
        }
    }

    private data class QuestionTurn(val question: Message, val answers: List<Message>)

    /** A question owns all following final assistant replies, up to (but excluding) the next question. */
    private fun questionTurns(turns: List<Message>): List<QuestionTurn> {
        val result = ArrayList<QuestionTurn>()
        var question: Message? = null
        var answers = ArrayList<Message>()
        for (turn in turns) {
            if (turn.role == "user") {
                question?.let { result.add(QuestionTurn(it, answers.toList())) }
                question = turn; answers = ArrayList()
            } else if (question != null && turn.role == "assistant" && !turn.isStep && turn.content.isNotBlank()) {
                answers.add(turn)
            }
        }
        question?.let { result.add(QuestionTurn(it, answers.toList())) }
        return result
    }

    private fun openHistory() {
        if (destroyed) return
        if (inputVisible) closeInput()
        companionVisible = false
        historyVisible = true
        refreshHistory(force = true)
        fitWindows(); applyVisibility()
    }

    private fun closeHistory() { historyVisible = false; applyVisibility() }

    private fun refreshHistory(force: Boolean = false) {
        val chat = selectedChat() ?: return
        val turns = synchronized(chat.messages) { chat.messages.toList() }
        val questions = questionTurns(turns)
        val keys = questions.map { it.question to it.question.content }
        if (!force && chat.id == historyChatId && keys == historyKeys) return
        historyChatId = chat.id
        historyKeys = keys
        val container = historyItems ?: return
        container.removeAllViews()
        historyTitle?.text = "${ScreenStrings.history} (${questions.size})"
        if (questions.isEmpty()) {
            container.addView(text(ScreenStrings.noHistory, 13f).apply { setPadding(dp(4), dp(12), dp(4), dp(12)) })
        } else questions.asReversed().forEachIndexed { index, entry ->
            val label = entry.question.content.trim().ifEmpty { ScreenStrings.attachmentQuestion }
            val row = text("${questions.size - index}. $label", 13f).apply {
                maxLines = 3; ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(4), dp(10), dp(4), dp(10))
                contentDescription = label
                setOnClickListener { selectHistoryQuestion(entry.question) }
            }
            container.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        if (force) historyScroll?.scrollTo(0, 0)
    }

    private fun selectHistoryQuestion(question: Message) {
        selectedQuestion = question; selectedQuestionDismissed = false
        selectedQuestionIndex = selectedChat()?.let { chat -> synchronized(chat.messages) { chat.messages.indexOf(question) } } ?: -1
        currentReply = null; dismissedReply = null
        autoPaused = true; userPaused = true; wasStreaming = false
        historyVisible = false; inputVisible = false
        replyScroll?.scrollTo(0, 0)
        refreshReply(); applyVisibility()
    }

    private fun showLatestAnswer() {
        if (inputVisible) closeInput()
        selectedQuestion = null; selectedQuestionIndex = -1; selectedQuestionDismissed = false
        currentReply = null; dismissedReply = null; historyVisible = false
        autoPaused = false; userPaused = false; wasStreaming = false
        selectedChat()?.let { chat -> synchronized(chat.messages) {
            replyFloor = (chat.messages.indexOfLast { it.role == "user" } + 1).coerceAtLeast(0)
        } }
        replyScroll?.scrollTo(0, 0)
        refreshReply(); applyVisibility()
    }

    fun refreshStatus() {
        if (destroyed) return
        val ready = PetCompanion.shouldObserve() && PetCompanion.monitorConnected
        if (ready && !companionReady) PetCompanionService.requestCurrentHint()
        companionReady = ready
        val activity = PetActivity.current()
        val label = PetActivity.label(activity)
        cat?.syncAppearance()
        cat?.setState(activity.state, label)
        badge?.text = label
        companionTitle?.text = if (PetCompanion.modeName() == "auto" && !PetCompanion.monitorConnected)
            ScreenStrings.companionNeedsPermission else ScreenStrings.companionTitle
        badge?.contentDescription = label + " · " + if (ScreenShareSession.isSharing) ScreenStrings.sharing else ScreenShareSession.status
        send?.text = if (AgentBus.isBusy()) ScreenStrings.stop else ScreenStrings.send
        send?.contentDescription = if (AgentBus.isBusy()) ScreenStrings.stop else ScreenStrings.send
        applyVisibility()
    }

    private fun selectedChat(): Chat? {
        AgentBus.liveChat?.let { if (AgentBus.isBusy()) ScreenShareSession.chat = it }
        return ScreenShareSession.chat
    }

    private fun refreshReply() {
        val current = selectedChat() ?: return
        val turns = synchronized(current.messages) { current.messages.toList() }
        val run = AgentBus.runId()
        if (current.id != currentChatId) {
            currentChatId = current.id; currentReply = null; dismissedReply = null
            selectedQuestion = null; selectedQuestionIndex = -1; selectedQuestionDismissed = false; historyKeys = emptyList()
            replyFloor = if (AgentBus.isBusy()) (turns.indexOfLast { it.role == "user" } + 1).coerceAtLeast(0) else turns.size
            replyWanted = false
        }
        if (AgentBus.isBusy() && run != observedRun) {
            observedRun = run
            replyFloor = (turns.indexOfLast { it.role == "user" } + 1).coerceAtLeast(0)
            if (selectedQuestion == null) { currentReply = null; dismissedReply = null; replyWanted = false }
        }
        val grouped = questionTurns(turns)
        // The app/service may reload this same chat into new Message instances.
        // Preserve the selected question by its original turn position, including duplicate question texts.
        selectedQuestion?.let { previous ->
            if (turns.none { it === previous }) {
                val replacement = turns.getOrNull(selectedQuestionIndex)
                if (replacement?.role == "user" && replacement.content == previous.content) selectedQuestion = replacement
            }
        }
        val historical = selectedQuestion?.let { wanted -> grouped.firstOrNull { it.question === wanted } }
        if (selectedQuestion != null && historical == null) {
            selectedQuestion = null; selectedQuestionIndex = -1; selectedQuestionDismissed = false
        }
        if (historical != null && selectedQuestionDismissed) {
            replyWanted = false; applyVisibility(); return
        }
        val answer = if (historical != null) historical.answers.lastOrNull()
            else turns.drop(replyFloor).lastOrNull { it.role == "assistant" && !it.isStep && it.content.isNotEmpty() }
        if (answer == null && historical == null) {
            if (replyWanted) { replyWanted = false; applyVisibility() }
            return
        }
        val question = historical?.question ?: grouped.lastOrNull { group -> group.answers.any { it === answer } }?.question
        replyQuestion?.text = question?.content?.trim()?.ifEmpty { ScreenStrings.attachmentQuestion } ?: ""
        val fresh = currentReply !== answer
        if (fresh) {
            currentReply = answer
            autoPaused = historical != null; userPaused = historical != null; wasStreaming = false
            replyScroll?.scrollTo(0, 0)
        }
        if (answer != null && answer === dismissedReply) return
        val viewport = replyScroll
        val nearBottom = viewport == null || viewport.scrollY + viewport.height >=
            (replyBody?.height ?: 0) - dp(24)
        val content = if (historical != null) historical.answers.joinToString("\n\n") { it.content }
            .ifEmpty { ScreenStrings.noAnswer } else answer?.content ?: ScreenStrings.noAnswer
        val streaming = answer?.streaming == true || (historical == null && AgentBus.isBusy())
        val changed = renderedReply != content || renderedReplyStreaming != streaming
        if (changed) {
            replyBody?.let { body ->
                if (streaming) {
                    body.setBackgroundColor(Color.TRANSPARENT)
                    body.removeAllViews()
                    replyText?.text = content
                    body.addView(replyText)
                } else {
                    body.setBackgroundColor(dev.wind.agent.Theme.BG)
                    dev.wind.agent.MarkdownRenderer.render(context, body, content)
                }
            }
            renderedReply = content; renderedReplyStreaming = streaming
        }
        replyWanted = true
        resizeReply()
        if (streaming && !autoPaused && (fresh || nearBottom) && changed) {
            viewport?.post { if (!userPaused && currentReply === answer) viewport.fullScroll(View.FOCUS_DOWN) }
        }
        if (wasStreaming && !streaming && !userPaused) {
            viewport?.post { if (!userPaused && currentReply === answer) viewport.scrollTo(0, 0) }
        }
        wasStreaming = streaming
        refreshPlayback()
        fitWindows()
        applyVisibility()
    }

    private fun resizeReply() {
        val body = replyBody ?: return
        val available = availableBounds()
        val width = minOf(dp(244), available.width() - dp(12)).coerceAtLeast(dp(140))
        body.measure(View.MeasureSpec.makeMeasureSpec((width - dp(16)).coerceAtLeast(1), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
        replyQuestion?.let {
            it.measure(View.MeasureSpec.makeMeasureSpec((width - dp(16)).coerceAtLeast(1), View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED))
            replyOverhead = dp(36) + it.measuredHeight
        }
        replyBodyHeight = body.measuredHeight.coerceIn(dp(30),
            minOf(dp(136), (available.height() * 0.27f).toInt()).coerceAtLeast(dp(30)))
        replyScroll?.layoutParams?.let {
            if (it.height != replyBodyHeight) { it.height = replyBodyHeight; replyScroll?.layoutParams = it }
        }
    }

    private fun refreshPlayback() {
        playback?.text = if (autoPaused) "▶" else "Ⅱ"
        playback?.contentDescription = if (autoPaused) ScreenStrings.scrollResume else ScreenStrings.scrollPause
    }

    private fun togglePlayback() {
        autoPaused = !autoPaused
        userPaused = autoPaused
        if (!autoPaused) {
            val viewport = replyScroll
            val max = ((viewport?.getChildAt(0)?.height ?: 0) - (viewport?.height ?: 0)).coerceAtLeast(0)
            if (viewport != null && viewport.scrollY >= max) viewport.scrollTo(0, 0)
        }
        refreshPlayback()
    }

    private fun showInput() {
        // A pending grant can be decided here without opening the app's Activity.
        if (AgentBus.currentApproval() != null) {
            approvalDeferredId = 0L; refreshApproval(); fitWindows(); applyVisibility(); return
        }
        historyVisible = false; companionVisible = false
        if (inputVisible) { applyVisibility(); enableKeyboard(); return }
        inputVisible = true
        replyWanted = false
        field?.setText(draft)
        refreshStatus(); fitWindows(); applyVisibility()
        main.post { if (inputVisible && !hiddenForCapture) enableKeyboard() }
    }

    private fun closeInput() {
        draft = field?.text?.toString() ?: draft
        hideKeyboard(); inputVisible = false
        applyVisibility()
    }

    private fun dismissReply() {
        if (selectedQuestion != null) selectedQuestionDismissed = true
        dismissedReply = currentReply
        replyWanted = false
        applyVisibility()
    }

    private fun sendOrStop() {
        val current = selectedChat() ?: return
        AgentBus.selectChat(current.id)
        if (AgentBus.isRunningFor(current.id)) { AgentBus.requestCancel(true); refreshStatus(); return }
        val content = field?.text?.toString()?.trim() ?: ""
        if (content.isEmpty()) return
        if (!dev.wind.agent.webprovider.WebModelRouting.configured(context, current, prefs)) {
            Toast.makeText(context, ScreenStrings.configure, Toast.LENGTH_LONG).show()
            openApp(); return
        }
        val runId = AgentBus.beginStarting(current.id, current)
        if (runId == 0L) return
        val message = Message("user", content)
        if (current.webBinding != null) dev.wind.agent.webprovider.WebContextInbox.consume(current.id, message)
        ScreenShareSession.bind(message, fromPet = true)
        synchronized(current.messages) { current.messages.add(message); replyFloor = current.messages.size }
        if (Fa.isPlaceholderTitle(current.title)) current.autoTitle()
        prefs.setLastChatId(current.id) // Initial message persistence runs on AgentService's worker.
        field?.setText(""); draft = ""
        selectedQuestion = null; selectedQuestionIndex = -1; selectedQuestionDismissed = false; historyVisible = false
        currentReply = null; dismissedReply = null; replyWanted = false
        hideKeyboard(); inputVisible = false
        refreshStatus(); applyVisibility()
        try {
            AgentService.start(context, current.id, runId)
            AgentBus.watchdog.postDelayed({
                if (AgentBus.state(runId) == AgentBus.RunState.STARTING) {
                    PetActivity.update(runId, PetActivity.State.ERROR)
                    AgentBus.requestCancel(runId); AgentBus.finish(runId)
                    val failure = Message("assistant", ScreenStrings.serviceFailed).also { it.isError = true }
                    synchronized(current.messages) { current.messages.add(failure) }
                    store.save(current)
                }
            }, 12000L)
        } catch (error: Exception) {
            PetActivity.update(runId, PetActivity.State.ERROR)
            AgentBus.finish(runId)
            val failure = Message("assistant", error.message ?: ScreenStrings.serviceFailed).also { it.isError = true }
            synchronized(current.messages) { current.messages.add(failure) }
            store.save(current)
            Toast.makeText(context, failure.content, Toast.LENGTH_LONG).show()
        }
    }

    private fun openApp() {
        hideKeyboard()
        selectedChat()?.let { prefs.setLastChatId(it.id) }
        try {
            // Keep a visible overlay until the Activity starts, for the Android 15 launch exemption.
            context.startActivity(Intent(context, MainActivity::class.java)
                .putExtra("floatingChatId", selectedChat()?.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        } catch (error: Exception) { Toast.makeText(context, error.message, Toast.LENGTH_LONG).show() }
    }

    private fun openControls() {
        hideKeyboard()
        try { ScreenSharePermissionActivity.open(context, selectedChat()?.id) }
        catch (error: Exception) { Toast.makeText(context, error.message, Toast.LENGTH_LONG).show() }
    }

    private fun enableKeyboard() {
        if (!inputVisible || hiddenForCapture || isAppVisible() || approvalWanted || companionVisible) return
        val owner = composer ?: return
        owner.layout.flags = owner.layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
        update(owner)
        field?.post {
            if (!inputVisible || hiddenForCapture || isAppVisible() || approvalWanted || companionVisible) return@post
            field?.requestFocus()
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).showSoftInput(field, InputMethodManager.SHOW_IMPLICIT)
        }
    }

    private fun hideKeyboard() {
        field?.let {
            (context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(it.windowToken, 0)
            it.clearFocus()
        }
        composer?.let {
            it.layout.flags = it.layout.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            update(it)
        }
        keyboardHeight = 0
    }

    fun hideForCapture(hideInputKeyboard: Boolean = true) { hiddenForCapture = true; if (hideInputKeyboard) hideKeyboard(); applyVisibility() }
    fun restoreAfterCapture() { hiddenForCapture = false; applyVisibility(); fitWindows() }
    fun hideForPhoneAction() { hiddenForPhoneAction = true; applyVisibility() }
    fun restoreAfterPhoneAction() { hiddenForPhoneAction = false; applyVisibility(); fitWindows() }
    fun setMainVisible(visible: Boolean) { ScreenShareSession.mainVisible = visible; applyVisibility() }
    private fun isAppVisible() = ScreenShareSession.mainVisible || ScreenShareSession.controlsVisible || ScreenShareSession.appActivitiesVisible

    private fun applyVisibility() {
        // Keep the pet visible while composing, sending and capturing, as requested.
        val hidePet = isAppVisible() || destroyed || hiddenForPhoneAction
        val hide = hiddenForCapture || hidePet
        pet?.view?.visibility = if (!hidePet) View.VISIBLE else View.INVISIBLE
        val textHidden = hide || approvalWanted
        composer?.view?.visibility = if (!textHidden && inputVisible && !companionVisible) View.VISIBLE else View.INVISIBLE
        reply?.view?.visibility = if (!textHidden && !inputVisible && !historyVisible && !companionVisible && replyWanted && !suggestionsWanted) View.VISIBLE else View.INVISIBLE
        suggestions?.view?.visibility = if (!textHidden && !inputVisible && !historyVisible && !companionVisible && suggestionsWanted) View.VISIBLE else View.INVISIBLE
        history?.view?.visibility = if (!textHidden && !inputVisible && historyVisible && !companionVisible) View.VISIBLE else View.INVISIBLE
        companion?.view?.visibility = if (!textHidden && companionVisible) View.VISIBLE else View.INVISIBLE
        approval?.view?.visibility = if (!hide && approvalWanted) View.VISIBLE else View.INVISIBLE
        if (hide && field?.hasFocus() == true) hideKeyboard()
    }

    private fun availableBounds(): Rect {
        if (Build.VERSION.SDK_INT >= 30) {
            val metrics = wm.currentWindowMetrics
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            return Rect(0, 0, (metrics.bounds.width() - bars.left - bars.right).coerceAtLeast(1),
                (metrics.bounds.height() - bars.top - maxOf(bars.bottom, keyboardHeight)).coerceAtLeast(1))
        }
        val metrics = android.util.DisplayMetrics()
        @Suppress("DEPRECATION") wm.defaultDisplay.getMetrics(metrics)
        return Rect(0, 0, metrics.widthPixels, (metrics.heightPixels - keyboardHeight).coerceAtLeast(1))
    }

    private fun fitWindows() {
        if (destroyed) return
        val bounds = availableBounds()
        val petWidth = minOf(dp(72), bounds.width())
        val petHeight = minOf(dp(92), bounds.height())
        val left = x.coerceIn(0, (bounds.width() - petWidth).coerceAtLeast(0))
        val top = y.coerceIn(0, (bounds.height() - petHeight).coerceAtLeast(0))
        pet?.let { place(it, left, top, petWidth, petHeight) }
        composer?.let {
            val width = minOf(dp(284), (bounds.width() - dp(12)).coerceAtLeast(1))
            val height = minOf(inputHeight, bounds.height())
            val anchor = bubbleAnchor(left, top, petWidth, petHeight, width, height, bounds)
            place(it, anchor[0], anchor[1], width, height)
        }
        reply?.let {
            val width = minOf(dp(244), (bounds.width() - dp(12)).coerceAtLeast(1))
            val height = minOf(replyBodyHeight + replyOverhead, bounds.height())
            val anchor = bubbleAnchor(left, top, petWidth, petHeight, width, height, bounds)
            place(it, anchor[0], anchor[1], width, height)
        }
        suggestions?.let {
            val width = minOf(dp(if (suggestionExpanded) 320 else 280), bounds.width())
            val height = minOf(dp(if (suggestionExpanded) 550 else 260), (bounds.height() * if (suggestionExpanded) .65f else .48f).toInt()).coerceAtLeast(1)
            suggestionScroll?.layoutParams?.let { params -> params.height = (height - dp(36)).coerceAtLeast(1); suggestionScroll?.layoutParams = params }
            val anchor = bubbleAnchor(left, top, petWidth, petHeight, width, height, bounds)
            place(it, anchor[0], anchor[1], width, height)
        }
        history?.let {
            val width = minOf(dp(260), (bounds.width() - dp(12)).coerceAtLeast(1))
            val height = minOf(dp(240), (bounds.height() * 0.45f).toInt()).coerceAtLeast(1)
            val scrollHeight = (height - dp(40)).coerceAtLeast(1)
            historyScroll?.layoutParams?.let { params ->
                if (params.height != scrollHeight) { params.height = scrollHeight; historyScroll?.layoutParams = params }
            }
            val anchor = bubbleAnchor(left, top, petWidth, petHeight, width, height, bounds)
            place(it, anchor[0], anchor[1], width, height)
        }
        listOf(approval to approvalScroll, companion to companionScroll).forEach { (owner, scroll) ->
            owner?.let {
                val isApproval = it === approval
                val width = minOf(dp(if (isApproval) 284 else 260), (bounds.width() - dp(12)).coerceAtLeast(1))
                val height = minOf(dp(if (isApproval) 420 else 280), (bounds.height() * (if (isApproval) 0.68f else 0.55f)).toInt()).coerceAtLeast(1)
                scroll?.let { scroller -> scroller.layoutParams?.let { params ->
                    val next = (height - dp(if (isApproval) 44 else 40)).coerceAtLeast(1)
                    if (params.height != next) { params.height = next; scroller.layoutParams = params }
                } }
                val anchor = bubbleAnchor(left, top, petWidth, petHeight, width, height, bounds)
                place(it, anchor[0], anchor[1], width, height)
            }
        }
    }

    private fun bubbleAnchor(left: Int, top: Int, petWidth: Int, petHeight: Int, width: Int, height: Int, bounds: Rect): IntArray {
        val rightRoom = bounds.width() - left - petWidth - dp(6)
        val bubbleX = if (rightRoom >= width) left + petWidth + dp(6)
            else if (left - dp(6) >= width) left - width - dp(6)
            else (bounds.width() - width) / 2
        val bubbleY = if (top >= height + dp(6)) top - height - dp(6)
            else top + petHeight + dp(6)
        return intArrayOf(bubbleX.coerceIn(0, (bounds.width() - width).coerceAtLeast(0)),
            bubbleY.coerceIn(0, (bounds.height() - height).coerceAtLeast(0)))
    }

    private fun place(owner: Overlay, left: Int, top: Int, width: Int, height: Int) {
        val layout = owner.layout
        if (layout.x == left && layout.y == top && layout.width == width && layout.height == height) return
        layout.x = left; layout.y = top; layout.width = width; layout.height = height
        update(owner)
    }

    private fun update(owner: Overlay) {
        if (destroyed) return
        try { wm.updateViewLayout(owner.view, owner.layout) }
        catch (_: Exception) { onClose() }
    }

    private fun installPetGestures(handle: View) {
        val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(event: MotionEvent): Boolean = true
            override fun onSingleTapConfirmed(event: MotionEvent): Boolean { showInput(); return true }
            override fun onDoubleTap(event: MotionEvent): Boolean { cat?.interact(); return true }
            override fun onLongPress(event: MotionEvent) { openCompanion() }
        })
        installDrag(handle, detector)
    }

    private fun installDrag(handle: View, detector: GestureDetector? = null) {
        var downX = 0f; var downY = 0f; var startX = 0; var startY = 0; var moved = false
        handle.setOnTouchListener { _, event ->
            detector?.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = event.rawX; downY = event.rawY
                    startX = pet?.layout?.x ?: x; startY = pet?.layout?.y ?: y; moved = false
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.rawX - downX; val dy = event.rawY - downY
                    if (kotlin.math.abs(dx) + kotlin.math.abs(dy) > dp(6)) moved = true
                    if (moved) { x = startX + dx.toInt(); y = startY + dy.toInt(); fitWindows() }
                }
                MotionEvent.ACTION_UP -> {
                    x = pet?.layout?.x ?: x; y = pet?.layout?.y ?: y
                    geometry.edit().putInt("x", x).putInt("y", y).apply()
                    if (!moved) handle.performClick()
                }
            }
            true
        }
    }

    fun destroy() {
        if (destroyed) return
        draft = field?.text?.toString() ?: draft
        destroyed = true
        hideKeyboard()
        main.removeCallbacksAndMessages(null)
        cat?.release(); cat = null
        listOf(suggestions, companion, approval, history, reply, composer, pet).forEach { owner ->
            owner?.let { try { wm.removeView(it.view) } catch (_: Exception) { } }
        }
        suggestions = null; suggestionList = null
        companion = null; approval = null; history = null; reply = null; composer = null; pet = null
        approvalOwner = null; PetCompanion.clear()
        historyKeys = emptyList(); historyChatId = ""; selectedQuestion = null
        currentReply = null; dismissedReply = null
    }
}
