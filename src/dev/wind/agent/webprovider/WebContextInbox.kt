package dev.wind.agent.webprovider

import dev.wind.agent.Message

/** Host integrations enqueue already-authorized results here; this API never invokes MCP/tools. */
object WebContextInbox {
    private data class Pending(val context: MutableList<WebContextItem> = mutableListOf(), val files: MutableList<Message.Attachment> = mutableListOf())
    private val pending = HashMap<String, Pending>()
    @Synchronized fun add(chatId: String, item: WebContextItem) {
        require(item.kind in WebContextItem.allowedKinds && item.text.length <= 24000)
        val data = pending.getOrPut(chatId) { Pending() }
        require(data.context.size < 12) { "TOO_MANY_CONTEXT_ITEMS" }
        data.context.add(item)
    }
    @Synchronized fun addImage(chatId: String, image: Message.Attachment) {
        require(image.kind == "image")
        val data = pending.getOrPut(chatId) { Pending() }
        require(data.files.size < 8) { "TOO_MANY_WEB_ATTACHMENTS" }
        data.files.add(image)
    }
    @Synchronized fun labels(chatId: String): List<String> = pending[chatId]?.let { data ->
        data.context.map { it.kind + ": " + it.label } + data.files.map { it.name ?: "image" }
    } ?: emptyList()
    @Synchronized fun clear(chatId: String) { pending.remove(chatId) }
    @Synchronized fun consume(chatId: String, message: Message) {
        pending.remove(chatId)?.let { message.webContexts.addAll(it.context); message.attachments.addAll(it.files) }
    }
}
