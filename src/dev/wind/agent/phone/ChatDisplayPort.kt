package dev.wind.agent.phone

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.util.Base64
import dev.wind.agent.Message
import java.io.ByteArrayOutputStream
import dev.wind.agent.CancellationToken
import org.json.JSONObject

/** One injectable display-scoped chain, shared by UI and local OCR paths. */
internal interface ChatDisplayPort {
    fun observe(token: CancellationToken,mode: String="ui"): JSONObject
    fun capture(token: CancellationToken): ChatVisualFrame
    fun action(args: JSONObject,token: CancellationToken): JSONObject
    fun pause(ms: Long,token: CancellationToken) { token.sleep(ms); token.throwIfCancelled() }
    fun now(): Long=System.nanoTime()/1000000
}
internal class ChatVisualFrame(val observation: JSONObject,val png: ByteArray,val lines: List<ChatPageParser.Line>,
    val ocrStatus: String,val signature: (ChatVisualScope.Region)->String)
internal class ChatVisualBindingRequired(val frame: ChatVisualFrame): IllegalStateException("CHAT_VISUAL_BINDING_REQUIRED")
internal class NativeChatDisplayPort(private val context: Context,private val lease: VirtualDisplays.Session,private val binding: JSONObject?=null): ChatDisplayPort {
    override fun observe(token: CancellationToken,mode: String): JSONObject {
        check(lease.valid()) { "CHAT_DISPLAY_LOST" }
        val o=lease.bridge.rpc("observe",lease.scope(JSONObject().put("screen","virtual").put("mode",mode).put("fresh",true)),token)
        check(o.optString("status")=="COMPLETED") { o.optString("error","CHAT_OBSERVATION_FAILED") }
        VirtualDisplayScope.session(lease.id,o.optString("display_session_id"),o.optInt("display_id",-1))
        return o
    }
    override fun capture(token: CancellationToken): ChatVisualFrame = ChatVisualReadPolicy.stable({ captureOnce(token) },{ pause(180,token) })
    private fun captureOnce(token: CancellationToken): ChatVisualFrame {
        val before=observe(token,"visual")
        val frame=lease.bridge.captureFrame(token,lease.scope(JSONObject().put("screen","virtual")))
        VirtualDisplayScope.same(before,frame.metadata)
        check(before.getLong("event_epoch")==frame.metadata.getLong("event_epoch")) { "CHAT_SCREEN_CHANGED_DURING_CAPTURE" }
        val ocr=PhoneVision.recognize(context,PhoneVirtualTools.attachment(frame,lease.id),token,5000)
        check(ocr.status=="OK" && !ocr.truncated) { "CHAT_LOCAL_OCR_UNAVAILABLE: "+ocr.status }
        val full=ocr.targets.map { ChatPageParser.Line(it.text,it.left,it.top,it.right,it.bottom,it.confidence) }
        val lines=if(binding==null) full else {
            val header=ChatVisualScope.Region.parse(binding.getJSONArray("header"))
            val composer=ChatVisualScope.Region.parse(binding.getJSONArray("composer")); ChatVisualScope.validate(header,composer)
            val send=ChatVisualScope.Region(composer.right,(composer.top-50).coerceAtLeast(0),1000,(composer.bottom+50).coerceAtMost(1000))
            val field=cropLines(frame.png,composer,token); val button=cropLines(frame.png,send,token)
            full.filter { !composer.overlaps(it) && !send.overlaps(it) }+field+button
        }
        val after=observe(token,"visual"); VirtualDisplayScope.same(before,after)
        check(before.getLong("event_epoch")==after.getLong("event_epoch")) { "CHAT_SCREEN_CHANGED_DURING_OCR" }
        return ChatVisualFrame(after,frame.png,lines,ocr.status) { region->
            val bmp=BitmapFactory.decodeByteArray(frame.png,0,frame.png.size) ?: error("CHAT_FRAME_DECODE_FAILED")
            try {
                val pixels=IntArray(bmp.width*bmp.height); bmp.getPixels(pixels,0,bmp.width,0,0,bmp.width,bmp.height)
                VisualPatchDigest.digest(pixels,bmp.width,bmp.height,region.array)
            } finally { bmp.recycle() }
        }
    }
    private fun cropLines(png: ByteArray,region: ChatVisualScope.Region,token: CancellationToken): List<ChatPageParser.Line> {
        val source=BitmapFactory.decodeByteArray(png,0,png.size) ?: error("CHAT_FRAME_DECODE_FAILED")
        val w=source.width; val h=source.height
        val l=region.left*w/1000; val t=region.top*h/1000; val r=region.right*w/1000; val b=region.bottom*h/1000
        check(r>l && b>t) { "CHAT_VISUAL_REGION_INVALID" }
        val bytes=try {
            val crop=Bitmap.createBitmap(source,l,t,r-l,b-t)
            try { ByteArrayOutputStream().use { out->check(crop.compress(Bitmap.CompressFormat.PNG,100,out)); out.toByteArray() } }
            finally { if(crop!==source) crop.recycle() }
        } finally { source.recycle() }
        val image=Message.Attachment("local-composer.png",null,"image/png",bytes.size.toLong(),"image").apply {
            dataUri="data:image/png;base64,"+Base64.encodeToString(bytes,Base64.NO_WRAP)
        }
        val ocr=PhoneVision.recognize(context,image,token,5000)
        check(ocr.status=="OK" && !ocr.truncated) { "CHAT_LOCAL_OCR_UNAVAILABLE: "+ocr.status }
        return ocr.targets.map { ChatVisualScope.mapCrop(ChatPageParser.Line(it.text,it.left,it.top,it.right,it.bottom,it.confidence),region,w,h) }
    }
    override fun action(args: JSONObject,token: CancellationToken)=lease.bridge.action(lease.scope(args).put("screen","virtual"),token)
}
