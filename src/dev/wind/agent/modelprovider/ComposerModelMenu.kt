package dev.wind.agent.modelprovider

import android.app.AlertDialog
import android.content.Context
import android.graphics.Rect
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.*
import dev.wind.agent.*
import dev.wind.agent.webprovider.*

/** Model switching stays in the composer; account setup remains a separate screen. */
object ComposerModelMenu {
    fun show(context: Context, anchor: View, chat: Chat?, reasoningOnly: Boolean = false, initialQuery: String = "", changed: () -> Unit) {
        if (AgentBus.isBusy()) { toast(context, "请先停止当前任务再切换模型"); return }
        val store = ModelProviderStore(context)
        val web = WebProviderPrefs(context)
        fun dp(n: Float) = Theme.dp(context, n)
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(12f), dp(12f), dp(12f), dp(8f))
            background = Theme.roundStroke(Theme.BG_ELEV, Theme.BORDER, 16f, 1, context)
        }
        fun label(text: String, size: Float = 13f, muted: Boolean = false) = TextView(context).apply {
            this.text = text; textSize = size; setTextColor(if (muted) Theme.TEXT_MUTED else Theme.TEXT)
            typeface = Theme.ui(); setPadding(dp(8f), dp(6f), dp(8f), dp(6f))
        }
        root.addView(label(if (reasoningOnly) "推理强度" else "选择模型", 16f).apply { typeface = Theme.uiSemi() })
        val search = EditText(context).apply {
            hint = "搜索模型或供应商"; textSize = 14f; isSingleLine = true
            setText(initialQuery)
            setTextColor(Theme.TEXT); setHintTextColor(Theme.TEXT_MUTED)
            background = Theme.roundRect(Theme.SURFACE_2, 10f, context)
            setPadding(dp(12f), dp(9f), dp(12f), dp(9f))
        }
        root.addView(search, LinearLayout.LayoutParams(-1, dp(42f)))
        val rows = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(context).apply { isFillViewport = false; addView(rows) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f).apply { topMargin = dp(6f) })
        if (reasoningOnly) { search.visibility = View.GONE; scroll.visibility = View.GONE }
        val reasoningTitle = label("推理强度", 12f, true)
        root.addView(reasoningTitle)
        val efforts = LinearLayout(context)
        root.addView(HorizontalScrollView(context).apply { isHorizontalScrollBarEnabled = false; addView(efforts) })
        val footer = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        root.addView(footer)
        val frame = Rect(); anchor.getWindowVisibleDisplayFrame(frame)
        val width = minOf(dp(368f), (frame.width() - dp(24f)).coerceAtLeast(dp(120f)))
        val height = minOf(dp(520f), (frame.height() - dp(24f)).coerceAtLeast(dp(160f)))
        val popup = PopupWindow(root, width, if (reasoningOnly) -2 else height, true).apply {
            setBackgroundDrawable(Theme.roundRect(Theme.BG_ELEV, 16f, context))
            elevation = dp(12f).toFloat(); isOutsideTouchable = true
            inputMethodMode = if (reasoningOnly) PopupWindow.INPUT_METHOD_NOT_NEEDED else PopupWindow.INPUT_METHOD_NEEDED
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        fun action(parent: LinearLayout, text: String, selected: Boolean = false, click: () -> Unit) {
            parent.addView(label(text).apply {
                minHeight = dp(44f); gravity = Gravity.CENTER_VERTICAL
                background = Theme.rippleOver(Theme.roundRect(if (selected) Theme.SURFACE_2 else Theme.BG_ELEV, 8f, context), 8f, context)
                if (selected) typeface = Theme.uiSemi()
                setOnClickListener { if (AgentBus.isBusy()) toast(context, "请先停止当前任务") else click() }
            }, LinearLayout.LayoutParams(if (parent.orientation == LinearLayout.VERTICAL) -1 else -2, -2))
        }
        fun save() { chat?.let { ChatStore(context).saveNow(it); dev.wind.agent.screen.ScreenShareSession.chat = it }; changed() }
        lateinit var render: () -> Unit
        fun selectApi(p: ModelProvider, m: DiscoveredModel, effort: String) {
            val apply = {
                if (!AgentBus.isBusy()) try {
                    store.select(p.id, m.id, effort)
                    chat?.webBinding?.let { chat.webBindings[it.providerId] = it }; chat?.webBinding = null
                    web.select(""); save(); render()
                } catch (e: Exception) { toast(context, e.message ?: "模型切换失败") }
            }
            if (chat?.webBinding != null) {
                popup.dismiss()
                AlertDialog.Builder(context).setMessage("切换到 API 后，本地对话历史会作为后续请求的上下文发送。继续？")
                    .setPositiveButton("切换") { _, _ -> apply() }.setNegativeButton("取消", null).show()
            } else apply()
        }
        fun selectWeb(p: WebProviderDefinition) {
            popup.dismiss()
            val apply = {
                if (!AgentBus.isBusy()) {
                    web.acknowledge(p.id); web.setEnabled(true); web.disable(p.id, false); web.markRecent(p.id)
                    if (chat == null) web.select(p.id) else {
                        chat.webBinding?.let { chat.webBindings[it.providerId] = it }
                        val binding = chat.webBindings[p.id]?.takeIf { it.profileName == web.profile(p.id) }
                            ?: WebConversationBinding(p.id, web.profile(p.id))
                        binding.includeHistory = false; chat.webBinding = binding; chat.webBindings[p.id] = binding
                    }
                    save()
                    WebProviderBrowserActivity.open(context, p.id, chat?.id)
                }
            }
            if (!web.acknowledged(p.id)) AlertDialog.Builder(context).setTitle(p.name + " · 实验性网页模型")
                .setMessage(WebStrings.risk(context) + "\n\n" + p.note)
                .setPositiveButton("了解并使用") { _, _ -> apply() }.setNegativeButton("取消", null).show()
            else apply()
        }
        render = {
            val y = scroll.scrollY; rows.removeAllViews(); efforts.removeAllViews()
            val active = store.active(); val query = search.text.toString().trim()
            rows.addView(label("API / 订阅账户", 12f, true))
            var found = 0
            for (p in store.all()) {
                val models = ComposerModelChoices.filter(p, query)
                if (models.isEmpty()) continue
                rows.addView(label(p.label, 12f, true))
                for (m in models) {
                    found++
                    val selected = chat?.webBinding == null && active?.id == p.id && active.model == m.id
                    val row = LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL; setPadding(dp(8f), dp(7f), dp(8f), dp(7f))
                        background = Theme.rippleOver(Theme.roundRect(if (selected) Theme.SURFACE_2 else Theme.BG_ELEV, 10f, context), 10f, context)
                        contentDescription = "${p.label}，${m.name}" + if (selected) "，当前模型" else ""
                        setOnClickListener {
                            if (AgentBus.isBusy()) toast(context, "请先停止当前任务")
                            else selectApi(p, m, ComposerModelChoices.effort(p, m))
                        }
                    }
                    row.addView(label((if (selected) "✓  " else "    ") + m.name, 14f).apply { maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END })
                    if (m.name != m.id) row.addView(label(m.id, 11f, true))
                    rows.addView(row, LinearLayout.LayoutParams(-1, -2))
                }
            }
            if (found == 0) rows.addView(label(if (store.hasAccounts()) "没有匹配的模型；可在账户管理中读取模型" else "添加 API Key 或登录订阅账户后显示模型", 12f, true))
            rows.addView(label("实验性网页模型", 12f, true))
            web.ordered(query, false).filter { it.configured && !it.manualOnly && it.id != "fixture" && !web.disabled(it.id) }.forEach { p ->
                action(rows, (if (chat?.webBinding?.providerId == p.id) "✓  " else "    ") + p.name, chat?.webBinding?.providerId == p.id) { selectWeb(p) }
            }
            val selected = active?.selected()
            if (chat?.webBinding != null) {
                reasoningTitle.text = "推理选项由原网页提供"
                action(efforts, "打开网页 / 登录") { popup.dismiss(); WebProviderBrowserActivity.open(context, chat.webBinding!!.providerId, chat.id) }
            } else if (active != null && selected != null && active.model.isNotBlank()) {
                reasoningTitle.text = selected.name + " · 推理强度"
                selected.efforts.forEach { level ->
                    action(efforts, ReasoningCapabilities.label(level), level == active.effort) { selectApi(active, selected, level) }
                }
            } else { reasoningTitle.text = "选择模型后可调整推理强度" }
            scroll.post { scroll.scrollTo(0, y) }
        }
        if (!reasoningOnly) {
            action(footer, "管理账户") { popup.dismiss(); ModelProvidersActivity.open(context, chat?.id) }
            action(footer, "网页设置") { popup.dismiss(); WebProviderActivity.open(context, chat?.id) }
        }
        action(footer, "完成") { popup.dismiss() }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) { render() }
            override fun afterTextChanged(s: Editable?) {}
        })
        render(); root.isFocusableInTouchMode = true; root.requestFocus()
        popup.showAsDropDown(anchor, 0, dp(6f), Gravity.START)
    }
    private fun toast(context: Context, text: String) = Toast.makeText(context, text, Toast.LENGTH_SHORT).show()
}

/** Preserve each account's effort only where the selected model actually supports it. */
object ComposerModelChoices {
    fun filter(p: ModelProvider, query: String): List<DiscoveredModel> = p.models.filter {
        query.isBlank() || (p.label + " " + p.preset + " " + it.name + " " + it.id).contains(query, true)
    }
    fun effort(p: ModelProvider, m: DiscoveredModel): String = p.effort.takeIf { it in m.efforts }
        ?: "default".takeIf { it in m.efforts } ?: m.efforts.first()
}
