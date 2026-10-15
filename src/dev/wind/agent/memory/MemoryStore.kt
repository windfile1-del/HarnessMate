package dev.wind.agent.memory

import android.content.Context
import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Private SQLite primary. Every mutation, epoch and backup outbox change shares one transaction. */
class MemoryStore(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val helper = Db(app)
    private val db get() = helper.writableDatabase
    init {
        transaction {
            if(meta("projectScopeGuard")!="2") {
                val suspect=db.rawQuery("SELECT body FROM items WHERE scope='PERSONAL' AND status='ACTIVE'",null).use {c ->buildList {
                    while(c.moveToNext())MemoryRecord.parse(JSONObject(c.getString(0))).takeIf(::needsProjectReview)?.let(::add)
                } }
                if(suspect.isNotEmpty()) {
                    val generation=advance()
                    suspect.forEach {write(it.copy(status="CANDIDATE",revision=Math.addExact(it.revision,1),generation=generation))}
                    db.delete("jobs",null,null)
                }
                put(db,"projectScopeGuard","2")
            }
        }
    }
    internal val database get()=db
    private class Db(c: Context) : SQLiteOpenHelper(c, File(c.noBackupFilesDir, "harness-memory/memory-v1.db").also { it.parentFile!!.mkdirs() }.absolutePath, null, 5) {
        private val legacy=c.getSharedPreferences("harness-memory-policy",Context.MODE_PRIVATE)
        private fun additions(d: SQLiteDatabase) {
            d.execSQL("CREATE TABLE redactions(id TEXT PRIMARY KEY,chat TEXT NOT NULL,message TEXT NOT NULL,body TEXT NOT NULL)")
            d.execSQL("CREATE INDEX redaction_source ON redactions(chat,message)")
            for((key,default) in listOf("use_saved" to true,"auto_write" to true,"use_history" to false))put(d,"policy_$key",if(legacy.getBoolean(key,default))"1" else "0")
        }
        init { setWriteAheadLoggingEnabled(true) }
        override fun onCreate(d: SQLiteDatabase) {
            d.execSQL("CREATE TABLE meta(k TEXT PRIMARY KEY,v TEXT NOT NULL)")
            d.execSQL("CREATE TABLE items(id TEXT PRIMARY KEY,scope TEXT NOT NULL,scope_key TEXT NOT NULL,key TEXT NOT NULL,status TEXT NOT NULL,expires INTEGER NOT NULL,body TEXT NOT NULL,affinity TEXT NOT NULL DEFAULT 'GENERAL',UNIQUE(scope,scope_key,key))")
            d.execSQL("CREATE INDEX item_scope ON items(scope,scope_key,status)")
            d.execSQL("CREATE TABLE postings(term TEXT NOT NULL,id TEXT NOT NULL,PRIMARY KEY(term,id))")
            d.execSQL("CREATE INDEX postings_id ON postings(id)")
            d.execSQL("CREATE TABLE revisions(id TEXT NOT NULL,revision INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(id,revision))")
            d.execSQL("CREATE TABLE tombstones(id TEXT PRIMARY KEY,generation INTEGER NOT NULL)")
            d.execSQL("CREATE TABLE suppressions(digest TEXT PRIMARY KEY)")
            d.execSQL("CREATE TABLE jobs(id TEXT PRIMARY KEY,body TEXT NOT NULL)")
            d.execSQL("CREATE TABLE usage(day TEXT PRIMARY KEY,body TEXT NOT NULL)")
            put(d,"namespace","ns_"+UUID.randomUUID()); put(d,"generation","1"); put(d,"epoch","1"); put(d,"outbox","1")
            additions(d)
            jobs(d)
            queueIndex(d)
        }
        private fun jobs(d: SQLiteDatabase) {
            d.execSQL("CREATE TABLE IF NOT EXISTS memory_marks(chat TEXT NOT NULL,message TEXT NOT NULL,revision INTEGER NOT NULL,digest TEXT NOT NULL,offset INTEGER NOT NULL,PRIMARY KEY(chat,message))")
            d.execSQL("CREATE TABLE IF NOT EXISTS memory_calls(id TEXT PRIMARY KEY,day INTEGER NOT NULL,body TEXT NOT NULL)")
            put(d,"historyEpoch","1")
        }
        private fun queueIndex(d: SQLiteDatabase) {
            val columns=d.rawQuery("PRAGMA table_info(jobs)",null).use { c -> buildSet {while(c.moveToNext())add(c.getString(1))} }
            if("state" !in columns)d.execSQL("ALTER TABLE jobs ADD COLUMN state TEXT NOT NULL DEFAULT 'QUEUED'")
            if("due" !in columns)d.execSQL("ALTER TABLE jobs ADD COLUMN due INTEGER NOT NULL DEFAULT 0")
            d.rawQuery("SELECT id,body FROM jobs",null).use { c -> while(c.moveToNext()) {
                val body=JSONObject(c.getString(1))
                d.update("jobs",ContentValues().apply {put("state",body.optString("state","QUEUED"));put("due",body.optLong("due"))},"id=?",arrayOf(c.getString(0)))
            } }
            d.execSQL("CREATE INDEX IF NOT EXISTS memory_queue_due ON jobs(state,due,id)")
        }
        override fun onUpgrade(d: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            check(oldVersion in 1..4 && newVersion==5)
            if(oldVersion==1)additions(d)
            if(oldVersion<3)jobs(d)
            if(oldVersion<4)queueIndex(d)
            val columns=d.rawQuery("PRAGMA table_info(items)",null).use { c -> buildSet {while(c.moveToNext())add(c.getString(1))} }
            if("affinity" !in columns) {
                d.execSQL("ALTER TABLE items ADD COLUMN affinity TEXT NOT NULL DEFAULT 'GENERAL'")
                d.rawQuery("SELECT id,body FROM items",null).use { c -> while(c.moveToNext()) {
                    val record=MemoryRecord.parse(JSONObject(c.getString(1)))
                    d.update("items",ContentValues().apply {put("affinity",record.affinity)},"id=?",arrayOf(c.getString(0)))
                } }
            }
        }
    }
    companion object {
        private fun put(d: SQLiteDatabase, k: String, v: String) { d.insertWithOnConflict("meta",null,ContentValues().apply { put("k",k);put("v",v) },SQLiteDatabase.CONFLICT_REPLACE) }
    }
    private fun meta(k: String): String = db.rawQuery("SELECT v FROM meta WHERE k=?",arrayOf(k)).use { if(it.moveToFirst())it.getString(0) else "0" }
    fun namespace() = meta("namespace")
    fun epoch() = meta("epoch").toLong()
    fun generation() = meta("generation").toLong()
    fun dirty() = meta("outbox") != "0"
    fun policy(key: String): Boolean { require(key in listOf("use_saved","auto_write","use_history"));return meta("policy_$key")=="1" }
    fun setPolicy(key: String,value: Boolean) = transaction {
        require(key in listOf("use_saved","auto_write","use_history"))
        put(db,"policy_$key",if(value)"1" else "0");advance();db.delete("jobs",null,null);Unit
    }
    private fun advance(invalidate: Boolean = true): Long {
        val next = Math.addExact(generation(),1); require(next < Long.MAX_VALUE)
        put(db,"generation",next.toString()); put(db,"outbox","1")
        if(invalidate)put(db,"epoch",Math.addExact(epoch(),1).toString())
        return next
    }
    fun invalidate() = transaction { advance(); db.delete("jobs",null,null); Unit }
    internal fun <T> transaction(block: () -> T): T { db.beginTransaction(); try { val r=block();db.setTransactionSuccessful();return r } finally { db.endTransaction() } }
    fun historyEpoch()=meta("historyEpoch").toLong()
    fun historyDeleted(chatId: String,forgetRelated: Boolean=false,revision: Long=0)=transaction {
        if(revision>0 && meta("historyDeleted_$chatId").toLong()>=revision)return@transaction
        put(db,"historyEpoch",Math.addExact(historyEpoch(),1).toString());advance();db.delete("jobs",null,null)
        db.delete("memory_marks","chat=?",arrayOf(chatId))
        if(forgetRelated)list().filter { r -> r.sources.any { it.chatId==chatId } }.forEach { forget(it.id,it.revision) }
        if(revision>0)put(db,"historyDeleted_$chatId",revision.toString())
    }
    fun get(id: String): MemoryRecord? = db.rawQuery("SELECT body FROM items WHERE id=?",arrayOf(id)).use { if(it.moveToFirst())MemoryRecord.parse(JSONObject(it.getString(0))) else null }
    fun findByKey(scope: MemoryScope,scopeKey: String,key: String): MemoryRecord? = db.rawQuery("SELECT body FROM items WHERE scope=? AND scope_key=? AND key=?",arrayOf(scope.name,scopeKey,key)).use {if(it.moveToFirst())MemoryRecord.parse(JSONObject(it.getString(0))) else null}
    fun list(status: String? = null, offset: Int = 0, limit: Int = MemoryRules.MAX_RECORDS): List<MemoryRecord> {
        require(offset in 0..MemoryRules.MAX_RECORDS && limit in 1..MemoryRules.MAX_RECORDS)
        val args=(if(status==null)emptyList() else listOf(status))+listOf(limit.toString(),offset.toString())
        return db.rawQuery("SELECT body FROM items"+(if(status==null)"" else " WHERE status=?")+" ORDER BY id LIMIT ? OFFSET ?",args.toTypedArray()).use { c -> buildList {while(c.moveToNext())add(MemoryRecord.parse(JSONObject(c.getString(0))))} }
    }
    fun count(status: String? = null)=db.rawQuery("SELECT COUNT(*) FROM items"+(if(status==null)"" else " WHERE status=?"),if(status==null)null else arrayOf(status)).use {it.moveToFirst();it.getInt(0)}
    fun suppressed(digest: String) = db.rawQuery("SELECT digest FROM suppressions WHERE digest=?",arrayOf(digest)).use { it.moveToFirst() }
    fun blocked(s: MemorySource)=suppressed(s.fingerprint()) || suppressed(s.legacyIdentity()) ||
        (s.hasSpan && suppressed(s.copy(start=-1,end=-1,spanDigest="",rolling=0).fingerprint()))
    fun redactions(chatId: String): Map<String,List<MemorySource>> = db.rawQuery("SELECT body FROM redactions WHERE chat=? ORDER BY id",arrayOf(chatId)).use { c ->
        val result=mutableListOf<MemorySource>();while(c.moveToNext())result.add(MemorySource.parse(JSONObject(c.getString(0))));result.groupBy { it.messageId }
    }
    fun projectText(chatId: String,m: dev.wind.agent.Message) = MemoryRedactions.apply(m.content,redactions(chatId)[m.messageId] ?: emptyList())
    private fun suppress(s: MemorySource) {
        db.insertWithOnConflict("suppressions",null,ContentValues().apply { put("digest",s.fingerprint()) },SQLiteDatabase.CONFLICT_IGNORE)
        if(s.hasSpan)writeRedaction(s) else db.insertWithOnConflict("suppressions",null,ContentValues().apply { put("digest",s.legacyIdentity()) },SQLiteDatabase.CONFLICT_IGNORE)
    }
    private fun writeRedaction(s: MemorySource) {
        require(s.hasSpan)
        check(db.rawQuery("SELECT COUNT(*) FROM redactions",null).use { it.moveToFirst();it.getInt(0) }<20000 || db.rawQuery("SELECT id FROM redactions WHERE id=?",arrayOf(s.fingerprint())).use { it.moveToFirst() }) { "MEMORY_REDACTION_LIMIT" }
        db.insertWithOnConflict("redactions",null,ContentValues().apply { put("id",s.fingerprint());put("chat",s.chatId);put("message",s.messageId);put("body",s.json().toString()) },SQLiteDatabase.CONFLICT_IGNORE)
    }
    fun revoked(id: String, revision: Long) = get(id)?.let { it.revision != revision || it.status != "ACTIVE" } ?: true
    private fun needsProjectReview(r: MemoryRecord)=r.origin in listOf("automatic","user_explicit") && r.scope==MemoryScope.PERSONAL && MemoryRules.projectFact("fact",r.summary)
    private fun write(record: MemoryRecord): MemoryRecord {
        // Old backup catalogs also pass this gate; restoring them cannot re-activate
        // an automatic project fact that an older version mislabeled as global.
        val r=if(needsProjectReview(record))record.copy(status="CANDIDATE") else record
        check(get(r.id)!=null || count()<MemoryRules.MAX_RECORDS) { "MEMORY_ITEM_LIMIT" }
        db.insertWithOnConflict("items",null,ContentValues().apply { put("id",r.id);put("scope",r.scope.name);put("scope_key",r.scopeKey);put("key",r.key);put("status",r.status);put("expires",r.expiresAt);put("body",r.json().toString());put("affinity",r.affinity) },SQLiteDatabase.CONFLICT_REPLACE)
        db.delete("postings","id=?",arrayOf(r.id))
        for(term in MemoryRules.terms(r.key+" "+r.summary)) db.insertWithOnConflict("postings",null,ContentValues().apply { put("term",term);put("id",r.id) },SQLiteDatabase.CONFLICT_IGNORE)
        db.insertWithOnConflict("revisions",null,ContentValues().apply { put("id",r.id);put("revision",r.revision);put("body",r.json().toString()) },SQLiteDatabase.CONFLICT_REPLACE)
        return r
    }
    fun save(scope: MemoryScope, scopeKey: String, key: String, summary: String, sources: List<MemorySource>,
             expectedRevision: Long, expectedEpoch: Long, status: String = "ACTIVE", affinity: String = "GENERAL", expiresAt: Long = 0,
             origin: String = "user_managed", allowSuppressed: Boolean = false): MemoryRecord = transaction {
        MemoryRules.validate(scope,scopeKey,key,summary)
        require(status in listOf("ACTIVE","CANDIDATE") && affinity in listOf("GENERAL","CODE","MATE") && expiresAt>=0 && sources.size<=16)
        check(epoch()==expectedEpoch) { "MEMORY_EPOCH_CHANGED" }
        if(origin in listOf("user_explicit","automatic"))check(policy("use_saved") && (origin!="automatic" || policy("auto_write"))) { "MEMORY_POLICY_CHANGED" }
        check(allowSuppressed || sources.none(::blocked)) { "MEMORY_SOURCE_FORGOTTEN" }
        val existing=findByKey(scope,scopeKey,key)
        check((existing?.revision ?: 0)==expectedRevision) { "MEMORY_REVISION_CONFLICT" }
        val g=advance(existing!=null)
        val r=MemoryRecord(existing?.id ?: "mem_"+UUID.randomUUID(),namespace(),scope,scopeKey,key,summary,(existing?.revision?:0)+1,g,status,affinity,System.currentTimeMillis(),expiresAt,sources,origin)
        write(r)
    }
    fun forget(id: String, expectedRevision: Long, expectedEpoch: Long? = null, modelWrite: Boolean = false) = transaction {
        if(expectedEpoch!=null)check(epoch()==expectedEpoch) { "MEMORY_EPOCH_CHANGED" }
        if(modelWrite)check(policy("use_saved")) { "MEMORY_POLICY_CHANGED" }
        val r=get(id) ?: return@transaction
        check(r.revision==expectedRevision) { "MEMORY_REVISION_CONFLICT" }
        val g=advance()
        db.insertWithOnConflict("tombstones",null,ContentValues().apply { put("id",id);put("generation",g) },SQLiteDatabase.CONFLICT_REPLACE)
        db.rawQuery("SELECT body FROM revisions WHERE id=?",arrayOf(id)).use { c -> while(c.moveToNext())MemoryRecord.parse(JSONObject(c.getString(0))).sources.forEach(::suppress) }
        r.sources.forEach(::suppress)
        db.delete("items","id=?",arrayOf(id)); db.delete("postings","id=?",arrayOf(id));db.delete("revisions","id=?",arrayOf(id));db.delete("jobs",null,null)
        // Other candidates from the same forgotten source are removed too.
        list("CANDIDATE").filter { c -> c.sources.any(::blocked) }.forEach { c -> db.delete("items","id=?",arrayOf(c.id));db.delete("postings","id=?",arrayOf(c.id));db.delete("revisions","id=?",arrayOf(c.id)) }
    }
    fun clear() = transaction {
        val records=list(); val g=advance()
        records.forEach { r -> db.insertWithOnConflict("tombstones",null,ContentValues().apply { put("id",r.id);put("generation",g) },SQLiteDatabase.CONFLICT_REPLACE)
            r.sources.forEach(::suppress) }
        db.rawQuery("SELECT body FROM revisions",null).use { c -> while(c.moveToNext())MemoryRecord.parse(JSONObject(c.getString(0))).sources.forEach(::suppress) }
        for(table in listOf("items","postings","revisions","jobs"))db.delete(table,null,null)
        put(db,"clearBarrier",g.toString())
    }
    /** Filter scope before postings ranking; Chinese ngrams work on minSdk23 SQLite without FTS5. */
    fun search(query: String, project: String? = null, task: String? = null, affinity: String = "GENERAL", limit: Int = 10): List<MemoryRecord> {
        require(query.length<=12000 && limit in 1..10)
        val terms=MemoryRules.terms(query).take(64)
        val now=System.currentTimeMillis()
        // Rank in SQLite and decode only the bounded result. No full-catalog JSON scan.
        val join=if(terms.isEmpty())"" else "LEFT JOIN (SELECT id,COUNT(*) AS hits FROM postings WHERE term IN (${terms.joinToString(","){"?"}}) GROUP BY id) h ON h.id=i.id"
        val hit=if(terms.isEmpty())"0" else "COALESCE(h.hits,0)"
        val args=terms+listOf(now.toString(),project ?: "",task ?: "",affinity,limit.toString())
        val sql="SELECT i.body FROM items i $join WHERE i.status='ACTIVE' AND (i.expires=0 OR i.expires>?) AND (i.scope='PERSONAL' OR (i.scope='PROJECT' AND i.scope_key=?) OR (i.scope='TASK' AND i.scope_key=?)) AND ($hit>0 OR i.key IN ('response.language','response.style','communication.style')) ORDER BY ($hit*10+CASE WHEN i.scope!='PERSONAL' THEN 3 ELSE 0 END+CASE WHEN i.affinity=? THEN 1 ELSE 0 END) DESC,i.id LIMIT ?"
        return db.rawQuery(sql,args.toTypedArray()).use { c -> buildList {while(c.moveToNext())add(MemoryRecord.parse(JSONObject(c.getString(0))))} }
    }
    /** Consistent JSON export, never a live DB/WAL copy. Candidates and jobs stay private. */
    fun export(): JSONObject = transaction {
        val dead=JSONArray();db.rawQuery("SELECT id,generation FROM tombstones ORDER BY id",null).use { c -> while(c.moveToNext())dead.put(JSONObject().put("id",c.getString(0)).put("generation",c.getLong(1))) }
        val suppressed=JSONArray();db.rawQuery("SELECT digest FROM suppressions ORDER BY digest",null).use { c -> while(c.moveToNext())suppressed.put(c.getString(0)) }
        val redacted=JSONArray();db.rawQuery("SELECT body FROM redactions ORDER BY id",null).use { c -> while(c.moveToNext())redacted.put(JSONObject(c.getString(0))) }
        val privacy=JSONObject();for(key in listOf("use_saved","auto_write","use_history"))privacy.put(key,policy(key))
        JSONObject().put("format",1).put("namespace",namespace()).put("generation",generation()).put("epoch",epoch()).put("clearBarrier",meta("clearBarrier").toLong())
            .put("items",JSONArray(list("ACTIVE").map { it.json() })).put("tombstones",dead).put("suppressions",suppressed).put("redactions",redacted).put("privacy",privacy)
    }
    fun markBackedUp(generation: Long) = transaction { if(generation()==generation)put(db,"outbox","0") }
    fun restore(j: JSONObject): Int = transaction {
        MemorySnapshot.validate(j)
        require(j.optInt("format")==1 && j.toString().length<=8*1024*1024)
        val ns=j.getString("namespace");require(MemoryRules.id(ns))
        val generation=j.getLong("generation");val importedEpoch=j.getLong("epoch");val barrier=j.optLong("clearBarrier")
        require(generation in 1 until Long.MAX_VALUE && importedEpoch in 1 until Long.MAX_VALUE && barrier in 0..generation)
        val items=j.getJSONArray("items");val tomb=j.getJSONArray("tombstones");val suppress=j.getJSONArray("suppressions")
        require(items.length()<=MemoryRules.MAX_RECORDS && tomb.length()<=20000 && suppress.length()<=20000)
        val records=(0 until items.length()).map { MemoryRecord.parse(items.getJSONObject(it)).also { r -> require(r.namespace==ns && r.status=="ACTIVE" && r.generation<=generation) } }
        // A reinstall may adopt a namespace only while no local data/deletion barriers exist.
        if(ns!=namespace()) {
            check(list().isEmpty() && db.rawQuery("SELECT id FROM tombstones LIMIT 1",null).use { !it.moveToFirst() } && meta("clearBarrier")=="0") { "MEMORY_NAMESPACE_CONFLICT" }
            put(db,"namespace",ns)
            j.optJSONObject("privacy")?.let { p -> for(key in listOf("use_saved","auto_write","use_history"))put(db,"policy_$key",if(p.getBoolean(key))"1" else "0") }
        }
        for(i in 0 until suppress.length()) { val d=suppress.getString(i);require(d.matches(Regex("[a-f0-9]{64}")));db.insertWithOnConflict("suppressions",null,ContentValues().apply { put("digest",d) },SQLiteDatabase.CONFLICT_IGNORE) }
        j.optJSONArray("redactions")?.let { a -> for(i in 0 until a.length())writeRedaction(MemorySource.parse(a.getJSONObject(i))) }
        for(i in 0 until tomb.length()) { val t=tomb.getJSONObject(i);val id=t.getString("id");val g=t.getLong("generation");require(MemoryRules.id(id) && g in 1..generation)
            val old=db.rawQuery("SELECT generation FROM tombstones WHERE id=?",arrayOf(id)).use { if(it.moveToFirst())it.getLong(0) else 0 }
            if(g>old)db.insertWithOnConflict("tombstones",null,ContentValues().apply { put("id",id);put("generation",g) },SQLiteDatabase.CONFLICT_REPLACE)
        }
        val clear=maxOf(barrier,meta("clearBarrier").toLong());put(db,"clearBarrier",clear.toString())
        fun dead(r: MemoryRecord) = r.generation<=clear || r.sources.any(::blocked) || db.rawQuery("SELECT generation FROM tombstones WHERE id=?",arrayOf(r.id)).use { it.moveToFirst() }
        list().filter(::dead).forEach { r -> db.delete("items","id=?",arrayOf(r.id));db.delete("postings","id=?",arrayOf(r.id));db.delete("revisions","id=?",arrayOf(r.id)) }
        var count=0
        for(r in records) {
            if(dead(r))continue
            val local=get(r.id)
            if(local!=null && local.revision==r.revision && local.json().toString()!=r.json().toString()) {
                write(local.copy(status="CANDIDATE"))
                val suffix=MemoryRules.hash(r.json().toString()).take(12)
                val conflict=r.copy(id="conflict_$suffix",key=(r.key.take(70)+".conflict.$suffix"),status="CANDIDATE",origin="import_conflict")
                write(conflict);continue
            }
            val sameKey=db.rawQuery("SELECT body FROM items WHERE scope=? AND scope_key=? AND key=? AND id!=?",arrayOf(r.scope.name,r.scopeKey,r.key,r.id)).use {if(it.moveToFirst())MemoryRecord.parse(JSONObject(it.getString(0))) else null}
            if(sameKey!=null) {
                write(sameKey.copy(status="CANDIDATE"))
                val suffix=MemoryRules.hash(r.json().toString()).take(12)
                val conflict=r.copy(id="conflict_$suffix",key=r.key.take(70)+".conflict.$suffix",status="CANDIDATE",origin="import_conflict")
                write(conflict);continue
            }
            if(local==null || r.revision>local.revision) { write(r);count++ }
        }
        put(db,"generation",maxOf(generation(),generation).toString());put(db,"epoch",Math.addExact(maxOf(epoch(),importedEpoch),1).toString());put(db,"outbox","1");db.delete("jobs",null,null)
        count
    }
    override fun close() = helper.close()
}
