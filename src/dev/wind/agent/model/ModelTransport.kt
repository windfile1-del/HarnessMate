package dev.wind.agent.model

import dev.wind.agent.CancellationToken
import dev.wind.agent.Chat
import dev.wind.agent.LlmClient
import org.json.JSONArray

/** A model channel does not grant any authority to execute tools. */
data class ModelRequest(val messages: JSONArray, val chat: Chat)

interface ModelStreamCallback {
    fun onToken(text: String)
    fun onThinking(text: String)
    fun onDone(text: String?)
    fun onError(message: String)
    fun onRetry() {}
    fun onUsage(usage: dev.wind.agent.context.TokenUsage) {}
    fun onUsageSnapshot(usage: dev.wind.agent.context.TokenUsage) {}
    fun onTruncated() {}
    fun isCancelled(): Boolean = false
    /** Browser DOM updates can replace text, rather than append to it. */
    fun onSnapshot(text: String) {}
    fun onState(state: String) {}
}

interface ModelTransport {
    fun stream(request: ModelRequest, token: CancellationToken, callback: ModelStreamCallback)
}

class ApiModelTransport(private val client: LlmClient) : ModelTransport {
    override fun stream(request: ModelRequest, token: CancellationToken, callback: ModelStreamCallback) {
        client.promptCacheScope = request.chat.id
        client.streamChat(request.messages, token, object : LlmClient.StreamCallback {
            override fun onToken(text: String) = callback.onToken(text)
            override fun onThinking(text: String) = callback.onThinking(text)
            override fun onDone(text: String?) = callback.onDone(text)
            override fun onError(message: String) = callback.onError(message)
            override fun onRetry() = callback.onRetry()
            override fun onUsage(usage: dev.wind.agent.context.TokenUsage) = callback.onUsage(usage)
            override fun onUsageSnapshot(usage: dev.wind.agent.context.TokenUsage) = callback.onUsageSnapshot(usage)
            override fun onTruncated() = callback.onTruncated()
            override fun isCancelled(): Boolean = callback.isCancelled()
        })
    }
}
