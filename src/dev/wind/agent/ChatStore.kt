package dev.wind.agent

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import org.json.JSONObject

/**
 * Atomic chat files with background serialization and fsync. A coalescing lane
 * orders saves/deletes for one conversation; independent conversations do not
 * share its serialization lock or wait for its disk I/O. Main-thread callers
 * enqueue without waiting. Worker saveNow waits at most five seconds; timeout
 * leaves the durable write queued and readable through the pending live cache.
 * Message list snapshots protect background serialization from live appends.
 */
class ChatStore(context: Context) {

    private val appContext = context.applicationContext

    private val dir: File = File(context.filesDir, "chats").also {
        if (!it.exists()) {
            it.mkdirs()
        }
    }

    /** Strictly monotonic timestamp, so two saves in the same millisecond still order. */
    private fun now(minimum: Long = 0L): Long = nowStatic(minimum)

    fun create(retention: dev.wind.agent.profile.RetentionPolicy = dev.wind.agent.profile.RetentionPolicy.PERSISTENT): Chat {
        val stamp = now()
        // The title is stored EMPTY, never as the "New chat" placeholder text.
        // Writing the placeholder to disk froze whatever wording the build used at
        // creation time, and chats made by older builds are still on disk carrying
        // theirs. Every display site falls back to Fa.NEW_CHAT for an empty title
        // (and Fa.isPlaceholderTitle recognises the older spellings), so the label
        // always matches the current build, and autoTitle() replaces it on the
        // first real message.
        val chat = Chat("chat_$stamp", "", stamp)
        chat.agentProfile = Prefs(appContext).defaultAgentProfile()
        chat.retentionPolicy=retention
        if(retention==dev.wind.agent.profile.RetentionPolicy.TEMPORARY)chat.memoryPolicy=dev.wind.agent.profile.MemorySessionPolicy.OFF
        dev.wind.agent.webprovider.WebModelRouting.bindDefault(appContext, chat)
        // Blocking: the id is handed straight to AgentService, which loads the
        // chat back off disk by id. An async create could still be queued when
        // that load runs, and the run would abort on a chat that "does not exist".
        // Once per new conversation, so the cost is irrelevant.
        saveNow(chat)
        return chat
    }

    /**
     * Queues the conversation to be written, and returns immediately.
     *
     * Coalescing: the snapshot is filed under the chat's id, so N saves of one
     * chat that arrive faster than the disk can absorb them collapse into a
     * single write of the LATEST state. A burst of tool observations therefore
     * costs one fsync, not one per observation.
     *
     * Does not throw. A write that fails has no caller left to tell — the
     * failure is logged, and the next [saveNow] (teardown, always on a path the
     * user can be told about) reports a fresh attempt honestly.
     */
    fun save(chat: Chat) {
        enqueue(chat)
    }

    private fun enqueue(chat: Chat): KeyedWorkQueue.Ticket {
        require(dev.wind.agent.session.SessionVault.validId(chat.id)) { "CHAT_ID_INVALID" }
        // This captures metadata only once for a direct input, never stream/tool/wake saves.
        dev.wind.agent.mate.MateTaskCoordinator.capture(chat)
        runCatching {dev.wind.agent.memory.MemoryCoordinator.capture(appContext,chat)}
        if(chat.retentionPolicy==dev.wind.agent.profile.RetentionPolicy.TEMPORARY) {
            check(!File(dir,"${chat.id}.json").exists()) { "TEMPORARY_CANNOT_REPLACE_PERSISTED_CHAT" }
            val cache=appContext.cacheDir.canonicalFile
            val temporary=File(cache,"temporary-chats/${chat.id}").apply { mkdirs() }.canonicalFile
            check(temporary.parentFile?.parentFile==cache) { "TEMPORARY_CACHE_OUTSIDE_SCOPE" }
            synchronized(chat.messages) { chat.messages.flatMap { it.attachments.toList() }.forEach { attachment ->
                attachment.path?.let { p -> val file=File(p).canonicalFile
                    if(file.parentFile==cache && file.name.startsWith("att_") && file.isFile) {
                        val target=File(temporary,file.name)
                        check(file.renameTo(target)) { "TEMPORARY_ATTACHMENT_MOVE_FAILED" };attachment.path=target.path
                    }
                }
            } }
            temporaryChats[dir.canonicalPath+":"+chat.id]=chat
            return writes.submit(chat.id) { chat.updatedAt=now(chat.updatedAt) }
        }
        val cache=appContext.cacheDir.canonicalFile
        val temporary=File(cache,"temporary-chats/${chat.id}").canonicalFile
        synchronized(chat.messages) { chat.messages.flatMap { it.attachments.toList() }.forEach { attachment ->
            attachment.path?.let { p -> val file=File(p).canonicalFile
                if(file.parentFile==temporary && file.name.startsWith("att_") && file.isFile) {
                    val target=File(cache,file.name);check(!target.exists() && file.renameTo(target)) { "PERSIST_ATTACHMENT_CONFLICT" };attachment.path=target.path
                }
            }
        } }
        temporaryChats.remove(dir.canonicalPath+":"+chat.id)
        val snapshot = PendingChat(chat)
        liveSnapshots[chat.id] = snapshot
        return writes.submit(chat.id) {
        // Serialization and fsync are both off the caller/UI thread. Only this
        // conversation's lane is held; another chat can save/start independently.
        chat.updatedAt = now(chat.updatedAt)
        val data = chat.toJson().toString().toByteArray(Charsets.UTF_8)
        writeSnapshot(chat.id, File(dir, "${chat.id}.json"), data)?.let { report(chat.id, it); throw it }
        File(dir, ".deleted-${chat.id}").delete()
        dev.wind.agent.session.SessionVaultSync.publish(appContext, chat.id, chat.updatedAt, String(data, Charsets.UTF_8))
        dev.wind.agent.memory.MemoryCoordinator.saved(appContext,String(data,Charsets.UTF_8),chat.runState in listOf("completed","failed","stopped","interrupted"))
        liveSnapshots.remove(chat.id, snapshot)
        }
    }

    /**
     * Writes the conversation and does not return until it is on disk.
     *
     * For teardown only — `onStop`, `onDestroy`, the end of a run — where an
     * asynchronous write could still be queued when the process is reclaimed.
     * Throws [IllegalStateException] if the write fails, exactly as the old
     * synchronous [save] did, so the existing "could not save" handling keeps
     * working on the paths that still use it.
     */
    fun saveNow(chat: Chat) {
        val ticket = enqueue(chat)
        if (!onMainThread()) ticket.await(5000L)
    }

    /** Writes a completed snapshot in this conversation's serial lane. */
    private fun writeSnapshot(id: String, target: File, data: ByteArray): IllegalStateException? {
        val tmp = File(
            dir,
            "$id.json.tmp." + Thread.currentThread().id + "." + System.nanoTime()
        )
        return try {
            FileOutputStream(tmp).use { out ->
                out.write(data)
                out.fd.sync()
            }
            if (!tmp.renameTo(target)) {
                throw IllegalStateException("atomic chat save failed")
            }
            null
        } catch (e: Exception) {
            if (tmp.exists()) {
                tmp.delete()
            }
            IllegalStateException("chat save failed: " + e.message, e)
        }
    }

    /** The async path's only recourse: there is no caller left to throw at. */
    private fun report(id: String, problem: IllegalStateException?) {
        if (problem != null) {
            Log.e("Vega", "chat save failed for $id", problem)
        }
    }

    fun load(id: String): Chat? {
        if (!dev.wind.agent.session.SessionVault.validId(id)) return null
        if(isDeleted(id))return null
        temporaryChats[dir.canonicalPath+":"+id]?.let { return it }
        liveSnapshots[id]?.let { return it.chat }
        val file = File(dir, "$id.json")
        if (!file.exists()) {
            return null
        }
        return try {
            Chat.fromJson(JSONObject(String(Util.readAll(file), Charsets.UTF_8))).also {
                if (it.runState in listOf("running", "approval") && !AgentBus.isRunningFor(it.id)) it.runState = "interrupted"
            }
        } catch (e: Exception) {
            null
        }
    }

    fun isDeleted(id: String): Boolean = dev.wind.agent.session.SessionVault.validId(id) && File(dir,".deleted-$id").isFile

    private fun deletionIntent(id: String,revision: Long,forgetRelated: Boolean) {
        val data=JSONObject().put("id",id).put("revision",revision).put("forgetRelated",forgetRelated).toString().toByteArray(Charsets.UTF_8)
        writeSnapshot(id,File(dir,".memory-delete-$id"),data)?.let {throw it}
    }

    fun delete(id: String,forgetRelatedMemory: Boolean=false) {
        val ephemeral=temporaryChats[dir.canonicalPath+":"+id]
        if(ephemeral!=null) {
            val ticket=writes.submit(id,coalesce=false) {
                temporaryChats.remove(dir.canonicalPath+":"+id)
                runCatching {dev.wind.agent.mate.MateTaskCoordinator.store(appContext,true).forgetTemporaryChat(id)}
                val cache=appContext.cacheDir.canonicalFile
                val temporary=File(cache,"temporary-chats/$id").canonicalFile
                check(temporary.parentFile?.parentFile==cache)
                temporary.deleteRecursively()
            }
            if(!onMainThread())ticket.await(5000L)
            return
        }
        val pending = liveSnapshots[id]
        val ticket = writes.submit(id, coalesce = false) {
            require(dev.wind.agent.session.SessionVault.validId(id))
            val revision = now()
            deletionIntent(id,revision,forgetRelatedMemory)
            writeSnapshot(id, File(dir, ".deleted-$id"), revision.toString().toByteArray())?.let { throw it }
            // Runs on the file worker, never the UI. The journal survives any gap/crash.
            runCatching {dev.wind.agent.memory.MemoryCoordinator.reconcileDeletions(appContext)}
            val file = File(dir, "$id.json")
            if (file.exists() && !file.delete()) throw IllegalStateException("CHAT_DELETE_FAILED")
            if (pending != null) liveSnapshots.remove(id, pending)
            dev.wind.agent.session.SessionVaultSync.publish(appContext, id, revision, null)
            dev.wind.agent.memory.MemoryCoordinator.deleted(appContext,id,forgetRelatedMemory)
        }
        if (!onMainThread()) ticket.await(5000L)
    }

    /**
     * Lightweight drawer row: id + title + updatedAt, WITHOUT parsing the
     * conversation body.
     *
     * The drawer only needs titles, but [list] used to deserialize every message
     * of every chat just to show them — and the delete flow did that two or three
     * times in a single tap, then rebuilt a whole transcript. With many (or
     * large) chats that is megabytes of transient allocation on the UI thread,
     * which is what made deleting a chat crash (OutOfMemoryError) or freeze long
     * enough for the system to kill the app. Reading just the file header keeps
     * this O(number-of-chats), not O(total-conversation-bytes).
     */
    class Summary(
        val id: String,
        val title: String,
        val updatedAt: Long,
        val pinned: Boolean = false,
        val archived: Boolean = false
    )

    fun listSummaries(archived: Boolean = false): MutableList<Summary> {
        val summaries = linkedMapOf<String, Summary>()
        dir.listFiles()?.filter { it.name.endsWith(".json") }?.forEach { file ->
            readSummary(file)?.let { summaries[it.id] = it }
        }
        liveSnapshots.forEach { (id, pending) -> pending.chat.let { summaries[id] = Summary(id, it.title, it.updatedAt, it.pinned, it.archived) } }
        temporaryChats.filterKeys { it.startsWith(dir.canonicalPath+":") }.values.forEach { c -> summaries[c.id]=Summary(c.id,c.title.ifBlank { "临时会话" },c.updatedAt,c.pinned,c.archived) }
        val out = summaries.values.filter { it.archived == archived }.toMutableList()
        // Pinned first, then most recent. A comparator rather than two passes so
        // the order is one rule the drawer cannot render out of step with.
        out.sortWith(
            compareByDescending<Summary> { it.pinned }.thenByDescending { it.updatedAt }
        )
        return out
    }

    /** Recovery shares the normal per-chat lane and never replaces a live run. */
    fun restoreRecord(record: dev.wind.agent.session.SessionVault.Record): Int {
        require(dev.wind.agent.session.SessionVault.validId(record.id) && record.revision > 0 && record.revision < Long.MAX_VALUE)
        now(record.revision) // seed the process clock even when the imported chat is later deleted without another save
        var count = 0
        val ticket = writes.submit(record.id, coalesce = false) {
            if (AgentBus.isRunningFor(record.id)) return@submit
            val file = File(dir, "${record.id}.json")
            val local = if (file.exists()) runCatching { Chat.fromJson(JSONObject(file.readText())) }.getOrNull() else null
            val deleted = File(dir, ".deleted-${record.id}").takeIf { it.exists() }?.readText()?.trim()?.toLongOrNull() ?: 0L
            if (maxOf(deleted, local?.updatedAt ?: 0L) > record.revision) return@submit
            if (record.deleted) {
                deletionIntent(record.id,record.revision,false)
                writeSnapshot(record.id, File(dir, ".deleted-${record.id}"), record.revision.toString().toByteArray())?.let { throw it }
                runCatching {dev.wind.agent.memory.MemoryCoordinator.reconcileDeletions(appContext)}
                if (file.exists()) check(file.delete())
                liveSnapshots.remove(record.id)
                dev.wind.agent.memory.MemoryCoordinator.deleted(appContext,record.id)
            } else if (deleted < record.revision) {
                val incoming = Chat.fromJson(JSONObject(record.payload!!))
                if(incoming.retentionPolicy==dev.wind.agent.profile.RetentionPolicy.TEMPORARY)return@submit
                var target = file
                if (local != null && local.updatedAt == record.revision) {
                    // Binding is local authorization, not a content conflict. Normalize only
                    // for comparison; never revoke a valid local binding during ordinary sync.
                    val localPayload=local.toJson().apply { remove("projectBindingId") }
                    val incomingPayload=incoming.toJson().apply { remove("projectBindingId") }
                    for(payload in listOf(localPayload,incomingPayload))dev.wind.agent.memory.MemoryCoordinator.normalizeRestoredMetadata(payload)
                    if (localPayload.toString() == incomingPayload.toString()) return@submit
                    incoming.id = record.id.take(120) + "_recovered_" + record.checksum.take(16)
                    incoming.title += "（恢复副本）"
                    target = File(dir, "${incoming.id}.json")
                    if (target.exists()) return@submit
                }
                incoming.projectBindingId=null // newly restored content must be explicitly rebound
                dev.wind.agent.memory.MemoryCoordinator.restored(incoming)
                if (target == file && file.exists() && local == null) check(file.renameTo(File(dir, "${record.id}.json.corrupt.${now()}")))
                writeSnapshot(incoming.id, target, incoming.toJson().toString().toByteArray(Charsets.UTF_8))?.let { throw it }
                File(dir, ".deleted-${incoming.id}").delete(); count++
            }
        }
        ticket.await(10000L)
        return count
    }

    private fun readSummary(file: File): Summary? {
        // Chats are written id/title/createdAt/updatedAt/messages in order, so the
        // header is normally the first ~150 bytes. Try a small head, then a larger
        // one (a very long title, or a title containing the literal text
        // "messages", can push the real cut further out) before paying for a full
        // parse.
        headerSummary(file, 8192)?.let { return it }
        headerSummary(file, 65536)?.let { return it }
        // Older or unusually-ordered files: full parse. Correct, just not cheap.
        // Unreadable files are skipped, not fatal.
        return try {
            val chat = Chat.fromJson(JSONObject(String(Util.readAll(file), Charsets.UTF_8)))
            Summary(chat.id, chat.title, chat.updatedAt, chat.pinned, chat.archived)
        } catch (ignored: Exception) {
            null
        }
    }

    private fun headerSummary(file: File, headBytes: Int): Summary? {
        return try {
            val head = readHead(file, headBytes)
            val cut = head.indexOf("\"messages\"")
            val headerJson = if (cut > 0) {
                var h = head.substring(0, cut).trimJava()
                if (h.endsWith(",")) {
                    h = h.substring(0, h.length - 1)
                }
                "$h}"
            } else {
                head
            }
            val obj = JSONObject(headerJson)
            Summary(
                obj.getString("id"),
                obj.optStr("title", ""),
                obj.optLong("updatedAt", obj.optLong("createdAt", 0L)),
                obj.optBoolean("pinned", false),
                obj.optBoolean("archived", false)
            )
        } catch (headerFailure: Exception) {
            null
        }
    }

    private fun readHead(file: File, max: Int): String {
        FileInputStream(file).use { input ->
            val buffer = ByteArray(max)
            var total = 0
            while (total < max) {
                val n = input.read(buffer, total, max - total)
                if (n < 0) {
                    break
                }
                total += n
            }
            return String(buffer, 0, total, Charsets.UTF_8)
        }
    }

    /** Most recently updated first. Unreadable files are skipped, not fatal. */
    fun list(): MutableList<Chat> {
        val chats = mutableListOf<Chat>()
        dir.listFiles()?.forEach { file ->
            if (file.name.endsWith(".json")) {
                try {
                    chats.add(Chat.fromJson(JSONObject(String(Util.readAll(file), Charsets.UTF_8))))
                } catch (ignored: Exception) {
                }
            }
        }
        chats.sortByDescending { it.updatedAt }
        return chats
    }

    companion object {
        private fun onMainThread(): Boolean = runCatching {
            val current = android.os.Looper.myLooper()
            current != null && current === android.os.Looper.getMainLooper()
        }.getOrDefault(false)
        // Shared across Activity/Service stores, with one independently scheduled lane per id.
        private val writes = KeyedWorkQueue()
        private class PendingChat(val chat: Chat)
        private val liveSnapshots = java.util.concurrent.ConcurrentHashMap<String, PendingChat>()
        private val temporaryChats = java.util.concurrent.ConcurrentHashMap<String, Chat>()
        fun cleanAbandonedTemporaryCaches(context: Context) {
            val cache=context.cacheDir.canonicalFile;val target=File(cache,"temporary-chats").canonicalFile
            check(target.parentFile==cache) { "TEMPORARY_CACHE_OUTSIDE_SCOPE" }
            if(target.exists())target.deleteRecursively()
        }

        /** Best-effort bounded durability barrier used before crash teardown. */
        fun flushPendingWrites(timeoutMs: Long): Boolean = try {
            writes.flush(timeoutMs)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }

        // Monotonic timestamps remain process-wide; serialization does not.
        private var clock: Long = System.currentTimeMillis()

        @Synchronized
        private fun nowStatic(minimum: Long): Long {
            val previous = maxOf(clock, minimum)
            check(previous < Long.MAX_VALUE) { "CHAT_REVISION_EXHAUSTED" }
            var stamp = System.currentTimeMillis()
            if (stamp <= previous) {
                stamp = previous + 1
            }
            clock = stamp
            return stamp
        }
    }
}
