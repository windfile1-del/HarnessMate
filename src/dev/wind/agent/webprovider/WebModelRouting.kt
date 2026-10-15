package dev.wind.agent.webprovider

import android.content.Context
import dev.wind.agent.Chat
import dev.wind.agent.Prefs

object WebModelRouting {
    fun bindDefault(context: Context, chat: Chat) {
        val prefs = WebProviderPrefs(context)
        val id = prefs.selected()
        if (chat.webBinding == null && chat.messages.isEmpty() && prefs.runnable(id))
            chat.webBinding = WebConversationBinding(id, prefs.profile(id))
    }
    fun configured(context: Context, chat: Chat, apiPrefs: Prefs): Boolean {
        bindDefault(context, chat)
        val binding = chat.webBinding ?: return apiPrefs.isConfigured()
        val prefs = WebProviderPrefs(context)
        if (binding.profileName != prefs.profile(binding.providerId)) {
            chat.webBinding = WebConversationBinding(binding.providerId, prefs.profile(binding.providerId))
            chat.webBindings[binding.providerId] = chat.webBinding!!
        }
        return prefs.runnable(binding.providerId)
    }
}
