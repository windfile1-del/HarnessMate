package dev.wind.agent.phone

internal object PhoneInterruptions {
    data class Candidate(val node: NodeFacts, val kind: String, val text: String, val confidence: Double)
    private val negative = setOf("跳过", "跳过广告", "稍后", "稍后再说", "以后再说", "下次再说", "暂不", "暂不更新", "知道了", "我知道了", "关闭", "取消", "不再提醒",
        "skip", "skip ad", "later", "not now", "close", "cancel", "dismiss", "got it")
    private val contexts = linkedMapOf("update" to "更新|新版本|update|new version", "youth" to "青少年|teen|youth",
        "advertisement" to "广告|活动|红包|advert|promotion", "notification" to "通知|权限|定位|notification|permission|location",
        "rating" to "评分|rate|rating", "region" to "切换.*站|全球站|switch.*region|global site")
    fun negativeButton(node: NodeFacts): Boolean {
        if (!node.actionable || !node.clickable) return false
        val text = node.text.trim().lowercase(); val desc = node.desc.trim().lowercase()
        // A negative accessibility description must never override an affirmative visible label.
        val forbidden = Regex("允许|同意|确定|立即更新|去开启|领取|支付|付款|购买|删除|清空|重置|取消订单|\\b(allow|agree|confirm|pay|purchase|delete|reset)\\b")
        if (forbidden.containsMatchIn(text) || forbidden.containsMatchIn(desc)) return false
        return text in negative || desc in negative || (text in setOf("×", "✕", "x") || desc in setOf("×", "✕", "x")) && node.width <= 160 && node.height <= 160
    }
    fun detect(nodes: List<NodeFacts>, dialogWindow: Boolean): List<Candidate> {
        val page = nodes.joinToString(" ") { it.text + " " + it.desc }.lowercase()
        val kind = contexts.entries.firstOrNull { Regex(it.value).containsMatchIn(page) }?.key
        val dialog = dialogWindow || nodes.any { it.cls.contains("dialog", true) }
        if (kind == null && !dialog) return emptyList()
        return nodes.filter(::negativeButton).take(8).map {
            Candidate(it, kind ?: "dialog", it.text.ifBlank { it.desc }, if (kind != null && dialog) 0.95 else 0.8)
        }
    }
    /** Automatic task recovery requires stronger evidence than a report/manual dismiss. */
    fun automatic(nodes: List<NodeFacts>, dialogWindow: Boolean, height: Int): Candidate? {
        val candidate = detect(nodes, dialogWindow).singleOrNull() ?: return null
        val page = nodes.joinToString(" ") { it.text + " " + it.desc }
        val strong = Regex("青少年模式|切换.{0,12}(站点|全球站)|发现新版本|版本更新|更新提示|通知权限|开启通知|为.{0,12}评分|new version|switch.{0,12}region|notification permission", RegexOption.IGNORE_CASE)
        if (!dialogWindow && !strong.containsMatchIn(page) && candidate.text != "跳过广告") return null
        // Ordinary search Cancel next to banner text must not be treated as a modal dismiss button.
        if (!dialogWindow && candidate.text != "跳过广告" && candidate.node.centerY < height * 0.15) return null
        return candidate
    }
}
