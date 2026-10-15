package dev.wind.agent.phone

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject

/** Private durable business records. No display lease, socket, task grant or notification is restored. */
internal class ChatConversationStore private constructor(c: Context): SQLiteOpenHelper(c,"chat_guardian_v1.db",null,1) {
    private val app=c.applicationContext
    companion object {
        @Volatile private var instance: ChatConversationStore?=null
        fun get(c: Context)=instance ?: synchronized(this) { instance ?: ChatConversationStore(c.applicationContext).also { instance=it } }
    }
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE records(id TEXT PRIMARY KEY,kind TEXT NOT NULL,session TEXT NOT NULL,state TEXT NOT NULL,version INTEGER NOT NULL,payload TEXT NOT NULL,created INTEGER NOT NULL)")
        db.execSQL("CREATE INDEX record_scope ON records(kind,session,created)")
    }
    override fun onUpgrade(db: SQLiteDatabase, old: Int, new: Int) { error("CHAT_STORE_MIGRATION_REQUIRED") }
    @Synchronized fun put(kind: String, session: String, o: JSONObject) {
        if(kind=="batch") ChatDeliveryState.check(record(o.getString("id"))?.optString("state"),o.getString("state"))
        val values=ContentValues().apply {
            put("id",o.getString("id")); put("kind",kind); put("session",session); put("state",o.optString("state"))
            put("version",o.optLong("message_version")); put("payload",o.toString()); put("created",o.optLong("created_at",System.currentTimeMillis()))
        }
        check(writableDatabase.insertWithOnConflict("records",null,values,SQLiteDatabase.CONFLICT_REPLACE)!=-1L) { "CHAT_STORE_WRITE_FAILED" }
        if(kind in setOf("session","batch","approval"))dev.wind.agent.mate.MateTaskCoordinator.guardian(app,session,o)
    }
    @Synchronized fun record(id: String): JSONObject? = readableDatabase.rawQuery("SELECT payload FROM records WHERE id=?",arrayOf(id)).use { if(it.moveToFirst()) JSONObject(it.getString(0)) else null }
    @Synchronized fun list(kind: String, session: String?=null, limit: Int=100): List<JSONObject> {
        val sql=if(session==null) "SELECT payload FROM records WHERE kind=? ORDER BY created DESC,rowid DESC LIMIT ?"
            else "SELECT payload FROM records WHERE kind=? AND session=? ORDER BY created DESC,rowid DESC LIMIT ?"
        val args=if(session==null) arrayOf(kind,limit.coerceIn(1,1000).toString()) else arrayOf(kind,session,limit.coerceIn(1,1000).toString())
        return readableDatabase.rawQuery(sql,args).use { cur->buildList { while(cur.moveToNext()) add(JSONObject(cur.getString(0))) } }
    }
    @Synchronized fun session(id: String)=record(id) ?: error("CHAT_SESSION_NOT_FOUND")
    @Synchronized fun change(id: String, update: (JSONObject)->Unit): JSONObject {
        val s=session(id); update(s); put("session",id,s); return s
    }
    @Synchronized fun transaction(body: ()->Unit) {
        val db=writableDatabase; db.beginTransaction()
        try { body(); db.setTransactionSuccessful() } finally { db.endTransaction() }
    }
    /** Baseline and inbox advance atomically; identical event sources cannot enqueue twice. */
    @Synchronized fun observe(id: String, turns: List<ChatPageParser.Turn>, seed: Boolean=false,visualHash: String?=null): JSONObject? {
        var batch: JSONObject?=null
        transaction {
            val s=session(id); val previous=ChatSessionKeys.turns(s.optJSONArray("baseline") ?: JSONArray())
            check(!ChatVisualScope.bodyChangedUnparsed(s.optString("visual_body_hash"),visualHash,previous,turns)) { "CHAT_VISUAL_UNREADABLE_BODY_CHANGE" }
            if(visualHash!=null) s.put("visual_body_hash",visualHash)
            val appended=ChatPageParser.incoming(previous,turns,seed,s.optBoolean("empty_baseline_pending"))
            if(turns.isNotEmpty()) s.put("empty_baseline_pending",false)
            s.put("baseline",ChatSessionKeys.json(turns))
            if(appended.isNotEmpty()) {
                val version=s.optLong("message_version")+1; s.put("message_version",version)
                list("approval",id).filter { it.optString("state") in setOf("PENDING","APPROVED") }.forEach { put("approval",id,it.put("state","STALE")) }
                list("batch",id).filter { it.optString("state") in setOf("PENDING","MODELING","WAITING") }.forEach { put("batch",id,it.put("state","SUPERSEDED")) }
                batch=JSONObject().put("id",id+":"+version).put("state","PENDING").put("message_version",version)
                    .put("incoming",appended.joinToString("\n") { it.text }).put("context",ChatSessionKeys.json(turns))
                    .put("created_at",System.currentTimeMillis()).put("wait_count",0)
                put("batch",id,batch!!)
            }
            put("session",id,s)
        }
        return batch
    }
    @Synchronized fun audit(id: String, state: String, text: String, evidence: JSONObject?=null) {
        put("event",id,JSONObject().put("id",java.util.UUID.randomUUID().toString()).put("state",state)
            .put("text",text.take(4000)).put("evidence",evidence ?: JSONObject.NULL).put("created_at",System.currentTimeMillis()))
    }
    @Synchronized fun recoverProcess() {
        list("session",limit=1000).filter { it.optString("state") !in setOf("STOPPED","NEEDS_REAUTHORIZE") }.forEach { s ->
            val id=s.getString("id")
            transaction {
                s.put("state","NEEDS_REAUTHORIZE").remove("display_session_id"); s.remove("display_id")
                put("session",id,s)
                list("approval",id,1000).filter { it.optString("state") in setOf("PENDING","APPROVED") }.forEach { put("approval",id,it.put("state","STALE")) }
                list("batch",id,1000).filter { it.optString("state") in setOf("RESERVED","FILLED","DISPATCHING","DISPATCHED","MODELING") }.forEach { put("batch",id,it.put("state",if(it.optString("state")=="MODELING") "PENDING" else "SEND_UNKNOWN")) }
            }
        }
    }
}
