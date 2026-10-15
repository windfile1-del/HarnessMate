package dev.wind.agent.memory

import android.app.Activity
import android.app.AlertDialog
import android.widget.*
import dev.wind.agent.Chat
import dev.wind.agent.ChatStore
import dev.wind.agent.profile.MemorySessionPolicy
import java.util.UUID

/** Native management: visible review/edit/forget, with disk work off the UI thread. */
object MemoryUi {
    private val io=java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r,"memory-manager").apply { isDaemon=true } }
    private fun <T> work(a: Activity, action: ()->T, done: (T)->Unit) {
        io.execute { try { val result=action();a.runOnUiThread { if(!a.isFinishing && !a.isDestroyed)done(result) } }
            catch(e: Exception) { a.runOnUiThread { if(!a.isFinishing)Toast.makeText(a,e.message ?: "记忆操作失败",Toast.LENGTH_LONG).show() } } }
    }
    private data class Page(val records: List<MemoryRecord>,val total: Int,val offset: Int)
    fun show(a: Activity, offset: Int = 0) {
        work(a, { MemoryStore(a).use {db -> db.transaction {
            val total=db.count();val start=offset.coerceIn(0,((total-1).coerceAtLeast(0)/50)*50)
            Page(db.list(offset=start,limit=50),total,start)
        } } to MemoryAccess(a).policySnapshot() }) { snapshot ->
            val page=snapshot.first;val records=page.records;val policy=snapshot.second
            var dialog: AlertDialog?=null
            val content=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setPadding(32,16,32,16) }
            val access=MemoryAccess(a)
            for((key,label,value) in listOf(Triple("use_saved","使用已保存记忆",policy.getValue("use_saved")),Triple("auto_write","允许从对话自动补充记忆",policy.getValue("auto_write")),Triple("use_history","允许查询跨会话历史",policy.getValue("use_history")))) {
                content.addView(Switch(a).apply { text=label;isChecked=value;setOnCheckedChangeListener { _,checked -> work(a,{access.set(key,checked)}){} } })
            }
            content.addView(TextView(a).apply { text="个人偏好跨会话使用；项目和任务记录限制在绑定范围内。记忆不授予操作权限。\n${MemoryVaultSync.status(a)}\n${MemoryCoordinator.status(a)}";setTextIsSelectable(true) })
            content.addView(Button(a).apply { text="自动补充预算与用量";setOnClickListener {budget(a)} })
            content.addView(Button(a).apply { text="检查待补充对话";setOnClickListener {MemoryCoordinator.start(a);Toast.makeText(a,"已安排后台检查，不发送提取提示到聊天",Toast.LENGTH_SHORT).show()} })
            content.addView(Button(a).apply { text="添加记忆";setOnClickListener { edit(a,null) } })
            content.addView(Button(a).apply { text="审核旧版记忆";setOnClickListener {
                work(a,{
                    val file=java.io.File(a.filesDir,"agent_memory.txt")
                    if(!file.isFile)0 else {
                        require(file.length()<=65536) { "旧记忆过大，请先导出后分批审核" }
                        val body=file.readText();var count=0
                        MemoryStore(a).use { store ->
                            for(line in body.lineSequence().map { it.removePrefix("- ").trim() }.filter { it.isNotBlank() }.take(100)) {
                                if(line.length>2000 || MemoryRules.secret(line))continue
                                val digest=MemoryRules.hash(line);val source=MemorySource("legacy_memory","legacy_"+digest.take(32),1,digest)
                                val key="legacy.note."+digest.take(16)
                                if(store.suppressed(source.fingerprint()) || store.list().any { it.key==key })continue
                                store.save(MemoryScope.PERSONAL,"",key,line,listOf(source),0,store.epoch(),"CANDIDATE",origin="legacy_unknown");count++
                            }
                        };count
                    }
                }) { count -> Toast.makeText(a,"$count 条旧记忆进入候选审核，不自动使用",Toast.LENGTH_LONG).show();show(a) }
            } })
            for(r in records)content.addView(Button(a).apply {
                text="${if(r.status=="CANDIDATE")"待确认 · " else ""}${r.scope.name} ${r.key}\n${r.summary.take(180)}"
                setOnClickListener { detail(a,r) }
            })
            if(records.isEmpty())content.addView(TextView(a).apply { text="暂无已保存记忆" })
            if(page.total>50) {
                content.addView(TextView(a).apply {text="${page.offset+1}–${page.offset+records.size} / ${page.total}"})
                if(page.offset>0)content.addView(Button(a).apply {text="上一页";setOnClickListener {dialog?.dismiss();show(a,page.offset-50)}})
                if(page.offset+records.size<page.total)content.addView(Button(a).apply {text="下一页";setOnClickListener {dialog?.dismiss();show(a,page.offset+50)}})
            }
            content.addView(Button(a).apply { text="重试长期目录同步";setOnClickListener { work(a,{MemoryVaultSync.sync(a,false)}) { show(a) } } })
            content.addView(Button(a).apply { text="忘记全部记忆";setOnClickListener {
                AlertDialog.Builder(a).setTitle("忘记全部记忆？").setMessage("已保存条目、候选和派生上下文将停止使用。原始聊天记录仍保留供你查看；删除标记阻止旧备份恢复记忆。")
                    .setPositiveButton("忘记全部") { _,_ -> work(a,{MemoryStore(a).use { it.clear() };MemoryVaultSync.schedule(a)}) { show(a) } }.setNegativeButton("取消",null).show()
            } })
            dialog=AlertDialog.Builder(a).setTitle("长期记忆").setView(ScrollView(a).apply { addView(content) }).setPositiveButton("关闭",null).show()
        }
    }
    private fun budget(a: Activity) {
        work(a,{MemoryStore(a).use {db ->MemoryJobStore(db).limits() to MemoryJobStore(db).usage()}}) {data ->
            val box=LinearLayout(a).apply {orientation=LinearLayout.VERTICAL;setPadding(32,16,32,16)}
            box.addView(TextView(a).apply {text="今日调用 ${data.second.optInt("calls")} 次；输入预留 ${data.second.optLong("inputReserved")}；输出预留 ${data.second.optLong("outputReserved")} tokens。失败也计入；缺少供应商用量时保留预估，不算零。"})
            val receipts=data.second.getJSONArray("receipts")
            if(data.second.optBoolean("halted"))box.addView(TextView(a).apply {text="供应商返回的用量超过预留，今日自动补充已暂停。"})
            var reportedInput=0L;var reportedOutput=0L;var reportedCache=0L;var reported=0;var estimated=0;var cacheUnknown=0
            for(i in 0 until receipts.length()) {
                val r=receipts.getJSONObject(i)
                if(r.optString("usageSource")=="PROVIDER_REPORTED") {
                    reported++;reportedInput+=r.optLong("input");reportedOutput+=r.optLong("output")
                    if(r.optBoolean("cacheKnown"))reportedCache+=r.optLong("cached") else cacheUnknown++
                } else estimated++
            }
            box.addView(TextView(a).apply {text="供应商已报告 $reported 次：输入 $reportedInput、输出 $reportedOutput、已知缓存读取 $reportedCache tokens。另有 $estimated 次仅记录预留估算；$cacheUnknown 次缓存用量未报告。"})
            val calls=EditText(a).apply {hint="每天最多调用次数（包含修复/失败）";inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(data.first.first.toString())}
            val input=EditText(a).apply {hint="每天输入 token 预算";inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(data.first.second.toString())}
            val output=EditText(a).apply {hint="每天输出 token 预算";inputType=android.text.InputType.TYPE_CLASS_NUMBER;setText(data.first.third.toString())}
            box.addView(calls);box.addView(input);box.addView(output)
            AlertDialog.Builder(a).setTitle("自动记忆预算").setView(ScrollView(a).apply {addView(box)}).setPositiveButton("保存") {_,_ ->
                val values=Triple(calls.text.toString().toIntOrNull(),input.text.toString().toIntOrNull(),output.text.toString().toIntOrNull())
                work(a,{require(values.first!=null && values.second!=null && values.third!=null);MemoryStore(a).use {MemoryJobStore(it).setLimits(values.first!!,values.second!!,values.third!!) }}) {show(a)}
            }.setNegativeButton("关闭",null).show()
        }
    }
    fun references(a: Activity,message: dev.wind.agent.Message) {
        work(a,{MemoryStore(a).use {db ->message.memoryRefs.map { (id,version)->db.get(id)?.takeIf {it.revision==version} }}}) {records ->
            val items=records.filterNotNull()
            if(items.isEmpty())AlertDialog.Builder(a).setTitle("记忆引用").setMessage("当时引用的条目已修改或撤销；原始回复仍可查看。") .setPositiveButton("关闭",null).show()
            else AlertDialog.Builder(a).setTitle("此回复参考的记忆").setItems(items.map {it.summary.take(160)}.toTypedArray()) {_,index->detail(a,items[index])}.setNegativeButton("关闭",null).show()
        }
    }
    private fun detail(a: Activity, r: MemoryRecord) {
        val text=TextView(a).apply { setPadding(32,16,32,16);setTextIsSelectable(true);text="${r.summary}\n\n键：${r.key}\n范围：${r.scope.name} ${r.scopeKey}\n版本：${r.revision}\n来源：${r.origin}\n有效期：${if(r.expiresAt==0L)"长期" else java.util.Date(r.expiresAt).toString()}" }
        val box=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;addView(text) }
        box.addView(Button(a).apply { this.text="查看来源";setOnClickListener {
            work(a, {
                r.sources.joinToString("\n\n") { s ->
                    val chat=ChatStore(a).load(s.chatId);val m=chat?.messages?.firstOrNull { it.messageId==s.messageId }
                    "${s.chatId} / ${s.messageId} v${s.revision}\n"+if(m==null)"来源会话尚未恢复或已删除" else if(m.sourceRevision!=s.revision || MemoryRules.hash(m.content)!=s.digest)"来源消息已修改；旧证据不可继续核实" else m.content.take(4000)
                }.ifEmpty { "由你在记忆管理中保存" }
            }) { source -> AlertDialog.Builder(a).setTitle("记忆来源").setMessage(source).setPositiveButton("关闭",null).show() }
        } })
        AlertDialog.Builder(a).setTitle(if(r.status=="CANDIDATE")"确认候选记忆" else "记忆详情").setView(ScrollView(a).apply { addView(box) })
            .setPositiveButton(if(r.status=="CANDIDATE")"审核并保存" else "编辑") { _,_ -> edit(a,r) }
            .setNeutralButton("忘记") { _,_ -> work(a,{MemoryStore(a).use { it.forget(r.id,r.revision) };MemoryVaultSync.schedule(a)}) { show(a) } }
            .setNegativeButton("关闭",null).show()
    }
    private fun edit(a: Activity, r: MemoryRecord?) {
        val box=LinearLayout(a).apply { orientation=LinearLayout.VERTICAL;setPadding(32,16,32,16) }
        val key=EditText(a).apply { hint="规范键，例如 response.style";setText(r?.key ?: "personal.note."+UUID.randomUUID().toString().take(8));isEnabled=r==null }
        val summary=EditText(a).apply { hint="要保存的事实或偏好";setText(r?.summary ?: "");minLines=3 }
        box.addView(key);box.addView(summary)
        box.addView(TextView(a).apply { text=if(r==null)"新条目保存为个人记忆。不要填写密码、密钥或验证码。" else "${r.scope.name} ${r.scopeKey}；修改将产生新版本。确认保存代表你审核了该事实。" })
        AlertDialog.Builder(a).setTitle("保存记忆").setView(box).setPositiveButton("保存") { _,_ ->
            val k=key.text.toString().trim();val body=summary.text.toString().trim()
            work(a,{MemoryStore(a).use { store -> store.save(r?.scope ?: MemoryScope.PERSONAL,r?.scopeKey ?: "",k,body,r?.sources ?: emptyList(),r?.revision ?: 0,store.epoch(),"ACTIVE",r?.affinity ?: "GENERAL",r?.expiresAt ?: 0,"user_managed") };MemoryVaultSync.schedule(a)}) { show(a) }
        }.setNegativeButton("取消",null).show()
    }
    fun session(a: Activity, chat: Chat, save: ()->Unit) {
        val options=if(chat.retentionPolicy==dev.wind.agent.profile.RetentionPolicy.TEMPORARY)
            arrayOf("保存此临时会话") else arrayOf("读写记忆","只读记忆","关闭本会话记忆","关联项目记忆")
        AlertDialog.Builder(a).setTitle("本会话记忆").setItems(options) { _,i ->
            if(chat.retentionPolicy==dev.wind.agent.profile.RetentionPolicy.TEMPORARY) {
                chat.retentionPolicy=dev.wind.agent.profile.RetentionPolicy.PERSISTENT;save();return@setItems
            }
            if(i<3) { chat.memoryPolicy=arrayOf(MemorySessionPolicy.NORMAL,MemorySessionPolicy.READ_ONLY,MemorySessionPolicy.OFF)[i];work(a,{MemoryStore(a).use { it.invalidate() }}) { save() } }
            else {
                work(a,{MemoryStore(a).use { store -> store.list().filter { it.scope==MemoryScope.PROJECT }.map { it.scopeKey }.distinct() }}) { keys ->
                    val choices=(listOf("新建独立项目记忆","解除关联")+keys).toTypedArray()
                    AlertDialog.Builder(a).setTitle("项目逻辑绑定").setMessage("绑定只决定记忆范围，不恢复或授予文件访问权限。")
                        .setItems(choices) { _,index -> chat.projectMemoryKey=when(index){0->"project_"+UUID.randomUUID();1->null;else->keys[index-2]};chat.projectBindingId=chat.projectMemoryKey;work(a,{MemoryStore(a).use { it.invalidate() }}) { save() } }.setNegativeButton("取消",null).show()
                }
            }
        }.setNegativeButton("关闭",null).show()
    }
}
