package dev.wind.agent.webprovider

import android.content.Context
import android.util.AtomicFile
import java.io.File
import org.json.JSONObject

/** Bounded local state, atomic writes, no raw DOM or credentials. */
class WebProviderStore(context: Context) {
    private val root = File(context.applicationContext.filesDir, "web-provider-state")
    private fun safeId(value: String): String {
        require(value.matches(Regex("[a-zA-Z0-9_-]{1,180}"))) { "INVALID_WEB_STATE_ID" }
        return value
    }
    @Synchronized fun read(chatId: String): WebSendReceipt? {
        val f = AtomicFile(File(root, "${safeId(chatId)}.json"))
        if (!f.baseFile.exists()) return null
        val bytes = f.openRead().use { input ->
            require(f.baseFile.length() <= 2_000_000) { "WEB_STATE_TOO_LARGE" }
            input.readBytes()
        }
        return WebSendReceipt.fromJson(JSONObject(String(bytes, Charsets.UTF_8)))
    }
    @Synchronized fun write(receipt: WebSendReceipt) {
        root.mkdirs()
        receipt.updatedAt = System.currentTimeMillis()
        require(receipt.answer.length <= 500000) { "WEB_ANSWER_TOO_LARGE" }
        val f = AtomicFile(File(root, "${safeId(receipt.chatId)}.json"))
        val data = receipt.toJson().toString().toByteArray(Charsets.UTF_8)
        require(data.size <= 2_000_000) { "WEB_STATE_TOO_LARGE" }
        val stream = f.startWrite()
        try { stream.write(data); f.finishWrite(stream) }
        catch (failure: Throwable) { f.failWrite(stream); throw failure }
    }
    @Synchronized fun clearProvider(id: String) {
        require(WebProviderRegistry.get(id) != null)
        root.listFiles()?.filter { it.isFile && it.name.endsWith(".json") }?.forEach { file ->
            val content = file.takeIf { it.length() <= 2_000_000 }?.readText(Charsets.UTF_8)
            if (content != null && JSONObject(content).optString("providerId") == id) AtomicFile(file).delete()
        }
    }
    @Synchronized fun clearChat(id: String) { AtomicFile(File(root, "${safeId(id)}.json")).delete() }
}
