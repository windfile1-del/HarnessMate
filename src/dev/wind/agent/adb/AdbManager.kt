package dev.wind.agent.adb

import android.content.Context
import android.os.Build
import dev.wind.agent.CancellationToken
import dev.wind.agent.SecureStore
import dev.wind.agent.permissions.AgentCapability
import dev.wind.agent.permissions.AgentPermissions
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.locks.ReentrantLock

/** Local TLS pairing or explicitly selected classic RSA AUTH; authority stays task scoped. */
class AdbManager private constructor(private val context: Context) {
    private val permissions = AgentPermissions.get(context)
    private val prefs = context.getSharedPreferences("embedded_adb_v1", Context.MODE_PRIVATE)
    private val slot = "adb_identity_" + context.filesDir.absolutePath.hashCode()
    private val operationLock = ReentrantLock()
    private val generation = AtomicLong()
    private val setupGuard = Any()
    private val channels = ConcurrentHashMap<AdbTransport, () -> Boolean>()
    val connectionEpoch get() = generation.get()
    private val receipts = java.util.ArrayDeque<JSONObject>()
    @Volatile private var transport: AdbTransport? = null
    @Volatile private var state = "DISCONNECTED"
    @Volatile private var lastError = ""
    @Volatile private var connectedPort = 0
    @Volatile private var connectionMode = "NONE"
    @Volatile private var localRoute = AdbLocalRoute()
    @Volatile private var setupStage = "NONE"
    @Volatile private var activeTask = ""
    @Volatile private var activeRevision = -1L
    init {
        // Auxiliary service owners keep only their own/global authority; unrelated tasks cannot close their sockets.
        permissions.observeChanges {
            channels.forEach { (channel, valid) -> if(!valid()) { channels.remove(channel); runCatching { channel.close() } } }
            if (activeRevision >= 0 && activeRevision != permissions.authorityRevision(activeTask)) disconnect()
        }
    }
    companion object {
        private val managers = ConcurrentHashMap<String, AdbManager>()
        fun get(context: Context): AdbManager {
            val app = context.applicationContext
            return managers[app.filesDir.absolutePath] ?: synchronized(managers) {
                managers[app.filesDir.absolutePath] ?: AdbManager(app).also { managers[app.filesDir.absolutePath] = it }
            }
        }
    }
    private fun requirePlatform() {
        require(Build.VERSION.SDK_INT >= 30) { "ADB_WIRELESS_REQUIRES_ANDROID_11" }
        check(NativeAdb.available()) { "ADB_NATIVE_COMPONENT_UNAVAILABLE" }
    }
    private fun checkSetup(attempt: Long) {
        check(!Thread.currentThread().isInterrupted && generation.get() == attempt) { "ADB_SETUP_CANCELLED" }
    }
    private fun identity(): ByteArray {
        val saved = prefs.getString("identity", "")
        val plain = SecureStore.decrypt(saved, slot)
        if (plain.isNotEmpty()) return plain.toByteArray(Charsets.UTF_8)
        check(saved.isNullOrBlank() || !SecureStore.encrypted(saved)) { "ADB_KEY_UNAVAILABLE_FORGET_AND_PAIR_AGAIN" }
        val generated = NativeAdb.generateIdentity()
        val protected = SecureStore.encrypt(String(generated, Charsets.UTF_8), slot)
        check(prefs.edit().putString("identity", protected).remove("guid").commit()) { "ADB_IDENTITY_SAVE_FAILED" }
        return generated
    }
    fun pair(port: Int, code: String): String {
        requirePlatform(); require(port in 1..65535 && code.matches(Regex("[0-9]{6}"))) { "ADB_PAIRING_INPUT_INVALID" }
        check(operationLock.tryLock()) { "ADB_BUSY" }
        closeAuxiliary()
        val attempt = generation.incrementAndGet()
        try {
            checkSetup(attempt)
            transport?.close(); transport = null; connectedPort = 0; state = "PAIRING"; lastError = ""
            localRoute = AdbLocalNetwork.snapshot(context); setupStage = "PAIR_SOCKET_TLS_SPAKE2"
            val key = identity()
            val guid = try { NativeAdb.pair(port, code, key, 30000, localRoute.handle) } finally { key.fill(0) }
            check(guid.isNotBlank() && guid.length <= 256) { "ADB_PAIRING_GUID_INVALID" }
            synchronized(setupGuard) {
                checkSetup(attempt)
                check(prefs.edit().putString("guid", guid).commit()) { "ADB_PAIRING_SAVE_FAILED" }
                state = "PAIRED_DISCONNECTED"
            }
            return guid
        } catch (e: Exception) {
            if (generation.get() == attempt) { state = "DISCONNECTED"; lastError = "ADB_SETUP_FAILED[$setupStage,${localRoute.name}]: ${e.message ?: "ADB_PAIRING_FAILED"}" }
            throw e
        } finally { operationLock.unlock() }
    }
    fun connect(port: Int, mode: AdbConnectionMode = AdbConnectionMode.AUTO, onProgress: (String) -> Unit = {}) {
        require(port in 1..65535) { "ADB_CONNECT_PORT_INVALID" }
        val selected = mode.resolve(port)
        if (selected == AdbConnectionMode.TLS) requirePlatform() else check(NativeAdb.available()) { "ADB_NATIVE_COMPONENT_UNAVAILABLE" }
        check(operationLock.tryLock()) { "ADB_BUSY" }
        closeAuxiliary()
        val attempt = generation.incrementAndGet()
        try {
            checkSetup(attempt)
            transport?.close(); transport = null; connectedPort = 0; connectionMode = selected.name; state = "CONNECTING"; lastError = ""
            if (selected == AdbConnectionMode.TLS) check(!prefs.getString("guid", "").isNullOrBlank()) { "ADB_PAIRING_REQUIRED" }
            localRoute = AdbLocalNetwork.snapshot(context); setupStage = "SOCKET_CONNECT"
            onProgress("CONNECTING")
            val key = identity()
            val handle = try {
                if (selected == AdbConnectionMode.TLS) check(!prefs.getString("guid", "").isNullOrBlank()) { "ADB_PAIRING_REQUIRED" }
                checkSetup(attempt); NativeAdb.open(port, key, 10000, localRoute.handle)
            } finally { key.fill(0) }
            val io = object : AdbWireIo {
                override fun read(data: ByteArray, offset: Int, length: Int, timeoutMs: Int) = NativeAdb.read(handle, data, offset, length, timeoutMs)
                override fun write(data: ByteArray, timeoutMs: Int) = NativeAdb.write(handle, data, 0, data.size, timeoutMs)
                override fun upgradeTls(timeoutMs: Int) = NativeAdb.upgradeTls(handle, timeoutMs)
                override fun close() = NativeAdb.close(handle)
            }
            val candidate = AdbTransport(io, object : AdbAuthenticator {
                override fun sign(token: ByteArray) = NativeAdb.signToken(handle, token)
                override fun publicKey() = NativeAdb.publicKey(handle)
            })
            synchronized(setupGuard) {
                try { checkSetup(attempt) } catch (error: Exception) { candidate.close(); throw error }
                transport = candidate // disconnect can cancel an in-progress TLS handshake.
            }
            candidate.connect(if (selected == AdbConnectionMode.TCP) 60000 else 15000, selected,
                beforeStep = { checkSetup(attempt) }, onProgress = { phase ->
                    synchronized(setupGuard) { checkSetup(attempt); state = phase; setupStage = phase }
                    onProgress(phase)
                })
            setupStage = "SHELL_IDENTITY_PROBE"; onProgress(setupStage)
            val probe = candidate.shell("id -u", CancellationToken(), 5000, 1024)
            check(probe.status == "COMPLETED" && String(probe.stdout).trim() == "2000") { "ADB_SHELL_IDENTITY_REQUIRED" }
            synchronized(setupGuard) {
                checkSetup(attempt)
                connectedPort = port; state = "READY"
            }
        } catch (e: Exception) {
            val diagnostic = "ADB_SETUP_FAILED[$setupStage,${localRoute.name}]: ${e.message ?: "ADB_CONNECT_FAILED"}"
            if (generation.get() == attempt) { transport?.close(); transport = null; state = "DISCONNECTED"; connectedPort = 0; lastError = diagnostic }
            throw java.io.IOException(diagnostic, e)
        } finally { operationLock.unlock() }
    }
    fun disconnect() {
        closeAuxiliary()
        val old = synchronized(setupGuard) {
            generation.incrementAndGet()
            transport.also { transport = null; connectedPort = 0; state = "DISCONNECTED" }
        }
        old?.close()
    }
    private fun closeAuxiliary() { channels.keys.forEach { runCatching { it.close() } }; channels.clear() }
    fun forget() {
        disconnect()
        synchronized(setupGuard) {
            SecureStore.clearSessionKey(slot)
            check(prefs.edit().clear().commit()) { "ADB_FORGET_FAILED" }
        }
        // The Android paired-device record is removed by the user in system settings.
    }
    /** Only called by an explicit, confirmed Settings action; no Agent tool exposes it. */
    fun restartTcpip(): AdbResult {
        check(operationLock.tryLock()) { "ADB_BUSY" }
        val attempt = generation.get()
        try {
            checkSetup(attempt)
            val current = transport
            check(state == "READY" && current != null) { "ADB_ACTIVE_CONNECTION_REQUIRED" }
            return current.restartTcpip(CancellationToken()) { checkSetup(attempt) }
        } finally { disconnect(); operationLock.unlock() }
    }
    fun execute(command: String, taskId: String, token: CancellationToken, timeoutMs: Int = 30000,
                maxBytes: Int = 262144, stdin: ByteArray? = null, beforeSend: () -> Unit = {}, binaryImage: Boolean = false): AdbResult {
        require(timeoutMs in 1..120000 && maxBytes in 1..(if (binaryImage && command == "screencap -p") 16777216 else 1048576) && (stdin?.size ?: 0) <= 128 * 1024 * 1024) { "ADB_LIMIT_INVALID" }
        val deadline = System.nanoTime() + timeoutMs.toLong() * 1_000_000
        while (!operationLock.tryLock(50, TimeUnit.MILLISECONDS)) {
            token.throwIfCancelled()
            if (System.nanoTime() >= deadline) return record(taskId, AdbResult(status = "NOT_SUBMITTED", error = "ADB_BUSY_TIMEOUT"))
        }
        try {
            token.throwIfCancelled()
            check(System.nanoTime() < deadline) { "ADB_BUSY_TIMEOUT" }
            val revision = permissions.authorityRevision(taskId)
            fun checkAuthority() {
                token.throwIfCancelled()
                beforeSend()
                check(!permissions.taskEnded(taskId) && permissions.enabled(AgentCapability.LOCAL_ADB, taskId)) { "AGENT_PERMISSION_REQUIRED: LOCAL_ADB" }
                check(permissions.authorityRevision(taskId) == revision) { "ADB_PERMISSION_CHANGED" }
            }
            checkAuthority()
            val current = transport
            if (state != "READY" || current == null) return record(taskId, AdbResult(status = "NOT_SUBMITTED", error = "ADB_NOT_CONNECTED"))
            activeTask = taskId; activeRevision = revision
            checkAuthority()
            val remaining = ((deadline - System.nanoTime()) / 1_000_000).coerceIn(1, 120000).toInt()
            val result = current.shell(command, token, remaining, maxBytes, stdin, ::checkAuthority)
            if (result.status !in setOf("COMPLETED", "FAILED") || token.isCancelled || permissions.authorityRevision(taskId) != revision) {
                disconnect(); lastError = result.error
            }
            return record(taskId, result)
        } catch (error: Exception) {
            return record(taskId, AdbResult(status = if (token.isCancelled) "CANCELLED" else "NOT_SUBMITTED",
                error = error.message ?: "ADB_AUTHORITY_CHECK_FAILED"))
        } finally { activeRevision = -1; activeTask = ""; operationLock.unlock() }
    }
    private fun record(task: String, result: AdbResult): AdbResult {
        synchronized(receipts) {
            // Audit metadata only; logs/command output may contain personal data.
            receipts.addLast(JSONObject().put("operation_id", result.operationId).put("task_id", task)
                .put("status", result.status).put("submitted", result.submitted).put("exit_code", result.exitCode ?: JSONObject.NULL)
                .put("error", result.error).put("at", System.currentTimeMillis()))
            while (receipts.size > 100) receipts.removeFirst()
        }
        return result
    }
    /** Independent raw/launcher connection, cancelled on disconnect or permission revision. */
    fun auxiliary(task: String, token: CancellationToken, beforeStep: () -> Unit = {}): AdbTransport {
        val epoch = generation.get(); val revision = permissions.authorityRevision(task)
        fun guard() {
            token.throwIfCancelled(); beforeStep()
            check(state == "READY" && generation.get() == epoch) { "ADB_SESSION_CHANGED" }
            check(permissions.authorityRevision(task) == revision && !permissions.taskEnded(task) &&
                permissions.enabled(AgentCapability.LOCAL_ADB, task)) { "ADB_PERMISSION_CHANGED" }
        }
        guard()
        val key = identity()
        val handle = try { NativeAdb.open(connectedPort, key, 10000, localRoute.handle) } finally { key.fill(0) }
        val candidate = AdbTransport(object : AdbWireIo {
            override fun read(data: ByteArray, offset: Int, length: Int, timeoutMs: Int) = NativeAdb.read(handle, data, offset, length, timeoutMs)
            override fun write(data: ByteArray, timeoutMs: Int) = NativeAdb.write(handle, data, 0, data.size, timeoutMs)
            override fun upgradeTls(timeoutMs: Int) = NativeAdb.upgradeTls(handle, timeoutMs)
            override fun close() = NativeAdb.close(handle)
        }, object : AdbAuthenticator {
            override fun sign(token: ByteArray) = NativeAdb.signToken(handle, token)
            override fun publicKey() = NativeAdb.publicKey(handle)
        })
        channels[candidate] = { permissions.authorityRevision(task)==revision && !permissions.taskEnded(task) && permissions.enabled(AgentCapability.LOCAL_ADB,task) }
        val cancel = token.onCancel { candidate.close() }
        try {
            candidate.connect(15000, AdbConnectionMode.valueOf(connectionMode), ::guard)
            guard(); return candidate
        } catch (e: Exception) { candidate.close(); channels.remove(candidate); throw e }
        finally { cancel.close() }
    }
    fun release(channel: AdbTransport) { channels.remove(channel); channel.close() }
    fun status(taskId: String = "") = JSONObject().put("backend", "EMBEDDED_LOCAL_ADB")
        .put("native_available", NativeAdb.available()).put("wireless_supported", Build.VERSION.SDK_INT >= 30)
        .put("tcp_supported", NativeAdb.available()).put("connection_mode", connectionMode)
        .put("local_route", localRoute.name).put("vpn_detected", localRoute.vpnDetected).put("setup_stage", setupStage)
        .put("encrypted_transport", state == "READY" && connectionMode == "TLS")
        .put("connection_state", state).put("available", state == "READY")
        .put("paired", !prefs.getString("guid", "").isNullOrBlank())
        .put("key_storage", if (SecureStore.encrypted(prefs.getString("identity", ""))) "ANDROID_KEYSTORE_ENCRYPTED" else "SESSION_ONLY")
        .put("host", "127.0.0.1").put("connect_port", connectedPort).put("identity", if (state == "READY") "uid=2000(shell)" else "UNVERIFIED")
        .put("local_adb_authorized", permissions.enabled(AgentCapability.LOCAL_ADB, taskId))
        .put("raw_shell_authorized", permissions.enabled(AgentCapability.RAW_ADB_SHELL, taskId))
        .put("active_task", activeTask).put("last_error", lastError)
        .put("operations", synchronized(receipts) { JSONArray(receipts.filter { it.optString("task_id") == taskId }) })
}
