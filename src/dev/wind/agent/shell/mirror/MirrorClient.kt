package dev.wind.agent.shell.mirror

import dev.wind.agent.CancellationToken
import java.io.InputStream
import java.io.OutputStream
import org.json.JSONObject

/**
 * Provider-neutral mirror RPC surface. Implementations must use the exact
 * RuntimeBinding selected for the project; this interface contains no physical
 * root.
 */
interface MirrorClient {
    val workspaceId: String

    /**
     * Mirror data-plane revision this client speaks. P3 preparation requires a
     * bounded streaming plane, so a client that cannot stream is rejected
     * before any state is written or any remote mutation is attempted.
     */
    val mirrorStreamProtocolVersion: Int
        get() = 1

    fun call(
        operation: String,
        args: JSONObject = JSONObject(),
        sources: List<() -> InputStream> = emptyList(),
        token: CancellationToken? = null,
        timeoutMs: Long = 600_000L,
        sinks: List<MirrorSink> = emptyList()
    ): JSONObject

    fun callDetailed(
        operation: String,
        args: JSONObject = JSONObject(),
        sources: List<() -> InputStream> = emptyList(),
        token: CancellationToken? = null,
        timeoutMs: Long = 600_000L,
        sinks: List<MirrorSink> = emptyList()
    ): MirrorCallResult

    fun downloadFile(
        path: String,
        expectedSize: Long,
        expectedSha256: String,
        token: CancellationToken? = null
    ): ByteArray

    /** Verified file export, independent of the buffered text/control limits. */
    fun copyFileTo(path: String, expectedSize: Long, expectedSha256: String,
                   output: OutputStream, token: CancellationToken? = null): Long {
        val input = try {
            openArtifactStream(path, expectedSize, token)
        } catch (unsupported: UnsupportedOperationException) {
            // Compatibility with old bounded clients; production clients stream at every size.
            if (expectedSize !in 0..(16L * 1024 * 1024)) throw unsupported
            downloadFile(path, expectedSize, expectedSha256, token).inputStream()
        }
        return input.use { MirrorFileTransfer.copyVerified(it, output, expectedSize, expectedSha256, token) }
    }

    /** Descriptor stream for file payloads, with the manifest size as the bound. */
    fun openArtifactStream(path: String, expectedSize: Long, token: CancellationToken?): InputStream =
        throw UnsupportedOperationException("R3_ARTIFACT_STREAM_UNAVAILABLE: select VEGA_LINUX")
}


/** Compatibility adapter used while existing Termux callers keep their defaults. */
class TermuxMirrorClientAdapter(private val delegate: TermuxMirrorClient) : MirrorClient {
    override fun copyFileTo(path: String, expectedSize: Long, expectedSha256: String,
                            output: OutputStream, token: CancellationToken?): Long =
        delegate.copyFileTo(path, expectedSize, expectedSha256, output, token)
    override val workspaceId: String
        get() = delegate.workspaceId

    override fun call(
        operation: String,
        args: JSONObject,
        sources: List<() -> InputStream>,
        token: CancellationToken?,
        timeoutMs: Long,
        sinks: List<MirrorSink>
    ): JSONObject = delegate.call(operation, args, sources, token, timeoutMs, sinks)

    override fun callDetailed(
        operation: String,
        args: JSONObject,
        sources: List<() -> InputStream>,
        token: CancellationToken?,
        timeoutMs: Long,
        sinks: List<MirrorSink>
    ): MirrorCallResult = delegate.callDetailed(operation, args, sources, token, timeoutMs, sinks)

    override fun downloadFile(
        path: String,
        expectedSize: Long,
        expectedSha256: String,
        token: CancellationToken?
    ): ByteArray = delegate.downloadFile(path, expectedSize, expectedSha256, token)
}
