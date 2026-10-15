package dev.wind.agent.phone

/** Android/JSON-free classification shared by initial approval and live-target recheck. */
internal object PhoneRisk {
    private val patterns = linkedMapOf(
        "payment" to Regex("支付|付款|转账|购买|下单|收银|银行卡|钱包|pay|payment|purchase|transfer|checkout|bank|wallet"),
        "account" to Regex("密码|验证码|登录|授权|权限|安全|password|verification|login|sign.in|permission|security|authorize"),
        "destructive" to Regex("删除|清空|重置|卸载|格式化|delete|erase|reset|uninstall|format"),
        "send" to Regex("发送|发布|提交|拨号|呼叫|send|publish|submit|post|dial|call"))
    fun categories(pkg: String, action: String, declared: String, editorAction: String, target: NodeFacts?, page: String, visual: Boolean): Set<String> {
        require(declared in setOf("normal", "send", "payment", "account", "destructive", "unknown")) { "PHONE_SENSITIVITY_INVALID" }
        val out = linkedSetOf<String>()
        if (declared != "normal") out.add(declared)
        if (action in setOf("back", "home", "recents", "notifications", "scroll", "swipe")) return out
        val evidence = (pkg + " " + target?.let { "${it.text} ${it.desc} ${it.resId}" }.orEmpty()).lowercase()
        patterns.forEach { (category, pattern) ->
            val relevant = evidence + if (category != "send" && action in setOf("tap", "long_press", "click")) " ${page.lowercase()}" else ""
            if (pattern.containsMatchIn(relevant)) out.add(category)
        }
        if (action == "ime_action" && editorAction in setOf("auto", "send", "go", "done")) out.add("submit")
        if (out.isEmpty() && action in setOf("tap", "long_press", "click") &&
            (visual || target == null || target.text.isBlank() && target.desc.isBlank())) out.add("unclassified_interaction")
        return out
    }
    fun extra(approved: Set<String>, current: Set<String>, fullyManaged: Boolean = false): Set<String> =
        if (fullyManaged) emptySet() else current - approved
}
