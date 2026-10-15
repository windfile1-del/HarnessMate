package dev.wind.agent.phone

/** Seeing a NEW self bubble is local send evidence, never recipient delivery evidence. */
internal object ChatDeliveryVerifier {
    fun verified(before: List<ChatPageParser.Turn>, after: List<ChatPageParser.Turn>, reply: String, editorEmpty: Boolean): Boolean {
        if(!editorEmpty || before.isEmpty() || after.isEmpty()) return false
        val max=minOf(before.size,after.size)
        val overlap=(max downTo 1).firstOrNull { before.takeLast(it).map { t->t.key }==after.take(it).map { t->t.key } } ?: return false
        return after.drop(overlap).any { it.role=="self" && it.text.replace("\r\n","\n")==reply.replace("\r\n","\n") }
    }
    fun pageKey(turns: List<ChatPageParser.Turn>)=ChatSessionKeys.hash(turns.joinToString("\u0000") { it.role+":"+it.text })
}
internal object ChatSessionKeys {
    fun hash(text: String)=java.security.MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    fun turns(o: org.json.JSONArray)= (0 until o.length()).map { val t=o.getJSONObject(it); ChatPageParser.Turn(t.getString("role"),t.getString("text")) }
    fun json(turns: List<ChatPageParser.Turn>)=org.json.JSONArray(turns.map { org.json.JSONObject().put("role",it.role).put("text",it.text) })
}
