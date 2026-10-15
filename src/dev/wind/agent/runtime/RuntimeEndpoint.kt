package dev.wind.agent.runtime

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/**
 * Transport-neutral endpoint for the frozen P6 Binder protocol.
 *
 * The protocol is deliberately independent from where the service lives.  The
 * same request can be sent to the historical external Companion APK or to the
 * host application's `:runtime` process.  Higher layers therefore depend on
 * an endpoint, not on a package name or an executable-shaped transport.
 */
interface RuntimeEndpoint : AutoCloseable {
    val endpointId: String

    fun transact(code: Int, payload: JSONObject): JSONObject

    /** Typed protocol entry points keep callers independent from transaction IDs. */
    fun probe(): JSONObject = transact(CompanionRuntimeClient.TRANSACTION_PROBE, JSONObject())

    fun openWorkspace(projectId: String, rootGeneration: Long, workspaceId: String): JSONObject =
        transact(
            CompanionRuntimeClient.TRANSACTION_OPEN_WORKSPACE,
            JSONObject()
                .put("projectId", projectId)
                .put("rootGeneration", rootGeneration)
                .put("workspaceId", workspaceId)
        )

    fun startCommand(
        binding: RuntimeBinding,
        argv: List<String>,
        env: Map<String, String> = emptyMap(),
        timeoutMs: Long = 30_000L,
        workDir: String = ""
    ): JSONObject = transact(
        CompanionRuntimeClient.TRANSACTION_START_COMMAND,
        JSONObject()
            .put("projectId", binding.projectId)
            .put("rootGeneration", binding.rootGeneration)
            .put("workspaceId", binding.workspaceId)
            .put("runtimeInstanceId", binding.runtimeInstanceId)
            .put("argv", JSONArray().apply { argv.forEach { put(it) } })
            .put("env", JSONObject().apply { env.forEach { (key, value) -> put(key, value) } })
            .put("timeoutMs", timeoutMs)
            .put("workDir", workDir)
    )

    fun cancel(binding: RuntimeBinding, executionId: String, waitMs: Long = 2_500L): JSONObject =
        transact(
            CompanionRuntimeClient.TRANSACTION_CANCEL,
            JSONObject()
                .put("projectId", binding.projectId)
                .put("rootGeneration", binding.rootGeneration)
                .put("workspaceId", binding.workspaceId)
                .put("runtimeInstanceId", binding.runtimeInstanceId)
                .put("executionId", executionId)
                .put("waitMs", waitMs)
        )

    fun mirror(binding: RuntimeBinding, operation: String, args: JSONObject = JSONObject()): JSONObject =
        transact(
            CompanionRuntimeClient.TRANSACTION_MIRROR_CALL,
            JSONObject()
                .put("projectId", binding.projectId)
                .put("rootGeneration", binding.rootGeneration)
                .put("workspaceId", binding.workspaceId)
                .put("runtimeInstanceId", binding.runtimeInstanceId)
                .put("operation", operation)
                .put("args", args)
        )

    /**
     * Streams a source payload through the same descriptor transaction used by
     * the historical Companion client.  Endpoint callers that only need the
     * request/response contract may use the default implementation; the Binder
     * implementation below preserves the real pipe for sync transactions.
     */
    fun transactSource(
        code: Int,
        payload: JSONObject,
        source: () -> InputStream,
        token: dev.wind.agent.CancellationToken?
    ): JSONObject = transact(code, payload)

    fun openFileStream(
        binding: RuntimeBinding,
        path: String,
        operationId: String,
        expectedSize: Long,
        maxBytes: Long,
        expectedSha256: String? = null
    ): InputStream

    override fun close() = Unit
    fun openExecutionLog(binding: RuntimeBinding, executionId: String, stream: String): InputStream =
        throw IllegalStateException("RUNTIME_LOG_UNAVAILABLE")
    /** Growing read-only PFD output. EOF is final only after poll is terminal. */
    fun openExecutionOutput(binding: RuntimeBinding, executionId: String, stream: String): InputStream =
        throw IllegalStateException("RUNTIME_LOG_UNAVAILABLE")
}

/** Common Binder implementation used by both endpoint locations. */
open class BinderRuntimeEndpoint(
    private val context: Context? = null,
    private val component: () -> ComponentName,
    override val endpointId: String,
    private val action: String = CompanionRuntimeClient.ACTION_BIND,
    private val descriptor: String = CompanionRuntimeClient.DESCRIPTOR,
    private val inProcessDispatcher: ((Int, String) -> String)? = null,
    private val inProcessStreamOpener:
        ((RuntimeBinding, String, Long, String?) -> InputStream)? = null
) : RuntimeEndpoint {
    private val binderRef = AtomicReference<IBinder?>(null)
    private val connectionRef = AtomicReference<ServiceConnection?>(null)

    @Synchronized private fun binder(): IBinder? {
        val existing = binderRef.get()
        if (existing != null && existing.isBinderAlive) return existing
        val owner = context ?: return null
        // Release the stale registration before reconnecting after service death.
        connectionRef.getAndSet(null)?.let { runCatching { owner.unbindService(it) } }
        binderRef.set(null)
        val latch = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                binderRef.set(service)
                latch.countDown()
            }

            override fun onServiceDisconnected(name: ComponentName?) {
                binderRef.set(null)
            }
        }
        val intent = Intent(action).apply { component = this@BinderRuntimeEndpoint.component() }
        val bound = runCatching {
            owner.bindService(intent, connection, Context.BIND_AUTO_CREATE)
        }.getOrElse { false }
        if (bound) {
            connectionRef.set(connection)
            latch.await(5, TimeUnit.SECONDS)
        }
        return binderRef.get()
    }

    override fun transact(code: Int, payload: JSONObject): JSONObject {
        return if (InterruptibleRuntimeRead.isRead(code, payload)) InterruptibleRuntimeRead.call { transactDirect(code, payload) }
        else transactDirect(code, payload)
    }

    private fun transactDirect(code: Int, payload: JSONObject): JSONObject {
        val dispatcher = inProcessDispatcher
        if (dispatcher != null) return JSONObject(dispatcher(code, payload.toString()))

        val remote = binder() ?: throw IllegalStateException("RUNTIME_ENDPOINT_UNAVAILABLE:$endpointId")
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.writeString(payload.toString())
            if (code == CompanionRuntimeClient.TRANSACTION_START_COMMAND) data.writeStrongBinder(RuntimeClientLifetime.binder)
            check(remote.transact(code, data, reply, 0)) {
                "RUNTIME_ENDPOINT_TRANSACTION_REJECTED:$endpointId"
            }
            reply.readException()
            JSONObject(reply.readString() ?: "{}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun transactSource(
        code: Int,
        payload: JSONObject,
        source: () -> InputStream,
        token: dev.wind.agent.CancellationToken?
    ): JSONObject {
        val dispatcher = inProcessDispatcher
        if (dispatcher != null) return JSONObject(dispatcher(code, payload.toString()))

        val remote = binder() ?: throw IllegalStateException("RUNTIME_ENDPOINT_UNAVAILABLE:$endpointId")
        val pipe = android.os.ParcelFileDescriptor.createReliablePipe()
        val reader = pipe[0]
        val writer = pipe[1]
        val failure = AtomicReference<Throwable?>(null)
        val activeSource = AtomicReference<InputStream?>(null)
        val pump = Thread {
            runCatching {
                android.os.ParcelFileDescriptor.AutoCloseOutputStream(writer).use { output ->
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
                    writer.closeWithError(
                        if (token?.isCancelled == true) "MIRROR_CANCELLED" else "MIRROR_SOURCE_FAILED"
                    )
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
            data.writeInterfaceToken(descriptor)
            data.writeString(payload.toString())
            data.writeInt(1)
            reader.writeToParcel(data, 0)
            check(remote.transact(code, data, reply, 0)) {
                "RUNTIME_ENDPOINT_TRANSACTION_REJECTED:$endpointId"
            }
            reply.readException()
            failure.get()?.let { throw IllegalStateException("MIRROR_SOURCE_FAILED", it) }
            return JSONObject(reply.readString() ?: "{}")
        } finally {
            transferCancellation?.close()
            runCatching { reader.close() }
            data.recycle()
            reply.recycle()
            pump.join(2000L)
        }
    }

    override fun openFileStream(
        binding: RuntimeBinding,
        path: String,
        operationId: String,
        expectedSize: Long,
        maxBytes: Long,
        expectedSha256: String?
    ): InputStream {
        val opener = inProcessStreamOpener
        if (opener != null) return opener(binding, path, expectedSize, expectedSha256)

        // Production reads use the common PFD data plane.  The descriptor is
        // transferred independently from the JSON metadata, so file contents
        // never become a Binder string or a Base64 payload.
        val remote = binder() ?: throw IllegalStateException("RUNTIME_ENDPOINT_UNAVAILABLE:$endpointId")
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
            data.writeInterfaceToken(descriptor)
            data.writeString(request.toString())
            check(remote.transact(CompanionRuntimeClient.TRANSACTION_OPEN_MIRROR_READ, data, reply, 0)) {
                "RUNTIME_ENDPOINT_TRANSACTION_REJECTED:$endpointId"
            }
            reply.readException()
            val metadata = JSONObject(reply.readString() ?: "{}")
            if (!metadata.optBoolean("ok")) {
                throw IllegalStateException(metadata.optString("errorCode", "MIRROR_READ_FAILED"))
            }
            if (reply.readInt() != 1) throw IllegalStateException("MIRROR_READ_DESCRIPTOR_MISSING")
            val descriptor = ParcelFileDescriptor.CREATOR.createFromParcel(reply)
                ?: throw IllegalStateException("MIRROR_READ_DESCRIPTOR_INVALID")
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun openExecutionLog(binding: RuntimeBinding, executionId: String, stream: String): InputStream {
        return openCommandOutput(binding, executionId, stream, CompanionRuntimeClient.TRANSACTION_OPEN_EXECUTION_LOG)
    }

    override fun openExecutionOutput(binding: RuntimeBinding, executionId: String, stream: String): InputStream {
        return openCommandOutput(binding, executionId, stream, CompanionRuntimeClient.TRANSACTION_OPEN_EXECUTION_OUTPUT)
    }

    private fun openCommandOutput(binding: RuntimeBinding, executionId: String, stream: String, transaction: Int): InputStream {
        require(stream == "stdout" || stream == "stderr") { "RUNTIME_LOG_UNAVAILABLE" }
        val remote = binder() ?: throw IllegalStateException("RUNTIME_ENDPOINT_UNAVAILABLE:$endpointId")
        val request = binding.toJson().put("executionId", executionId).put("stream", stream)
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(descriptor)
            data.writeString(request.toString())
            check(remote.transact(transaction, data, reply, 0)) {
                "RUNTIME_ENDPOINT_TRANSACTION_REJECTED:$endpointId"
            }
            reply.readException()
            val metadata = JSONObject(reply.readString() ?: "{}")
            check(metadata.optBoolean("ok")) { metadata.optString("errorCode", "RUNTIME_LOG_UNAVAILABLE") }
            check(reply.readInt() == 1) { "RUNTIME_LOG_DESCRIPTOR_MISSING" }
            val handle = ParcelFileDescriptor.CREATOR.createFromParcel(reply)
                ?: throw IllegalStateException("RUNTIME_LOG_DESCRIPTOR_MISSING")
            ParcelFileDescriptor.AutoCloseInputStream(handle)
        } finally { data.recycle(); reply.recycle() }
    }

    override fun close() {
        val owner = context ?: return
        val connection = connectionRef.getAndSet(null) ?: return
        runCatching { owner.unbindService(connection) }
        binderRef.set(null)
    }
}

/** The P6.3 external Companion APK endpoint. */
class ExternalCompanionEndpoint(
    context: Context? = null,
    inProcessDispatcher: ((Int, String) -> String)? = null,
    inProcessStreamOpener: ((RuntimeBinding, String, Long, String?) -> InputStream)? = null
) : BinderRuntimeEndpoint(
    context = context,
    component = {
        ComponentName(
            CompanionRuntimeClient.COMPONENT_PACKAGE,
            CompanionRuntimeClient.COMPONENT_CLASS
        )
    },
    endpointId = "external-companion",
    inProcessDispatcher = inProcessDispatcher,
    inProcessStreamOpener = inProcessStreamOpener
)

/**
 * The host APK's `:runtime` process endpoint.  It intentionally speaks the
 * exact same descriptor and transaction contract as [ExternalCompanionEndpoint].
 */
class EmbeddedRuntimeEndpoint(
    context: Context? = null,
    inProcessDispatcher: ((Int, String) -> String)? = null,
    inProcessStreamOpener: ((RuntimeBinding, String, Long, String?) -> InputStream)? = null
) : BinderRuntimeEndpoint(
    context = context,
    component = {
        ComponentName(
            context?.packageName ?: "dev.wind.agent",
            "dev.wind.agent.runtime.VegaRuntimeService"
        )
    },
    endpointId = "embedded-runtime",
    inProcessDispatcher = inProcessDispatcher,
    inProcessStreamOpener = inProcessStreamOpener
)
