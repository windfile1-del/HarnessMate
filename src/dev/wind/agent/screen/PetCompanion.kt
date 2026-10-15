package dev.wind.agent.screen

import android.content.Context
import android.content.SharedPreferences
import java.util.Locale

/** Only a coarse, local animation choice is retained; no page text, images or model requests. */
internal object PetCompanion {
    @Volatile private var preferences: SharedPreferences? = null
    @Volatile private var mode = "off"
    @Volatile private var observed = PetActivity.State.IDLE
    @Volatile var monitorConnected = false
    val states = listOf(PetActivity.State.READING, PetActivity.State.WATCHING, PetActivity.State.LISTENING,
        PetActivity.State.GAMING, PetActivity.State.BROWSING, PetActivity.State.CHATTING,
        PetActivity.State.NAVIGATING, PetActivity.State.STUDYING)

    fun initialize(context: Context) {
        if (preferences != null) return
        synchronized(this) {
            if (preferences == null) {
                val prefs = context.applicationContext.getSharedPreferences("pet_companion", Context.MODE_PRIVATE)
                mode = prefs.getString("mode", "off") ?: "off"
                preferences = prefs
            }
        }
    }

    fun choose(value: String) {
        if (value != "off" && value != "auto" && states.none { it.name == value }) return
        mode = value; observed = PetActivity.State.IDLE
        preferences?.edit()?.putString("mode", value)?.apply()
    }

    fun modeName(): String = mode
    fun shouldObserve() = mode == "auto" && ScreenShareSession.hasWindow &&
        !ScreenShareSession.mainVisible && !ScreenShareSession.controlsVisible && !ScreenShareSession.appActivitiesVisible
    fun observe(value: PetActivity.State?) { observed = value ?: PetActivity.State.IDLE }
    fun clear() { observed = PetActivity.State.IDLE }
    fun current(): PetActivity.State = when (mode) {
        "off" -> PetActivity.State.IDLE
        "auto" -> if (monitorConnected && ScreenShareSession.hasWindow) observed else PetActivity.State.IDLE
        else -> states.firstOrNull { it.name == mode } ?: PetActivity.State.IDLE
    }

    /** Heuristic UI hints, not a claim of understanding a video, novel, message or game. */
    fun classify(packageName: String, hints: String): PetActivity.State? {
        val pkg = packageName.lowercase(Locale.ROOT)
        val text = hints.lowercase(Locale.ROOT)
        fun app(vararg keys: String) = keys.any { pkg.contains(it) }
        fun page(vararg keys: String) = keys.any { text.contains(it) }
        return when {
            app("kindle", "ireader", "qidian", "fanqie", "novel", "ebook", "readera", "moonreader", "dragon.read", "weread", "qqreader", "zlibrary") ||
                page("下一章", "上一章", "章节目录", "阅读设置", "翻页模式", "next chapter", "table of contents") -> PetActivity.State.READING
            app("music", "spotify", "soundcloud", "podcast", "audiobook") ||
                page("歌词", "播放队列", "lyrics", "now playing") -> PetActivity.State.LISTENING
            app("bilibili", "danmaku.bili", "youtube", "netflix", "youku", "iqiyi", "douyin", "ugc.aweme", "tiktok", "kuaishou", "gifmaker", "videoplayer", "mxtech") ||
                page("videoview", "playercontrolview", "视频播放", "倍速播放", "全屏播放", "playback speed") -> PetActivity.State.WATCHING
            app("maps", "amap", "autonavi", "baidumap", "navigation") || page("开始导航", "路线规划", "start navigation", "route options") -> PetActivity.State.NAVIGATING
            app("game", "minecraft", "mihoyo", "hoyoverse", "supercell", "roblox", "tencent.tmgp") -> PetActivity.State.GAMING
            app("duolingo", "anki", "notion", "onenote", "obsidian", "study", "course") ||
                page("学习进度", "课程目录", "flashcards", "practice lesson") -> PetActivity.State.STUDYING
            app("wechat", "tencent.mm", "mobileqq", "whatsapp", "telegram", "messenger", "discord", "slack", "chat") -> PetActivity.State.CHATTING
            app("browser", "chrome", "firefox", "edge", "shopping", "taobao", "jd.") ||
                page("scrollview", "recyclerview", "网页", "搜索结果") -> PetActivity.State.BROWSING
            else -> null
        }
    }
}
