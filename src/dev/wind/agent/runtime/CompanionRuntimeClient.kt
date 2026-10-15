package dev.wind.agent.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import dev.wind.agent.CancellationToken
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * A descriptor the companion transferred, plus the size it reported for the
 * file at the moment it was opened. The size is informational: it is a snapshot,
 * and the reader still proves the bytes it actually received.
 */
data class CompanionReadHandle(
    val descriptor: ParcelFileDescriptor,
    val reportedSize: Long
)

/**
 * Host-side typed client for the Vega Runtime Companion.
 *
 * Implements Protocol v2 over Binder, handling binding lifecycle, identity
 * pinning, and fail-closed disconnection. In offline/JVM tests, an in-process
 * dispatcher can be supplied so tests do not require an Android Service environment.
 */
open class CompanionRuntimeClient(
    protected val context: Context? = null,
    private val inProcessDispatcher: ((Int, String) -> String)? = null,
    /**
     * JVM stand-in for the descriptor the companion transfers for
     * TRANSACTION_OPEN_MIRROR_READ. There is no Binder outside Android, but the
     * production call sequence - validate, stream, verify - must still be the
     * one that runs, so the seam is the opener and nothing above it.
     */
    private val inProcessStreamOpener:
        ((RuntimeBinding, String, Long, String?) -> java.io.InputStream)? = null,
    /**
     * JVM stand-in for a Binder transaction that carries a file descriptor.
     * There is no Parcel pipe outside Android, but the production call sequence
     * (validate, stream, verify, commit) must still be the one that runs.
     */
    private val inProcessPipeline: ((Int, String, () -> java.io.InputStream, CancellationToken?) -> String)? = null,
    /** Optional P6.4 endpoint. Null preserves the historical external client. */
    val endpointOverride: RuntimeEndpoint? = null,
    val expectedProviderId: RuntimeProviderId = RuntimeProviderId.VEGA_COMPANION
) {
    companion object {
        const val ACTION_BIND = "dev.wind.agent.runtime.BIND"
        const val COMPONENT_PACKAGE = "dev.wind.agent.runtime"
        const val COMPONENT_CLASS = "dev.wind.agent.runtime.VegaRuntimeService"
        const val DESCRIPTOR = "dev.wind.agent.runtime.IVegaRuntime"

        const val TRANSACTION_PROBE = IBinder.FIRST_CALL_TRANSACTION
        const val TRANSACTION_OPEN_WORKSPACE = IBinder.FIRST_CALL_TRANSACTION + 1
        const val TRANSACTION_RUN_COMMAND = IBinder.FIRST_CALL_TRANSACTION + 2
        const val TRANSACTION_CANCEL = IBinder.FIRST_CALL_TRANSACTION + 3
        const val TRANSACTION_QUERY = IBinder.FIRST_CALL_TRANSACTION + 4
        const val TRANSACTION_RECONCILE = IBinder.FIRST_CALL_TRANSACTION + 5
        const val TRANSACTION_STOP = IBinder.FIRST_CALL_TRANSACTION + 6
        const val TRANSACTION_START_COMMAND = IBinder.FIRST_CALL_TRANSACTION + 7
        const val TRANSACTION_AWAIT_COMMAND = IBinder.FIRST_CALL_TRANSACTION + 8
        const val TRANSACTION_MIRROR_CALL = IBinder.FIRST_CALL_TRANSACTION + 9
        const val TRANSACTION_POLL_COMMAND = IBinder.FIRST_CALL_TRANSACTION + 10
        /**
         * Protocol 2 additions. The binding's protocolVersion stays 2: a durable
         * binding is never silently upgraded in place, so the new plane is an
         * additive capability announced by the probe instead of a version bump.
         */
        const val TRANSACTION_OPEN_MIRROR_READ = IBinder.FIRST_CALL_TRANSACTION + 11
        const val TRANSACTION_SYNC_BEGIN = IBinder.FIRST_CALL_TRANSACTION + 12
        const val TRANSACTION_SYNC_PLAN_PAGE = IBinder.FIRST_CALL_TRANSACTION + 13
        const val TRANSACTION_SYNC_FILE = IBinder.FIRST_CALL_TRANSACTION + 14
        const val TRANSACTION_SYNC_COMMIT = IBinder.FIRST_CALL_TRANSACTION + 15
        const val TRANSACTION_SYNC_ABORT = IBinder.FIRST_CALL_TRANSACTION + 16
        const val TRANSACTION_OPEN_EXECUTION_LOG = IBinder.FIRST_CALL_TRANSACTION + 17
        const val TRANSACTION_OPEN_EXECUTION_OUTPUT = IBinder.FIRST_CALL_TRANSACTION + 18

        /** Mirror data-plane revision implemented by this client. */
        const val MIRROR_STREAM_PROTOCOL_VERSION = 1
    }

    private val binderRef = AtomicReference<IBinder?>(null)
    private val connectionRef = AtomicReference<ServiceConnection?>(null)

    protected open fun getBinder(): IBinder? {
        val existing = binderRef.get()
        if (existing != null && existing.isBinderAlive) return existing
        if (context == null) return null

        val latch = CountDownLatch(1)
        val conn = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binderRef.set(service)
                latch.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName?) {
                binderRef.set(null)
            }
        }
        val intent = Intent(ACTION_BIND).apply {
            component = ComponentName(COMPONENT_PACKAGE, COMPONENT_CLASS)
        }
        val bound = runCatching { context?.bindService(intent, conn, Context.BIND_AUTO_CREATE) ?: false }.getOrElse { false }
        if (bound) {
            connectionRef.set(conn)
            latch.await(5, TimeUnit.SECONDS)
        }
        return binderRef.get()
    }

    private fun call(code: Int, payload: JSONObject): JSONObject {
        endpointOverride?.let { return it.transact(code, payload) }
        return if (InterruptibleRuntimeRead.isRead(code, payload)) InterruptibleRuntimeRead.call { callDirect(code, payload) }
        else callDirect(code, payload)
    }

    private fun callDirect(code: Int, payload: JSONObject): JSONObject {
        val dispatcher = inProcessDispatcher
        if (dispatcher != null) {
            val resp = dispatcher(code, payload.toString())
            return JSONObject(resp)
        }
        val binder = getBinder() ?: throw IllegalStateException("RUNTIME_COMPANION_UNAVAILABLE")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(payload.toString())
            if (code == TRANSACTION_START_COMMAND) data.writeStrongBinder(RuntimeClientLifetime.binder)
            binder.transact(code, data, reply, 0)
            reply.readException()
            val resp = reply.readString() ?: "{}"
            return JSONObject(resp)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    /**
     * Sends one source stream to the companion through a [ParcelFileDescriptor]
     * pipe and returns the JSON reply.
     *
     * The bytes never become a Parcel string: a 64 KiB chunk loop writes into
     * the pipe's write end while the companion reads the read end, so an
     * arbitrarily large file crosses the Binder boundary with a fixed memory
     * footprint. A source that fails mid-transfer is signalled with
     * [ParcelFileDescriptor.closeWithError] so the peer sees a truncated stream
     * instead of a silently short payload.
     *
     * [inProcessPipeline] substitutes for the descriptor in JVM tests, where no
     * Binder exists but the production call sequence must still be exercised.
     */
    protected open fun transactSource(
        code: Int,
        payload: JSONObject,
        source: () -> InputStream,
        token: CancellationToken?
    ): JSONObject {
        endpointOverride?.let { return it.transactSource(code, payload, source, token) }
        val pipeline = inProcessPipeline
        if (pipeline != null) {
            val result = pipeline(code, payload.toString(), source, token)
            return JSONObject(result)
        }
        val binder = getBinder() ?: throw IllegalStateException("RUNTIME_COMPANION_UNAVAILABLE")
        val pipe = ParcelFileDescriptor.createReliablePipe()
        val reader = pipe[0]
        val writer = pipe[1]
        val failure = AtomicReference<Throwable?>(null)
        val activeSource = AtomicReference<InputStream?>(null)
        val pump = Thread {
            runCatching {
                ParcelFileDescriptor.AutoCloseOutputStream(writer).use { output ->
                    val buffer = ByteArray(64 * 1024)
                    source().use { input ->
                        activeSource.set(input)
                        while (true) {
                            token?.throwIfCancelled()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                        }
                    }
                    output.flush()
                }
            }.onFailure { error ->
                failure.set(error)
                runCatching {
                    writer.closeWithError(if (token?.isCancelled == true) "MIRROR_CANCELLED" else "MIRROR_SOURCE_FAILED")
                }
            }
        }
        pump.isDaemon = true
        pump.start()
        val transferCancellation = token?.onCancel {
            runCatching { writer.closeWithError("MIRROR_CANCELLED") }
            runCatching { reader.close() }
            pump.interrupt()
            runCatching { activeSource.getAndSet(null)?.close() }
        }
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(payload.toString())
            data.writeInt(1)
            reader.writeToParcel(data, 0)
            binder.transact(code, data, reply, 0)
            reply.readException()
            val resp = reply.readString() ?: "{}"
            failure.get()?.let { throw IllegalStateException("MIRROR_SOURCE_FAILED", it) }
            return JSONObject(resp)
        } finally {
            transferCancellation?.close()
            runCatching { reader.close() }
            data.recycle()
            reply.recycle()
            pump.join(2000L)
        }
    }

    /**
     * Opens a bounded, integrity-checked read stream for one mirror file.
     *
     * Production receives a real descriptor from the companion, wrapped so the
     * caller closes it by closing the stream. JVM tests supply
     * [inProcessStreamOpener] (the dispatcher and the stream opener share one
     * companion instance, so no descriptor can exist) and the shared call
     * sequence is unchanged.
     */
    open fun openFileStream(
        binding: RuntimeBinding,
        path: String,
        operationId: String,
        expectedSize: Long,
        maxBytes: Long,
        expectedSha256: String? = null
    ): InputStream {
        endpointOverride?.let {
            return it.openFileStream(binding, path, operationId, expectedSize, maxBytes, expectedSha256)
        }
        val opener = inProcessStreamOpener
        if (opener != null) {
            // The opener is the desktop stand-in for the descriptor, so the
            // integrity rules below stay in one place and are exercised on both
            // paths instead of only on Android.
            return opener(binding, path, expectedSize, expectedSha256)
        }
        val handle = openMirrorRead(binding, path, expectedSize, maxBytes)
        return ParcelFileDescriptor.AutoCloseInputStream(handle.descriptor)
    }

    /**
     * Asks the companion to open one mirror file and returns the descriptor it
     * transferred, together with the size the companion claims to have opened.
     * The companion still owns the file; only the read capability crosses the
     * boundary, and it is released by the caller as soon as it is done.
     *
     * There is deliberately no integrity check on the wire: the descriptor is a
     * live view of a file that may still be written to, so size and digest are
     * only meaningful once the bytes have actually been read, which the caller
     * does while consuming the stream.
     */
    protected open fun openMirrorRead(
        binding: RuntimeBinding,
        path: String,
        expectedSize: Long,
        maxBytes: Long
    ): CompanionReadHandle {
        val binder = getBinder() ?: throw IllegalStateException("RUNTIME_COMPANION_UNAVAILABLE")
        val request = JSONObject()
            .put("projectId", binding.projectId)
            .put("rootGeneration", binding.rootGeneration)
            .put("workspaceId", binding.workspaceId)
            .put("runtimeInstanceId", binding.runtimeInstanceId)
            .put("path", path)
            .put("expectedSize", expectedSize)
            .put("maxBytes", maxBytes)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(request.toString())
            binder.transact(TRANSACTION_OPEN_MIRROR_READ, data, reply, 0)
            reply.readException()
            val metadata = JSONObject(reply.readString() ?: "{}")
            if (!metadata.optBoolean("ok")) {
                throw IllegalStateException(metadata.optString("errorCode", "MIRROR_READ_FAILED"))
            }
            if (reply.readInt() != 1) throw IllegalStateException("MIRROR_READ_DESCRIPTOR_MISSING")
            val descriptor = reply.readParcelableDescriptor()
                ?: throw IllegalStateException("MIRROR_READ_DESCRIPTOR_INVALID")
            return CompanionReadHandle(descriptor, metadata.optLong("size", -1L))
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun Parcel.readParcelableDescriptor(): ParcelFileDescriptor? {
        @Suppress("DEPRECATION")
        return ParcelFileDescriptor.CREATOR.createFromParcel(this)
    }

    /**
     * One staged P3 synchronisation, driven from the host.
     *
     * The shape of the sequence is the contract: begin performs the first
     * compare-and-set against the digest the plan was built from, the payloads
     * stream through descriptors into a staging tree, and commit re-checks the
     * digest before installing anything. An abort is always safe to issue,
     * including when a previous step already failed.
     */
    fun syncBegin(
        binding: RuntimeBinding,
        syncId: String,
        expectedRemoteDigest: String,
        ignorePolicy: JSONObject,
        plannedFiles: Long,
        plannedBytes: Long
    ): JSONObject = call(TRANSACTION_SYNC_BEGIN, bindingEnvelope(binding)
        .put("syncId", syncId)
        .put("expectedRemoteDigest", expectedRemoteDigest)
        .put("ignorePolicy", ignorePolicy)
        .put("plan", JSONObject().put("files", plannedFiles).put("bytes", plannedBytes)))
        .requireMirrorOk()

    fun syncPlanPage(
        binding: RuntimeBinding,
        syncId: String,
        page: ByteArray,
        token: CancellationToken?
    ): JSONObject = transactSource(
        TRANSACTION_SYNC_PLAN_PAGE,
        bindingEnvelope(binding).put("syncId", syncId),
        { java.io.ByteArrayInputStream(page) },
        token
    ).requireMirrorOk()

    fun syncFile(
        binding: RuntimeBinding,
        syncId: String,
        path: String,
        source: () -> InputStream,
        token: CancellationToken?
    ): JSONObject = transactSource(
        TRANSACTION_SYNC_FILE,
        bindingEnvelope(binding).put("syncId", syncId).put("path", path),
        source,
        token
    ).requireMirrorOk()

    fun syncCommit(binding: RuntimeBinding, syncId: String): JSONObject =
        call(TRANSACTION_SYNC_COMMIT, bindingEnvelope(binding).put("syncId", syncId))
            .requireMirrorOk()

    /** Idempotent: a missing or already-ended sync is a success, not an error. */
    fun syncAbort(binding: RuntimeBinding, syncId: String): JSONObject =
        call(TRANSACTION_SYNC_ABORT, bindingEnvelope(binding).put("syncId", syncId))
            .requireMirrorOk()

    private fun bindingEnvelope(binding: RuntimeBinding): JSONObject = JSONObject()
        .put("projectId", binding.projectId)
        .put("rootGeneration", binding.rootGeneration)
        .put("workspaceId", binding.workspaceId)
        .put("runtimeInstanceId", binding.runtimeInstanceId)

    /**
     * A transaction that failed on the companion side reports it as an error
     * code in the reply, exactly like the other operations. The sync
     * transactions must surface it as a failure, because the difference between
     * "the payload was staged" and "the companion rejected the payload" decides
     * whether the host may continue to commit.
     */
    private fun JSONObject.requireMirrorOk(): JSONObject {
        if (!optBoolean("ok")) {
            throw IllegalStateException(optString("errorCode", "RUNTIME_SYNC_FAILED"))
        }
        return this
    }

    fun probe(): RuntimeProbeResult {
        return runCatching {
            val resp = call(TRANSACTION_PROBE, JSONObject())
            if (!resp.optBoolean("ok")) {
                return RuntimeProbeResult(
                    expectedProviderId,
                    RuntimeProbeState.UNAVAILABLE,
                    RuntimeCapabilities(false, false, false, 0),
                    diagnostic = resp.optString("errorCode", "PROBE_FAILED")
                )
            }
            val capsJson = resp.getJSONObject("capabilities")
            require(resp.optString("providerId", expectedProviderId.name) == expectedProviderId.name) {
                "RUNTIME_PROVIDER_MISMATCH"
            }
            if (expectedProviderId == RuntimeProviderId.VEGA_LINUX) {
                require(resp.optString("providerId") == expectedProviderId.name) { "RUNTIME_PROVIDER_MISMATCH" }
            }
            val reportedTools = capsJson.optJSONObject("toolchains") ?: JSONObject()
            val toolchains = linkedMapOf<String, String>()
            val toolKeys = reportedTools.keys()
            while (toolKeys.hasNext()) {
                val key = toolKeys.next()
                toolchains[key] = reportedTools.getString(key)
            }
            fun reportedSet(name: String): Set<String> {
                val entries = capsJson.optJSONArray(name) ?: JSONArray()
                return (0 until entries.length()).map { entries.getString(it) }.toSet()
            }
            val caps = RuntimeCapabilities(
                commandExecution = capsJson.optBoolean("commandExecution"),
                persistentWorkspace = capsJson.optBoolean("persistentWorkspace"),
                processGroupCancellation = capsJson.optBoolean("processGroupCancellation"),
                mirrorProtocolVersion = capsJson.optInt("mirrorProtocolVersion", 2),
                mirrorStreamProtocolVersion = capsJson.optInt("mirrorStreamProtocolVersion", 0),
                helperPythonVersion = capsJson.optString("helperPythonVersion", ""),
                toolchains = toolchains,
                languageServers = reportedSet("languageServers"),
                supportedAbis = reportedSet("supportedAbis"),
                networkIsolation = capsJson.optString("networkIsolation", "POLICY_ONLY"),
                runtimeVersion = capsJson.optString("runtimeVersion", "")
            )
            RuntimeProbeResult(
                expectedProviderId,
                if (resp.optBoolean("ready", true)) RuntimeProbeState.READY else RuntimeProbeState.UNAVAILABLE,
                caps,
                diagnostic = resp.optString("diagnostic", ""),
                runtimeInstanceId = resp.getString("runtimeInstanceId")
            )
        }.getOrElse {
            RuntimeProbeResult(
                expectedProviderId,
                RuntimeProbeState.NOT_INSTALLED,
                RuntimeCapabilities(false, false, false, 0),
                diagnostic = "PROBE_ERROR:" + (it.message ?: it.javaClass.simpleName)
            )
        }
    }

    fun openWorkspace(projectId: String, rootGeneration: Long, workspaceId: String): RuntimeBinding {
        val resp = call(TRANSACTION_OPEN_WORKSPACE, JSONObject()
            .put("projectId", projectId)
            .put("rootGeneration", rootGeneration)
            .put("workspaceId", workspaceId)
        )
        check(resp.optBoolean("ok")) { resp.optString("errorCode", "OPEN_WORKSPACE_FAILED") }
        val bJson = resp.getJSONObject("binding")
        return RuntimeBinding.fromJson(bJson).also {
            require(it.providerId == expectedProviderId) { "RUNTIME_PROVIDER_MISMATCH" }
        }
    }

    fun startCommand(
        binding: RuntimeBinding,
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30_000L,
        workDir: String = "",
        stdin: String? = null
    ): String {
        val envJson = JSONObject().apply { env.forEach { (k, v) -> put(k, v) } }
        val resp = call(TRANSACTION_START_COMMAND, JSONObject()
            .put("projectId", binding.projectId)
            .put("rootGeneration", binding.rootGeneration)
            .put("workspaceId", binding.workspaceId)
            .put("runtimeInstanceId", binding.runtimeInstanceId)
            .put("argv", JSONArray().apply { argv.forEach { put(it) } })
            .put("env", envJson)
            .put("timeoutMs", timeoutMs)
            .put("workDir", workDir)
            .put("stdin", stdin ?: JSONObject.NULL)
        )
        check(resp.optBoolean("ok")) { resp.optString("errorCode", "START_COMMAND_FAILED") }
        return resp.getString("executionId")
    }

    fun awaitCommand(
        binding: RuntimeBinding,
        executionId: String,
        waitMs: Long = 30_000L
    ): RuntimeCommandResult {
        val resp = call(TRANSACTION_AWAIT_COMMAND, JSONObject()
            .put("projectId", binding.projectId)
            .put("rootGeneration", binding.rootGeneration)
            .put("workspaceId", binding.workspaceId)
            .put("runtimeInstanceId", binding.runtimeInstanceId)
            .put("executionId", executionId)
            .put("waitMs", waitMs)
        )
        if (!resp.optBoolean("ok") && resp.has("errorCode")) {
            throw IllegalStateException(resp.getString("errorCode"))
        }
        val exitCode = if (resp.has("exitCode") && resp.getInt("exitCode") != -1) resp.getInt("exitCode") else null
        return RuntimeCommandResult(
            exitCode = exitCode,
            stdout = resp.optString("stdout", ""),
            stderr = resp.optString("stderr", ""),
            elapsedMs = resp.optLong("durationMs", 0L),
            timedOut = resp.optBoolean("timedOut"),
            cancelled = resp.optBoolean("cancelled"),
            cleanupConfirmed = resp.optBoolean("cleanupConfirmed"),
            executionHandle = executionId,
            error = resp.optString("failure").takeIf { it.isNotBlank() },
            outputTruncated = resp.optBoolean("outputTruncated")
        )
    }

    fun openExecutionLog(binding: RuntimeBinding, executionId: String, stream: String): InputStream {
        require(binding.providerId == expectedProviderId) { "RUNTIME_PROVIDER_MISMATCH" }
        return (endpointOverride ?: throw IllegalStateException("RUNTIME_LOG_UNAVAILABLE"))
            .openExecutionLog(binding, executionId, stream)
    }

    fun pollCommand(
        binding: RuntimeBinding,
        executionId: String
    ): JSONObject {
        val response = call(TRANSACTION_POLL_COMMAND, JSONObject()
            .put("projectId", binding.projectId)
            .put("rootGeneration", binding.rootGeneration)
            .put("workspaceId", binding.workspaceId)
            .put("runtimeInstanceId", binding.runtimeInstanceId)
            .put("executionId", executionId)
        )
        check(!response.has("errorCode")) { response.optString("errorCode") }
        return response
    }

    /** The descriptor tails a bounded file; temporary EOF waits for terminal
     * poll. Service death and unknown handles fail instead of returning success. */
    fun openExecutionOutput(binding: RuntimeBinding, executionId: String, stream: String): InputStream {
        require(binding.providerId == expectedProviderId) { "RUNTIME_PROVIDER_MISMATCH" }
        val endpoint = endpointOverride ?: throw IllegalStateException("RUNTIME_LOG_UNAVAILABLE")
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(125)
        var input: InputStream? = null
        val openingDeadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(2)
        while (input == null) {
            try { input = endpoint.openExecutionOutput(binding, executionId, stream) }
            catch (failure: IllegalStateException) {
                if (failure.message != "RUNTIME_LOG_UNAVAILABLE" || System.nanoTime() >= openingDeadline) throw failure
                pollCommand(binding, executionId)
                Thread.sleep(20)
            }
        }
        val source = input
        return object : InputStream() {
            @Volatile private var closed = false
            private var terminal = false
            override fun read(): Int {
                val single = ByteArray(1)
                return if (read(single, 0, 1) < 0) -1 else single[0].toInt() and 255
            }
            override fun read(bytes: ByteArray, offset: Int, length: Int): Int {
                require(offset >= 0 && length >= 0 && offset <= bytes.size - length)
                if (length == 0) return 0
                while (!closed) {
                    val count = source.read(bytes, offset, length)
                    if (count >= 0 || terminal) return count
                    if (System.nanoTime() >= deadline) { close(); throw java.io.IOException("RUNTIME_OUTPUT_DEADLINE") }
                    try {
                        terminal = pollCommand(binding, executionId).optString("state") == "TERMINATED"
                        if (!terminal) Thread.sleep(100)
                    } catch (interrupted: InterruptedException) {
                        close(); Thread.currentThread().interrupt()
                        throw java.io.InterruptedIOException("RUNTIME_OUTPUT_INTERRUPTED")
                    } catch (failure: Exception) {
                        close(); throw java.io.IOException("RUNTIME_OUTPUT_AUTHORITY_LOST", failure)
                    }
                }
                throw java.io.IOException("RUNTIME_OUTPUT_CLOSED")
            }
            override fun close() { closed = true; source.close() }
        }
    }

    fun cancel(binding: RuntimeBinding, executionId: String, waitMs: Long = 2500L): Boolean {
        val resp = call(TRANSACTION_CANCEL, JSONObject()
            .put("projectId", binding.projectId)
            .put("rootGeneration", binding.rootGeneration)
            .put("workspaceId", binding.workspaceId)
            .put("runtimeInstanceId", binding.runtimeInstanceId)
            .put("executionId", executionId)
            .put("waitMs", waitMs)
        )
        return resp.optBoolean("ok")
    }

    fun query(binding: RuntimeBinding, executionId: String? = null): JSONObject {
        val req = JSONObject()
            .put("projectId", binding.projectId)
            .put("rootGeneration", binding.rootGeneration)
            .put("workspaceId", binding.workspaceId)
            .put("runtimeInstanceId", binding.runtimeInstanceId)
        if (executionId != null) req.put("executionId", executionId)
        return call(TRANSACTION_QUERY, req)
    }

    fun reconcile(binding: RuntimeBinding): Boolean {
        val resp = call(TRANSACTION_RECONCILE, JSONObject()
            .put("projectId", binding.projectId)
            .put("rootGeneration", binding.rootGeneration)
            .put("workspaceId", binding.workspaceId)
            .put("runtimeInstanceId", binding.runtimeInstanceId)
        )
        return resp.optBoolean("ok")
    }

    /**
     * One mirror operation.
     *
     * [ignorePolicy] is required for the operations that enumerate the mirror and
     * must be absent for the ones that name a single path. That split is the
     * point: the host is the only authority on what the mirror contains, so an
     * enumeration without it fails with MIRROR_IGNORE_POLICY_MISSING rather than
     * silently using a second, private list on the companion.
     */
    fun mirrorCall(
        binding: RuntimeBinding,
        operation: String,
        args: JSONObject = JSONObject(),
        ignorePolicy: JSONObject? = null
    ): JSONObject {
        val request = JSONObject()
            .put("projectId", binding.projectId)
            .put("rootGeneration", binding.rootGeneration)
            .put("workspaceId", binding.workspaceId)
            .put("runtimeInstanceId", binding.runtimeInstanceId)
            .put("operation", operation)
            .put("args", args)
        if (ignorePolicy != null) request.put("ignorePolicy", ignorePolicy)
        val resp = call(TRANSACTION_MIRROR_CALL, request)
        if (!resp.optBoolean("ok") && resp.has("errorCode")) {
            throw IllegalStateException(resp.getString("errorCode"))
        }
        return resp
    }

}

/** Host-side client for the in-APK `:runtime` process. */
class EmbeddedRuntimeClient(
    context: Context,
    inProcessDispatcher: ((Int, String) -> String)? = null,
    inProcessStreamOpener:
        ((RuntimeBinding, String, Long, String?) -> InputStream)? = null
) : CompanionRuntimeClient(
    context = context.applicationContext,
    inProcessDispatcher = inProcessDispatcher,
    inProcessStreamOpener = inProcessStreamOpener,
    endpointOverride = EmbeddedRuntimeEndpoint(
        context = context.applicationContext,
        inProcessDispatcher = inProcessDispatcher,
        inProcessStreamOpener = inProcessStreamOpener
    )
)
