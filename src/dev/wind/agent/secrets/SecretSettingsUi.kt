package dev.wind.agent.secrets

import android.app.AlertDialog
import android.content.Context
import android.text.InputType
import android.widget.*

object SecretSettingsUi {
    fun show(context:Context) {
        val broker=SecretBroker.get(context)
        AlertDialog.Builder(context).setTitle("Git / 下载凭据")
            .setItems(arrayOf("添加凭据","查看 / 撤销凭据")) { _, index ->
                if(index==0) add(context,broker) else {
                    val entries=broker.catalog().getJSONArray("secrets")
                    AlertDialog.Builder(context).setTitle("凭据引用（不显示密钥）")
                        .setItems((0 until entries.length()).map { val i=entries.getJSONObject(it); "${i.getString("label")}\n${i.getString("repository")}\n${i.getString("id")}" }.toTypedArray()) { _, n ->
                            val id=entries.getJSONObject(n).getString("id")
                            AlertDialog.Builder(context).setMessage("撤销 $id？后续网络请求将停止使用该凭据。")
                                .setPositiveButton("撤销") { _,_->broker.revoke(id) }.setNegativeButton("取消",null).show()
                        }.setNegativeButton("关闭",null).show()
                }
            }.setNegativeButton("关闭",null).show()
    }
    private fun add(context:Context,broker:SecretBroker) {
        val box=LinearLayout(context).apply { orientation=LinearLayout.VERTICAL;setPadding(24,8,24,8) }
        fun field(hint:String)=EditText(context).apply { this.hint=hint;isSingleLine=true;box.addView(this) }
        val label=field("名称")
        val repo=field("完整 HTTPS Git 仓库 URL（含 .git）")
        val username=field("用户名，例如 x-access-token")
        val token=field("Token / 密码")
        token.inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val hours=field("有效小时数，默认 24")
        val write=CheckBox(context).apply { text="允许 Git push";box.addView(this) }
        val download=CheckBox(context).apply { text="允许此精确 HTTPS URL 的 Basic 认证下载（不转发重定向）";box.addView(this) }
        val session=CheckBox(context).apply { text="仅本次 App 进程使用（不持久保存）";box.addView(this) }
        box.addView(TextView(context).apply { text="模型仅获取凭据 ID。持久凭据由 Android Keystore 加密；失败时不会改存明文。到期或撤销后停止使用。" })
        val dialog=AlertDialog.Builder(context).setTitle("添加凭据").setView(ScrollView(context).apply { addView(box) })
            .setPositiveButton("保存",null).setNegativeButton("取消") { _,_->token.text.clear() }.create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            try {
                val duration=hours.text.toString().ifBlank { "24" }.toLong();require(duration in 1..8760)
                broker.save(label.text.toString(),repo.text.toString(),username.text.toString(),token.text.toString().toCharArray(),
                    setOf("git_read")+(if(write.isChecked) setOf("git_write") else emptySet())+
                        (if(download.isChecked) setOf("artifact_read") else emptySet()),System.currentTimeMillis()+duration*3600000,session.isChecked)
                token.text.clear();dialog.dismiss()
            } catch(e:Exception) { Toast.makeText(context,"保存失败：${if(e is IllegalArgumentException) e.message else "密钥库不可用，可选择仅本次进程使用"}",Toast.LENGTH_LONG).show() }
        } };dialog.show()
    }
}
