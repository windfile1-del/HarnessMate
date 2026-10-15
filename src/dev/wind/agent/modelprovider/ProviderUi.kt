package dev.wind.agent.modelprovider

import android.app.Activity
import android.app.Dialog
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.*
import dev.wind.agent.*

/** Shared native surfaces for API, subscription and website account setup. */
object ProviderUi {
    fun dp(c: Context, n: Int) = Theme.dp(c, n.toFloat())
    fun column(c: Context, padding: Int = 0) = LinearLayout(c).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(c,padding), dp(c,padding), dp(c,padding), dp(c,padding))
    }
    fun text(c: Context, value: String, size: Float = 14f, muted: Boolean = false) = TextView(c).apply {
        text = value; textSize = size; typeface = Theme.ui(); setTextColor(if (muted) Theme.TEXT_MUTED else Theme.TEXT)
        setLineSpacing(dp(c,3).toFloat(), 1f)
    }
    fun gap(parent: LinearLayout, size: Int = 12) { parent.addView(View(parent.context), LinearLayout.LayoutParams(1,dp(parent.context,size))) }
    fun card(c: Context, selected: Boolean = false) = column(c,16).apply {
        background = Theme.roundStroke(Theme.SURFACE, if (selected) Theme.BORDER_HI else Theme.BORDER, 16f, 1, c)
        layoutParams = LinearLayout.LayoutParams(-1,-2).apply { bottomMargin = dp(c,12) }
    }
    fun header(a: Activity, parent: LinearLayout, title: String, subtitle: String, icon: String = "plus", more: (() -> Unit)? = null) {
        val row = LinearLayout(a).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(0,dp(a,12),0,dp(a,12)) }
        row.addView(Ui.circleButton(a,"chevron-left",40f,20f,Theme.TEXT,Theme.BG) { a.finish() })
        val stack = column(a)
        stack.addView(text(a,title,21f).apply { typeface=Theme.uiSemi() })
        stack.addView(text(a,subtitle,12f,true))
        row.addView(stack, LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(a,8) })
        if (more != null) row.addView(Ui.circleButton(a,icon,40f,20f,Theme.TEXT,Theme.SURFACE_2) { more() })
        parent.addView(row)
    }
    fun tabs(parent: LinearLayout, labels: List<String>, selected: Int, choose: (Int) -> Unit) {
        val c=parent.context
        val row=LinearLayout(c).apply { setPadding(dp(c,4),dp(c,4),dp(c,4),dp(c,4)); background=Theme.roundRect(Theme.SURFACE_2,12f,c) }
        labels.forEachIndexed { i, title ->
            row.addView(text(c,title,13f).apply {
                gravity=Gravity.CENTER; minHeight=dp(c,40); typeface=if(i==selected) Theme.uiSemi() else Theme.ui()
                if(i==selected) background=Theme.roundRect(Theme.SURFACE,9f,c)
                contentDescription=title + if(i==selected) "，已选择" else ""
                setOnClickListener { choose(i) }
            },LinearLayout.LayoutParams(0,-2,1f))
        }
        parent.addView(row); gap(parent,16)
    }
    fun search(c: Context, hintText: String, value: String = "") = EditText(c).apply {
        hint=hintText; setText(value); textSize=14f; isSingleLine=true; setTextColor(Theme.TEXT); setHintTextColor(Theme.TEXT_MUTED)
        setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12)); background=Theme.roundRect(Theme.SURFACE_2,12f,c)
        layoutParams=LinearLayout.LayoutParams(-1,dp(c,48))
    }
    fun badge(c: Context, title: String) = text(c,title,11f,true).apply {
        setPadding(dp(c,8),dp(c,4),dp(c,8),dp(c,4)); background=Theme.roundRect(Theme.SURFACE_2,8f,c)
    }
    fun identity(parent: LinearLayout, name: String, subtitle: String, badge: String, more: ((View) -> Unit)? = null) {
        val c=parent.context; val row=LinearLayout(c).apply { gravity=Gravity.CENTER_VERTICAL }
        row.addView(text(c,name.take(1).uppercase(),20f).apply {
            gravity=Gravity.CENTER; typeface=Theme.uiSemi(); background=Theme.roundRect(Theme.SURFACE_2,12f,c)
        },LinearLayout.LayoutParams(dp(c,44),dp(c,44)))
        val stack=column(c)
        stack.addView(text(c,name,16f).apply { typeface=Theme.uiSemi(); maxLines=1; ellipsize=android.text.TextUtils.TruncateAt.END })
        stack.addView(text(c,subtitle,12f,true).apply { maxLines=2; ellipsize=android.text.TextUtils.TruncateAt.END })
        row.addView(stack,LinearLayout.LayoutParams(0,-2,1f).apply { marginStart=dp(c,12); marginEnd=dp(c,8) })
        row.addView(badge(c,badge))
        if(more!=null) row.addView(text(c,"⋯",22f).apply { gravity=Gravity.CENTER; contentDescription="$name，更多操作"; setOnClickListener { more(this) } },LinearLayout.LayoutParams(dp(c,40),dp(c,44)))
        parent.addView(row)
    }
    fun actions(parent: LinearLayout, entries: List<Pair<String,()->Unit>>, primary: Boolean = true) {
        val c=parent.context; val row=LinearLayout(c)
        entries.forEachIndexed { i,(title,action) ->
            row.addView(Ui.pillButton(c,title,null,if(i==0 && primary) Ui.PRIMARY else Ui.SECONDARY) { action() },
                LinearLayout.LayoutParams(0,dp(c,44),1f).apply { if(i>0) marginStart=dp(c,8) })
        }
        parent.addView(row)
    }
    fun menu(anchor: View, entries: List<Pair<String,()->Unit>>) {
        val popup=PopupMenu(anchor.context,anchor)
        entries.forEachIndexed { i,(title,_) -> popup.menu.add(0,i,i,title) }
        popup.setOnMenuItemClickListener { item -> entries.getOrNull(item.itemId)?.second?.invoke(); true }; popup.show()
    }
    fun sheet(a: Activity, title: String, subtitle: String, content: LinearLayout): Dialog {
        val dialog=Dialog(a)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val root=column(a,20).apply { background=Theme.roundRect(Theme.BG_ELEV,24f,a) }
        val handle=View(a).apply { background=Theme.roundRect(Theme.BORDER_HI,2f,a) }
        root.addView(handle,LinearLayout.LayoutParams(dp(a,36),dp(a,4)).apply { gravity=Gravity.CENTER_HORIZONTAL; bottomMargin=dp(a,18) })
        root.addView(text(a,title,21f).apply { typeface=Theme.uiSemi() }); gap(root,6)
        root.addView(text(a,subtitle,12f,true)); gap(root,16)
        val scroll=ScrollView(a).apply { addView(content) }
        root.addView(scroll,LinearLayout.LayoutParams(-1,-2))
        dialog.setContentView(root)
        dialog.window?.apply {
            setBackgroundDrawable(ColorDrawable(android.graphics.Color.TRANSPARENT))
            setGravity(Gravity.BOTTOM); setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        }
        dialog.setOnShowListener {
            val metrics=a.resources.displayMetrics
            root.measure(View.MeasureSpec.makeMeasureSpec(metrics.widthPixels,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED))
            dialog.window?.setLayout(ViewGroup.LayoutParams.MATCH_PARENT,minOf(root.measuredHeight,(metrics.heightPixels*0.85f).toInt()))
        }
        return dialog
    }
}
