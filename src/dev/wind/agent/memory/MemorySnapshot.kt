package dev.wind.agent.memory

import org.json.JSONObject

object MemorySnapshot {
    fun validate(j: JSONObject) {
        require(j.optInt("format")==1 && j.toString().length<=8*1024*1024)
        val ns=j.getString("namespace");val generation=j.getLong("generation");val epoch=j.getLong("epoch")
        require(MemoryRules.id(ns) && generation in 1 until Long.MAX_VALUE-1 && epoch in 1 until Long.MAX_VALUE-1 && j.optLong("clearBarrier") in 0..generation)
        val items=j.getJSONArray("items");val tomb=j.getJSONArray("tombstones");val suppress=j.getJSONArray("suppressions")
        require(items.length()<=MemoryRules.MAX_RECORDS && tomb.length()<=20000 && suppress.length()<=20000)
        val seen=mutableSetOf<String>();val keys=mutableSetOf<String>()
        for(i in 0 until items.length()) {
            val r=MemoryRecord.parse(items.getJSONObject(i));require(r.namespace==ns && r.status=="ACTIVE" && r.generation<=generation && seen.add(r.id))
            require(keys.add("${r.scope}:${r.scopeKey}:${r.key}"))
        }
        for(i in 0 until tomb.length()) { val t=tomb.getJSONObject(i);require(MemoryRules.id(t.getString("id")) && t.getLong("generation") in 1..generation) }
        for(i in 0 until suppress.length())require(suppress.getString(i).matches(Regex("[a-f0-9]{64}")))
        if(j.has("redactions"))require(j.opt("redactions") is org.json.JSONArray)
        j.optJSONArray("redactions")?.let { a ->
            require(a.length()<=20000)
            val unique=mutableSetOf<String>()
            for(i in 0 until a.length()) { val s=MemorySource.parse(a.getJSONObject(i));require(s.hasSpan && unique.add(s.fingerprint())) }
        }
        if(j.has("privacy"))require(j.opt("privacy") is JSONObject)
        j.optJSONObject("privacy")?.let { p -> for(key in listOf("use_saved","auto_write","use_history"))require(p.opt(key) is Boolean) }
    }
}
