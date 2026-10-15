package dev.wind.agent

import dev.wind.agent.session.*
import dev.wind.agent.workspace.*
import java.io.*
import org.json.JSONObject

/** Real vault/serialization/ChatStore code; memory documents provider and small Android host fixtures. */
object SessionFeatureTests {
    private var checks = 0
    private fun verify(value: Boolean, why: String) { check(value) { why }; checks++ }
    private class Fs : WorkspaceFs {
        val files = linkedMapOf<String, ByteArray>(); val dirs = linkedSetOf("")
        var tear = false
        override fun isConfigured() = true
        override fun getRootDisplayName() = "fixture"
        override fun list(path: String): List<WorkspaceEntry> = (dirs.filter { it.isNotEmpty() && it.substringBeforeLast('/', "") == path }.map {
            WorkspaceEntry(it,it.substringAfterLast('/'),true,0,0)
        } + files.keys.filter { it.substringBeforeLast('/', "") == path }.map { WorkspaceEntry(it,it.substringAfterLast('/'),false,files[it]!!.size.toLong(),0) })
        override fun read(path: String) = files[path] ?: error("missing $path")
        override fun readText(path: String, charset: String) = String(read(path), java.nio.charset.Charset.forName(charset))
        override fun write(path: String, data: ByteArray) {
            if (tear) { files[path] = data.take(10).toByteArray(); tear=false; error("provider disconnected") }
            files[path]=data.copyOf()
        }
        override fun writeText(path: String, text: String, charset: String) = write(path,text.toByteArray(java.nio.charset.Charset.forName(charset)))
        override fun mkdir(path: String): Boolean { dirs.add(path); return true }
        override fun delete(path: String): Boolean = files.remove(path) != null || dirs.remove(path)
        override fun move(from: String, to: String): Boolean { val data=files.remove(from) ?: return false; files[to]=data; return true }
        override fun exists(path: String) = path in files || path in dirs
        override fun stat(path: String) = list(path.substringBeforeLast('/', "")).firstOrNull { it.path == path }
        override fun search(query: String, maxResults: Int, scopePath: String) = emptyList<WorkspaceEntry>()
        override fun glob(pattern: String, maxResults: Int, scopePath: String) = emptyList<WorkspaceEntry>()
        override fun openInputStream(path: String): InputStream = ByteArrayInputStream(read(path))
        override fun openOutputStream(path: String): OutputStream = object : ByteArrayOutputStream() { override fun close() { this@Fs.write(path,toByteArray()) } }
    }
    private fun chat(id: String, revision: Long, text: String = "original task") = Chat(id,"session",1).apply { updatedAt=revision; messages.add(Message("user",text)) }
    @JvmStatic fun main(args: Array<String>) {
        val fs=Fs(); val vault=SessionVault(fs)
        val c=chat("session-A",10).apply { archived=true; runState="running" }
        val request=Message("user", "Visible task").apply { modelHint="[selected skill paths]" }
        val resume=Message("user", "Continue without replaying uncertain tool actions").apply { modelOnly=true }
        c.messages.add(request); c.messages.add(resume)
        val raw=c.toJson().toString(); vault.publish(c.id,c.updatedAt,raw)
        val selectedVault=Fs()
        fs.files.forEach { (path,bytes) -> if (path.startsWith("HarnessMate/")) selectedVault.files[path.removePrefix("HarnessMate/")]=bytes.copyOf() }
        fs.dirs.forEach { path -> if (path.startsWith("HarnessMate/")) selectedVault.dirs.add(path.removePrefix("HarnessMate/")) }
        val direct=SessionVault(SessionVault.dataWorkspace(selectedVault))
        verify(direct.records().single().payload==raw,"selecting existing vault folder created wrong nesting")
        direct.prepare(); verify(!selectedVault.dirs.contains("HarnessMate"),"existing vault was nested again")
        val restored=Chat.fromJson(JSONObject(vault.records().single().payload!!))
        verify(restored.archived && restored.runState=="running", "archive/run metadata lost")
        verify(restored.messages.last().modelOnly, "hidden continuation became visible after reload")
        verify(restored.messages[1].content=="Visible task" && restored.messages[1].textForModel().contains("selected skill"), "model hints mixed into visible text")
        val title=Chat("title", "",1).apply { messages.add(resume); messages.add(Message("user","Real user request")); autoTitle() }
        verify(title.title=="Real user request", "hidden request became title")
        vault.publish(c.id,20,chat(c.id,20,"newer task").toJson().toString())
        fs.tear=true
        verify(runCatching { vault.publish(c.id,30,chat(c.id,30,"torn task").toJson().toString()) }.isFailure, "torn write not surfaced")
        verify(vault.records().single().revision==20L, "torn generation replaced last good data")
        vault.publish(c.id,40,chat(c.id,40,"latest task").toJson().toString())
        verify(vault.records().single().revision==40L, "latest generation not selected")
        val current=fs.files.keys.first { it.contains("--40-") }; fs.files[current]="bad JSON".toByteArray()
        verify(vault.records().single().revision==20L, "corrupt newest generation did not fall back")
        vault.publish(c.id,50,null)
        verify(vault.records().single().deleted, "deleted conversation resurrected from old backup")
        vault.publish(c.id,60,chat(c.id,60,"one copy").toJson().toString())
        vault.publish(c.id,60,chat(c.id,60,"conflicting copy").toJson().toString())
        verify(vault.records().count()==2, "same-version conflict silently overwritten")
        verify(runCatching { vault.publish("../escape",70,null) }.isFailure, "vault id traversed directory")
        verify(runCatching { vault.publish(c.id,80,chat("wrong-id",80).toJson().toString()) }.isFailure, "envelope/payload mismatch accepted")
        val binary=byteArrayOf(0,-1,0x7f,0x28); fs.write("binary",binary)
        verify(vault.readBytes("binary").contentEquals(binary), "binary skill assets decoded as text")
        verify(runCatching { vault.readBytes("binary",2) }.isFailure, "bounded read ignored limit")
        verify(!TaskNotifications.shouldAlert(true,true,false), "notification while user inside unlocked app")
        verify(TaskNotifications.shouldAlert(false,true,false), "no background notification")
        verify(TaskNotifications.shouldAlert(true,false,true), "no locked-screen notification")
        verify(TaskNotifications.shouldAlert(true,true,true), "keyguard state ignored")
        val cache=File(System.getProperty("java.io.tmpdir"),"session-assets-${System.nanoTime()}").apply { mkdirs() }
        val image=File(cache,"att_123_photo.png").apply { writeBytes(binary) }
        val attached=chat("attachments",100).apply { messages[0].attachments.add(Message.Attachment("photo.png",image.path,"image/png",4,"image")) }
        val assets=SessionAttachments(fs,cache); assets.backup(attached.toJson().toString()); image.delete(); assets.restore()
        verify(image.readBytes().contentEquals(binary),"uploaded attachment did not survive cache deletion/reinstall")
        val secret=File(cache,"credentials.json").apply { writeText("not an uploaded attachment") }
        attached.messages[0].attachments.add(Message.Attachment("secret",secret.path,"application/json",20,"file"))
        assets.backup(attached.toJson().toString())
        verify(fs.files.values.none { String(it)=="not an uploaded attachment" },"arbitrary app-private data exported")
        image.writeText("conflicting current upload")
        verify(runCatching { assets.restore() }.isFailure && image.readText()=="conflicting current upload","asset conflict overwrote current upload")
        cache.deleteRecursively()
        storeTests()
        println("PASS SessionFeatureTests: $checks assertions")
    }
    private fun storeTests() {
        val root=File(System.getProperty("java.io.tmpdir"),"session-store-${System.nanoTime()}").apply { mkdirs() }
        val ctx=object : android.content.ContextWrapper(null) {
            override fun getFilesDir()=root
            override fun getApplicationContext(): android.content.Context=this
        }
        val store=ChatStore(ctx); android.os.Looper.clearMainThread()
        val archived=chat("archive",100).apply { this.archived=true; runState="running" }
        store.saveNow(archived)
        verify(store.listSummaries().none { it.id==archived.id } && store.listSummaries(true).single().id==archived.id,"archived chat still in active list")
        verify(store.load(archived.id)!!.runState=="interrupted","cold running task not marked interrupted")
        android.os.Looper.setAsMainThread(); archived.archived=false; store.save(archived)
        verify(store.listSummaries().any { it.id==archived.id },"pending archive change not visible")
        android.os.Looper.clearMainThread(); check(ChatStore.flushPendingWrites(10000))
        val incoming=chat("restore",200).toJson().toString()
        verify(store.restoreRecord(SessionVault.Record("restore",200,incoming,SessionVault.hash(incoming)))==1,"restore did not install chat")
        verify(store.restoreRecord(SessionVault.Record("restore",200,incoming,SessionVault.hash(incoming)))==0,"restore duplicated equal data")
        val conflict=chat("restore",200,"conflicting facts").toJson().toString()
        verify(store.restoreRecord(SessionVault.Record("restore",200,conflict,SessionVault.hash(conflict)))==1,"restore conflict lost data")
        verify(store.listSummaries().count { it.id.startsWith("restore") }==2,"no conflict recovery copy")
        val stale=chat("restore",100,"older facts").toJson().toString()
        verify(store.restoreRecord(SessionVault.Record("restore",100,stale,SessionVault.hash(stale)))==0,"older backup replaced newer local chat")
        store.restoreRecord(SessionVault.Record("restore",300,null,SessionVault.hash("deleted:restore:300")))
        verify(store.load("restore")==null,"tombstone did not delete local copy")
        verify(store.restoreRecord(SessionVault.Record("restore",200,incoming,SessionVault.hash(incoming)))==0,"old backup resurrected deleted chat")
        val live=chat("live-run",1); val run=AgentBus.beginStarting(live.id,live)
        try {
            val data=chat(live.id,500).toJson().toString()
            verify(store.restoreRecord(SessionVault.Record(live.id,500,data,SessionVault.hash(data)))==0,"restore replaced live task")
        } finally { AgentBus.finish(run) }
        File(root,"chats/damaged.json").writeText("broken history")
        val recovered=chat("damaged",500).toJson().toString()
        verify(store.restoreRecord(SessionVault.Record("damaged",500,recovered,SessionVault.hash(recovered)))==1,"damaged chat not recovered")
        verify(File(root,"chats").listFiles()!!.any { it.name.startsWith("damaged.json.corrupt.") },"damaged original not preserved")
        verify(store.load("../../outside")==null,"unsafe chat id accepted")
        val future=System.currentTimeMillis()+24L*60*60*1000
        val clockChat=chat("clock-restored",future); val clockData=clockChat.toJson().toString()
        store.restoreRecord(SessionVault.Record(clockChat.id,future,clockData,SessionVault.hash(clockData)))
        store.delete(clockChat.id)
        val tombstone=File(root,"chats/.deleted-${clockChat.id}").readText().toLong()
        verify(tombstone>future,"clock rollback made deletion older than restored backup")
        verify(store.restoreRecord(SessionVault.Record(clockChat.id,future,clockData,SessionVault.hash(clockData)))==0,"future backup resurrected deleted chat")
        val saveClock=chat("clock-save",future+1000); store.saveNow(saveClock)
        verify(saveClock.updatedAt>future+1000,"new save revision went backwards after clock change")
        val waiting=chat("approval-owner",1); val approvalRun=AgentBus.beginStarting(waiting.id,waiting)
        val done=java.util.concurrent.atomic.AtomicBoolean(false); var approved=true
        val thread=Thread {
            AgentBus.bindWorker(approvalRun)
            try { approved=AgentBus.awaitApproval("write_file",JSONObject(),AgentBus.token(approvalRun)!!); done.set(true) }
            finally { AgentBus.unbindWorker() }
        }
        thread.start()
        val deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(3)
        while (approvalRun !in AgentBus.pendingApprovalRunIds() && System.nanoTime()<deadline) Thread.sleep(5)
        verify(approvalRun in AgentBus.pendingApprovalRunIds() && !done.get(),"owned approval not queued for background return")
        AgentBus.requestCancel(approvalRun,true); thread.join(3000)
        verify(done.get() && !approved && AgentBus.pendingApprovalRunIds().none { it==approvalRun },"cancel did not retire owned approval")
        AgentBus.finish(approvalRun)
        check(ChatStore.flushPendingWrites(10000)); root.deleteRecursively()
    }
}
