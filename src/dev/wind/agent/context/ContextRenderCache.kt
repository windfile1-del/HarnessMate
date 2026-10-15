package dev.wind.agent.context

import dev.wind.agent.Message
import org.json.JSONObject

/** LRU bounded by entry count and approximate UTF-16 footprint, including image data URIs. */
class ContextRenderCache(private val maxChars: Int = 4 * 1024 * 1024, private val maxEntries: Int = 64) {
    private val entries = LinkedHashMap<Message, Pair<String, JSONObject>>(16, 0.75f, true)
    private val weights = HashMap<Message, Int>()
    private var chars = 0L
    @Synchronized operator fun get(message: Message): Pair<String, JSONObject>? = entries[message]
    @Synchronized operator fun set(message: Message, value: Pair<String, JSONObject>) {
        entries.remove(message); chars -= weights.remove(message) ?: 0
        val weight = value.first.length.toLong() + value.second.toString().length
        if (weight > maxChars) return
        entries[message] = value; weights[message] = weight.toInt(); chars += weight
        while (chars > maxChars || entries.size > maxEntries) {
            val oldest = entries.keys.first(); entries.remove(oldest); chars -= weights.remove(oldest) ?: 0
        }
    }
    @Synchronized fun retainedChars(): Long = chars
    @Synchronized fun size(): Int = entries.size
    @Synchronized fun clear() { entries.clear();weights.clear();chars=0 }
}
