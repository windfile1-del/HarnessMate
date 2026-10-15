package dev.wind.agent.phone

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Bundle
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import dev.wind.agent.AgentBus
import dev.wind.agent.CancellationToken
import org.json.JSONObject

/** User-opened read-only consumer; no screenshots on disk or tap-to-control surface. */
class PhoneDisplayPreviewActivity : Activity() {
    private var token=CancellationToken()
    private var shown: Bitmap?=null
    private lateinit var image: ImageView
    private lateinit var status: TextView
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        image=ImageView(this).apply { scaleType=ImageView.ScaleType.FIT_CENTER }
        status=TextView(this).apply { text="副屏只读预览 · 最多 1 fps" }
        setContentView(LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(status); addView(image,LinearLayout.LayoutParams(-1,0,1f)) })
    }
    override fun onStart() {
        super.onStart(); token=CancellationToken(); val lifetime=token
        val guardian=intent.getStringExtra("guardian_id")
        val owner=if(guardian!=null) ChatSessionManager.active?.takeIf { it.id==guardian }?.owner.orEmpty() else AgentBus.activeTaskId.orEmpty()
        Thread({
            while(!lifetime.isCancelled) {
                val started=android.os.SystemClock.elapsedRealtime()
                try {
                    val s=VirtualDisplays.preview(owner) ?: error("VIRTUAL_DISPLAY_SESSION_LOST")
                    val frame=s.bridge.captureFrame(lifetime,s.scope(JSONObject()))
                    val bitmap=BitmapFactory.decodeByteArray(frame.png,0,frame.png.size) ?: error("PHONE_IMAGE_INVALID")
                    runOnUiThread {
                        if(lifetime.isCancelled) bitmap.recycle()
                        else { val old=shown; shown=bitmap; image.setImageBitmap(bitmap); old?.recycle(); status.text="只读 · display ${frame.metadata.optInt("display_id")} · frame ${frame.metadata.optLong("frame_sequence")}" }
                    }
                } catch(e: Exception) {
                    runOnUiThread { if(!lifetime.isCancelled) { image.setImageDrawable(null); shown?.recycle(); shown=null; status.text=e.message ?: "预览已停止" } }
                    break
                }
                try { lifetime.sleep((1000-(android.os.SystemClock.elapsedRealtime()-started)).coerceAtLeast(1)) } catch(_: Exception) { break }
            }
        },"virtual-display-preview").start()
    }
    override fun onStop() { token.cancel(); image.setImageDrawable(null); shown?.recycle(); shown=null; super.onStop() }
}
