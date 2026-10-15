package dev.wind.agent

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import java.util.Locale

/**
 * Settings — a power user's control surface, not a preferences list.
 *
 * ### One silhouette
 *
 * Every group on this screen is the same object: a grey [Ui.sectionLabel]
 * introduces it, and the group itself is a flat [Ui.groupedCard] on the page
 * ground. There used to be TWO silhouettes — six filled cards and three bare
 * hairline tracks (protocol, theme, language) — so a third of the column read as
 * a different design from the rest. The segmented tracks now live INSIDE a card
 * like everything else, on a [Theme.SURFACE] ground so they are still visible
 * against the card's own [Theme.SURFACE_2].
 *
 * ### Separation inside a card
 *
 * A dense card is no longer one undifferentiated slab: rows are parted by an
 * INSET [Ui.divider] hairline that starts where the labels start, so the glyph
 * column reads as a continuous rail down the card and each row still reads as
 * its own line. [Ui.divider]'s own doc warns this is usually the wrong answer —
 * it is the right one here, because a settings card is a LIST, and a list of
 * eight identically-shaped rows with nothing between them is a wall.
 *
 * ### Explanations belong to their group
 *
 * A group's long explanation is the last block INSIDE its card, under a
 * hairline, rather than a paragraph floating on the page below it. Floating
 * footnotes made the vertical rhythm lumpy — a card, a gap, some prose, a
 * bigger gap, a heading — and left prose with no visible owner.
 *
 * ### One kind of meter
 *
 * The temperature dial and the reasoning dial are built from the same three
 * pieces ([blockHead], [styleSeek], [meterCaptions]), so they cannot drift into
 * two different-looking sliders again. Both carry a mono readout on the trailing
 * edge and two [Ui.Type.MICRO] captions naming the ends of the range.
 *
 * ### Appearance is one group
 *
 * Theme and language sit in ONE card under one heading, because both answer
 * "how does the app look" and neither changes what the agent does. They are the
 * same segmented track, each named by a [blockCaption] and parted by a hairline,
 * with a single closing note covering the pair. Two adjacent cards holding one
 * track apiece would have been two silhouettes again for no gain.
 *
 * ### Persian mirrors, and these are the seams
 *
 * The root sets `layoutDirection = Lang.direction(this)` and the whole tree
 * flips, because every inset here is `setPaddingRelative` / `marginStart` /
 * `Gravity.START`. What that does NOT reach is anything positioned in PHYSICAL
 * pixels or anything whose meaning is Latin, so those are named individually:
 *
 *  * **Directional glyphs** come from [Lang.chevronBack] / [Lang.chevronForward]
 *    — the masthead's back arrow and every "opens something" chevron point at
 *    the edge they mean rather than at a fixed side.
 *  * **LTR islands** — the API key, the router keys, the base URL, the model
 *    name, the number fields and the connection row's `host · model · protocol`
 *    line — pin themselves to `LAYOUT_DIRECTION_LTR` / `TEXT_DIRECTION_LTR` and
 *    then pin their ALIGNMENT back to the view's start edge, so a Latin
 *    identifier still reads left-to-right while sitting on the reading edge of a
 *    mirrored row.
 *  * **The two meters** mirror with the interface (see [styleSeek]): a magnitude
 *    is laid along the reading axis, and in Persian that axis runs from the
 *    right.
 *  * **Numbers** are shown in the interface's own numerals via [Lang.num] and
 *    [localizeDigits]; numbers the user TYPES are normalised back to ASCII by
 *    [normalizeDigits].
 *  * **Tracking** ([blockCaption], the masthead title) is a Latin device and is
 *    zeroed in Persian, which is a joined script.
 *
 * ### Colour
 *
 * There is no accent colour here: every token is black, white or a grey between
 * them. The single exception is the connection row's state dot, which uses the
 * diff inks — the one sanctioned hue in the palette — because "reachable" and
 * "rejected" are opposites, not two amounts of one thing, and lightness alone
 * cannot say which is which.
 *
 * The Vega mark appears exactly ONCE in the whole app, in the About group at
 * the bottom of this screen. The chat screen shows no logo at all.
 */
class SettingsActivity : Activity() {

    private lateinit var prefs: Prefs

    private var selTheme: String = Prefs.THEME_SYSTEM
    private var selLevel: String = "medium"
    private var selProtocol: String = Prefs.PROV_AUTO
    private var selLanguage: String = "en"

    private val themePills = ArrayList<LinearLayout>(3)
    private val langPills = ArrayList<LinearLayout>(2)
    private val protoPills = ArrayList<LinearLayout>(4)
    private val presetChipViews = ArrayList<LinearLayout>(8)
    private val presetChipData = ArrayList<Array<String>>(8)

    private var etBase: EditText? = null
    private var etKey: EditText? = null
    private var etMaxTok: EditText? = null
    private var etTimeout: EditText? = null
    private var etContextWindow: EditText? = null
    private var etModel: EditText? = null
    private var etNewKey: EditText? = null
    private var etSys: EditText? = null
    private var etBraveKey: EditText? = null
    private var etTavilyKey: EditText? = null
    private var etSearxngUrl: EditText? = null
    private var tvSearchStatus: TextView? = null
    private var searchTestToken: CancellationToken? = null
    private var searchTestGeneration = 0L

    private var keysBox: LinearLayout? = null
    private var tvKeyCount: TextView? = null
    private var sbTemp: SeekBar? = null
    private var swWeb: Switch? = null
    private var swWorkflow: Switch? = null
    private var swLocalNet: Switch? = null
    private var tvTemp: TextView? = null
    private var tvTestStatus: TextView? = null
    private var tvConnSummary: TextView? = null
    private var tvConnProblem: TextView? = null
    /** The unprotected-key note AND its hairline, shown as one block or not at all. */
    private var tvKeyWarning: View? = null

    /** The connection row's state pip — neutral, testing, reachable, rejected. */
    private var connDot: View? = null
    private var sbThink: SeekBar? = null
    private var thinkReadout: TextView? = null
    private var thinkDesc: TextView? = null

    private val ui = Handler(Looper.getMainLooper())
    private var extensionStatus: TextView? = null
    private val extensionRequestCode = 7042
    private val skillRequestCode = 7043
    private var skillStatus: TextView? = null
    private val extensionObserver: (dev.wind.agent.runtime.linux.LinuxToolPackJobs.Status) -> Unit = { status ->
        ui.post {
            if (!isFinishing && !isDestroyed) {
                val label = when (status.phase) {
                    "IDLE" -> ""
                    "STARTING", "IMPORTING" -> Lang.text(this, "Reading extension…", "در حال خواندن…", "正在读取扩展包…")
                    "RECOVERING" -> Lang.text(this, "Recovering interrupted installation…", "بازیابی…", "正在恢复中断的安装…")
                    "VERIFYING", "INTEGRITY" -> Lang.text(this, "Verifying extension…", "بررسی…", "正在校验扩展包…")
                    "EXTRACTING" -> Lang.text(this, "Unpacking extension…", "استخراج…", "正在解压扩展包…")
                    "PROBING" -> Lang.text(this, "Checking offline tools…", "آزمایش ابزار…", "正在检查离线工具…")
                    "ACTIVATING" -> Lang.text(this, "Activating extension…", "فعال‌سازی…", "正在启用扩展…")
                    "CANCELLING" -> Lang.text(this, "Cancelling…", "لغو…", "正在取消…")
                    "CANCELLED" -> Lang.text(this, "Cancelled; current tools kept", "لغو شد", "已取消，保留当前工具")
                    "FAILED" -> Lang.text(this, "Tool operation failed", "خطا", "工具操作失败")
                    else -> Lang.text(this, "Tool operation complete", "تکمیل شد", "工具操作完成")
                }
                if (label.isNotBlank()) extensionStatus?.text = label + if (status.message.isBlank()) "" else "\n" + status.message
            }
        }
    }
    private fun runToolPackOperation(operation: () -> String) {
        val app = applicationContext
        dev.wind.agent.runtime.linux.LinuxToolPackJobs.start {
            try { operation() }
            catch (failure: Exception) {
                dev.wind.agent.runtime.linux.LinuxRuntimeDiagnostics.record(app, failure.toString())
                throw failure
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == skillRequestCode && resultCode == RESULT_OK) {
            val uri = data?.data ?: return
            val app = applicationContext
            Thread {
                val result = runCatching {
                    val filename = app.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                        if (cursor.moveToFirst()) cursor.getString(0) else null
                    } ?: uri.lastPathSegment ?: "SKILL.md"
                    app.contentResolver.openInputStream(uri)?.use { dev.wind.agent.skills.SkillStore(app).install(it, filename) }
                        ?: error("SKILL_INPUT_UNAVAILABLE")
                }
                ui.post {
                    if (!isFinishing && !isDestroyed) {
                        skillStatus?.text = result.fold({ "已安装 ${it.name}\n${it.path}" }, { "Skill 安装失败：${it.message}" })
                    }
                }
            }.start()
            return
        }
        if (requestCode != extensionRequestCode || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        val app = applicationContext
        runToolPackOperation {
            val family = app.contentResolver.openInputStream(uri)?.use {
                dev.wind.agent.runtime.linux.LinuxToolPackStore.importPack(app, it)
            } ?: throw java.io.IOException("TOOL_PACK_INPUT_UNAVAILABLE")
            dev.wind.agent.runtime.linux.LinuxRuntimeInstaller.installExtension(app, family)
            family
        }
    }
    private val applyTextSettings = Runnable { applyTextSettingsNow() }

    /**
     * True from the moment a cell in the appearance card has asked for a
     * rebuild until the rebuilt instance replaces this one.
     *
     * It guards BOTH tracks in that card, because both end in `recreate()` and
     * neither survives being asked twice: a second tap while the first rebuild
     * is still pending would queue a second one, and [finish] reads the flag to
     * know that this teardown is a rebuild rather than a navigation and must
     * not be dressed with the exit transition.
     */
    private var applyingTheme = false
    private var lastSavedKey: String = ""
    private var connectionTestGeneration = 0

    /** Palette generation this screen was painted with — see MainActivity. */
    private var appliedRevision = -1
    private var renderedModelAccounts = false

    /** Set while [resetAll] is clearing prefs, so onPause does not write them back. */
    private var resetting = false

    override fun onCreate(bundle: Bundle?) {
        super.onCreate(bundle)
        prefs = Prefs(this)
        NetworkPolicy.applyPrefs(prefs)
        Fa.apply(this)
        Theme.init(this)
        Theme.applyFromPrefs(this, prefs)
        appliedRevision = Theme.revision
        // Everything the PLATFORM draws — text cursor, selection handles and
        // highlight, the ActionMode bar, overscroll glow, Toast and Dialog
        // chrome — comes from the activity theme, not from our palette. Pick
        // the matching one before any view exists.
        setTheme(if (Theme.DARK) R.style.AppTheme else R.style.AppThemeLight)
        selProtocol = prefs.provider()
        selTheme = prefs.themeMode()
        selLanguage = prefs.language()
        lastSavedKey = prefs.apiKey()

        // Same rule as the chat screen: the status bar sits on the app bar, so
        // it takes the app bar's colour, not the page's.
        window.statusBarColor = Theme.BG_ELEV
        window.navigationBarColor = Theme.BG
        window.setBackgroundDrawable(Theme.windowBg())

        val decor = window.decorView
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                decor.isForceDarkAllowed = false
            } catch (e: Exception) {
            }
        }
        @Suppress("DEPRECATION")
        var vis = decor.systemUiVisibility
        if (Theme.DARK) {
            vis = vis and View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR.inv()
            if (Build.VERSION.SDK_INT >= 26) {
                vis = vis and View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR.inv()
            }
        } else {
            vis = vis or View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
            if (Build.VERSION.SDK_INT >= 26) {
                vis = vis or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            }
        }
        @Suppress("DEPRECATION")
        decor.systemUiVisibility = vis

        val scroll = ScrollView(this)
        scroll.setBackgroundColor(Theme.BG)
        scroll.isFillViewport = true
        // The scroller is vertical, so this is not about layout — it is about the
        // one piece of chrome the scroller draws itself. A ScrollView puts its
        // scrollbar on the trailing edge of its resolved direction, and the
        // Activity's decor resolves from the RESOURCE configuration, whose locale
        // this app never changes. Left alone, a Persian screen would have kept its
        // scrollbar on the right, against the reading edge.
        scroll.layoutDirection = Lang.direction(this)

        val panel = LinearLayout(this)
        panel.orientation = LinearLayout.VERTICAL
        panel.layoutDirection = Lang.direction(this)
        // The screen's own gutter, PLUS whatever the system bars need — added, not
        // replaced. `fitsSystemWindows = true` used to be here, and the framework's
        // default implementation OVERWRITES a view's padding with the insets rather
        // than adding to them: on Android 15/16 and on MIUI, where the insets
        // actually reach this view, the 16dp side gutter was replaced with the
        // horizontal insets (zero on a portrait phone) and every card ran into both
        // edges of the glass. On Android 12 with a non-edge-to-edge window the decor
        // had already consumed the insets, nothing arrived, and the padding survived
        // by accident — which is exactly why this looked correct on one phone and
        // broken on another.
        val padH = Theme.dp(this, 16.0f)
        Ui.applyWindowInsets(panel, padH, 0, padH, Theme.dp(this, 40.0f))
        scroll.addView(panel)

        val secTerminal = Ui.column(this)
        val secModels = Ui.column(this)
        val secRuntime = Ui.column(this)
        val secGeneral = Ui.column(this)

        panel.addView(masthead(), Ui.matchWrap())
        panel.addView(categoryTabs(secModels, secTerminal, secRuntime, secGeneral))

        secTerminal.addView(Ui.sectionLabel(this, if (Fa.chinese) "终端权限与自动化" else "Terminal & Automation"))
        val termCard = card()
        termCard.addView(
            Ui.cardRow(
                this,
                "shield",
                if (Fa.chinese) "文件与自动化权限管理" else "File & Automation Permissions",
                if (Fa.chinese) "管理工作区、屏幕感知与系统操作授权" else "Manage workspace, screen & action grants",
                chevronRight()
            ) {
                dev.wind.agent.permissions.AgentPermissionsUi.open(this)
            }
        )
        rowDivider(termCard, ROW_INSET)
        termCard.addView(
            Ui.cardRow(
                this,
                "settings",
                if (Fa.chinese) "本机无线 ADB 设置" else "Local Wireless ADB Setup",
                if (Fa.chinese) "本地免 Root Shell 配对、连接与会话状态" else "Rootless local shell pairing & status",
                chevronRight()
            ) {
                dev.wind.agent.adb.AdbSettingsUi.open(this)
            }
        )
        rowDivider(termCard, ROW_INSET)
        termCard.addView(Ui.cardRow(this, "settings",
            if (Fa.chinese) "后台虚拟屏幕" else "Background Display",
            if (Fa.chinese) "权限、ADB 连接、允许应用与副屏预览；无需校准" else "Grants, ADB, allowed apps & preview; no calibration",
            chevronRight()) {
            startActivity(Intent(this, dev.wind.agent.phone.PhoneDisplaySettingsActivity::class.java))
        })
        secTerminal.addView(termCard)

        // --- provider + connection ------------------------------------------
        //
        // ONE card holds everything about "where do requests go": the preset strip,
        // the three fields the presets write, and — as the card's closing row — the
        // test that exercises exactly those three values.
        //
        // The test used to be a 152dp bordered card at the very top of the screen,
        // above the heading for the fields it tests. It answered the right question
        // ("is this configuration going to work?") in the wrong place: an icon
        // badge, a title, a summary, a red block and a 48dp button, all detached
        // from the inputs they describe. As a trailing row it is ~58dp, it sits
        // directly under the values it reads, and it still says the same three
        // things — what will be used, what [Preflight] already knows is wrong, and
        // what happened when you last pressed Test.
        secModels.addView(Ui.sectionLabel(this, Fa.SET_PROVIDER))
        renderedModelAccounts = prefs.modelProviders.hasAccounts()
        val accounts = card()
        accounts.addView(Ui.pillButton(this,"管理多个 API / 订阅账户、模型和推理强度","cpu",Ui.SECONDARY) {
            dev.wind.agent.modelprovider.ModelProvidersActivity.open(this,prefs.lastChatId().takeIf { it.isNotBlank() })
        })
        cardNote(accounts,"内置 OpenAI、DeepSeek、OpenCode Zen、Anthropic、Gemini、Kimi、GLM；支持独立 API Key 和订阅登录。")
        secModels.addView(accounts)
        val provider = card()
        provider.addView(presetChips())
        rowDivider(provider, 0.0f)
        etBase = field(
            provider, "globe", Fa.SET_BASE_URL, prefs.baseUrl(),
            InputType.TYPE_TEXT_VARIATION_URI, "https://api.openai.com/v1", true
        )
        rowDivider(provider, ROW_INSET)
        addKeyField(provider)
        rowDivider(provider, ROW_INSET)
        etModel = field(
            provider, "cpu", Fa.SET_MODEL, prefs.model(), InputType.TYPE_CLASS_TEXT, "gpt-4o",
            true
        )
        rowDivider(provider, 0.0f)
        provider.addView(connectionCard())
        // Said once, quietly, next to the key it is about. `SecureStore` now saves a
        // key even when the device's keystore cannot protect it — refusing to save it
        // left the app permanently unusable on those devices — so the state has to be
        // visible somewhere rather than silently accepted.
        val keyWarning = cardNote(provider, Fa.SET_KEY_PLAIN, Theme.DIFF_DEL)
        keyWarning.visibility = View.GONE
        tvKeyWarning = keyWarning
        if (!prefs.modelProviders.hasAccounts()) secModels.addView(provider)

        secModels.addView(Ui.sectionLabel(this, dev.wind.agent.webprovider.WebStrings.t(this, "Experimental Web Providers", "实验性网页模型")))
        val webProviders = card()
        webProviders.addView(Ui.pillButton(this, dev.wind.agent.webprovider.WebStrings.t(this, "Choose / manage website providers", "选择 / 管理网页模型"), "globe", Ui.SECONDARY) {
            dev.wind.agent.webprovider.WebProviderActivity.open(this, prefs.lastChatId().takeIf { it.isNotBlank() })
        })
        cardNote(webProviders, dev.wind.agent.webprovider.WebStrings.t(this, "Uses your own website accounts; separate from official API configuration.", "使用你自己的第三方网页账号；与正式 API 配置分开。"))
        secModels.addView(webProviders)

        // --- protocol -----------------------------------------------------
        secModels.addView(Ui.sectionLabel(this, Fa.SET_PROTOCOL))
        val protocol = card()
        protocol.addView(protocolSegment())
        cardNote(protocol, Fa.SET_PROTOCOL_H)
        if (!prefs.modelProviders.hasAccounts()) secModels.addView(protocol)

        // --- key router ---------------------------------------------------
        //
        // The heading carries the counter. It used to float on its own line inside
        // the card, under the add row and above the list, belonging to neither —
        // and "how full is the router" is exactly the kind of fact a heading is
        // for.
        if (!prefs.modelProviders.hasAccounts()) { panel.addView(routerHeading()); panel.addView(keyRouterSection()) }

        // --- generation ----------------------------------------------------
        secModels.addView(Ui.sectionLabel(this, Fa.SET_GENERATION))
        val generation = card()
        generation.addView(temperatureBlock())
        rowDivider(generation, ROW_INSET)
        etMaxTok = field(
            generation, "layers", Fa.SET_MAXTOK, prefs.maxTokens().toString(),
            InputType.TYPE_CLASS_NUMBER, "10000", true
        )
        rowDivider(generation, ROW_INSET)
        etTimeout = field(
            generation, "gauge", Fa.SET_TIMEOUT, prefs.timeoutSeconds().toString(),
            InputType.TYPE_CLASS_NUMBER, Prefs.DEFAULT_TIMEOUT_SECONDS.toString(), true
        )
        cardNote(generation, Fa.SET_TIMEOUT_H)
        secModels.addView(generation)

        secModels.addView(Ui.sectionLabel(this, "上下文与缓存 / Context & Cache"))
        val contextCard = card()
        etContextWindow = field(contextCard, "layers", pick3("Context window (tokens)", "پنجره زمینه", "上下文窗口（tokens）"),
            prefs.contextWindowTokens().toString(), InputType.TYPE_CLASS_NUMBER, "65536", true)
        val autoCompact = toggleRow(contextCard, "layers", pick3("Automatic compaction", "فشرده‌سازی خودکار", "自动压缩上下文"),
            pick3("Summarize older history near the input limit", "خلاصه تاریخچه قدیمی", "达到窗口 85% 或输出预留边界时生成摘要，保留近期交互与原始聊天"), prefs.autoCompact())
        autoCompact.setOnCheckedChangeListener { _, checked -> prefs.setAutoCompact(checked) }
        cardNote(contextCard, pick3("Set the actual model window. /compact summarizes now; /context shows estimates and provider cache usage. Cache hits depend on the provider.",
            "پنجره واقعی مدل را تنظیم کنید. /compact و /context", "按所用模型设置实际窗口；预留输出空间。聊天输入 /compact 手动压缩，/context 查看用量和缓存读取。服务端是否命中取决于供应商。"))
        secModels.addView(contextCard)

        // --- reasoning -------------------------------------------------------
        //
        // Its own group, because the dial and the workflow toggle are one control
        // in two parts: turning Dynamic Workflow on RAISES the effective effort to
        // xhigh and locks the dial. Sitting eight rows apart inside a single
        // "Behavior" card, that relationship was invisible and the lock read as a
        // bug.
        secModels.addView(Ui.sectionLabel(this, Fa.SET_REASONING))
        val reasoning = card()
        selLevel = prefs.thinkingLevel()
        if (!prefs.modelProviders.hasAccounts()) reasoning.addView(thinkLevelSlider())
        else cardNote(reasoning,"推理强度在模型选择中设置；动态工作流不会覆盖已选模型的推理强度。")
        rowDivider(reasoning, ROW_INSET)
        swWorkflow = toggleRow(
            reasoning, "zap", Fa.SET_WORKFLOW, "为复杂任务规划步骤并交给子代理执行；使用已选模型和推理强度。", prefs.dynamicWorkflow()
        )
        secModels.addView(reasoning)

        // --- tools and access -------------------------------------------------
        secModels.addView(Ui.sectionLabel(this, Fa.SET_TOOLS))
        val tools = card()
        swWeb = toggleRow(tools, "search", pick3("Web search", "جستجو", "网页搜索"), null, prefs.webSearch())
        toggleRow(tools, "globe", pick3("Read web pages", "صفحات وب", "网页抓取"), null, prefs.webFetch())
            .setOnCheckedChangeListener { _, checked -> prefs.setWebFetch(checked) }
        toggleRow(tools, "globe", pick3("Real browser", "مرورگر", "真实浏览器"), "搜索关闭时浏览器仍可独立使用", prefs.browserEnabled())
            .setOnCheckedChangeListener { _, checked -> prefs.setBrowserEnabled(checked) }
        rowDivider(tools, ROW_INSET)

        val providerIds = arrayOf("auto", "brave", "tavily", "searxng", "duckduckgo")
        val providerLabels = arrayOf(
            pick3("Auto (Brave → Tavily → SearXNG → DuckDuckGo)", "خودکار", "自动（Brave → Tavily → SearXNG → DuckDuckGo）"),
            "Brave", "Tavily", "SearXNG", "DuckDuckGo")
        val providerBlock = cardBlock(tools)
        val providerButton = Ui.pillButton(this,
            pick3("Search provider: ", "ارائه‌دهنده جستجو: ", "搜索服务商：") +
                providerLabels[providerIds.indexOf(prefs.searchProvider()).coerceAtLeast(0)], "search", Ui.SECONDARY) { }
        providerButton.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle(pick3("Search provider", "ارائه‌دهنده جستجو", "搜索服务商"))
                .setSingleChoiceItems(providerLabels, providerIds.indexOf(prefs.searchProvider()).coerceAtLeast(0)) { dialog, index ->
                    prefs.setSearchProvider(providerIds[index])
                    (providerButton.getChildAt(providerButton.childCount - 1) as TextView).text =
                        pick3("Search provider: ", "ارائه‌دهنده جستجو: ", "搜索服务商：") + providerLabels[index]
                    dialog.dismiss()
                }.setNegativeButton(android.R.string.cancel, null).show()
        }
        providerBlock.addView(providerButton)
        val providerHint = TextView(this)
        providerHint.text = pick3("Brave / Tavily: enter an API key. SearXNG: enter your server URL. DuckDuckGo needs no key.",
            "Brave / Tavily: کلید API؛ SearXNG: نشانی سرور؛ DuckDuckGo بدون کلید.",
            "Brave / Tavily 只需填写 API Key；SearXNG 填写服务地址；DuckDuckGo 无需密钥。")
        providerHint.setTextColor(Theme.TEXT_MUTED)
        providerHint.textSize = Ui.Type.MICRO
        providerBlock.addView(providerHint)
        rowDivider(tools, ROW_INSET)

        // Brave API Key
        val braveBlock = cardBlock(tools)
        val braveLabel = TextView(this)
        braveLabel.text = Fa.SET_BRAVE_KEY
        braveLabel.textSize = Ui.Type.MICRO
        braveLabel.setTextColor(Theme.TEXT_MUTED)
        braveLabel.typeface = Theme.uiMedium()
        braveBlock.addView(braveLabel)
        val braveField = EditText(this)
        braveField.typeface = Theme.ui()
        etBraveKey = braveField
        braveField.setText(prefs.braveApiKey())
        braveField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        braveField.setSingleLine(true)
        braveField.hint = "Optional: Brave Search token"
        braveField.setHintTextColor(Theme.TEXT_FAINT)
        braveField.setTextColor(Theme.TEXT)
        braveField.textSize = Ui.Type.LABEL
        braveField.background = fieldBg(false)
        braveField.setOnFocusChangeListener { _, hasFocus -> braveField.background = fieldBg(hasFocus) }
        val bPad = Theme.dp(this, Ui.Space.S)
        braveField.setPadding(bPad, bPad, bPad, bPad)
        braveBlock.addView(braveField)
        rowDivider(tools, ROW_INSET)

        val tavilyBlock = cardBlock(tools)
        val tavilyLabel = TextView(this)
        tavilyLabel.text = "Tavily API Key"
        tavilyLabel.textSize = Ui.Type.MICRO
        tavilyLabel.setTextColor(Theme.TEXT_MUTED)
        tavilyBlock.addView(tavilyLabel)
        val tavilyField = EditText(this)
        etTavilyKey = tavilyField
        tavilyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        tavilyField.setSingleLine(true)
        tavilyField.setText(prefs.tavilyApiKey())
        tavilyField.hint = "tvly-…"
        tavilyField.setHintTextColor(Theme.TEXT_FAINT)
        tavilyField.setTextColor(Theme.TEXT)
        tavilyField.textSize = Ui.Type.LABEL
        tavilyField.background = fieldBg(false)
        tavilyField.setOnFocusChangeListener { _, focus -> tavilyField.background = fieldBg(focus) }
        tavilyField.setPadding(bPad, bPad, bPad, bPad)
        tavilyBlock.addView(tavilyField)
        rowDivider(tools, ROW_INSET)

        val fallback = toggleRow(tools, "search",
            pick3("Allow search fallback", "جستجوی جایگزین", "允许搜索回退"),
            pick3("If the provider fails, try other configured providers, then DuckDuckGo.",
                "در صورت خطا، ارائه‌دهنده بعدی استفاده شود.", "服务失败时尝试其他已配置服务，再尝试 DuckDuckGo。"),
            prefs.searchFallbackEnabled())
        fallback.setOnCheckedChangeListener { _, enabled -> prefs.setSearchFallbackEnabled(enabled) }
        rowDivider(tools, ROW_INSET)

        // SearXNG Base URL
        val searxngBlock = cardBlock(tools)
        val searxngLabel = TextView(this)
        searxngLabel.text = Fa.SET_SEARXNG_URL
        searxngLabel.textSize = Ui.Type.MICRO
        searxngLabel.setTextColor(Theme.TEXT_MUTED)
        searxngLabel.typeface = Theme.uiMedium()
        searxngBlock.addView(searxngLabel)
        val searxngField = EditText(this)
        searxngField.typeface = Theme.ui()
        etSearxngUrl = searxngField
        searxngField.setText(prefs.searxngBaseUrl())
        searxngField.hint = "https://search.example.com"
        searxngField.setHintTextColor(Theme.TEXT_FAINT)
        searxngField.setTextColor(Theme.TEXT)
        searxngField.textSize = Ui.Type.LABEL
        searxngField.background = fieldBg(false)
        searxngField.setOnFocusChangeListener { _, hasFocus -> searxngField.background = fieldBg(hasFocus) }
        searxngField.setPadding(bPad, bPad, bPad, bPad)
        searxngBlock.addView(searxngField)
        rowDivider(tools, ROW_INSET)

        // Test search button
        val testSearchBlock = cardBlock(tools)
        val testRow = LinearLayout(this)
        testRow.orientation = LinearLayout.HORIZONTAL
        testRow.gravity = Gravity.CENTER_VERTICAL
        val testBtn = Ui.pillButton(this, Fa.SET_SEARCH_TEST, "search", Ui.SECONDARY) {
            runSearchTest()
        }
        testRow.addView(testBtn)
        val statusText = TextView(this)
        statusText.textSize = Ui.Type.MICRO
        statusText.typeface = Theme.ui()
        statusText.setTextColor(Theme.TEXT_MUTED)
        val sLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        sLp.marginStart = Theme.dp(this, Ui.Space.M)
        testRow.addView(statusText, sLp)
        tvSearchStatus = statusText
        testSearchBlock.addView(testRow)
        rowDivider(tools, ROW_INSET)
        swLocalNet = toggleRow(
            tools, "server", Fa.SET_LOCAL_NET, Fa.SET_LOCAL_NET_H, prefs.allowLocalNetwork()
        )
        secModels.addView(tools)

        secRuntime.addView(Ui.sectionLabel(this, if (Fa.chinese) "开发环境" else "Development Runtime"))
        val runtimeCard = card()
        val runtimeBlock = cardBlock(runtimeCard)
        val runtimeStatus = TextView(this).apply {
            text = Lang.text(this@SettingsActivity, "Python, JDK and basic tools are bundled. Build and language servers are optional extensions.", "ابزارهای پایه داخلی؛ ابزارهای ساخت اختیاری هستند.", "内置完整 Python、JDK 和基础工具；构建工具与语言服务通过扩展包安装。")
            textSize = Ui.Type.MICRO
            setTextColor(Theme.TEXT_MUTED)
            setSingleLine(false)
            ellipsize = null
            setTextIsSelectable(true)
        }
        runtimeBlock.addView(runtimeStatus)
        extensionStatus = TextView(this).apply {
            text = Lang.text(this@SettingsActivity, "Install base tools first. Android builds require the JVM extension; the code intelligence pack needs only base tools.", "ابتدا ابزارهای پایه را نصب کنید.", "先安装基础工具。Android 构建扩展依赖 JVM 扩展；代码智能扩展仅依赖基础工具，支持 Java、Kotlin、Python、TS/JS。")
            textSize = Ui.Type.MICRO
            setTextColor(Theme.TEXT_MUTED)
            setTextIsSelectable(true)
        }
        val runtimeActions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        var runtimeFailure = dev.wind.agent.runtime.linux.LinuxRuntimeDiagnostics.last(applicationContext)
        fun showRuntimeFailure() {
            val detail = runtimeFailure.ifBlank {
                Lang.text(this, "No failure recorded.", "خطایی ثبت نشده است.", "暂无失败记录。")
            }
            val text = TextView(this).apply {
                this.text = detail
                setTextIsSelectable(true)
                val horizontal = Theme.dp(this@SettingsActivity, 16.0f)
                val vertical = Theme.dp(this@SettingsActivity, 12.0f)
                setPadding(horizontal, vertical, horizontal, vertical)
            }
            val scroll = ScrollView(this).apply { addView(text) }
            android.app.AlertDialog.Builder(this)
                .setTitle(Lang.text(this, "Runtime diagnostics", "گزارش خطا", "运行环境错误详情"))
                .setView(scroll)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(Lang.text(this, "Copy diagnostics", "کپی گزارش", "复制诊断")) { _, _ ->
                    (getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager)
                        .setPrimaryClip(android.content.ClipData.newPlainText("Runtime diagnostics", detail))
                }.show()
        }
        fun runtimeAction(install: Boolean) {
            runtimeActions.isEnabled = false
            for (index in 0 until runtimeActions.childCount) runtimeActions.getChildAt(index).isEnabled = false
            runtimeStatus.text = Lang.text(this, "Working…", "در حال انجام…", "正在处理…")
            val app = applicationContext
            Thread {
                val client = dev.wind.agent.runtime.LinuxRuntimeClient(app)
                var failureDetail = ""
                val message = try {
                    val result = if (install) client.installBundledRuntime() else client.probe()
                    if (result.ready) {
                        if (install) Prefs(app).setRuntimePreference(Prefs.RUNTIME_SELF_CONTAINED)
                        Lang.text(app, "Ready", "آماده", "已就绪") + "\n" + result.capabilities.toolchains.entries.joinToString(" · ") { "${it.key} ${it.value}" }
                    } else {
                        failureDetail = result.diagnostic.ifBlank { "RUNTIME_UNAVAILABLE" }
                        dev.wind.agent.runtime.linux.LinuxRuntimeDiagnostics.record(app, failureDetail)
                        Lang.text(app, "Unavailable", "در دسترس نیست", "尚不可用") + ": " + failureDetail.lineSequence().first()
                    }
                } catch (failure: Exception) {
                    failureDetail = failure.toString()
                    dev.wind.agent.runtime.linux.LinuxRuntimeDiagnostics.record(app, failureDetail)
                    Lang.text(app, "Installation failed", "نصب ناموفق", "安装失败") + ": " + (failure.message ?: failure.javaClass.simpleName).lineSequence().first()
                } catch (failure: LinkageError) {
                    failureDetail = failure.toString()
                    dev.wind.agent.runtime.linux.LinuxRuntimeDiagnostics.record(app, failureDetail)
                    Lang.text(app, "Runtime unavailable", "در دسترس نیست", "运行环境不可用") + ": " + failure.javaClass.simpleName
                } finally { client.endpointOverride?.close() }
                ui.post {
                    if (!isFinishing && !isDestroyed) {
                        runtimeStatus.text = message
                        if (failureDetail.isNotBlank()) {
                            runtimeFailure = dev.wind.agent.runtime.linux.LinuxRuntimeDiagnostics.last(app).ifBlank { failureDetail }
                            showRuntimeFailure()
                        }
                        runtimeActions.isEnabled = true
                        for (index in 0 until runtimeActions.childCount) runtimeActions.getChildAt(index).isEnabled = true
                    }
                }
            }.apply { name = "vega-runtime-setup"; start() }
        }
        runtimeActions.addView(Ui.pillButton(this, Lang.text(this, "Install offline tools", "نصب ابزارها", "安装离线工具"), "download", Ui.SECONDARY) { runtimeAction(true) })
        runtimeActions.addView(Ui.pillButton(this, Lang.text(this, "Check status", "وضعیت", "检查状态"), "refresh", Ui.SECONDARY) { runtimeAction(false) })
        runtimeBlock.addView(runtimeActions)
        runtimeBlock.addView(extensionStatus)
        runtimeBlock.addView(Ui.pillButton(this, Lang.text(this, "Import tool extension", "افزونه ابزار", "导入工具扩展包"), "download", Ui.SECONDARY) {
            if (!dev.wind.agent.runtime.linux.LinuxToolPackJobs.isBusy()) {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    type = "*/*"
                    addCategory(Intent.CATEGORY_OPENABLE)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }, extensionRequestCode)
            }
        })
        dev.wind.agent.runtime.linux.LinuxToolPackJobs.observe(extensionObserver)
        runtimeBlock.addView(Ui.pillButton(this, Lang.text(this, "Install extension from downloads", "نصب از دانلودها", "从下载缓存安装扩展"), "download", Ui.SECONDARY) {
            if (!dev.wind.agent.runtime.linux.LinuxToolPackJobs.isBusy()) {
                val app = applicationContext
                runToolPackOperation {
                    val artifacts = dev.wind.agent.runtime.linux.LinuxToolPackDownloads.available(app)
                    ui.post {
                        if (!isFinishing && !isDestroyed) {
                            if (artifacts.isEmpty()) {
                                extensionStatus?.text = Lang.text(app, "No cached downloads", "دانلودی نیست", "没有可用的下载缓存")
                            } else {
                                AlertDialog.Builder(this).setTitle(Lang.text(app, "Choose a tool pack download", "انتخاب بسته", "选择下载的工具扩展包"))
                                    .setItems(artifacts.map { "${it.sha256.take(16)} · ${it.byteSize / (1024 * 1024)} MiB" }.toTypedArray()) { _, index ->
                                        runToolPackOperation { dev.wind.agent.runtime.linux.LinuxToolPackDownloads.installSelected(app, artifacts[index].uri) }
                                    }.setNegativeButton(Fa.CANCEL, null).show()
                            }
                        }
                    }
                    ""
                }
            }
        })
        runtimeBlock.addView(Ui.pillButton(this, Lang.text(this, "Extension status", "وضعیت افزونه", "扩展安装状态"), "info", Ui.SECONDARY) {
            val app = applicationContext
            runToolPackOperation { dev.wind.agent.runtime.linux.LinuxRuntimeInstaller.installationStatus(app) }
        })
        runtimeBlock.addView(Ui.pillButton(this, Lang.text(this, "Roll back tools", "بازگشت ابزارها", "回退工具版本"), "refresh", Ui.SECONDARY) {
            if (!dev.wind.agent.runtime.linux.LinuxToolPackJobs.isBusy()) {
                val app = applicationContext
                val families = arrayOf("core", "linux-jvm", "linux-android", "linux-lsp")
                AlertDialog.Builder(this).setTitle(Lang.text(app, "Choose tools to roll back", "انتخاب ابزار", "选择要回退的工具"))
                    .setItems(arrayOf("Python / JDK / Core", "JVM build", "Android build", "Java / Kotlin / Python / TS / JS")) { _, index ->
                        runToolPackOperation { dev.wind.agent.runtime.linux.LinuxRuntimeInstaller.rollback(app, families[index]).name }
                    }.setNegativeButton(Fa.CANCEL, null).show()
            }
        })
        runtimeBlock.addView(Ui.pillButton(this, Lang.text(this, "Cancel extension operation", "لغو نصب", "取消扩展操作"), "close", Ui.SECONDARY) {
            dev.wind.agent.runtime.linux.LinuxToolPackJobs.cancel()
        })
        runtimeBlock.addView(Ui.pillButton(this, Lang.text(this, "Error details", "گزارش خطا", "错误详情"), "info", Ui.SECONDARY) { showRuntimeFailure() })
        secRuntime.addView(runtimeCard)

        secGeneral.addView(Ui.pillButton(this, "Git / 下载凭据", "key", Ui.SECONDARY) {
            dev.wind.agent.secrets.SecretSettingsUi.show(this)
        })
        secGeneral.addView(Ui.sectionLabel(this, "Skills"))
        secGeneral.addView(Ui.pillButton(this,"长期记忆","book",Ui.SECONDARY) { dev.wind.agent.memory.MemoryUi.show(this) })
        val skillsCard = card()
        val skillsBlock = cardBlock(skillsCard)
        val skillStore = dev.wind.agent.skills.SkillStore(this)
        skillStatus = TextView(this).apply {
            text = "内置 HarnessMate.md 按需读取。导入 Markdown 或含 SKILL.md 的 ZIP；在聊天输入区选择 Skill。\n默认目录：${skillStore.directory()}"
            textSize = Ui.Type.MICRO
            setTextColor(Theme.TEXT_MUTED)
            setTextIsSelectable(true)
        }
        skillsBlock.addView(skillStatus)
        skillsBlock.addView(android.widget.Button(this).apply {
            text = "安装 Skill"
            setOnClickListener {
                startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/markdown", "text/plain", "application/zip", "application/octet-stream"))
                }, skillRequestCode)
            }
        })
        skillsBlock.addView(android.widget.Button(this).apply {
            text = "查看已安装 Skill"
            setOnClickListener {
                val entries = skillStore.list()
                android.app.AlertDialog.Builder(this@SettingsActivity).setTitle("Skills")
                    .setItems(entries.map { "${it.name}\n${it.path}" }.toTypedArray()) { _, index ->
                        val entry = entries[index]
                        val content = TextView(this@SettingsActivity).apply { text = skillStore.read(entry.path); setTextIsSelectable(true) }
                        android.app.AlertDialog.Builder(this@SettingsActivity).setTitle(entry.name)
                            .setView(ScrollView(this@SettingsActivity).apply { addView(content) })
                            .setPositiveButton(android.R.string.ok, null).show()
                    }.setNegativeButton(android.R.string.cancel, null).show()
            }
        })
        secGeneral.addView(skillsCard)

        // --- custom prompt -------------------------------------------------
        secGeneral.addView(Ui.sectionLabel(this, Fa.SET_CUSTOM))
        val custom = card()
        val promptBlock = cardBlock(custom)
        val systemPrompt = EditText(this)
        systemPrompt.typeface = Theme.ui()
        etSys = systemPrompt
        systemPrompt.setText(prefs.systemPrompt())
        systemPrompt.hint = Fa.SET_CUSTOM_HINT
        systemPrompt.setHintTextColor(Theme.TEXT_FAINT)
        systemPrompt.setTextColor(Theme.TEXT)
        systemPrompt.textSize = Ui.Type.LABEL
        systemPrompt.background = fieldBg(false)
        systemPrompt.setOnFocusChangeListener { _, hasFocus ->
            systemPrompt.background = fieldBg(hasFocus)
        }
        val promptPad = Theme.dp(this, Ui.Space.M)
        systemPrompt.setPadding(promptPad, promptPad, promptPad, promptPad)
        systemPrompt.setLineSpacing(Theme.dpf(this, 3.0f), 1.0f)
        // PROSE, in whichever language the user writes it — so it takes its
        // direction from its own first strong character rather than from the
        // interface. A Persian instruction reads right-to-left inside an English
        // interface and an English one reads left-to-right inside a Persian one.
        systemPrompt.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        // START, not a bare TOP. TOP alone leaves the horizontal gravity bits
        // empty; TextView reads that as "no opinion" and happens to land on the
        // same ALIGN_NORMAL, but it states nothing about which edge it means, and
        // an unstated edge is the one that gets "fixed" to a physical LEFT later.
        systemPrompt.gravity = Gravity.TOP or Gravity.START
        systemPrompt.minLines = 4
        systemPrompt.inputType =
            InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
        promptBlock.addView(systemPrompt, Ui.matchWrap())
        cardNote(custom, Fa.SET_CUSTOM_H)
        secGeneral.addView(custom)

        // --- appearance -------------------------------------------------------
        //
        // Below the working settings, not above them. It is the one group here
        // that changes nothing about what the agent DOES.
        //
        // TWO tracks, one card: the palette and the interface language are the
        // same question asked twice, and the heading now says so. It used to say
        // "Theme", which was accurate while theme was the only thing in here and
        // would have left the language track sitting under a heading that did not
        // mention it. `Lang.text` rather than a Fa key only because Fa.kt is not
        // this file's to edit — see the note in the report.
        secGeneral.addView(Ui.sectionLabel(this, if (Fa.chinese) "外观与偏好" else "Appearance & Preferences"))
        val appearance = card()
        appearance.addView(captionedTrack(Fa.SET_THEME, themeSegment()))
        // Full-bleed, like every other rule under a block that has no glyph
        // column to run past.
        rowDivider(appearance, 0.0f)
        appearance.addView(captionedTrack(Fa.SET_LANGUAGE, languageSegment()))
        // ONE closing note for the pair, so the card keeps the shape every other
        // group has: content, hairline, explanation. Two notes would have put an
        // "explanation" block in the middle of the card, where the design says a
        // group's prose is always the LAST thing in it.
        cardNote(appearance, Fa.SET_THEME_H + "\n" + Fa.SET_LANGUAGE_H)
        secGeneral.addView(appearance)

        // --- reset ----------------------------------------------------------
        secGeneral.addView(Ui.sectionLabel(this, Fa.SET_RESET))
        secGeneral.addView(resetSection())

        // --- about ----------------------------------------------------------
        

        panel.addView(secModels)
        panel.addView(secTerminal)
        panel.addView(secRuntime)
        panel.addView(secGeneral)

        installInstantTextSettings()
        setContentView(scroll)
    }

    // =====================================================================
    // Masthead
    // =====================================================================

    /**
     * The screen's top: a back affordance, the title at [Ui.Type.TITLE], a line
     * saying what is in here, and a full-width hairline closing the block.
     *
     * The old header was a 56dp row with a chevron and the word "Settings" at
     * body size — the same size as every row label under it — so the screen began
     * with no statement of what it was. This is the one place on the page that
     * uses the display size, which is what gives the column a top.
     */
    private fun masthead(): LinearLayout {
        val head = Ui.column(this)
        head.setPaddingRelative(0, Theme.dp(this, Ui.Space.S), 0, 0)

        val back = Ui.row(this)
        val backButton = Ui.circleButton(
            this,
            // BACK, so it points at the edge it returns to: chevron-left in
            // English, chevron-right in Persian. A fixed "chevron-left" here
            // would have pointed INTO the page on a mirrored screen — away from
            // the edge the gesture actually goes to.
            Lang.chevronBack(this),
            40.0f,
            20.0f,
            Theme.TEXT,
            0
        ) { finish() }
        // Ui.iconLabel resolves both chevrons through Lang.text, which is why the
        // one icon-only control on this screen would have been announced in the
        // wrong language. Named here instead.
        backButton.contentDescription = Lang.text(this, "Back", "بازگشت", "返回")
        back.addView(backButton)
        // The button's own optical inset is cancelled so the glyph, the title
        // below it and the section labels below THAT all start on one line.
        //
        // Mirrored, this still holds: circleButton pads (40-20)/2 = 10dp on all
        // four sides, so the glyph's leading edge sits 10dp inside the button's
        // leading edge — whichever side that is. A marginStart of 4-10 = -6dp
        // pulls the button 6dp PAST the column's start edge, which puts the glyph
        // back on the 4dp the title and the section labels use. Every term is
        // relative, so the whole calculation reflects with the layout.
        val backLp = Ui.wrapWrap()
        backLp.marginStart = Theme.dp(this, Ui.Space.XS) - Theme.dp(this, 10.0f)
        head.addView(back, backLp)

        val title = Ui.text(this, Fa.SETTINGS, Ui.Type.TITLE, Theme.TEXT, Theme.uiBold())
        title.setSingleLine(true)
        title.ellipsize = android.text.TextUtils.TruncateAt.END
        // Slightly tight: a display-size line in a bold face sets loose by default
        // and reads as spaced-out rather than as a title.
        title.letterSpacing = tracking(-0.01f)
        Ui.rowLabel(title)
        val titleLp = Ui.matchWrap()
        titleLp.topMargin = Theme.dp(this, Ui.Space.S)
        titleLp.marginStart = Theme.dp(this, Ui.Space.XS)
        head.addView(title, titleLp)

        val caption = Ui.text(this, Fa.SET_SUBTITLE, Ui.Type.META, Theme.TEXT_MUTED, Theme.ui())
        caption.setLineSpacing(Theme.dpf(this, 3.0f), 1.0f)
        Ui.rowLabel(caption)
        val captionLp = Ui.matchWrap()
        captionLp.topMargin = Theme.dp(this, 3.0f)
        captionLp.marginStart = Theme.dp(this, Ui.Space.XS)
        head.addView(caption, captionLp)

        val rule = Ui.divider(this)
        val ruleLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Theme.hairline(this)
        )
        ruleLp.topMargin = Theme.dp(this, Ui.Space.XL)
        head.addView(rule, ruleLp)
        return head
    }

    // =====================================================================
    // Provider preset chips
    // =====================================================================

    /**
     * The preset strip — now the provider card's FIRST block rather than a loose
     * scroller floating between the heading and the card.
     *
     * It writes the three fields directly beneath it, so it belongs to the same
     * object they do. The strip keeps its own [Ui.Space.L] leading inset so the
     * first chip lines up with the labels under it, and it runs to the card's
     * edge on the trailing side, which is what tells you it scrolls.
     */

    private fun chevronRight(): ImageView {
        val chevron = ImageView(this)
        chevron.setImageDrawable(Icons.of(Lang.chevronForward(this), Theme.TEXT_FAINT, Ui.STROKE))
        chevron.scaleType = ImageView.ScaleType.FIT_CENTER
        chevron.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val size = Theme.dp(this, 18.0f)
        chevron.layoutParams = LinearLayout.LayoutParams(size, size)
        return chevron
    }

    private fun categoryTabs(
        secModels: LinearLayout,
        secTerminal: LinearLayout,
        secRuntime: LinearLayout,
        secGeneral: LinearLayout
    ): View {
        val wrap = HorizontalScrollView(this)
        wrap.isHorizontalScrollBarEnabled = false
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val padH = Theme.dp(this, 16.0f)
        row.setPadding(padH, Theme.dp(this, 8.0f), padH, Theme.dp(this, 16.0f))

        val tabs = if (Fa.chinese) {
            listOf("全部设置", "模型接入", "终端权限", "开发环境", "通用偏好")
        } else {
            listOf("All", "Models & API", "Terminal & ADB", "Runtime", "General")
        }

        val tabViews = ArrayList<LinearLayout>()

        fun updateTabs(selectedIndex: Int) {
            for (i in tabViews.indices) {
                val chip = tabViews[i]
                val selected = i == selectedIndex
                chip.background = if (selected) {
                    Theme.actionButton(Theme.R_PILL, this)
                } else {
                    Theme.roundStroke(Theme.SURFACE_2, Theme.BORDER, Theme.R_PILL, 1, this)
                }
                val label = chip.getChildAt(0) as? TextView
                label?.setTextColor(if (selected) Theme.ON_ACCENT else Theme.TEXT_MUTED)
                label?.typeface = if (selected) Theme.uiSemi() else Theme.uiMedium()
            }
            secModels.visibility = if (selectedIndex == 0 || selectedIndex == 1) View.VISIBLE else View.GONE
            secTerminal.visibility = if (selectedIndex == 0 || selectedIndex == 2) View.VISIBLE else View.GONE
            secRuntime.visibility = if (selectedIndex == 0 || selectedIndex == 3) View.VISIBLE else View.GONE
            secGeneral.visibility = if (selectedIndex == 0 || selectedIndex == 4) View.VISIBLE else View.GONE
        }

        for (i in tabs.indices) {
            val title = tabs[i]
            val chip = Ui.row(this)
            chip.gravity = Gravity.CENTER
            chip.minimumHeight = Theme.dp(this, 36.0f)
            val chipPadH = Theme.dp(this, 14.0f)
            chip.setPadding(chipPadH, 0, chipPadH, 0)
            val label = Ui.text(this, title, Ui.Type.META, Theme.TEXT_MUTED, Theme.uiMedium())
            label.setSingleLine(true)
            chip.addView(label)

            val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            lp.marginEnd = Theme.dp(this, Ui.Space.S)
            chip.layoutParams = lp

            chip.setOnClickListener {
                updateTabs(i)
            }
            Ui.pressScale(chip)
            tabViews.add(chip)
            row.addView(chip)
        }

        wrap.addView(row)
        updateTabs(0)
        return wrap
    }

    private fun presetChips(): LinearLayout {
        presetChipViews.clear()
        presetChipData.clear()

        val block = Ui.column(this)
        block.setPaddingRelative(
            0, Theme.dp(this, 14.0f), 0, Theme.dp(this, Ui.Space.M)
        )

        val captionLp = Ui.matchWrap()
        captionLp.marginStart = Theme.dp(this, Ui.Space.L)
        captionLp.marginEnd = Theme.dp(this, Ui.Space.L)
        captionLp.bottomMargin = Theme.dp(this, Ui.Space.S)
        block.addView(blockCaption(Fa.SET_PRESET), captionLp)

        val wrap = HorizontalScrollView(this)
        wrap.isHorizontalScrollBarEnabled = false
        // The SCROLLER's direction, said explicitly and not left to inherit.
        //
        // A HorizontalScrollView reads its own resolved direction on first layout
        // and, when it is RTL, flips a zero scroll offset to the far end so the
        // strip opens on its FIRST child. Direction therefore has to agree between
        // the scroller and the row inside it: a mirrored row inside an unmirrored
        // scroller would open on OpenAI's chip scrolled off the right edge, with
        // "Together" showing instead.
        wrap.layoutDirection = Lang.direction(this)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.layoutDirection = Lang.direction(this)
        row.setPaddingRelative(Theme.dp(this, Ui.Space.L), 0, Theme.dp(this, Ui.Space.L), 0)

        val presets = arrayOf(
            arrayOf("DeepSeek", "https://api.deepseek.com", Prefs.PROV_OPENAI, "deepseek-chat"),
            arrayOf("智谱 GLM", "https://open.bigmodel.cn/api/paas/v4", Prefs.PROV_OPENAI, "glm-4-flash"),
            arrayOf("Moonshot (Kimi)", "https://api.moonshot.cn/v1", Prefs.PROV_OPENAI, "moonshot-v1-8k"),
            arrayOf("SiliconFlow", "https://api.siliconflow.cn/v1", Prefs.PROV_OPENAI, "deepseek-ai/DeepSeek-V3"),
            arrayOf("OpenAI", "https://api.openai.com/v1", Prefs.PROV_OPENAI, "gpt-4o"),
            arrayOf("Claude", "https://api.anthropic.com/v1", Prefs.PROV_ANTHRO, "claude-3-5-sonnet-20241022"),
            arrayOf("Gemini", "https://generativelanguage.googleapis.com/v1beta", Prefs.PROV_GEMINI, "gemini-1.5-flash"),
            arrayOf("Ollama (本地)", "http://127.0.0.1:11434/v1", Prefs.PROV_OPENAI, "qwen2.5-coder:7b"),
            arrayOf("OpenRouter", "https://openrouter.ai/api/v1", Prefs.PROV_OPENAI, "openai/gpt-4o")
        )
        // The chip matching the saved base URL renders as ACTIVE — a solid
        // ACCENT pill with an ON_ACCENT label, the same "chosen" treatment the
        // segmented tracks use — so the live provider is obvious at a glance.
        val activeBase = prefs.baseUrl().trimJava()
        for (i in presets.indices) {
            val preset = presets[i]
            val active = preset[1] == activeBase
            val chip = Ui.row(this)
            chip.background = presetChipBg(active)
            val chipPadH = Theme.dp(this, 14.0f)
            val chipPadV = Theme.dp(this, 9.0f)
            chip.setPadding(chipPadH, chipPadV, chipPadH, chipPadV)
            chip.minimumHeight = Theme.dp(this, 42.0f)

            val dot = View(this)
            dot.background = Theme.circle(presetDot(active))
            val dotSize = Theme.dp(this, 7.0f)
            val dotLp = LinearLayout.LayoutParams(dotSize, dotSize)
            dotLp.marginEnd = Theme.dp(this, 7.0f)
            chip.addView(dot, dotLp)

            val label = Ui.text(
                this, preset[0], Ui.Type.META,
                if (active) Theme.ON_ACCENT else Theme.TEXT,
                if (active) Theme.uiSemi() else Theme.uiMedium()
            )
            label.setSingleLine(true)
            // singleLine without ellipsize clips mid-glyph, so a long provider name
            // ended in half a letter rather than an ellipsis.
            label.ellipsize = android.text.TextUtils.TruncateAt.END
            chip.addView(label, Ui.wrapWrap())

            val chipLp = Ui.wrapWrap()
            chipLp.marginEnd = Theme.dp(this, Ui.Space.S)
            chip.setOnClickListener {
                etBase?.setText(preset[1])
                etModel?.setText(preset[3])
                selProtocol = preset[2]
                prefs.setProvider(selProtocol)
                prefs.setBaseUrl(preset[1])
                prefs.setModel(preset[3])
                refreshProto()
                // All three values just changed, so the last verdict was about a
                // different endpoint. Clearing it is the same reset the old card
                // did with `tvTestStatus?.text = ""`, plus the pip.
                resetConnectionState()
                refreshPresetChips()
            }
            Ui.pressScale(chip)
            presetChipViews.add(chip)
            presetChipData.add(preset)
            row.addView(chip, chipLp)
        }
        wrap.addView(row)
        block.addView(wrap, Ui.matchWrap())
        block.layoutParams = Ui.matchWrap()
        return block
    }

    /**
     * One definition of the preset chip's surface, used by both the initial
     * build and every refresh — they used to be two copies that could (and did)
     * drift apart.
     *
     * The active chip is the same solid [Theme.ACCENT] pill a chosen segment
     * cell gets. The tinted wash plus accent ring it used to wear was the last
     * "sort of selected" state on the screen, and against a monochrome page a
     * 20%-alpha black wash reads as a disabled control, not a chosen one.
     */
    private fun presetChipBg(active: Boolean) = if (active) {
        Theme.actionButton(Theme.R_PILL, this)
    } else {
        Theme.rippleOver(
            Theme.roundRect(Theme.SURFACE_2, Theme.R_PILL, this), Theme.R_PILL, this
        )
    }

    /**
     * The bullet colour for a preset chip: [Theme.ON_ACCENT] on the chosen chip
     * so it reads against the solid pill, and one flat [Theme.TEXT_FAINT] on
     * every other.
     *
     * There used to be a seven-entry per-preset colour table here (GREEN,
     * ACCENT, BLUE, CYAN, ROSE, YELLOW, TEXT_MUTED). Those were distinct hues in
     * the old palette; in this one they collapse to four grey levels — and the
     * ACCENT entry lands near-white on a SURFACE_2 chip in dark mode (near-black
     * in light), so one arbitrary chip in the row shouted louder than its six
     * neighbours while encoding nothing at all. That is the same hue-carried
     * distinction the mode-pill dot was deleted for: the preset's LABEL is the
     * distinction, and the bullet is only a bullet.
     */
    private fun presetDot(active: Boolean): Int =
        if (active) Theme.ON_ACCENT else Theme.TEXT_FAINT

    /**
     * The connection row — the provider card's closing line.
     *
     * It answers the same question the old 152dp card at the top of the screen
     * did ("is this configuration going to work?"), in the place where the answer
     * is meaningful: directly under the base URL, the key and the model, which
     * are the only three values it reads. Everything is on one ~58dp row:
     *
     *  * a 9dp state pip — neutral before a test, muted while one runs, and one
     *    of the two diff inks afterwards;
     *  * a state line, which is either the test's own verdict or, when
     *    [Preflight] can already see the request is impossible, that problem said
     *    in one tight line;
     *  * a `host · model · protocol` subtitle, forced LTR because all three are
     *    Latin identifiers;
     *  * a compact Test affordance.
     *
     * The whole thing is rebuilt by [refreshConnectionCard] on every keystroke,
     * so it describes the live fields rather than what the screen opened with.
     */
    private fun connectionCard(): View {
        val row = Ui.row(this)
        row.minimumHeight = Theme.dp(this, 58.0f)
        row.setPaddingRelative(
            Theme.dp(this, Ui.Space.L), Theme.dp(this, Ui.Space.M),
            Theme.dp(this, Ui.Space.M), Theme.dp(this, Ui.Space.M)
        )

        // The pip sits in the same 20dp slot a cardRow glyph occupies, so the
        // state line starts exactly where every label above it does.
        val slot = LinearLayout(this)
        slot.gravity = Gravity.CENTER
        // The pip is the only thing naming this row, and a bare View has nothing
        // for a screen reader to announce.
        slot.contentDescription = Fa.SET_CONNECTION
        val dot = View(this)
        connDot = dot
        val dotSize = Theme.dp(this, 9.0f)
        slot.addView(dot, LinearLayout.LayoutParams(dotSize, dotSize))
        val slotSize = Theme.dp(this, Ui.Space.XL)
        val slotLp = LinearLayout.LayoutParams(slotSize, slotSize)
        slotLp.marginEnd = Theme.dp(this, Ui.Space.L)
        row.addView(slot, slotLp)

        val stack = Ui.column(this)

        val status = Ui.text(this, Fa.SET_CONN_UNTESTED, Ui.Type.LABEL, Theme.TEXT, Theme.uiSemi())
        tvTestStatus = status
        status.setSingleLine(true)
        status.ellipsize = android.text.TextUtils.TruncateAt.END
        Ui.rowLabel(status)
        stack.addView(status, Ui.matchWrap())

        // The summary and the problem share one slot: exactly one of them is ever
        // visible, so a bad configuration reads as a CORRECTION of the subtitle
        // rather than as a red block bolted underneath it.
        val summary = Ui.text(this, "", Ui.Type.META, Theme.TEXT_MUTED, Theme.ui())
        summary.setSingleLine(true)
        summary.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
        Ui.rowLabel(summary)
        // AFTER rowLabel, not before it.
        //
        // The endpoint, the model and the protocol are Latin identifiers whatever
        // is typed, so the line is an LTR island — but [Ui.rowLabel] sets
        // FIRST_STRONG, and it used to run last and quietly undo exactly this.
        // The bug only hid because a host name usually starts with a Latin letter;
        // a Persian model name would have flipped the whole `host · model ·
        // protocol` line end to end.
        summary.textDirection = View.TEXT_DIRECTION_LTR
        // Forcing the direction is not enough on its own: an LTR paragraph inside
        // a mirrored row would strand itself at the far (left) edge, away from the
        // state line it belongs under. VIEW_START resolves against the LAYOUT
        // direction, so the island keeps its own reading order and still hugs the
        // reading edge — the same pairing the masked router keys use.
        summary.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
        tvConnSummary = summary
        val summaryLp = Ui.matchWrap()
        summaryLp.topMargin = Theme.dp(this, 2.0f)
        stack.addView(summary, summaryLp)

        val problem = Ui.text(this, "", Ui.Type.META, Theme.DIFF_DEL, Theme.uiMedium())
        problem.setLineSpacing(Theme.dpf(this, 2.0f), 1.0f)
        problem.maxLines = 2
        problem.ellipsize = android.text.TextUtils.TruncateAt.END
        Ui.rowLabel(problem)
        tvConnProblem = problem
        val problemLp = Ui.matchWrap()
        problemLp.topMargin = Theme.dp(this, 2.0f)
        problem.visibility = View.GONE
        stack.addView(problem, problemLp)

        row.addView(stack, Ui.grow())

        val test = testButton()
        val testLp = Ui.wrapWrap()
        testLp.marginStart = Theme.dp(this, Ui.Space.M)
        row.addView(test, testLp)

        row.layoutParams = Ui.matchWrap()
        refreshConnectionCard()
        return row
    }

    /**
     * The compact Test affordance: a 36dp outlined pill, not the app's 48dp
     * [Ui.pillButton].
     *
     * A standard pill is taller than the row it now lives in and would have
     * forced the whole line to 72dp for a control that is pressed once per setup.
     * The outline is [Theme.BORDER_HI] over [Theme.SURFACE] — the same treatment
     * the three fields above it wear, so the row's trailing control matches them.
     */
    private fun testButton(): LinearLayout {
        val pill = Ui.row(this)
        pill.gravity = Gravity.CENTER
        pill.minimumHeight = Theme.dp(this, 36.0f)
        pill.background = Theme.rippleOver(
            Theme.roundStroke(Theme.SURFACE, Theme.BORDER_HI, Theme.R_PILL, 1, this),
            Theme.R_PILL, this
        )
        pill.setPaddingRelative(Theme.dp(this, Ui.Space.M), 0, Theme.dp(this, 14.0f), 0)

        val glyph = ImageView(this)
        glyph.setImageDrawable(Icons.of("plug", Theme.TEXT_MUTED, Ui.STROKE))
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyphSize = Theme.dp(this, 15.0f)
        val glyphLp = LinearLayout.LayoutParams(glyphSize, glyphSize)
        glyphLp.marginEnd = Theme.dp(this, 6.0f)
        pill.addView(glyph, glyphLp)

        val label = Ui.text(this, Fa.SET_TEST_SHORT, Ui.Type.META, Theme.TEXT, Theme.uiSemi())
        label.setSingleLine(true)
        pill.addView(label, Ui.wrapWrap())

        pill.contentDescription = Fa.SET_TEST
        pill.setOnClickListener { runTest() }
        Ui.pressScale(pill)
        return pill
    }

    /**
     * Repaints the connection summary from whatever is in the fields right now.
     *
     * Reads the FIELDS, not the saved preferences, because the two differ for as long
     * as the user is typing — and a summary that lags a keystroke behind is worse than
     * none, since it would confirm a setup the user has already changed.
     */
    private fun refreshConnectionCard() {
        val base = etBase?.text?.toString()?.trimJava() ?: prefs.baseUrl()
        val model = etModel?.text?.toString()?.trimJava() ?: prefs.model()
        var key = etKey?.text?.toString()?.trimJava() ?: prefs.apiKey()
        if (key.isEmpty()) {
            // A router-keys-only setup is valid and chats fine.
            key = prefs.apiKeys().firstOrNull() ?: ""
        }
        val protocol = LlmClient.resolveProtocol(selProtocol, base, model)

        // ONE line, middle-ellipsised: host, model, and the protocol that will
        // actually be resolved. It used to wrap onto three, which is most of why
        // the old card stood 152dp tall.
        val host = Preflight.hostOf(base).ifEmpty { base }
        val line = StringBuilder()
        line.append(host)
        if (model.isNotEmpty()) {
            line.append(SEP).append(model)
        }
        line.append(SEP).append(protocol)
        tvConnSummary?.text = line.toString()

        val problem = Preflight.check(base, key, model)
        val view = tvConnProblem
        val summary = tvConnSummary
        if (view != null && summary != null) {
            if (problem == null) {
                view.visibility = View.GONE
                summary.visibility = View.VISIBLE
            } else {
                // The generic "Open Settings" hint is dropped — we ARE in
                // settings — so only a hint that names a real fix is appended.
                view.text = if (problem.hint == Fa.PRE_OPEN_SETTINGS) {
                    problem.message
                } else {
                    problem.message + " " + problem.hint
                }
                view.visibility = View.VISIBLE
                summary.visibility = View.GONE
            }
        }
        // A key stored without hardware protection is worth saying once, quietly,
        // where the key itself lives.
        val warn = tvKeyWarning
        if (warn != null) {
            val unprotected = prefs.apiKey().isNotEmpty() && !prefs.apiKeyIsEncrypted()
            warn.visibility = if (unprotected) View.VISIBLE else View.GONE
        }
        // A Preflight problem is a KNOWN failure, so the pip reports it before
        // anything is sent. Neither branch overwrites a test verdict that is
        // already on screen — that one was earned by an actual request.
        val state = tvTestStatus?.text?.toString() ?: ""
        if (state.isEmpty() || state == Fa.SET_CONN_UNTESTED) {
            paintConnDot(if (problem == null) Theme.TEXT_FAINT else Theme.DIFF_DEL)
        }
    }

    /** Repaints the connection row's state pip. */
    private fun paintConnDot(color: Int) {
        connDot?.background = Theme.circle(color)
    }

    /**
     * Puts the connection row back to "nothing has been tested yet".
     *
     * Called when a preset chip rewrites all three fields at once: the previous
     * verdict was about a different endpoint entirely, and leaving a green
     * "Connected" under a freshly-swapped provider is the screen lying.
     */
    private fun resetConnectionState() {
        tvTestStatus?.setTextColor(Theme.TEXT)
        tvTestStatus?.text = Fa.SET_CONN_UNTESTED
        paintConnDot(Theme.TEXT_FAINT)
        refreshConnectionCard()
    }

    private fun refreshPresetChips() {
        val activeBase = etBase?.text?.toString()?.trimJava() ?: prefs.baseUrl().trimJava()
        for (i in presetChipViews.indices) {
            if (i >= presetChipData.size) {
                break
            }
            val chip = presetChipViews[i]
            val active = presetChipData[i][1] == activeBase
            chip.background = presetChipBg(active)
            // child 0 is the bullet, child 1 the label
            val bullet = chip.getChildAt(0)
            if (bullet != null) {
                bullet.background = Theme.circle(presetDot(active))
            }
            val label = chip.getChildAt(1) as? TextView
            label?.setTextColor(if (active) Theme.ON_ACCENT else Theme.TEXT)
            label?.typeface = if (active) Theme.uiSemi() else Theme.uiMedium()
        }
    }

    // =====================================================================
    // Segments
    // =====================================================================

    /**
     * The segmented track, as a block INSIDE a group card.
     *
     * The track used to sit bare on the page ground with a hairline outline,
     * because a [Theme.SURFACE_2] track inside a [Theme.SURFACE_2] card is
     * invisible — and that left three of the screen's groups with a different
     * silhouette from the other six, which is exactly the split the redesign
     * removes. The fix is not to take the card away, it is to step the TRACK: the
     * ground is [Theme.SURFACE], the same token every field on this screen uses
     * to separate itself from the card it sits on, with the same [Theme.BORDER_HI]
     * hairline. A track now reads as an input, which is what it is.
     *
     * [Theme.R_MD] rather than a pill, so the track matches the fields above it
     * instead of introducing a third radius.
     *
     * [topDp] is the gap above the track: the full step when the track opens a
     * card, tightened when a [blockCaption] has already opened it and the two
     * have to read as one object.
     */
    private fun segmentContainer(topDp: Float): LinearLayout {
        val container = Ui.row(this)
        // Cells are laid out in CHOICE order, and choice order runs from the
        // reading edge — so "Auto"/"System"/"English" sit on the right in Persian.
        // The repaint helpers index the pill LISTS rather than the container's
        // children, so which physical slot a cell occupies never enters into
        // whether it is the one drawn as chosen.
        container.layoutDirection = Lang.direction(this)
        container.background = Theme.roundStroke(
            Theme.SURFACE, Theme.BORDER_HI, Theme.R_MD, 1, this
        )
        val pad = Theme.dp(this, Ui.Space.XS)
        container.setPadding(pad, pad, pad, pad)
        // The block's own insets match [cardBlock]'s, so a track and a row line up
        // down the card's start edge.
        val lp = Ui.matchWrap()
        lp.marginStart = Theme.dp(this, Ui.Space.L)
        lp.marginEnd = Theme.dp(this, Ui.Space.L)
        lp.topMargin = Theme.dp(this, topDp)
        lp.bottomMargin = Theme.dp(this, 14.0f)
        container.layoutParams = lp
        return container
    }

    /**
     * A segmented track with a [blockCaption] naming what it chooses.
     *
     * The appearance card holds two tracks, and two unlabelled rows of words
     * under one "Appearance" heading say nothing about which is which — least of
     * all to a screen reader, which would read six bare labels in a row. The
     * caption is the same MICRO treatment the preset strip already uses, so this
     * introduces no new shape.
     */
    private fun captionedTrack(caption: String, track: LinearLayout): LinearLayout {
        val block = Ui.column(this)
        val captionLp = Ui.matchWrap()
        captionLp.marginStart = Theme.dp(this, Ui.Space.L)
        captionLp.marginEnd = Theme.dp(this, Ui.Space.L)
        captionLp.topMargin = Theme.dp(this, 14.0f)
        block.addView(blockCaption(caption), captionLp)
        block.addView(track)
        block.layoutParams = Ui.matchWrap()
        return block
    }

    /**
     * The small grey label that names a free-form block inside a card — the
     * preset strip and each of the two appearance tracks.
     *
     * One definition, because these used to be one hand-rolled caption and would
     * have become three. The tracking is [tracking]-guarded: 6% is what gives a
     * MICRO Latin label its "caption" character, and the same 6% applied to
     * Persian opens gaps between letters that are drawn joined.
     */
    private fun blockCaption(value: String): TextView {
        val caption = Ui.text(this, value, Ui.Type.MICRO, Theme.TEXT_MUTED, Theme.uiSemi())
        caption.letterSpacing = tracking(0.06f)
        Ui.rowLabel(caption)
        return caption
    }

    /**
     * Letter-spacing that knows which script it is spacing.
     *
     * Tracking is a Latin typographic device. Persian is a CURSIVE script whose
     * letters join, and Android applies `letterSpacing` between those joined
     * forms too — so a tracked Persian heading reads as a word coming apart.
     * Every tracked line on this screen goes through here and gets zero in
     * Persian, which is the face's own designed fit.
     */
    private fun tracking(value: Float): Float = if (Lang.farsi(this)) 0.0f else value

    /**
     * A cell inside a segmented track: a solid [Theme.ACCENT] tile when chosen
     * (with an [Theme.ON_ACCENT] label, so it inverts correctly in both
     * palettes), bare otherwise. Exactly one thing in the track reads as chosen,
     * and no gradient is involved.
     *
     * [Theme.R_SM] inside the track's [Theme.R_MD] with 4dp of padding between
     * them is CONCENTRIC — the chosen tile's corners are struck from the same
     * centres as the track's. The old pill radius inside a pill track happened to
     * look right; inside a rounded rectangle it read as a lozenge dropped into a
     * box.
     */
    private fun segmentCellBg(selected: Boolean) = if (selected) {
        Theme.actionButton(Theme.R_SM, this)
    } else {
        Theme.rippleTransparent(Theme.R_SM, this)
    }

    /**
     * Repaints one segmented track. Shared by the protocol and theme tracks so
     * both behave identically: [pills] is the track and [isSelected] answers "is
     * cell i the chosen one".
     *
     * The pop the newly-selected cell used to do is gone — this design's motion
     * budget is near zero, and the fill change is already unambiguous.
     *
     * Mirror-safe by construction: [pills] is the order the cells were ADDED, and
     * `getChildAt(0)` is the order the label was added inside its cell. Neither is
     * the order they are drawn in. Under RTL the track paints itself from the
     * right, and index 0 is still index 0 — so the chosen tile and its inverted
     * label always land on the same cell the click came from. A repaint that
     * walked `container.getChildAt(i)` looking for a visual position is the shape
     * of the bug this avoids.
     */
    private fun paintSegment(pills: List<LinearLayout>, isSelected: (Int) -> Boolean) {
        for (i in pills.indices) {
            val cell = pills[i]
            val selected = isSelected(i)
            cell.background = segmentCellBg(selected)
            // The UNSELECTED cells carry full ink now, not muted.
            //
            // They were at TEXT_MUTED, which is 4.82:1 on the SURFACE_2 track — the
            // documented floor — for a control whose whole job is to show four
            // choices. Selection is already unambiguous: the chosen cell is a solid
            // pill. Dimming the others as well made three of the four options harder
            // to read than the body text around them.
            (cell.getChildAt(0) as TextView).setTextColor(
                if (selected) Theme.ON_ACCENT else Theme.TEXT
            )
        }
    }

    /**
     * One cell of a segmented track: a centred, single-line label on a
     * full-height touch target. Both tracks (protocol, theme) build their cells
     * here, so a protocol cell and a theme cell cannot end up disagreeing about
     * type size or padding — which is exactly what happened when each loop
     * hand-rolled its own.
     *
     * The label is `getChildAt(0)`; [paintSegment] repaints it in place.
     */
    private fun segmentCell(label: String): LinearLayout {
        val cell = LinearLayout(this)
        cell.gravity = Gravity.CENTER
        cell.setPadding(
            Theme.dp(this, 2.0f), Theme.dp(this, Ui.Space.M),
            Theme.dp(this, 2.0f), Theme.dp(this, Ui.Space.M)
        )
        // An honest touch target. 10dp of padding around a 13sp label is roughly
        // 36dp, under the 48dp the rest of the app holds itself to.
        cell.minimumHeight = Theme.dp(this, 44.0f)
        val view = Ui.text(this, label, Ui.Type.META, Theme.TEXT, Theme.uiSemi())
        view.gravity = Gravity.CENTER
        // A cell label is not always in the interface's language: the language
        // track deliberately puts "English" and "فارسی" side by side, and the
        // protocol track is Latin in both. FIRST_STRONG lets each label order
        // itself by its own script instead of by the track around it. The
        // alignment stays CENTER — [Ui.rowLabel] would pin it to the start edge
        // and is therefore the wrong helper here.
        view.textDirection = View.TEXT_DIRECTION_FIRST_STRONG
        // Without this "Anthropic" wraps to two lines in a quarter-width cell
        // while "Auto" stays on one, so the four cells end up different heights
        // and the whole track jumps.
        view.setSingleLine(true)
        view.ellipsize = android.text.TextUtils.TruncateAt.END
        cell.addView(view, Ui.wrapWrap())
        return cell
    }

    private fun protocolSegment(): LinearLayout {
        // Opens its card on its own, so it keeps the full step above it.
        val container = segmentContainer(14.0f)
        val labels = arrayOf(
            Fa.SET_PROTO_AUTO, Fa.SET_PROTO_OPENAI, Fa.SET_PROTO_ANTHRO, Fa.SET_PROTO_GEMINI
        )
        protoPills.clear()
        for (i in PROTOCOLS.indices) {
            val value = PROTOCOLS[i]
            val cell = segmentCell(labels[i])
            cell.setOnClickListener {
                selProtocol = value
                prefs.setProvider(value)
                refreshProto()
            }
            Ui.pressScale(cell)
            protoPills.add(cell)
            container.addView(
                cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            )
        }
        refreshProto()
        return container
    }

    private fun refreshProto() {
        if (protoPills.size < PROTOCOLS.size) {
            return
        }
        paintSegment(protoPills) { PROTOCOLS[it] == selProtocol }
        // The protocol is part of what the connection card reports.
        refreshConnectionCard()
    }

    /**
     * The temperature meter — the first of the screen's two dials.
     *
     * It and [thinkLevelSlider] are assembled from the same three pieces
     * ([blockHead], [styleSeek], [meterCaptions]) and therefore cannot drift into
     * two different-looking sliders, which is what they had already done: one had
     * a chip readout and no captions, the other a plain-text readout, a
     * description line and a hint paragraph.
     */
    private fun temperatureBlock(): LinearLayout {
        val col = cardBlock(null)

        val readout = monoReadout(true)
        tvTemp = readout
        col.addView(blockHead("sparkle", Fa.SET_TEMP, readout), Ui.matchWrap())

        val temperature = SeekBar(this)
        sbTemp = temperature
        temperature.max = 200
        temperature.progress = Math.round(prefs.temperature() * 100.0f)
        styleSeek(temperature)
        val tempSbLp = Ui.matchWrap()
        tempSbLp.topMargin = Theme.dp(this, 10.0f)
        // `false`: merely OPENING this screen must not rewrite the stored
        // value. It used to — the readout and the setter shared one call, so a
        // saved 0.735 was silently rounded to 0.74 by looking at the screen.
        updateTemp(temperature.progress, false)
        temperature.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                updateTemp(progress, fromUser)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })
        col.addView(temperature, tempSbLp)
        col.addView(meterCaptions(Fa.SET_TEMP_LOW, Fa.SET_TEMP_HIGH), Ui.matchWrap())

        val hint = Ui.text(this, Fa.SET_TEMP_H, Ui.Type.META, Theme.TEXT_MUTED, Theme.ui())
        hint.setLineSpacing(Theme.dpf(this, 4.0f), 1.0f)
        Ui.rowLabel(hint)
        val hintLp = Ui.matchWrap()
        hintLp.topMargin = Theme.dp(this, Ui.Space.S)
        col.addView(hint, hintLp)
        return col
    }

    /**
     * The reasoning dial: the same meter as [temperatureBlock], plus a live
     * description of what the chosen level actually does.
     *
     * The five-colour heat rail this replaced encoded "more thinking" as HUE,
     * which a monochrome palette cannot express. The ramp survives as LIGHTNESS
     * via [Theme.think] plus a typeface that steps regular → medium → semibold,
     * both applied in [refreshLevel].
     */
    private fun thinkLevelSlider(): LinearLayout {
        val col = cardBlock(null)

        // The same chip the temperature meter wears — but holding a WORD, so it
        // takes the interface's direction rather than the numeric chip's forced
        // LTR. [refreshLevel] repaints its ink and its face as the level steps.
        val readout = monoReadout(false)
        thinkReadout = readout
        col.addView(blockHead("sliders", Fa.SET_THINK_LEVEL, readout), Ui.matchWrap())

        val seek = SeekBar(this)
        sbThink = seek
        seek.max = 4
        seek.progress = idxOfLevel(selLevel)
        styleSeek(seek)
        val seekLp = Ui.matchWrap()
        seekLp.topMargin = Theme.dp(this, 10.0f)
        col.addView(seek, seekLp)
        col.addView(meterCaptions(Fa.TL_LOW, Fa.TL_MAX), Ui.matchWrap())

        // The live description of the CHOSEN level, under the rail where the two
        // captions frame it — it used to sit above the rail, between the label and
        // the control, which pushed the two apart.
        val desc = Ui.text(this, "", Ui.Type.META, Theme.TEXT, Theme.uiMedium())
        thinkDesc = desc
        Ui.rowLabel(desc)
        val descLp = Ui.matchWrap()
        descLp.topMargin = Theme.dp(this, Ui.Space.S)
        col.addView(desc, descLp)

        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                // ONLY a real drag changes the stored level.
                //
                // refreshLevel() parks the thumb on XHIGH while Dynamic Workflow
                // is locked; if that programmatic move also persisted, it would
                // overwrite the user's own choice and they would be stuck on
                // xhigh forever after switching the workflow back off. It would
                // also recurse (set progress -> listener -> refreshLevel -> …).
                if (!fromUser) {
                    return
                }
                selLevel = LEVELS[Math.max(0, Math.min(4, progress))]
                prefs.setThinkingLevel(selLevel)
                refreshLevel()
                bar?.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {}

            override fun onStopTrackingTouch(bar: SeekBar?) {}
        })

        val hint = Ui.text(
            this, Fa.SET_THINK_LEVEL_H, Ui.Type.META, Theme.TEXT_MUTED, Theme.ui()
        )
        hint.setLineSpacing(Theme.dpf(this, 4.0f), 1.0f)
        Ui.rowLabel(hint)
        val hintLp = Ui.matchWrap()
        hintLp.topMargin = Theme.dp(this, 6.0f)
        col.addView(hint, hintLp)

        refreshLevel()
        return col
    }

    /**
     * What the chosen reasoning level actually does, in one line.
     *
     * An instance method rather than the static table it used to be: these are
     * five sentences a user reads, they were the last untranslated prose on the
     * screen, and a `companion object` has no Context to ask which language to
     * write them in. [Lang.text] rather than [Fa] keys only because Fa.kt is not
     * this file's to edit — see the note in the report.
     */
    private fun levelDesc(index: Int): String = when (index) {
        0 -> Lang.text(this, "Fast and concise", "سریع و کوتاه", "快速简洁")
        1 -> Lang.text(this, "Balanced for everyday tasks", "متعادل برای کارهای روزمره", "日常任务均衡")
        2 -> Lang.text(
            this, "More detailed analysis and checking", "تحلیل و بازبینی دقیق‌تر", "更详细的分析与检查"
        )
        3 -> Lang.text(
            this, "Multi-path exploration and verification", "بررسی چند مسیر و راستی‌آزمایی", "多路径探索与验证"
        )
        else -> Lang.text(
            this, "Maximum possible accuracy and depth", "بیشترین دقت و ژرفای ممکن", "尽可能准确与深入"
        )
    }

    private fun idxOfLevel(level: String?): Int {
        for (i in LEVELS.indices) {
            if (LEVELS[i] == level) {
                return i
            }
        }
        return 1
    }

    private fun refreshLevel() {
        // Show what will ACTUALLY be used. Dynamic Workflow raises the floor to
        // XHIGH, and a readout still saying "medium" while the agent reasons at
        // xhigh is simply wrong.
        val idx = idxOfLevel(
            if (prefs.dynamicWorkflow() && selLevel != "max") "xhigh" else selLevel
        )
        val labels = arrayOf(Fa.TL_LOW, Fa.TL_MED, Fa.TL_HIGH, Fa.TL_XHIGH, Fa.TL_MAX)
        thinkReadout?.let {
            it.text = labels[idx]
            // Lightness AND weight step together. With the hue gone, one signal
            // on its own is too quiet to read as a ramp: [Theme.think] walks a
            // grey from faint to full text colour, and the face walks with it.
            it.setTextColor(Theme.think(idx))
            it.typeface = when {
                idx <= 1 -> Theme.ui()
                idx == 2 -> Theme.uiMedium()
                else -> Theme.uiSemi()
            }
        }
        thinkDesc?.let {
            it.text = levelDesc(idx)
        }
        sbThink?.let { bar ->
            // LOCKED while Dynamic Workflow is on. That mode raises the floor to
            // XHIGH, so leaving the slider draggable would let the user pick a
            // level the agent then silently ignores — the control would be lying.
            // It parks on XHIGH, dims, and stops accepting touches until the
            // toggle is turned off.
            val locked = prefs.dynamicWorkflow() && selLevel != "max"
            bar.isEnabled = !locked
            bar.alpha = if (locked) 0.55f else 1.0f
            if (locked && bar.progress != idx) {
                bar.progress = idx
            }
        }
    }

    private fun themeSegment(): LinearLayout {
        // Sits under a caption, so the gap above it closes to Space.S.
        val container = segmentContainer(Ui.Space.S)
        val labels = arrayOf(Fa.THEME_SYSTEM, Fa.THEME_LIGHT, Fa.THEME_DARK)
        themePills.clear()
        for (i in 0 until 3) {
            val value = THEMES[i]
            val cell = segmentCell(labels[i])
            cell.setOnClickListener {
                if (value != selTheme && !applyingTheme) {
                    applyingTheme = true
                    applyTextSettingsNow()
                    selTheme = value
                    prefs.setThemeMode(value)
                    // Deliberately do NOT touch the global palette here. Mutating
                    // it before recreate() leaves the whole visible tree painted
                    // in the old palette while every lazily-built drawable (focus
                    // rings, ripples, toasts) draws in the new one. onCreate
                    // applies it once, atomically, for the rebuilt tree.
                    recreate()
                }
            }
            Ui.pressScale(cell)
            themePills.add(cell)
            container.addView(
                cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            )
        }
        refreshThemeSeg()
        return container
    }

    private fun refreshThemeSeg() {
        paintSegment(themePills) { THEMES[it] == selTheme }
    }

    /**
     * The interface language, as the appearance card's second track.
     *
     * Each cell is written in its OWN script — `English` and `فارسی`, never
     * "Persian" and never "انگلیسی" — because this is the one control on the
     * screen whose job is to be legible to someone who cannot read the language
     * it is currently set to. An endonym is an identifier here, in the same
     * category as `OpenAI` and `Vega Agent`, which is why the Persian cell is a
     * literal rather than a translated string: `Fa.SET_LANGUAGE_FA` renders as
     * "Persian" while the interface is English, which is exactly the label this
     * cell must not carry.
     *
     * Changing it rebuilds the screen the same way a theme change does, and for
     * the same reason: [Fa] is read at paint time by every view already on
     * screen, so the only honest way to change it is to paint them all again.
     * `recreate()` is right HERE — this Activity is a leaf with no run state —
     * and is banned in `MainActivity`, which owns a live agent run.
     */
    private fun languageSegment(): LinearLayout {
        val container = segmentContainer(Ui.Space.S)
        // One endonym per value, in LANGUAGES order. Written in its own script
        // and never translated: see FA_ENDONYM.
        val labels = arrayOf(ZH_ENDONYM, Fa.SET_LANGUAGE_EN, FA_ENDONYM)
        langPills.clear()
        for (i in LANGUAGES.indices) {
            val value = LANGUAGES[i]
            val cell = segmentCell(labels[i])
            cell.setOnClickListener {
                if (value != selLanguage && !applyingTheme) {
                    applyingTheme = true
                    // FIRST, and for a sharper reason than the theme cells have:
                    // the pending 350ms text flush is dropped by the rebuild, so
                    // a base URL or a key that is still mid-edit would be lost —
                    // and unlike a theme flip, a language flip is something a
                    // user does WHILE setting the screen up for the first time.
                    applyTextSettingsNow()
                    selLanguage = value
                    prefs.setLanguage(value)
                    // Picking a language here answers the same question the
                    // first-launch picker asks, so it must not be asked again.
                    prefs.setLanguageChosen()
                    // Applied HERE, which is the opposite of what the theme cell
                    // above deliberately does — and the two are not inconsistent.
                    //
                    // A palette mutation is visible immediately: every drawable
                    // built lazily after it (ripples, focus rings) would paint in
                    // the new colours over a tree still painted in the old ones.
                    // The string table is not like that. It is read only at the
                    // instant something assigns `text =`, every view on screen
                    // already holds its string, and the one pending callback that
                    // would re-assign any was just flushed. What flipping it early
                    // buys is a screen that stays self-consistent if `recreate()`
                    // is DEFERRED — the same OEM relaunch quirk onStop guards
                    // against — instead of reading a stored "fa" through an
                    // English table for as long as the relaunch takes.
                    Fa.apply(this)
                    recreate()
                }
            }
            Ui.pressScale(cell)
            langPills.add(cell)
            container.addView(
                cell, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            )
        }
        refreshLanguageSeg()
        return container
    }

    private fun refreshLanguageSeg() {
        paintSegment(langPills) { (if (it == 0) "zh" else "en") == selLanguage }
    }

    // =====================================================================
    // Connection test + save
    // =====================================================================

    private fun runTest() {
        val base = etBase?.text?.toString()?.trimJava() ?: ""
        var key = etKey?.text?.toString()?.trimJava() ?: ""
        if (key.isEmpty()) {
            // A router-keys-only setup is explicitly valid (Prefs.isConfigured
            // accepts it) and chats fine, but the test used to send no key at
            // all and reported a hard auth failure.
            key = prefs.apiKeys().firstOrNull() ?: ""
        }
        val model = etModel?.text?.toString()?.trimJava() ?: ""
        if (base.isEmpty() || model.isEmpty()) {
            say(Fa.SET_NEED_FIELDS, false)
            return
        }
        val generation = ++connectionTestGeneration
        val resolvedProtocol = LlmClient.resolveProtocol(selProtocol, base, model)
        tvTestStatus?.setTextColor(Theme.TEXT_MUTED)
        tvTestStatus?.text = Fa.SET_TESTING
        paintConnDot(Theme.TEXT_MUTED)
        Thread {
            val failure = LlmClient.testConnection(
                base, key, model, resolvedProtocol, prefs.timeoutSeconds()
            )
            ui.post {
                if (generation != connectionTestGeneration || isFinishing || isDestroyed) {
                    return@post
                }
                // The diff inks, not Theme.RED / Theme.GREEN.
                //
                // Those two are near-black GREYS in this palette — a deliberate
                // choice everywhere else, and the wrong one here: "reachable" and
                // "rejected" are opposites, not two amounts of one thing, and two
                // shades of grey cannot say which is which at a glance. The diff
                // pair is the palette's one sanctioned hue, defined for exactly
                // that distinction, and this is the only place on the screen that
                // borrows it.
                if (failure != null) {
                    tvTestStatus?.setTextColor(Theme.DIFF_DEL)
                    tvTestStatus?.text = failure
                    paintConnDot(Theme.DIFF_DEL)
                } else {
                    tvTestStatus?.setTextColor(Theme.DIFF_ADD)
                    tvTestStatus?.text = Fa.SET_TEST_OK
                    paintConnDot(Theme.DIFF_ADD)
                }
            }
        }.start()
    }

    private fun pick3(en: String, fa: String, zh: String): String = Lang.text(this, en, fa, zh)

    private fun runSearchTest() {
        searchTestToken?.cancel()
        val token = CancellationToken()
        searchTestToken = token
        val generation = ++searchTestGeneration
        prefs.setBraveApiKey(etBraveKey?.text?.toString() ?: "")
        prefs.setTavilyApiKey(etTavilyKey?.text?.toString() ?: "")
        prefs.setSearxngBaseUrl(etSearxngUrl?.text?.toString() ?: "")
        NetworkPolicy.applyPrefs(prefs)
        val manager = dev.wind.agent.search.SearchProviderManager(prefs)
        tvSearchStatus?.text = "Testing..."
        Thread({
            val outcome = try {
                manager.search(dev.wind.agent.search.SearchRequest("OpenAI"), token) { target ->
                    // Explicit user-initiated Settings test; not an agent/task capability bypass.
                    try { NetworkPolicy.requireSafeHttps(target.toString()); true } catch (_: Exception) { false }
                }
            } catch (_: CancellationToken.CancelledException) {
                Web.SearchOutcome("CANCELLED", emptyList())
            } catch (error: Exception) {
                Web.SearchOutcome("WEB_SEARCH_FAILED: ${error.javaClass.simpleName}", emptyList())
            }
            val success = outcome.results.isNotEmpty()
            ui.post {
                if (generation != searchTestGeneration || isFinishing || isDestroyed) return@post
                searchTestToken = null
                if (success) {
                    tvSearchStatus?.text = "OK ✓ (" + outcome.results.size + " results)"
                } else {
                    tvSearchStatus?.text = outcome.text
                }
            }
        }, "test-search").start()
    }

    private fun updateTemp(progress: Int, persist: Boolean) {
        // Locale.US formats the VALUE — the app must never render 0,70 because a
        // phone is set to a comma locale — and [localizeDigits] then renders the
        // digits in the interface's own numerals. The separator stays an ASCII
        // '.': it is the one mark in the string that is part of the number's
        // notation rather than its script, and swapping it for U+066B would be
        // the only place in the app that does.
        tvTemp?.text = localizeDigits(String.format(Locale.US, "%.2f", progress / 100.0f))
        if (persist) {
            prefs.setTemperature(progress / 100.0f)
        }
    }

    /**
     * Rewrites the ASCII digits of an already-FORMATTED value in the interface's
     * own numerals, leaving everything else alone.
     *
     * [Lang.num] takes a number, and a temperature readout is a string with a
     * separator in it. Mapping digit by digit THROUGH that helper keeps one
     * forward digit table in the app instead of a second copy here — this file
     * already owns the reverse map ([normalizeDigits]) for what the user types,
     * and two tables pointing opposite ways is how they drift.
     */
    private fun localizeDigits(value: String): String {
        val sb = StringBuilder(value.length)
        for (ch in value) {
            if (ch in '0'..'9') {
                sb.append(Lang.num(this, ch - '0'))
            } else {
                sb.append(ch)
            }
        }
        return sb.toString()
    }

    private fun installInstantTextSettings() {
        val watcher = object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}

            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                scheduleTextSettings()
                // The connection summary describes the LIVE fields, so it has to move
                // with them — a summary a keystroke behind would confirm a setup the
                // user has already changed.
                refreshConnectionCard()
            }

            override fun afterTextChanged(editable: Editable?) {}
        }
        etBase?.addTextChangedListener(watcher)
        etKey?.addTextChangedListener(watcher)
        etModel?.addTextChangedListener(watcher)
        etMaxTok?.addTextChangedListener(watcher)
        etTimeout?.addTextChangedListener(watcher)
        etContextWindow?.addTextChangedListener(watcher)
        etSys?.addTextChangedListener(watcher)
        etBraveKey?.addTextChangedListener(watcher)
        etTavilyKey?.addTextChangedListener(watcher)
        etSearxngUrl?.addTextChangedListener(watcher)
        // These replace the feedback listener toggleRow() installs, so they have
        // to re-apply the haptic tick themselves — otherwise these three switches
        // would be the only ones in the app that flip silently.
        swWorkflow?.setOnCheckedChangeListener { view, checked ->
            prefs.setDynamicWorkflow(checked)
            // Switching the mode moves the reasoning dial to that mode's home
            // position: XHIGH (locked) while it is on, MEDIUM (freely
            // adjustable) when it is off. Leaving the dial wherever the forced
            // level had parked it would strand the user on xhigh with no
            // indication that it was the workflow's doing, not their choice.
            if (!prefs.modelProviders.hasAccounts()) {
                selLevel = if (checked) "xhigh" else "medium"
                prefs.setThinkingLevel(selLevel)
                sbThink?.progress = idxOfLevel(selLevel)
            }
            Ui.tick(view)
            // Turning this on raises the effective reasoning level to XHIGH, so
            // repaint the slider — otherwise it keeps showing the old level while
            // the agent actually runs at a higher one.
            refreshLevel()
        }
        swWeb?.setOnCheckedChangeListener { view, checked ->
            prefs.setWebSearch(checked)
            Ui.tick(view)
        }
        swLocalNet?.setOnCheckedChangeListener { view, checked ->
            prefs.setAllowLocalNetwork(checked)
            Ui.tick(view)
        }
    }

    private fun scheduleTextSettings() {
        ui.removeCallbacks(applyTextSettings)
        ui.postDelayed(applyTextSettings, 350L)
    }

    private fun applyTextSettingsNow() {
        ui.removeCallbacks(applyTextSettings)
        if (resetting) {
            // "Reset settings" clears prefs then recreates; recreate() runs
            // onPause first, which used to write the still-populated fields
            // straight back and silently un-reset base URL, model, max tokens
            // and the custom system prompt.
            return
        }
        etBraveKey?.text?.toString()?.trimJava()?.let { prefs.setBraveApiKey(it) }
        etTavilyKey?.text?.toString()?.trimJava()?.let { prefs.setTavilyApiKey(it) }
        etSearxngUrl?.text?.toString()?.trimJava()?.let { prefs.setSearxngBaseUrl(it) }
        etContextWindow?.text?.toString()?.toIntOrNull()?.takeIf { it in 8192..2000000 }?.let { prefs.setContextWindowTokens(it) }
        val baseField = etBase ?: return
        val base = baseField.text.toString().trimJava()
        val model = etModel?.text?.toString()?.trimJava() ?: ""
        val key = etKey?.text?.toString()?.trimJava() ?: ""
        if (!prefs.modelProviders.hasAccounts() && base.isNotEmpty()) {
            prefs.setBaseUrl(base)
        }
        if (!prefs.modelProviders.hasAccounts() && model.isNotEmpty()) {
            prefs.setModel(model)
        }
        val maxTokens = parseInt(etMaxTok?.text?.toString() ?: "", -1)
        if (maxTokens > 0) {
            prefs.setMaxTokens(maxTokens)
        }
        val timeout = parseInt(etTimeout?.text?.toString() ?: "", -1)
        if (timeout > 0) {
            // Prefs clamps to [MIN, MAX], so a typo like 1 or 99999 becomes a
            // usable value instead of an app that hangs or fails every request.
            prefs.setTimeoutSeconds(timeout)
        }
        prefs.setSystemPrompt(etSys?.text?.toString()?.trimJava() ?: "")
        if (!prefs.modelProviders.hasAccounts() && key != lastSavedKey) {
            // The save itself no longer fails when the keystore is unavailable — the
            // key is stored unencrypted instead, because refusing to save it left the
            // app permanently unusable on those devices. Only a genuine disk failure
            // returns false now, and the warning is about PROTECTION, not about
            // whether the key was kept.
            if (prefs.setApiKey(key)) {
                lastSavedKey = key
                if (key.isNotEmpty() && !prefs.apiKeyIsEncrypted()) {
                    say(Fa.SET_KEYSTORE_UNAVAILABLE, true)
                }
            } else {
                say(Fa.ERR_UNKNOWN, true)
            }
        }
        refreshPresetChips()
        refreshConnectionCard()
    }

    override fun onPause() {
        applyTextSettingsNow()
        super.onPause()
    }

    override fun onResume() {
        super.onResume()
        if (!isFinishing && prefs.modelProviders.hasAccounts() != renderedModelAccounts) {
            renderedModelAccounts = prefs.modelProviders.hasAccounts()
            recreate()
            return
        }
        // The palette is process-global; the chat screen's own theme toggle can
        // have swapped it while this screen sat on the back stack. Rebuild only
        // when the generation we painted with is no longer the live one.
        Theme.applyFromPrefs(this, prefs)
        if (Theme.revision != appliedRevision && !isFinishing) {
            appliedRevision = Theme.revision
            recreate()
        }
    }

    override fun onConfigurationChanged(configuration: android.content.res.Configuration) {
        super.onConfigurationChanged(configuration)
        // The screen is a single vertical scroll, so it reflows on its own; the
        // only change worth rebuilding for is a palette flip. Previously this
        // Activity declared no configChanges at all, so every rotation or
        // font-size change destroyed it — losing the scroll position, the
        // focused field, the pending router key and any in-flight connection
        // test (none of which are saved, since no view here has an id).
        Theme.applyFromPrefs(this, prefs)
        if (Theme.revision != appliedRevision && !isFinishing) {            appliedRevision = Theme.revision
            recreate()
        }
    }

    override fun onStop() {
        super.onStop()
        // Guards against double-taps queueing two recreate()s. It is normally
        // moot because recreate() yields a fresh instance, but when it is
        // deferred (activity not resumed, OEM relaunch quirks) the theme cells
        // would stay dead forever with no way to recover in-screen.
        applyingTheme = false
    }

    /**
     * Mirrors the fade MainActivity opens this screen with, so leaving matches
     * arriving. Overriding finish() covers every exit — the masthead chevron, the
     * system back gesture and any programmatic close — in one place.
     *
     * A theme change closes this screen via recreate(), which must NOT be dressed
     * as a navigation; the flag lets that path fall through cleanly.
     */
    override fun finish() {
        super.finish()
        if (applyingTheme) {
            return
        }
        try {
            @Suppress("DEPRECATION")
            overridePendingTransition(R.anim.settings_pop_enter, R.anim.settings_pop_exit)
        } catch (ignored: Throwable) {
        }
    }

    override fun onDestroy() {
        searchTestGeneration++
        searchTestToken?.cancel()
        searchTestToken = null
        dev.wind.agent.runtime.linux.LinuxToolPackJobs.removeObserver(extensionObserver)
        Sheet.dismissAll()
        connectionTestGeneration++
        ui.removeCallbacks(applyTextSettings)
        ui.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    // =====================================================================
    // Key Router
    // =====================================================================

    /**
     * The key-router group. Its content is not row-shaped — an input paired with
     * a button, a counter, and a variable-length list — so it lives in a single
     * free-form [cardBlock] inside the group card. The long explanation moved out
     * to a footnote under the card.
     */
    private fun keyRouterSection(): LinearLayout {
        val card = card()
        val block = cardBlock(card)

        // Add row: masked input (same chrome as the primary key field) + button.
        val addRow = Ui.row(this)

        val box = Ui.row(this)
        // An LTR island, like a code block: an API key is always Latin, so the
        // whole field — the text AND the reveal button — is laid out as one
        // left-to-right unit rather than inheriting the row's direction.
        box.layoutDirection = View.LAYOUT_DIRECTION_LTR
        box.background = fieldBg(false)
        // setPadding, not setPaddingRelative, and that is correct HERE and only
        // here: the island has just pinned itself to LTR, so its physical left IS
        // its start. The 8/4 split leans the text away from the reveal button,
        // which must stay on the same side of the key in both languages.
        box.setPadding(Theme.dp(this, Ui.Space.S), 0, Theme.dp(this, Ui.Space.XS), 0)
        // The Add button beside it is the app's standard 48dp pill, and a 40dp
        // field next to a 48dp button was the one mismatched pair on the screen.
        // Matching the HEIGHT rather than the padding leaves the input's own
        // metrics — shared with the two key fields above — untouched.
        box.minimumHeight = Theme.dp(this, 48.0f)

        val input = EditText(this)
        input.typeface = Theme.ui()
        etNewKey = input
        input.hint = "sk-…"
        input.setHintTextColor(Theme.TEXT_FAINT)
        input.setTextColor(Theme.TEXT)
        input.textSize = Ui.Type.LABEL
        input.setSingleLine(true)
        input.background = null
        input.textDirection = View.TEXT_DIRECTION_LTR
        input.layoutDirection = View.LAYOUT_DIRECTION_LTR
        input.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        input.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        input.setOnFocusChangeListener { _, hasFocus ->
            box.background = fieldBg(hasFocus)
        }
        // 10dp, the same as field() and the primary key field: three inputs on one
        // screen used 8dp, 10dp and 11dp and therefore three different heights.
        val inputPadV = Theme.dp(this, 10.0f)
        input.setPadding(Theme.dp(this, Ui.Space.XS), inputPadV, 0, inputPadV)
        box.addView(input, Ui.grow())
        box.addView(revealButton(input))
        addRow.addView(box, Ui.grow())

        val addLp = Ui.wrapWrap()
        addLp.marginStart = Theme.dp(this, Ui.Space.S)
        addRow.addView(
            Ui.pillButton(this, Fa.SET_KEY_ADD, "plus", Ui.PRIMARY) { addRouterKey() }, addLp
        )
        block.addView(addRow, Ui.matchWrap())

        val rows = Ui.column(this)
        keysBox = rows
        val rowsLp = Ui.matchWrap()
        rowsLp.topMargin = Theme.dp(this, Ui.Space.S)
        block.addView(rows, rowsLp)
        refreshKeyRows()
        cardNote(card, Fa.SET_KEY_ROUTER_H)
        return card
    }

    /**
     * The key-router heading, with the `n/50` counter on its trailing edge.
     *
     * [Ui.sectionLabel] returns the heading ROW, so a trailing readout can be
     * appended to it — the label itself stays `getChildAt(0)`, which is the
     * contract that helper documents.
     */
    private fun routerHeading(): LinearLayout {
        val heading = Ui.sectionLabel(this, Fa.SET_KEY_ROUTER)
        val spacer = View(this)
        heading.addView(spacer, Ui.grow())
        // A count, so: numeric chip. The greedy spacer between the label and the
        // chip is what puts the counter on the heading's trailing edge — the left
        // in Persian — without either side needing to know which edge that is.
        val count = monoReadout(true)
        tvKeyCount = count
        heading.addView(count, Ui.wrapWrap())
        return heading
    }

    private fun addRouterKey() {
        val key = etNewKey?.text?.toString()?.trimJava() ?: ""
        if (key.isEmpty()) {
            return
        }
        val current = prefs.apiKeys()
        if (current.size >= Prefs.MAX_ROUTER_KEYS) {
            say(Fa.SET_KEY_FULL, false)
            return
        }
        if (current.contains(key)) {
            say(Fa.SET_KEY_DUP, false)
            return
        }
        if (!prefs.addApiKey(key)) {
            say(Fa.SET_KEYSTORE_UNAVAILABLE, true)
            return
        }
        etNewKey?.setText("")
        refreshKeyRows()
    }

    private fun refreshKeyRows() {
        val container = keysBox ?: return
        container.removeAllViews()
        val keys = prefs.apiKeys()
        // "۳/۵۰" in Persian. A count the interface is REPORTING is prose in the
        // interface's numerals, unlike the key strings themselves, which are
        // Latin secrets and stay exactly as typed.
        tvKeyCount?.text =
            Lang.num(this, keys.size) + "/" + Lang.num(this, Prefs.MAX_ROUTER_KEYS)

        if (keys.isEmpty()) {
            // No sunken panel: the group card is already SURFACE_2, so a panel in
            // the same token drew an invisible box around this line.
            val empty = Ui.text(this, Fa.SET_KEY_EMPTY, Ui.Type.META, Theme.TEXT_MUTED, Theme.ui())
            empty.gravity = Gravity.CENTER
            val emptyPad = Theme.dp(this, Ui.Space.M)
            empty.setPadding(0, emptyPad, 0, emptyPad)
            container.addView(empty, Ui.matchWrap())
            return
        }

        for (i in keys.indices) {
            val index = i
            val row = Ui.row(this)
            // The SAME ground as every field on this screen — a SURFACE fill with
            // a BORDER_HI hairline — rather than a borderless fill. The stored
            // keys sit directly under the input that adds them, and a bare tile
            // next to an outlined one made two adjacent objects of the same size
            // and colour look like a rendering slip.
            row.background = fieldBg(false)
            row.setPaddingRelative(
                Theme.dp(this, 10.0f), Theme.dp(this, Ui.Space.XS),
                Theme.dp(this, Ui.Space.XS), Theme.dp(this, Ui.Space.XS)
            )

            // The router tries keys in order, so the position is the one fact
            // about a key that matters; the glyph marks the row as a key.
            val glyph = ImageView(this)
            glyph.setImageDrawable(Icons.of("key", Theme.TEXT_MUTED, Ui.STROKE))
            glyph.scaleType = ImageView.ScaleType.FIT_CENTER
            glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            val glyphSize = Theme.dp(this, 18.0f)
            val glyphLp = LinearLayout.LayoutParams(glyphSize, glyphSize)
            glyphLp.marginEnd = Theme.dp(this, Ui.Space.M)
            row.addView(glyph, glyphLp)

            val tv = Ui.text(this, maskKey(keys[i]), Ui.Type.META, Theme.TEXT, Theme.mono())
            tv.textDirection = View.TEXT_DIRECTION_LTR
            // Forcing the TEXT direction resolves the view to LTR, which sends the
            // glyphs to the far edge of a weighted slot; pinning the ALIGNMENT
            // keeps the masked key beside the glyph that labels it.
            tv.textAlignment = View.TEXT_ALIGNMENT_VIEW_START
            tv.setSingleLine(true)
            tv.ellipsize = android.text.TextUtils.TruncateAt.MIDDLE
            row.addView(tv, Ui.grow())

            row.addView(
                Ui.iconButton(this, "trash", 18.0f, Theme.TEXT_MUTED) {
                    prefs.removeApiKey(index)
                    refreshKeyRows()
                }
            )
            val rowLp = Ui.matchWrap()
            rowLp.topMargin = Theme.dp(this, 6.0f)
            container.addView(row, rowLp)
        }
    }

    // =====================================================================
    // Reset
    // =====================================================================

    /**
     * The reset group — a single row, not a card wrapped around a red button.
     * [Theme.RED] is near-black ink in this palette, so a "danger" pill would
     * have read as an ordinary primary button; the row's LABEL is the warning,
     * and the confirmation sheet is where the destructive step actually happens.
     */
    private fun resetSection(): LinearLayout {
        val card = card()
        card.addView(
            Ui.cardRow(this, "refresh", Fa.SET_RESET, Fa.SET_RESET_H, chevron()) {
                confirmReset()
            }
        )
        return card
    }

    private fun confirmReset() {
        val sheet = Sheet(this)
        sheet.header("refresh", Fa.SET_RESET, null)

        val msg = TextView(this)
        msg.typeface = Theme.ui()
        msg.text = Fa.SET_RESET_MSG
        msg.setTextColor(Theme.TEXT_MUTED)
        msg.textSize = Ui.Type.LABEL
        msg.setLineSpacing(Theme.dpf(this, 3.0f), 1.0f)
        // A raw TextView, so it starts with none of the treatment Ui.text's
        // callers get: FIRST_STRONG plus a start-edge alignment is what makes the
        // warning read from the right in Persian and from the left in English.
        Ui.rowLabel(msg)
        val msgLp = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        msgLp.bottomMargin = Theme.dp(this, 18.0f)
        sheet.body.addView(msg, msgLp)

        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        // Said here rather than left to inherit. A Sheet is a Dialog in its own
        // window and takes NOTHING from this Activity's tree — [Sheet] mirrors
        // its own panel and body, and the moment a caller builds a container of
        // its own inside that body, the caller owns the question again. Mirrored,
        // this puts Cancel on the right and the destructive button on the left,
        // which is the mirror of where they sit in English.
        row.layoutDirection = Lang.direction(this)
        val cancel = Ui.pillButton(this, Fa.CANCEL, null, Ui.SECONDARY) { sheet.dismiss() }
        val cancelLp = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        cancelLp.marginEnd = Theme.dp(this, 8.0f)
        row.addView(cancel, cancelLp)
        row.addView(
            Ui.pillButton(this, Fa.SET_RESET, "refresh", Ui.DANGER) {
                resetting = true
                ui.removeCallbacks(applyTextSettings)
                prefs.clearAll()
                lastSavedKey = ""
                say(Fa.SET_RESET_DONE, false)
                sheet.dismiss()
                recreate()
            },
            LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
        )
        sheet.body.addView(
            row,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )
        sheet.show()
    }

    // =====================================================================
    // Building blocks
    // =====================================================================

    /**
     * A settings group: the shared [Ui.groupedCard] — a flat [Theme.SURFACE_2]
     * card at [Theme.R_CARD] with no border, no elevation and NO padding of its
     * own, so the rows inside run full-bleed and paint their own ripple to the
     * card's edges.
     *
     * The old version was a [Theme.glassCard] with a hairline border, a 2dp
     * elevation and 16dp of padding all round; all three are gone with the
     * grouped-card look.
     */
    private fun card(): LinearLayout = Ui.groupedCard(this)

    /**
     * A free-form padded block inside a group card, for the handful of controls
     * that are not row-shaped (the two sliders, the prompt box, the key list) and
     * so cannot use [Ui.cardRow]'s geometry. Its insets match a row's, so a block
     * and a row line up down the card's start edge.
     *
     * [parent] may be null when the caller wants to add the block itself (see
     * [thinkLevelSlider], which returns one).
     */
    private fun cardBlock(parent: LinearLayout?): LinearLayout {
        val block = Ui.column(this)
        block.setPaddingRelative(
            Theme.dp(this, Ui.Space.L), Theme.dp(this, Ui.Space.M),
            Theme.dp(this, Ui.Space.L), Theme.dp(this, Ui.Space.M)
        )
        block.layoutParams = Ui.matchWrap()
        if (parent != null) {
            parent.addView(block)
        }
        return block
    }

    /**
     * A group's explanation, as the LAST block inside its own card.
     *
     * These used to float on the page ground below the card, which is the
     * reference screens' habit and the wrong one here. It made the rhythm lumpy —
     * card, small gap, prose, big gap, heading, card — and it detached the prose
     * from the thing it describes, so a paragraph between two cards read as
     * belonging to neither. Under a hairline, on the card's own ground, it is
     * unambiguously part of the group and the page returns to a single beat:
     * heading, card, heading, card.
     *
     * Returned so a caller can dim it, hide it, or hold on to it — the API-key
     * warning is a note that only appears when the key is stored unprotected.
     */
    private fun cardNote(parent: LinearLayout, value: String): LinearLayout =
        cardNote(parent, value, Theme.TEXT_MUTED)

    /**
     * As [cardNote], in a specific ink — the API-key warning is the one note on
     * this screen that is not neutral.
     *
     * The hairline and the paragraph are returned as ONE block on purpose: the
     * warning is hidden whenever the key is properly protected, and hiding only
     * the text would leave its rule stranded at the bottom of the card.
     */
    private fun cardNote(parent: LinearLayout, value: String, ink: Int): LinearLayout {
        val block = Ui.column(this)
        rowDivider(block, 0.0f)
        // TEXT_MUTED, not TEXT_FAINT, for the neutral case. A note is the sentence
        // that explains what a setting DOES — the text most likely to be read
        // carefully by someone who is unsure — and it used to be set in the palette's
        // faintest ink, one step above the disabled tone.
        val view = Ui.text(this, value, Ui.Type.META, ink, Theme.ui())
        view.setLineSpacing(Theme.dpf(this, 4.0f), 1.0f)
        Ui.rowLabel(view)
        view.setPaddingRelative(
            Theme.dp(this, Ui.Space.L), Theme.dp(this, 14.0f),
            Theme.dp(this, Ui.Space.L), Theme.dp(this, 14.0f)
        )
        block.addView(view, Ui.matchWrap())
        parent.addView(block, Ui.matchWrap())
        return block
    }

    /**
     * A hairline between two rows of a card, inset by [insetDp] from the start
     * edge.
     *
     * [Ui.divider]'s own documentation says a divider inside a grouped card is
     * almost always the wrong answer, and until now this screen used none at all.
     * That is right for a card holding two or three unlike things, and wrong for
     * one holding eight identically-shaped rows: with nothing between them a
     * dense card reads as a single slab of text, and the eye has to use the
     * switches on the trailing edge to work out where one row stops.
     *
     * [ROW_INSET] parts the labels while the glyph column runs on unbroken, which
     * is what makes a list read as a list. Blocks that have no glyph — a note, a
     * meter, a segmented track — pass 0 and get a full-bleed rule instead, so the
     * hairline always starts where the content above it starts.
     */
    private fun rowDivider(parent: LinearLayout, insetDp: Float) {
        val line = Ui.divider(this)
        // Repainted to BORDER_HI, because [Ui.divider]'s own [Theme.BORDER] is
        // invisible HERE specifically.
        //
        // A card is SURFACE_2, and the two tokens are three levels apart on the
        // light palette (0xEDEDED on 0xF0F0F0) and two on the dark one (0x262626
        // on 0x242424) — a rule nobody can see, which would have looked exactly
        // like the "no separation at all" this replaces. BORDER_HI is twenty
        // levels clear of the card in BOTH palettes, so the line reads the same
        // either way. The masthead's rule keeps the default: it sits on the page
        // ground, where BORDER has contrast to spare.
        line.setBackgroundColor(Theme.BORDER_HI)
        val params = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, Theme.hairline(this)
        )
        params.marginStart = Theme.dp(this, insetDp)
        parent.addView(line, params)
    }

    /**
     * The head of a free-form block: the glyph in the same slot a [Ui.cardRow]
     * gives it, the label at body size, and a trailing readout.
     *
     * Sharing this is what keeps a meter aligned with the rows above and below
     * it. Both dials used to draw their own head, and neither drew the glyph at
     * all — so the two blocks in the middle of a card full of rows were the only
     * things on the screen whose labels started 36dp further in.
     */
    private fun blockHead(icon: String, label: String, trailing: View): LinearLayout {
        val head = Ui.row(this)

        val glyph = ImageView(this)
        glyph.setImageDrawable(Icons.of(icon, Theme.TEXT_MUTED, Ui.STROKE))
        glyph.scaleType = ImageView.ScaleType.FIT_CENTER
        glyph.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val glyphSize = Theme.dp(this, Ui.Space.XL)
        val glyphLp = LinearLayout.LayoutParams(glyphSize, glyphSize)
        glyphLp.marginEnd = Theme.dp(this, Ui.Space.L)
        head.addView(glyph, glyphLp)

        val title = Ui.text(this, label, Ui.Type.BODY, Theme.TEXT, Theme.ui())
        Ui.rowLabel(title)
        head.addView(title, Ui.grow())

        val trailingLp = Ui.wrapWrap()
        trailingLp.marginStart = Theme.dp(this, Ui.Space.S)
        head.addView(trailing, trailingLp)
        return head
    }

    /**
     * The value chip a meter carries on its trailing edge.
     *
     * [numeric] separates the two things this chip is asked to hold, because
     * they want opposite treatment and getting either wrong is visible:
     *
     *  * **A number** (`0.70`, `3/50`) reads left-to-right in Persian exactly as
     *    it does in English — Persian digits carry the EN bidi class — so it
     *    keeps `TEXT_DIRECTION_LTR` and comes out in the written order. It gives
     *    up the mono face in Persian though: JetBrains Mono has no ۰-۹ at all, so
     *    asking for it would only guarantee a silent per-glyph fallback to a face
     *    nobody chose, at metrics that no longer line up. The chip's identity is
     *    its ground, stroke and radius, not its typeface.
     *  * **A word** (the reasoning level's name) must follow the INTERFACE. Under
     *    a forced LTR paragraph the two words of "خیلی زیاد" come out in the
     *    wrong order — which is exactly what this chip did before, because one
     *    helper served both cases.
     */
    private fun monoReadout(numeric: Boolean): TextView {
        val face = if (numeric && Lang.farsi(this)) Theme.ui() else Theme.mono()
        val view = Ui.text(this, "", Ui.Type.META, Theme.TEXT, face)
        view.textDirection = if (numeric) {
            View.TEXT_DIRECTION_LTR
        } else {
            Lang.textDirection(this)
        }
        view.setSingleLine(true)
        view.background = Theme.roundStroke(
            Theme.SURFACE, Theme.BORDER_HI, Theme.R_PILL, 1, this
        )
        val padH = Theme.dp(this, 10.0f)
        val padV = Theme.dp(this, 3.0f)
        view.setPaddingRelative(padH, padV, padH, padV)
        return view
    }

    /**
     * The one rail treatment on this screen.
     *
     * A default Android SeekBar draws a 2dp track with a 20dp thumb that carries
     * the platform's own 48dp ripple halo — which on a monochrome card reads as a
     * smudge following your finger. The thumb here is an explicit
     * [Theme.circle] sized to 16dp: deliberate, flat, the same ink as the filled
     * part of the rail, and the same on both dials.
     */
    private fun styleSeek(seek: SeekBar) {
        seek.splitTrack = false
        // BOTH dials run low -> high from the layout's START edge, so in Persian
        // they fill from the right.
        //
        // This is a reversal, and it is deliberate. The rail used to be pinned to
        // LTR on the reasoning that "a dial is an amount, not text". An amount is
        // still laid out along an AXIS, and the axis a reader scans is the axis
        // their language runs on: a Persian speaker reads the start of the range
        // where they start every other line on this screen, which is the right.
        // Pinned LTR, the "Precise" end of Temperature sat under the far edge and
        // dragging toward the reading edge made the number go DOWN — a mirrored
        // screen with one control still thinking in English.
        //
        // Both halves of the platform's behaviour move together here, so there is
        // no half-mirrored state to fall into: `AbsSeekBar` consults one flag
        // (`mirrorForRtl`, set by the Material seekbar style) for the fill it
        // draws AND for the value it computes from a touch. When the flag is off
        // the bar simply behaves as it does today.
        seek.layoutDirection = Lang.direction(this)
        seek.progressTintList = ColorStateList.valueOf(Theme.ACCENT)
        seek.progressBackgroundTintList = ColorStateList.valueOf(Theme.BORDER_HI)
        seek.thumbTintList = ColorStateList.valueOf(Theme.ACCENT)
        val thumbSize = Theme.dp(this, 16.0f)
        val thumb = Theme.circle(Theme.ACCENT)
        thumb.setSize(thumbSize, thumbSize)
        seek.thumb = thumb
        // An honest touch strip: the rail itself is 2dp, and a 2dp target is not
        // a control anyone can grab.
        seek.minimumHeight = Theme.dp(this, 32.0f)
    }

    /**
     * The two [Ui.Type.MICRO] labels naming the ends of a meter's range.
     *
     * The captions take the rail's direction, whatever it is — [startText] has to
     * land under the rail's ZERO end, and since [styleSeek] now mirrors the rail
     * with the interface, that end is the layout's start edge in both languages.
     * Asking `Lang.direction` in both places is what keeps them from disagreeing;
     * a caption row pinned to LTR under a mirrored rail would label "Precise" as
     * the maximum.
     */
    private fun meterCaptions(startText: String, endText: String): LinearLayout {
        val row = Ui.row(this)
        row.layoutDirection = Lang.direction(this)
        val low = Ui.text(this, startText, Ui.Type.MICRO, Theme.TEXT_FAINT, Theme.uiMedium())
        low.setSingleLine(true)
        row.addView(low, Ui.wrapWrap())
        val spacer = View(this)
        row.addView(spacer, Ui.grow())
        val high = Ui.text(this, endText, Ui.Type.MICRO, Theme.TEXT_FAINT, Theme.uiMedium())
        high.setSingleLine(true)
        row.addView(high, Ui.wrapWrap())
        return row
    }

    /**
     * Input ground inside a group card.
     *
     * [Theme.inputBg] fills with [Theme.SURFACE_2] — the card's own token — so on
     * this screen it drew an invisible box. A field steps to [Theme.SURFACE]
     * instead: lighter than the card on the light palette, darker on the dark
     * one, and clearly a field in both.
     *
     * The stroke WIDTH never changes between the two states, only its colour: a
     * GradientDrawable stroke is inset geometry, so a fatter focused ring would
     * reflow the text box every time focus moved.
     */
    private fun fieldBg(focused: Boolean): GradientDrawable = Theme.roundStroke(
        Theme.SURFACE, if (focused) Theme.ACCENT else Theme.BORDER_HI, Theme.R_SM, 1, this
    )

    /**
     * The trailing "this opens something" chevron, pointing away from the
     * layout's start edge.
     */
    private fun chevron(): ImageView {
        val view = ImageView(this)
        view.setImageDrawable(
            Icons.of(
                Lang.chevronForward(this),
                // TEXT_MUTED, not TEXT_FAINT. Measured on the light palette, FAINT is
                // 3.24:1 against SURFACE_2 — under the 4.5 floor, and this is the mark
                // that tells you a row opens something. MUTED is 4.82:1 and still
                // clearly secondary to the label it sits beside.
                Theme.TEXT_MUTED,
                Ui.STROKE
            )
        )
        view.scaleType = ImageView.ScaleType.FIT_CENTER
        view.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val size = Theme.dp(this, 18.0f)
        view.layoutParams = LinearLayout.LayoutParams(size, size)
        return view
    }

    /**
     * A labelled input as a card row: the glyph and the label on the start edge,
     * the EditText itself as the row's TRAILING control.
     *
     * The label used to be a separate 12sp letter-spaced line above a full-width
     * box; as a row it matches every other row in the group and halves the
     * vertical space the provider card takes. The input keeps a weight of 1.5
     * against the label's 1.0, because a base URL needs the room and a label
     * does not.
     */
    private fun field(
        parent: LinearLayout,
        icon: String,
        label: String,
        value: String,
        inputType: Int,
        hint: String,
        ltr: Boolean
    ): EditText {
        val editText = EditText(this)
        editText.typeface = Theme.ui()
        editText.setText(value)
        editText.hint = hint
        editText.setHintTextColor(Theme.TEXT_FAINT)
        editText.setTextColor(Theme.TEXT)
        editText.textSize = Ui.Type.LABEL
        editText.setSingleLine(true)
        editText.inputType = inputType
        if (ltr) {
            editText.textDirection = View.TEXT_DIRECTION_LTR
            editText.layoutDirection = View.LAYOUT_DIRECTION_LTR
            editText.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        }
        editText.background = fieldBg(false)
        editText.setOnFocusChangeListener { _, hasFocus ->
            editText.background = fieldBg(hasFocus)
        }
        // Space.M/Space.S, both on the scale, and taller than the 8dp it had.
        //
        // A LABEL-size input with 8dp of vertical padding, inside a row that has its
        // own 12dp, was the most cramped thing on the screen — and it sat next to the
        // key field, which used 11dp, so two adjacent inputs were different heights
        // for no reason anyone chose.
        val padH = Theme.dp(this, Ui.Space.M)
        val padV = Theme.dp(this, 10.0f)
        editText.setPadding(padH, padV, padH, padV)
        editText.minimumHeight = Theme.dp(this, 40.0f)
        editText.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f
        )
        parent.addView(Ui.cardRow(this, icon, label, null, editText, null))
        return editText
    }

    /**
     * The primary API key: the same card row as [field], except the trailing
     * control is an LTR island holding the masked input AND its reveal button.
     */
    private fun addKeyField(parent: LinearLayout) {
        val box = Ui.row(this)
        // An LTR island, like a code block: an API key is always Latin, so the
        // whole field — the text AND the reveal button — is laid out as one
        // left-to-right unit rather than inheriting the row's direction.
        box.layoutDirection = View.LAYOUT_DIRECTION_LTR
        box.background = fieldBg(false)
        // setPadding, not setPaddingRelative, and that is correct HERE and only
        // here: the island has just pinned itself to LTR, so its physical left IS
        // its start. The 8/4 split leans the text away from the reveal button,
        // which must stay on the same side of the key in both languages.
        box.setPadding(Theme.dp(this, Ui.Space.S), 0, Theme.dp(this, Ui.Space.XS), 0)

        val keyField = EditText(this)
        keyField.typeface = Theme.ui()
        etKey = keyField
        keyField.setText(prefs.apiKey())
        keyField.hint = "sk-…"
        keyField.setHintTextColor(Theme.TEXT_FAINT)
        keyField.setTextColor(Theme.TEXT)
        keyField.textSize = Ui.Type.LABEL
        keyField.setSingleLine(true)
        keyField.background = null
        keyField.textDirection = View.TEXT_DIRECTION_LTR
        keyField.layoutDirection = View.LAYOUT_DIRECTION_LTR
        keyField.gravity = Gravity.START or Gravity.CENTER_VERTICAL
        keyField.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        keyField.setOnFocusChangeListener { _, hasFocus ->
            box.background = fieldBg(hasFocus)
        }
        val keyPadV = Theme.dp(this, 10.0f)
        // Nudge the text/hint a touch away from the eye icon so "sk-…" no longer
        // reads as if it's tucked under the eye.
        keyField.setPadding(Theme.dp(this, Ui.Space.XS), keyPadV, 0, keyPadV)
        box.addView(keyField, Ui.grow())
        box.addView(revealButton(keyField))

        box.layoutParams = LinearLayout.LayoutParams(
            0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.5f
        )
        parent.addView(Ui.cardRow(this, "key", Fa.SET_API_KEY, null, box, null))
    }

    /**
     * The show/hide toggle at the end of an API-key field. Both key inputs (the
     * primary one and the router's) used to carry their own copy of this block,
     * and they had already drifted apart once.
     */
    private fun revealButton(target: EditText): ImageView {
        val eye = ImageView(this)
        var revealed = false
        eye.setImageDrawable(Icons.of("eye", Theme.TEXT_MUTED, Ui.STROKE))
        // The button sits INSIDE an LTR island, but what a screen reader says
        // about it is prose and follows the interface, not the island.
        eye.contentDescription = Lang.text(this, "Show or hide key", "نمایش یا پنهان کردن کلید", "显示或隐藏密钥")
        eye.scaleType = ImageView.ScaleType.FIT_CENTER
        // 36dp touch target with an 18dp glyph — was a bare 20dp icon.
        val eyeBox = Theme.dp(this, 36.0f)
        val eyePad = Theme.dp(this, 9.0f)
        eye.setPadding(eyePad, eyePad, eyePad, eyePad)
        eye.background = Theme.rippleTransparent(Theme.R_PILL, this)
        eye.layoutParams = LinearLayout.LayoutParams(eyeBox, eyeBox)
        eye.setOnClickListener {
            revealed = !revealed
            val caret = target.selectionEnd
            target.inputType = (
                if (revealed) {
                    InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
                } else {
                    InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
                ) or InputType.TYPE_CLASS_TEXT
            target.textDirection = View.TEXT_DIRECTION_LTR
            target.layoutDirection = View.LAYOUT_DIRECTION_LTR
            try {
                target.setSelection(caret)
            } catch (e: Exception) {
            }
            eye.setImageDrawable(
                Icons.of(if (revealed) "eye-off" else "eye", Theme.TEXT_MUTED, Ui.STROKE)
            )
        }
        return eye
    }

    /**
     * Switch row: a [Ui.cardRow] whose trailing control is the [Switch]. The
     * whole row is the touch target — a label next to a switch invites a tap on
     * the words, which used to do nothing.
     */
    private fun toggleRow(
        parent: LinearLayout,
        icon: String,
        label: String,
        subtitle: String?,
        checked: Boolean
    ): Switch {
        val toggle = Switch(this)
        toggle.isChecked = checked
        val states = arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf())
        toggle.thumbTintList = ColorStateList(
            states, intArrayOf(Theme.ACCENT, if (Theme.DARK) Theme.TEXT_FAINT else Theme.SURFACE)
        )
        toggle.trackTintList = ColorStateList(
            states,
            intArrayOf(
                Theme.alpha(Theme.ACCENT, 110), Theme.alpha(Theme.TEXT_FAINT, 70)
            )
        )
        toggle.layoutParams = Ui.wrapWrap()
        // Flipping a setting confirms itself with a haptic tick. The pop that
        // used to ride along with it is outside this design's motion budget.
        toggle.setOnCheckedChangeListener { view, _ -> Ui.tick(view) }
        parent.addView(Ui.cardRow(this, icon, label, subtitle, toggle) { toggle.toggle() })
        return toggle
    }

    // =====================================================================
    // About
    // =====================================================================

    /**
     * Every transient message this screen shows, in one place — because the one
     * thing worth saying about a Toast under a mirrored interface is worth saying
     * once.
     *
     * A Toast is a SYSTEM window. It inherits nothing from this Activity: not the
     * palette, not the theme, and not `layoutDirection`. Nor can that be fixed
     * from here — `Toast.getView()` returns null from API 30 and `setView` throws,
     * so there is no view to reach into on any device this ships to.
     *
     * It does not need fixing. The platform's toast layout centres its text and
     * leaves `textDirection` at the default, which resolves to FIRST_STRONG — so a
     * Persian message lays itself out right-to-left from its own first strong
     * character, and the one Latin message here (a URL with no handler) lays
     * itself out left-to-right, both inside a centred block where alignment
     * cannot be wrong. What WOULD break is a message that mixed a leading Latin
     * token into Persian prose; every string below is one language or the other.
     */
    private fun say(message: String, long: Boolean) {
        Toast.makeText(
            this, message, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        private val LEVELS = arrayOf("low", "medium", "high", "xhigh", "max")
        private val THEMES = arrayOf(Prefs.THEME_SYSTEM, Prefs.THEME_LIGHT, Prefs.THEME_DARK)

        /**
         * Every value [Prefs.setLanguage] accepts, in cell order. Chinese leads
         * because it is now the stored default; in Persian the track is
         * mirrored, so the leading cell is simply the one on the right.
         */
        private val LANGUAGES = arrayOf("zh", "en", "fa")

        /**
         * Persian's name for itself.
         *
         * A literal, and not [Fa.SET_LANGUAGE_FA], because that key answers
         * "what is this language called in the language you are reading" — it
         * says "Persian" to an English reader — and the one thing this cell must
         * never do is name Persian in a script a Persian-only reader cannot read.
         * Same category as `OpenAI` and `Vega Agent`: an endonym is a name.
         */
        private const val FA_ENDONYM = "فارسی"

        /**
         * Chinese's name for itself, in the same category: 中文, never "Chinese"
         * and never "中文" translated into the interface's current language.
         */
        private const val ZH_ENDONYM = "中文"
        private val PROTOCOLS = arrayOf(
            Prefs.PROV_AUTO, Prefs.PROV_OPENAI, Prefs.PROV_ANTHRO, Prefs.PROV_GEMINI
        )

        /**
         * The inset an in-card [Ui.divider] starts at: [Ui.Space.L] of row
         * padding plus a [Ui.Space.XL] glyph plus [Ui.Space.L] of gap, which is
         * exactly where a [Ui.cardRow]'s label begins. The rule therefore parts
         * the TEXT of two rows while the glyph column runs on unbroken.
         */
        private const val ROW_INSET = 52.0f

        /** The separator in the connection row's one-line summary. */
        private const val SEP = "  ·  "

        private fun parseInt(value: String, fallback: Int): Int = try {
            // Normalise Persian (۰-۹) and Arabic-Indic (٠-٩) digits to ASCII
            // first. A Persian keyboard enters Persian numerals, and "۱۰۰۰۰".toInt()
            // throws — which silently discarded the user's Maximum-tokens value.
            val parsed = normalizeDigits(value.trimJava()).toInt()
            if (parsed > 0) parsed else fallback
        } catch (e: Exception) {
            fallback
        }

        /** Maps Persian and Arabic-Indic digit code points onto ASCII 0-9. */
        private fun normalizeDigits(input: String): String {
            val sb = StringBuilder(input.length)
            for (ch in input) {
                val c = when (ch) {
                    in '۰'..'۹' -> '0' + (ch - '۰') // Persian ۰-۹
                    in '٠'..'٩' -> '0' + (ch - '٠') // Arabic ٠-٩
                    else -> ch
                }
                sb.append(c)
            }
            return sb.toString()
        }

        private fun maskKey(key: String?): String {
            if (key.isNullOrEmpty()) {
                return ""
            }
            if (key.length <= 10) {
                return "••••••"
            }
            return key.substring(0, 6) + "…" + key.substring(key.length - 4)
        }
    }
}
