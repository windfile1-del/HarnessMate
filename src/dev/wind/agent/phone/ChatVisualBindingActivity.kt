package dev.wind.agent.phone

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Color
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

/** User draws screenshot regions locally; selecting them never touches or sends to the chat app. */
class ChatVisualBindingActivity: Activity() {
    private var bitmap: Bitmap?=null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val nonce=intent.getStringExtra("nonce").orEmpty()
        val p=ChatVisualBindingCenter.get(nonce)
        if(p==null) { Toast.makeText(this,"预览已过期，请让模型重新 activate",Toast.LENGTH_LONG).show(); finish(); return }
        val s=ChatConversationStore.get(this).session(p.session)
        val image=BitmapFactory.decodeByteArray(p.frame.png,0,p.frame.png.size)
        if(image==null) { finish(); return }; bitmap=image
        val label=TextView(this).apply { text="请核对这是【${s.optString("peer")}】的单聊，名称包含表情。\n先拖拽框住完整标题，再拖拽框住整个空白文字输入框内部（不含发送按钮）。请在键盘布局稳定后确认；页面变化需要重新生成预览。截图仅在本机显示。"; setPadding(16,12,16,12) }
        val selection=Selection(this,image)
        val next=Button(this).apply { text="标题已标记，下一步标记输入框"; setOnClickListener { try { selection.header=selection.current ?: error("请先拖拽标记标题"); selection.current=null; selection.composerMode=true; isEnabled=false; text="请标记输入框，再点下方确认" } catch(e:Exception) { Toast.makeText(this@ChatVisualBindingActivity,e.message,Toast.LENGTH_LONG).show() } } }
        val confirm=Button(this).apply {
            text="确认联系人、单聊和空白输入框，激活托管"
            setOnClickListener {
                val h=selection.header; val box=selection.current
                if(h==null || box==null || !selection.composerMode) { Toast.makeText(this@ChatVisualBindingActivity,"请标记标题和输入框",Toast.LENGTH_LONG).show(); return@setOnClickListener }
                isEnabled=false
                Thread({
                    var error: String?=null
                    try { ChatVisualBindingCenter.accept(this@ChatVisualBindingActivity,nonce,h,box) } catch(e:Exception) { error=e.message }
                    runOnUiThread { if(error==null) { Toast.makeText(this@ChatVisualBindingActivity,"已绑定并在副屏激活托管",Toast.LENGTH_LONG).show(); finish() }
                        else { isEnabled=true; label.text="绑定未完成："+error+"\n请重新确认或让模型生成新预览。"; } }
                },"chat-visual-user-confirmation").start()
            }
        }
        setContentView(LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; addView(label); addView(selection,LinearLayout.LayoutParams(-1,0,1f)); addView(next); addView(confirm)
            addView(Button(this@ChatVisualBindingActivity).apply { text="取消，不激活"; setOnClickListener { finish() } }) })
    }
    override fun onDestroy() { bitmap?.recycle(); bitmap=null; super.onDestroy() }
    private class Selection(c: android.content.Context,private val image: Bitmap): View(c) {
        var header: ChatVisualScope.Region?=null
        var current: ChatVisualScope.Region?=null
        var composerMode=false
        private var firstX=0; private var firstY=0
        private val paint=Paint().apply { color=Color.RED; style=Paint.Style.STROKE; strokeWidth=3f }
        private fun scale()=minOf(width.toFloat()/image.width,height.toFloat()/image.height)
        private fun offsetX()=(width-image.width*scale())/2
        private fun offsetY()=(height-image.height*scale())/2
        override fun onDraw(canvas: Canvas) {
            val sc=scale(); canvas.save(); canvas.translate(offsetX(),offsetY()); canvas.scale(sc,sc); canvas.drawBitmap(image,0f,0f,null)
            for(r in listOfNotNull(header,current)) canvas.drawRect(r.left*image.width/1000f,r.top*image.height/1000f,r.right*image.width/1000f,r.bottom*image.height/1000f,paint)
            canvas.restore()
        }
        override fun onTouchEvent(e: MotionEvent): Boolean {
            val x=((e.x-offsetX())/scale()/image.width*1000).toInt().coerceIn(0,1000)
            val y=((e.y-offsetY())/scale()/image.height*1000).toInt().coerceIn(0,1000)
            if(e.action==MotionEvent.ACTION_DOWN) { firstX=x; firstY=y }
            if(e.action in listOf(MotionEvent.ACTION_MOVE,MotionEvent.ACTION_UP)) {
                current=ChatVisualScope.Region(minOf(firstX,x),minOf(firstY,y),maxOf(firstX,x),maxOf(firstY,y)); invalidate()
            }
            return true
        }
    }
}
