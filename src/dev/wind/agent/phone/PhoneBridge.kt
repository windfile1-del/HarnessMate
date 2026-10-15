package dev.wind.agent.phone

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.adb.AdbManager
import dev.wind.agent.adb.AdbTransport
import dev.wind.agent.permissions.AgentCapability
import dev.wind.agent.permissions.AgentPermissions
import org.json.JSONObject
import java.io.Closeable
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.UUID
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

internal object PhoneBridgeProtocol {
    const val VERSION = 2
    const val JSON_LIMIT = 1048576
    const val IMAGE_LIMIT = 16777216
    fun frame(bytes: ByteArray): ByteArray {
        require(bytes.size in 1..JSON_LIMIT) { "PHONE_BRIDGE_FRAME_LIMIT" }
        return ByteBuffer.allocate(4 + bytes.size).putInt(bytes.size).put(bytes).array()
    }
    fun length(bytes: ByteArray, limit: Int): Int = ByteBuffer.wrap(bytes).int.also {
        require(it in 1..limit) { "PHONE_BRIDGE_FRAME_LIMIT" }
    }
    fun hmac(secret: String, value: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return mac.doFinal(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    fun bootstrapDiagnostic(reply: JSONObject): JSONObject? {
        if(reply.optString("status")!="START_FAILED" || reply.optString("error") !in setOf("PHONE_BRIDGE_STARTUP_FAILED","PHONE_BRIDGE_RELAY_CONNECT_FAILED")) return null
        val diagnostic=reply.optJSONObject("diagnostic") ?: return null
        if(diagnostic.optString("failure_stage") !in setOf("CONFIG","MAIN_LOOPER","ACTIVITY_THREAD","CONFIGURATION_CONTROLLER","SYSTEM_CONTEXT","JOURNAL","DEVICE_LOCK","LOCAL_SOCKETS","UI_AUTOMATION","RELAY_CONNECT") ||
            !diagnostic.optString("exception_class").matches(Regex("[A-Za-z_$][A-Za-z0-9_.$]{0,159}"))) return null
        val result=JSONObject().put("failure_stage",diagnostic.getString("failure_stage")).put("exception_class",diagnostic.getString("exception_class"))
        for(key in listOf("pid","android_api")) if(diagnostic.has(key)) {
            val value=diagnostic.optInt(key,-1); if(value<=0) return null; result.put(key,value)
        }
        return result
    }
    fun exitDiagnostic(text: String): JSONObject? {
        if(text.length>4096) return null
        val fields=text.lineSequence().filter { it.isNotBlank() }.map { it.split('=',limit=2) }.toList()
        val base=setOf("state","pid","code","method","at")
        val crash=base+setOf("diagnostic_version","thread","exception_class","cause_classes","stack_frames")
        if(fields.any { it.size!=2 } || fields.map { it[0] }.toSet().size!=fields.size || fields.map { it[0] }.toSet() !in setOf(base,crash)) return null
        val values=fields.associate { it[0] to it[1] }
        val pid=values["pid"]?.toIntOrNull() ?: return null
        val at=values["at"]?.toLongOrNull() ?: return null
        if(pid<=0 || at<=0 || values["state"] !in setOf("RUNNING","EXIT","DISPLAY_CLOSED") ||
            !values.getValue("code").matches(Regex("[A-Z][A-Z0-9_]{2,100}")) || !values.getValue("method").matches(Regex("[A-Za-z_]{1,40}"))) return null
        val result=JSONObject().put("state",values["state"]).put("pid",pid).put("code",values["code"]).put("method",values["method"]).put("at",at)
        if(fields.size==10) {
            val identifier=Regex("[A-Za-z_$][A-Za-z0-9_.$<>]{0,159}")
            val causes=values.getValue("cause_classes").takeIf { it.isNotEmpty() }?.split(';') ?: emptyList()
            val frames=values.getValue("stack_frames").takeIf { it.isNotEmpty() }?.split(';') ?: emptyList()
            if(values["state"]!="EXIT" || values["code"] !in setOf("UNCAUGHT_BRIDGE_FAILURE","STARTUP_BRIDGE_FAILURE") || values["diagnostic_version"]!="1" ||
                !values.getValue("thread").matches(Regex("main|vega-phone-(ui|images|heartbeats|lease|client|expired-cleanup)|phone-virtual-frames|OTHER")) ||
                !values.getValue("exception_class").matches(identifier) || causes.size>3 || causes.any { !it.matches(identifier) } || frames.size>8) return null
            val parsed=org.json.JSONArray()
            for(frame in frames) {
                val parts=frame.split('#',':')
                if(parts.size!=3 || !parts[0].matches(identifier) || !parts[1].matches(Regex("[A-Za-z_$][A-Za-z0-9_.$<>]{0,99}"))) return null
                val line=parts[2].toIntOrNull()?.takeIf { it>=-2 } ?: return null
                parsed.put(JSONObject().put("class",parts[0]).put("method",parts[1]).put("line",line))
            }
            result.put("diagnostic_version",1).put("thread",values["thread"]).put("exception_class",values["exception_class"])
                .put("cause_classes",org.json.JSONArray(causes)).put("stack_frames",parsed)
        }
        return result
    }
}

/** Session is tied to ADB epoch, task, permission revision and cancellation. Secrets never reach tool results. */
internal data class PhoneFrame(val png: ByteArray, val metadata: JSONObject)

internal class PhoneBridge(private val context: Context, val owner: String, private val lifetime: CancellationToken,
    private val virtualOnly: Boolean = false, private val independentScope: Boolean = false, private val allowedPackages: Set<String>? = null) : Closeable {
    private val manager = AdbManager.get(context)
    private val permissions = AgentPermissions.get(context)
    private val epoch = manager.connectionEpoch
    private fun currentRevision() = if(independentScope) permissions.authorityRevision(owner) else permissions.revision
    private val revision = currentRevision()
    private val session = UUID.randomUUID().toString().replace("-", "")
    private val secret = ByteArray(32).also { java.security.SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it.toInt() and 255) }
    private var connection: AdbTransport? = null
    private var stream: AdbTransport.RawStream? = null
    private var heartbeatConnection: AdbTransport? = null
    private var heartbeatStream: AdbTransport.RawStream? = null
    private var eventConnection: AdbTransport? = null
    @Volatile private var eventListener: ((JSONObject) -> Unit)? = null
    private val channelLock=Any()
    private val heartbeatLock=Any()
    private val health=PhoneBridgeHealth()
    private var heartbeatEventEpoch=-1L
    @Volatile private var serverExit: JSONObject?=null
    @Volatile private var bootstrapDiagnostic: JSONObject?=null
    @Volatile private var processDiagnostic: JSONObject?=null
    private var payloadPath=""
    @Volatile private var closed = false
    private var listener: AutoCloseable? = null
    private var cancellation: CancellationToken.Registration? = null
    var capabilities = JSONObject(); private set
    @Volatile var liveDisplay: JSONObject? = null; private set
    private fun authorityFailure() = PhoneBridgeHealth.authorityFailure(lifetime.isCancelled,permissions.taskEnded(owner),epoch,manager.connectionEpoch,revision,currentRevision(),
        permissions.enabled(AgentCapability.LOCAL_ADB,owner) && (!virtualOnly || permissions.enabled(AgentCapability.VIRTUAL_DISPLAY,owner) && permissions.enabled(AgentCapability.ACCESSIBILITY,owner) && permissions.enabled(AgentCapability.SCREEN,owner)))
    fun valid(): Boolean = !closed && authorityFailure()==null
    fun diagnostic(): JSONObject {
        authorityFailure()?.let { health.failed("AUTHORITY",it) }
        return health.snapshot().put("closed",closed).put("bound_adb_epoch",epoch).put("current_adb_epoch",manager.connectionEpoch)
            .put("bound_permission_revision",revision).put("current_permission_revision",currentRevision())
            .put("lifetime_cancelled",lifetime.isCancelled).put("task_ended",permissions.taskEnded(owner))
            .put("heartbeat_channel","INDEPENDENT").put("server_exit",serverExit ?: JSONObject.NULL)
            .put("transport","MANAGED_SHELL_V2").put("bootstrap",bootstrapDiagnostic ?: JSONObject.NULL)
            .put("process",processDiagnostic ?: stream?.processDiagnostic() ?: JSONObject.NULL)
            .put("server_exit_interpretation",if(serverExit?.optString("state")=="DISPLAY_CLOSED")
                "HARNESS_RELEASED_DISPLAY_PROCESS_EXIT_NOT_ASSERTED" else "NO_RECORD_DOES_NOT_PROVE_OEM_KILL")
    }
    private fun authority(token: CancellationToken) {
        token.throwIfCancelled(); lifetime.throwIfCancelled(); check(valid()) { "PHONE_BRIDGE_LEASE_REVOKED" }
    }
    private fun read(s: AdbTransport.RawStream, limit: Int = PhoneBridgeProtocol.JSON_LIMIT): ByteArray {
        val size = PhoneBridgeProtocol.length(s.readExact(4), limit)
        return s.readExact(size)
    }
    private fun authenticate(s: AdbTransport.RawStream, channel: String) {
        val challenge = JSONObject(String(read(s), Charsets.UTF_8))
        check(challenge.getInt("version") == PhoneBridgeProtocol.VERSION && challenge.getString("session") == session) { "PHONE_BRIDGE_VERSION_MISMATCH" }
        if(challenge.optString("status")=="START_FAILED") {
            bootstrapDiagnostic=PhoneBridgeProtocol.bootstrapDiagnostic(challenge)?.put("channel",channel)
            bootstrapDiagnostic?.optInt("pid",-1)?.takeIf { it>0 }?.let { health.pid=it }
            error(if(bootstrapDiagnostic!=null) challenge.getString("error") else "PHONE_BRIDGE_BOOTSTRAP_DIAGNOSTIC_INVALID")
        }
        val proof = PhoneBridgeProtocol.hmac(secret, "$session:${challenge.getString("challenge")}:$channel")
        s.write(PhoneBridgeProtocol.frame(JSONObject().put("proof", proof).toString().toByteArray(Charsets.UTF_8)))
        check(JSONObject(String(read(s), Charsets.UTF_8)).getString("status") == "AUTHENTICATED") { "PHONE_BRIDGE_AUTH_FAILED" }
    }
    fun start(token: CancellationToken): PhoneBridge {
        try { return startManaged(token) }
        catch(failure: Exception) {
            processDiagnostic=stream?.processDiagnostic(); readServerExit()
            terminate("BOOTSTRAP",failure.message ?: "PHONE_BRIDGE_STARTUP_FAILED"); throw failure
        }
    }
    private fun startManaged(token: CancellationToken): PhoneBridge {
        authority(token)
        val manifest = context.assets.open("embedded/phone/payload.json").bufferedReader().use { JSONObject(it.readText()) }
        check(manifest.getInt("protocol") == PhoneBridgeProtocol.VERSION) { "PHONE_BRIDGE_VERSION_MISMATCH" }
        val payload = context.assets.open("embedded/phone/vega-phone.jar").use { it.readBytes() }
        require(payload.size in 1..1048576) { "PHONE_BRIDGE_PAYLOAD_LIMIT" }
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it.toInt() and 255) }
        check(digest == manifest.getString("sha256")) { "PHONE_BRIDGE_PAYLOAD_MISMATCH" }
        val base = "/data/local/tmp/vega-phone"
        val path = "$base/bridge-$digest.jar"
        payloadPath=path
        // Fixed shell-owned directory and symlink refusal. Only verified asset bytes enter stdin.
        val deploy = manager.execute("[ ! -L '$base' ] && mkdir -p '$base' && chmod 700 '$base' && [ ! -L '$path' ] && cat > '$path.tmp-$session' && chmod 400 '$path.tmp-$session' && mv '$path.tmp-$session' '$path' && sha256sum '$path'",
            owner, token, 15000, 1024, payload, { authority(token) })
        check(deploy.status == "COMPLETED" && String(deploy.stdout).substringBefore(' ') == digest) { "PHONE_BRIDGE_DEPLOY_FAILED" }
        val config = JSONObject().put("session", session).put("secret", secret)
            .put("allow_ui", permissions.enabled(AgentCapability.ACCESSIBILITY, owner))
            .put("allow_screen", permissions.enabled(AgentCapability.SCREEN, owner))
            .put("virtual_only", virtualOnly)
            .put("stdio_control",true)
            .put("allowed_packages", org.json.JSONArray((allowedPackages ?: VirtualDisplays.allowed(context)).sorted())).toString() + "\n"
        // The foreground app_process and binary RPC remain owned by this ADB connection.
        // No background pipeline, shell exit race, or adbd localabstract forwarding is involved.
        val launcher = manager.auxiliary(owner, token) { authority(lifetime) }
        var installed=false
        val startupCancel=token.onCancel { terminate("CANCELLATION","PHONE_BRIDGE_CANCELLED") }
        try {
            val raw=launcher.openBridgeProcess("CLASSPATH='$path' exec app_process / dev.wind.phonebridge.Main") { authority(lifetime) }
            synchronized(channelLock) { check(!closed) { "PHONE_BRIDGE_LEASE_REVOKED" }; connection=launcher; stream=raw; installed=true }
            raw.write(config.toByteArray(Charsets.UTF_8))
            authenticate(raw,"control")
        } finally { startupCancel.close(); if(!installed) manager.release(launcher) }
        listener = permissions.observeChanges { authorityFailure()?.let { terminate("AUTHORITY",it) } }
        cancellation = lifetime.onCancel { terminate("CANCELLATION","PHONE_BRIDGE_CANCELLED") }
        capabilities = rpc("status", JSONObject(), token)
        health.pid=capabilities.optInt("pid",-1)
        heartbeatEventEpoch=capabilities.optLong("event_epoch",-1)
        check(capabilities.optString("heartbeat_channel")=="INDEPENDENT") { "PHONE_BRIDGE_HEARTBEAT_CHANNEL_REQUIRED" }
        check(capabilities.optString("control_transport")=="MANAGED_SHELL_V2") { "PHONE_BRIDGE_PROCESS_TRANSPORT_REQUIRED" }
        var heartbeat: AdbTransport?=null
        try {
            heartbeat=manager.auxiliary(owner,token) { authority(lifetime) }
            val raw=openRelay(heartbeat,"lease",lifetime)
            authenticate(raw,"lease")
            synchronized(channelLock) { check(!closed) { "PHONE_BRIDGE_LEASE_REVOKED" }; heartbeatConnection=heartbeat; heartbeatStream=raw }
            heartbeat=null
            pulse(token)
        } finally { heartbeat?.let(manager::release) }
        Thread({
            while (!closed) {
                try {
                    lifetime.sleep(3000)
                    authorityFailure()?.let { terminate("AUTHORITY",it) }
                    if (!closed) pulse(lifetime)
                } catch (failure: Exception) { terminate("HEARTBEAT",failure.message ?: "PHONE_BRIDGE_HEARTBEAT_FAILED"); readServerExit(); break }
            }
        }, "phone-bridge-lease").apply { isDaemon = true; start() }
        return this
    }
    @Synchronized fun rpc(method: String, args: JSONObject, token: CancellationToken, id: String = UUID.randomUUID().toString()): JSONObject {
        authority(token)
        val s = stream ?: error("PHONE_BRIDGE_NOT_CONNECTED")
        val request = JSONObject().put("version", PhoneBridgeProtocol.VERSION).put("session", session).put("request_id", id).put("method", method).put("args", args)
        var submitted = false
        val start=System.nanoTime(); var wroteAt=start
        health.requested(method)
        val cancel = token.onCancel { terminate("CANCELLATION","PHONE_BRIDGE_CANCELLED") }
        try {
            authority(token); submitted = method == "commit"
            s.write(PhoneBridgeProtocol.frame(request.toString().toByteArray(Charsets.UTF_8)))
            wroteAt=System.nanoTime()
            val result = JSONObject(String(read(s), Charsets.UTF_8))
            check(result.optString("request_id") == id && result.optInt("version") == PhoneBridgeProtocol.VERSION) { "PHONE_BRIDGE_RESPONSE_MISMATCH" }
            authority(token)
            result.put("transport_timing",JSONObject().put("roundtrip_ms",(System.nanoTime()-start)/1_000_000.0)
                .put("write_ms",(wroteAt-start)/1_000_000.0).put("read_ms",(System.nanoTime()-wroteAt)/1_000_000.0))
            if(method=="commit" && result.optString("status")=="DISPATCHED") capabilities.put("input_verification","DISPATCH_ACKNOWLEDGED")
            if(method=="display_create" && result.optString("status")=="CREATED") liveDisplay=JSONObject(result.toString())
            return result
        } catch (e: Exception) {
            terminate("RPC",e.message ?: "PHONE_BRIDGE_TRANSPORT_FAILED"); readServerExit()
            if (submitted) return JSONObject().put("request_id", id).put("status", "UNKNOWN").put("submitted", true).put("retry_safe", false).put("error", "PHONE_BRIDGE_COMPLETION_UNKNOWN")
            throw e
        } finally { cancel.close() }
    }
    private fun pulse(token: CancellationToken) = synchronized(heartbeatLock) {
        authority(token)
        val raw=heartbeatStream ?: error("PHONE_BRIDGE_HEARTBEAT_NOT_CONNECTED")
        val id=UUID.randomUUID().toString()
        raw.write(PhoneBridgeProtocol.frame(JSONObject().put("version",PhoneBridgeProtocol.VERSION).put("session",session)
            .put("method","ping").put("request_id",id).toString().toByteArray(Charsets.UTF_8)),4000)
        val size=PhoneBridgeProtocol.length(raw.readExact(4,4000),PhoneBridgeProtocol.JSON_LIMIT)
        val reply=JSONObject(String(raw.readExact(size,4000),Charsets.UTF_8))
        check(reply.optString("request_id")==id && reply.optInt("version")==PhoneBridgeProtocol.VERSION) { "PHONE_BRIDGE_RESPONSE_MISMATCH" }
        check(reply.optString("status")=="COMPLETED") { reply.optString("error","PHONE_BRIDGE_HEARTBEAT_FAILED") }
        reply.optJSONObject("display")?.let { next ->
            liveDisplay?.let { old -> VirtualDisplayScope.session(old.getString("display_session_id"),next.optString("display_session_id"),next.optInt("display_id",-1)) }
            liveDisplay=JSONObject(next.toString())
        }
        val next=reply.optLong("event_epoch",heartbeatEventEpoch)
        if(next!=heartbeatEventEpoch) { heartbeatEventEpoch=next; PhoneManagedRecovery.signal() }
        health.renewed()
    }
    private fun readServerExit() {
        if(authorityFailure()!=null) return
        runCatching {
            val result=manager.execute("head -c 4096 '/data/local/tmp/vega-phone/diagnostic-$session'",owner,lifetime,1500,4096,
                beforeSend={ lifetime.throwIfCancelled(); check(authorityFailure()==null) { "PHONE_BRIDGE_LEASE_REVOKED" } })
            if(result.status=="COMPLETED") serverExit=PhoneBridgeProtocol.exitDiagnostic(String(result.stdout,Charsets.UTF_8))
        }
    }
    fun action(args: JSONObject, token: CancellationToken): JSONObject {
        val id = UUID.randomUUID().toString()
        val prepare = rpc("prepare", args, token, id)
        if (prepare.optString("status") != "PREPARED") return prepare
        return rpc("commit", JSONObject().also { if(args.has("screen")) it.put("screen",args.getString("screen")) }, token, id).put("retry_safe", false)
    }
    /** Metadata failures reconnect independently. The ordinary control/heartbeat channels own liveness. */
    fun watchEvents(listener: (JSONObject) -> Unit) {
        check(virtualOnly) { "CHAT_EVENT_SCOPE_REQUIRED" }
        synchronized(channelLock) { check(eventListener==null) { "CHAT_EVENT_STREAM_ALREADY_OPEN" }; eventListener=listener }
        if(!capabilities.optBoolean("chat_event_stream")) {
            listener(JSONObject().put("event_stream_state","POLLING").put("error","CHAT_EVENT_STREAM_UNAVAILABLE").put("fatal",false))
            return
        }
        Thread({
            var failures=0
            var after=-1L
            while(!closed && !lifetime.isCancelled) {
                var channel: AdbTransport?=null
                var failure: Exception?=null
                try {
                    authority(lifetime)
                    val display=liveDisplay ?: error("VIRTUAL_DISPLAY_SESSION_CHANGED")
                    val waitMs=capabilities.optInt("chat_event_wait_ms",ChatEventStreamPolicy.LEGACY_WAIT_MS)
                    val readTimeout=ChatEventStreamPolicy.readTimeout(waitMs)
                    channel=manager.auxiliary(owner,lifetime) { authority(lifetime) }
                    synchronized(channelLock) { check(!closed); eventConnection=channel }
                    val raw=openRelay(channel,"lease",lifetime); authenticate(raw,"lease")
                    while(!closed && !lifetime.isCancelled) {
                        authority(lifetime); val id=UUID.randomUUID().toString()
                        raw.write(PhoneBridgeProtocol.frame(JSONObject().put("version",PhoneBridgeProtocol.VERSION).put("session",session)
                            .put("request_id",id).put("method","events_wait").put("args",JSONObject().put("after_epoch",after)).toString().toByteArray(Charsets.UTF_8)))
                        val reply=ChatEventStreamPolicy.readResponse({ n,ms->raw.readExact(n,ms) },readTimeout,id,
                            display.getString("display_session_id"),display.getInt("display_id"))
                        authority(lifetime)
                        if(failures>0) eventListener?.invoke(JSONObject().put("event_stream_state","PUSH").put("fatal",false))
                        failures=0
                        val next=reply.getLong("event_epoch")
                        if(next!=after) { after=next; eventListener?.invoke(reply) }
                    }
                } catch(e: Exception) { failure=e }
                finally {
                    channel?.let(manager::release)
                    synchronized(channelLock) { if(eventConnection===channel) eventConnection=null }
                }
                if(closed || lifetime.isCancelled) break
                val code=failure?.message ?: "CHAT_EVENT_STREAM_FAILED"
                if(ChatEventStreamPolicy.fatal(code,valid())) {
                    eventListener?.invoke(JSONObject().put("error",code).put("fatal",true)); break
                }
                failures=(failures+1).coerceAtMost(4)
                eventListener?.invoke(JSONObject().put("event_stream_state",ChatEventStreamPolicy.state(failures))
                    .put("error",code).put("fatal",false).put("retry_after_ms",ChatEventStreamPolicy.delay(failures)))
                if(!lifetime.sleep(ChatEventStreamPolicy.delay(failures))) break
            }
        },"chat-bridge-events").apply { isDaemon=true; start() }
    }
    fun capture(token: CancellationToken): ByteArray = captureFrame(token).png
    fun captureFrame(token: CancellationToken, args: JSONObject = JSONObject()): PhoneFrame {
        authority(token)
        val channel = manager.auxiliary(owner, token) { authority(token) }
        val cancel = token.onCancel { manager.release(channel) }
        try {
            val raw = openRelay(channel,"image",token)
            authenticate(raw, "image")
            val id = UUID.randomUUID().toString()
            raw.write(PhoneBridgeProtocol.frame(JSONObject().put("version", PhoneBridgeProtocol.VERSION).put("session", session).put("request_id", id).put("method", "capture").put("args",args).toString().toByteArray(Charsets.UTF_8)))
            val result = JSONObject(String(read(raw), Charsets.UTF_8))
            check(result.getString("request_id") == id && result.getInt("version")==PhoneBridgeProtocol.VERSION) { "PHONE_BRIDGE_RESPONSE_MISMATCH" }
            check(result.getString("status") == "COMPLETED") { result.optString("error","PHONE_CAPTURE_FAILED") }
            val png=read(raw, PhoneBridgeProtocol.IMAGE_LIMIT).also { check(it.size == result.getInt("bytes")); authority(token) }
            if(virtualOnly && args.optString("screen")!="main") VirtualDisplayScope.frame(args,result)
            else check(result.optInt("display_id",0)==0) { "PHONE_MAIN_DISPLAY_UNVERIFIED" }
            return PhoneFrame(png,result)
        } finally { cancel.close(); manager.release(channel) }
    }
    private fun terminate(stage: String,code: String) {
        health.failed(stage,authorityFailure() ?: code)
        val channels=synchronized(channelLock) {
            processDiagnostic=stream?.processDiagnostic() ?: processDiagnostic
            closed=true
            listOfNotNull(connection,heartbeatConnection,eventConnection).also { connection=null; stream=null; heartbeatConnection=null; heartbeatStream=null; eventConnection=null; eventListener=null }
        }
        listener?.close(); listener=null; cancellation?.close(); cancellation=null
        channels.forEach { runCatching { manager.release(it) } }
    }
    private fun openRelay(connection: AdbTransport,channel: String,token: CancellationToken): AdbTransport.RawStream {
        require(channel in setOf("image","lease")) { "PHONE_BRIDGE_RELAY_SCOPE_INVALID" }
        return connection.openBridgeProcess("CLASSPATH='$payloadPath' exec app_process / dev.wind.phonebridge.SocketRelay $session $channel") { authority(token) }
    }
    fun close(reason: String) = terminate("CLIENT",reason)
    override fun close() = close("PHONE_BRIDGE_CLIENT_CLOSED")
}
