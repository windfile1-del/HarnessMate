package dev.wind.agent.webprovider

import android.content.Context
import android.content.Intent
import dev.wind.agent.AgentBus
import dev.wind.agent.AgentService
import dev.wind.agent.Chat
import dev.wind.agent.ChatStore
import dev.wind.agent.MainActivity
import dev.wind.agent.Message
import dev.wind.agent.Prefs

object WebChatActions {
    fun regenerate(context: Context, chat: Chat) {
        val question = synchronized(chat.messages) { chat.messages.lastOrNull { it.role == "user" } }
            ?: throw IllegalStateException("USER_QUESTION_REQUIRED")
        val message = Message("user",question.content).apply { webOperation = "REGENERATE" }
        start(context,chat,message)
    }
    fun editBranch(context: Context, original: Chat, question: String, includeHistory: Boolean) {
        require(question.isNotBlank())
        val binding = original.webBinding ?: throw IllegalStateException("WEB_BINDING_REQUIRED")
        val branch = ChatStore(context).create()
        branch.title = original.title.take(150) + " · branch"
        branch.webBinding = WebConversationBinding(binding.providerId,WebProviderPrefs(context).profile(binding.providerId),includeHistory = includeHistory)
        if (includeHistory) synchronized(original.messages) {
            val at = original.messages.indexOfLast { it.role == "user" }
            original.messages.take(at.coerceAtLeast(0)).filter { !it.isError && !it.isStep }.takeLast(8)
                .forEach { branch.messages.add(Message.fromJson(it.toJson())) }
        }
        start(context,branch,Message("user",question))
    }
    private fun start(context: Context, chat: Chat, message: Message) {
        check(!AgentBus.isBusy()) { "WEB_PROVIDER_BUSY" }
        check(chat.webBinding?.let { WebProviderPrefs(context).runnable(it.providerId) } == true) { "WEB_PROVIDER_DISABLED" }
        val runId = AgentBus.beginStarting(chat.id,chat)
        check(runId != 0L) { "WEB_PROVIDER_BUSY" }
        synchronized(chat.messages) { chat.messages.add(message) }
        try {
            ChatStore(context).saveNow(chat); Prefs(context).setLastChatId(chat.id)
            dev.wind.agent.screen.ScreenShareSession.chat = chat
            AgentService.start(context,chat.id,runId)
            AgentBus.watchdog.postDelayed({
                if (AgentBus.runId() == runId && AgentBus.state() == AgentBus.RunState.STARTING) {
                    AgentBus.requestCancel(); AgentBus.finish(runId)
                    synchronized(chat.messages) { chat.messages.add(Message("assistant","WEB_SERVICE_START_FAILED").apply { isError = true }) }
                    ChatStore(context).save(chat)
                }
            },12000L)
            context.startActivity(Intent(context,MainActivity::class.java).putExtra("floatingChatId",chat.id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (failure: Throwable) {
            AgentBus.requestCancel(); AgentBus.finish(runId)
            synchronized(chat.messages) { chat.messages.add(Message("assistant","WEB_SERVICE_START_FAILED").apply { isError = true }) }
            ChatStore(context).save(chat)
            throw failure
        }
    }
}
