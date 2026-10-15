package dev.wind.agent.phone

/** These are expected setup states, not transport failures or permission to guess another recipient. */
internal object ChatSetupReadiness {
    fun diagnostic(code: String): Pair<String,String>?=when(code) {
        "CHAT_UI_TREE_UNAVAILABLE" -> "VISUAL_BINDING_REQUIRED" to "副屏节点树为空，需要本地截图识别并由用户核对完整联系人、标记输入框后绑定，不能据此判定不在目标聊天。"
        "CHAT_SCREEN_CHANGED_DURING_OCR", "CHAT_SCREEN_CHANGED_DURING_CAPTURE" -> "WINDOW_NOT_READY" to "读取期间页面发生变化，保留原副屏并重新 activate。"
        "CHAT_EXACT_PEER_REQUIRED" -> "NAVIGATION_REQUIRED" to "使用此副屏的 display_session_id 和 screen=virtual，进入标题完全一致的指定单聊后再 activate；保留原副屏，不重新 create。联系人名称含表情时必须保留表情。"
        "CHAT_SINGLE_TEXT_EDITOR_REQUIRED" -> "TEXT_EDITOR_REQUIRED" to "目标单聊标题已核对，但未发现唯一可操作文字输入框；在同一副屏切换到文字输入模式并重新观察，不用主屏或猜测发送按钮。"
        "CHAT_DRAFT_PRESENT" -> "USER_DRAFT_PRESENT" to "输入框中存在草稿，请用户接管处理；托管不会覆盖或发送已有草稿。"
        "CHAT_PAGE_NOT_VERIFIED", "VIRTUAL_DISPLAY_WINDOW_NOT_READY", "VIRTUAL_DISPLAY_PACKAGE_NOT_READY",
        "VIRTUAL_DISPLAY_ROOT_UNAVAILABLE", "PHONE_WINDOW_CHANGED" ->
            "WINDOW_NOT_READY" to "应用窗口尚未就绪或存在遮挡；在同一副屏重新 observe，完成获准导航后再 activate。"
        else -> null
    }
}
