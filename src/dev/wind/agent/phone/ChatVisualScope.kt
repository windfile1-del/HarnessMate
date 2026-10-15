package dev.wind.agent.phone

import org.json.JSONArray
import org.json.JSONObject

internal object ChatVisualScope {
    data class Region(val left: Int,val top: Int,val right: Int,val bottom: Int) {
        val array get()=intArrayOf(left,top,right,bottom)
        val json get()=JSONArray(array.toList())
        val x get()=(left+right)/2; val y get()=(top+bottom)/2
        fun overlaps(line: ChatPageParser.Line)=line.left<right && line.right>left && line.top<bottom && line.bottom>top
        fun contains(line: ChatPageParser.Line)=line.left>=left-10 && line.right<=right+10 && line.top>=top-10 && line.bottom<=bottom+10
        companion object {
            fun parse(a: JSONArray)=Region(a.getInt(0),a.getInt(1),a.getInt(2),a.getInt(3)).also {
                require(a.length()==4 && it.left in 0..999 && it.right in 1..1000 && it.top in 0..999 && it.bottom in 1..1000 && it.left<it.right && it.top<it.bottom)
            }
        }
    }
    fun validate(header: Region,composer: Region) {
        require(header.top in 20..225 && header.bottom<=260 && header.right-header.left>=80) { "CHAT_HEADER_REGION_REQUIRED" }
        require(composer.top>=350 && composer.bottom<=1000 && composer.right in 400..850 && composer.right-composer.left>=300 &&
            composer.bottom-composer.top in 15..160) { "CHAT_COMPOSER_REGION_REQUIRED" }
    }
    fun binding(peer: String,window: String,display: String,header: Region,composer: Region,headerHash: String,blankHash: String)=JSONObject()
        .put("peer",peer).put("window",window).put("display_session_id",display).put("header",header.json).put("composer",composer.json)
        .put("header_hash",headerHash).put("blank_hash",blankHash).put("user_confirmed",true).put("approved_at",System.currentTimeMillis())
    fun check(binding: JSONObject,o: JSONObject,peer: String,signature: (Region)->String) {
        check(binding.optBoolean("user_confirmed") && binding.optString("peer")==peer &&
            binding.optString("window")==o.optString("window") && binding.optString("display_session_id")==o.optString("display_session_id")) { "CHAT_VISUAL_BINDING_CHANGED" }
        val header=Region.parse(binding.getJSONArray("header")); val composer=Region.parse(binding.getJSONArray("composer")); validate(header,composer)
        check(signature(header)==binding.getString("header_hash")) { "CHAT_VISUAL_PEER_CHANGED" }
    }
    fun bodyRegion(binding: JSONObject)=Region(0,Region.parse(binding.getJSONArray("header")).bottom+10,1000,Region.parse(binding.getJSONArray("composer")).top-10)
    fun bodyChangedUnparsed(previousHash: String?,hash: String?,previous: List<ChatPageParser.Turn>,current: List<ChatPageParser.Turn>)=
        !previousHash.isNullOrBlank() && hash!=null && previousHash!=hash && ChatDeliveryVerifier.pageKey(previous)==ChatDeliveryVerifier.pageKey(current)
    fun mapCrop(line: ChatPageParser.Line,region: Region,width: Int,height: Int): ChatPageParser.Line {
        val l=region.left*width/1000; val t=region.top*height/1000; val r=region.right*width/1000; val b=region.bottom*height/1000
        require(width>0 && height>0 && r>l && b>t)
        fun x(n: Int)=((l.toLong()*1000+n.toLong()*(r-l))/width).toInt()
        fun y(n: Int)=((t.toLong()*1000+n.toLong()*(b-t))/height).toInt()
        return line.copy(left=x(line.left),top=y(line.top),right=x(line.right),bottom=y(line.bottom))
    }
    fun composerText(lines: List<ChatPageParser.Line>,region: Region)=lines.filter { region.contains(it) && it.confidence>=70 }
        .sortedBy { it.top }.joinToString("\n") { it.text.trim() }
    fun sendButton(lines: List<ChatPageParser.Line>,composer: Region): Region {
        val line=lines.filter { it.confidence>=85 && it.text.trim() in setOf("发送","Send") &&
            it.left>=composer.right-30 && it.top>=composer.top-50 && it.bottom<=composer.bottom+50 }.singleOrNull()
            ?: error("CHAT_VISUAL_SEND_BUTTON_NOT_VERIFIED")
        return Region(line.left,line.top,line.right,line.bottom)
    }
    fun uiAvailable(o: JSONObject): Boolean {
        val nodes=o.optJSONArray("nodes") ?: return false
        return (0 until nodes.length()).any {
            val n=nodes.getJSONObject(it); val b=n.optJSONArray("bounds")
            n.optBoolean("visible") && b!=null && b.length()==4 && b.getInt(2)>b.getInt(0) && b.getInt(3)>b.getInt(1) &&
                (n.optBoolean("editable") || n.optString("text").isNotBlank() || n.optString("description").isNotBlank())
        }
    }
}
