package dev.wind.agent.shell.mirror

import android.content.Context
import dev.wind.agent.CancellationToken
import dev.wind.agent.shell.termux.TermuxRunCommandClient
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

data class MirrorCallResult(val result: JSONObject, val sinks: Map<String, ByteArray>)

/**
 * The companion-style transaction facts carried by a sync plan. They are
 * optional for the helper (which is handed plan pages as plan sources and keeps
 * its own session), but the companion plane requires them to be present, so
 * both clients read the same descriptor here rather than re-deriving defaults.
 */
data class MirrorSyncPlan(
    val pageSources: List<Int>,
    val expectedRemoteDigest: String,
    val plannedFiles: Long,
    val plannedBytes: Long,
    val plannedDeleted: Int
) {
    companion object {
        fun from(args: JSONObject): MirrorSyncPlan? {
            val pages = args.optJSONArray("planSources") ?: return null
            val digest = args.optString("expectedRemoteDigest", "")
            if (!ManifestDigest.isDigest(digest)) return null
            val indices = mutableListOf<Int>()
            for (i in 0 until pages.length()) indices.add(pages.getInt(i))
            return MirrorSyncPlan(
                pageSources = indices,
                expectedRemoteDigest = digest,
                plannedFiles = args.optLong("plannedFiles", -1L),
                plannedBytes = args.optLong("plannedBytes", -1L),
                plannedDeleted = args.optInt("plannedDeleted", -1)
            )
        }
    }
}

/**
 * A sync plan page: either a page of deletions or a page of entries, in the
 * order the plan produced them.
 */
sealed class MirrorSyncPage {
    data class Deleted(val paths: List<String>) : MirrorSyncPage()
    data class Entries(val entries: List<MirrorSyncEntry>) : MirrorSyncPage()
}

data class MirrorSyncEntry(
    val path: String,
    val directory: Boolean,
    val size: Long,
    val sha256: String,
    val sourceIndex: Int
)

/** Decodes one bounded plan page; the page never carries file payload. */
fun decodeMirrorSyncPage(bytes: ByteArray): MirrorSyncPage {
    require(bytes.size <= MirrorSyncPageLimits.PAGE_BYTES) { "MIRROR_SYNC_PLAN_PAGE_TOO_LARGE" }
    val page = JSONObject(String(bytes, Charsets.UTF_8))
    val deleted = page.optJSONArray("deleted")
    if (deleted != null) {
        require(deleted.length() <= MirrorSyncPageLimits.PAGE_ENTRIES) { "MIRROR_SYNC_PLAN_PAGE_TOO_LARGE" }
        val paths = mutableListOf<String>()
        for (i in 0 until deleted.length()) paths.add(deleted.getString(i))
        return MirrorSyncPage.Deleted(paths)
    }
    val entries = page.optJSONArray("entries") ?: error("MIRROR_SYNC_PLAN_PAGE_INVALID")
    require(entries.length() <= MirrorSyncPageLimits.PAGE_ENTRIES) { "MIRROR_SYNC_PLAN_PAGE_TOO_LARGE" }
    val decoded = mutableListOf<MirrorSyncEntry>()
    for (i in 0 until entries.length()) {
        val item = entries.getJSONObject(i)
        decoded.add(MirrorSyncEntry(
            path = item.getString("path"),
            directory = item.getBoolean("directory"),
            size = item.optLong("size", -1L),
            sha256 = item.optString("sha256", ""),
            sourceIndex = item.optInt("source", -1)
        ))
    }
    return MirrorSyncPage.Entries(decoded)
}

object MirrorSyncPageLimits {
    const val PAGE_ENTRIES = 2048
    const val PAGE_BYTES = 1024 * 1024
}

open class TermuxMirrorClient(private val context: Context, val workspaceId: String) {
    open fun call(
        operation: String,
        args: JSONObject = JSONObject(),
        sources: List<() -> InputStream> = emptyList(),
        token: CancellationToken? = null,
        timeoutMs: Long = 600_000L,
        sinks: List<MirrorSink> = emptyList()
    ): JSONObject = callDetailed(operation, args, sources, token, timeoutMs, sinks).result

    /**
     * Reverse streams are returned only to the caller and are bounded by the
     * sink descriptor. They are never written to Vega's filesDir or Termux.
     */
    open fun callDetailed(
        operation: String,
        args: JSONObject = JSONObject(),
        sources: List<() -> InputStream> = emptyList(),
        token: CancellationToken? = null,
        timeoutMs: Long = 600_000L,
        sinks: List<MirrorSink> = emptyList()
    ): MirrorCallResult {
        WorkspaceIdentity.remoteRoot(workspaceId)
        val sinkJson = JSONArray().apply {
            sinks.forEach {
                put(JSONObject().put("id", it.id).put("maxBytes", it.maxBytes)
                    .put("expectedSize", it.expectedSize).put("expectedSha256", it.expectedSha256 ?: ""))
            }
        }
        val control = JSONObject().put("protocolVersion", 2)
            .put("operation", operation).put("workspaceId", workspaceId)
            .put("args", args)
            .put("sinks", sinkJson)
            .put("ignoredSegments", WorkspaceIgnorePolicy.sortedSegmentsJson())
            .put("ignoredSuffixes", WorkspaceIgnorePolicy.sortedSuffixesJson())
        val helper = context.assets.open("mirror_helper.py").use { it.readBytes() }
        MirrorSyncBridgeServer(
            helper,
            control.toString().toByteArray(Charsets.UTF_8),
            { index -> sources.getOrNull(index)?.invoke() ?: error("MIRROR_SOURCE_UNKNOWN") },
            { token?.isCancelled == true },
            sinks
        ).use { server ->
            // The bootstrap contains only an internally generated URL, never model text.
            val script = "export PATH=/data/data/com.termux/files/usr/bin:/system/bin\n" +
                "command -v python3 >/dev/null || { echo MIRROR_REQUIRES_PYTHON >&2; exit 69; }\n" +
                "set -o pipefail\n" +
                "curl --noproxy '*' -fsS '${server.url}/helper' | python3 - '${server.url}'\n"
            val outcome = TermuxRunCommandClient(context).run(TermuxRunCommandClient.Request(
                commandPath = "/data/data/com.termux/files/usr/bin/bash", args = listOf("-s"), stdin = script,
                timeoutMs = timeoutMs + 30_000L))
            val raw = outcome.result
            check(outcome.callbackReceived && raw != null && !raw.hasInternalError && raw.exitCode == 0) {
                "MIRROR_RPC_FAILED: ${outcome.dispatchError ?: raw?.stderr ?: "callback timeout; mirror state uncertain"}"
            }
            server.failure?.let { throw it }
            val bytes = server.result ?: error("MIRROR_RPC_MISSING_RESULT")
            val result = JSONObject(String(bytes, Charsets.UTF_8))
            check(result.optBoolean("ok", false)) { result.optString("error", "MIRROR_FAILED") }
            sinks.forEach { server.requireSink(it.id) }
            val received = sinks.filter { it.destination == null }.associate { it.id to server.sinkBytes(it.id) }
            return MirrorCallResult(result, received)
        }
    }

    open fun copyFileTo(path: String, expectedSize: Long, expectedSha256: String,
                        output: OutputStream, token: CancellationToken? = null): Long {
        require(expectedSize >= 0) { "MIRROR_REMOTE_SIZE_INVALID" }
        val sink = MirrorSink("file-" + UUID.randomUUID().toString().replace("-", "").take(24),
            expectedSize, expectedSize, expectedSha256, output)
        val result = callDetailed("export_file", JSONObject().put("path", path).put("sink", sink.id)
            .put("size", expectedSize).put("sha256", expectedSha256), token = token, sinks = listOf(sink)).result
        check(result.optLong("size", -1L) == expectedSize) { "MIRROR_EXPORT_SIZE_MISMATCH" }
        check(result.optString("sha256") == expectedSha256) { "MIRROR_EXPORT_HASH_MISMATCH" }
        return expectedSize
    }

    open fun downloadFile(
        path: String,
        expectedSize: Long,
        expectedSha256: String,
        token: CancellationToken? = null
    ): ByteArray {
        require(expectedSize in 0..MirrorSyncBridgeServer.MAX_SINK_BYTES) { "UNSUPPORTED_APPLY_FILE_SIZE" }
        require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "MIRROR_REMOTE_HASH_INVALID" }
        val sink = MirrorSink(
            id = "file-" + UUID.randomUUID().toString().replace("-", "").take(24),
            maxBytes = expectedSize,
            expectedSize = expectedSize,
            expectedSha256 = expectedSha256
        )
        val outcome = callDetailed(
            "export_file",
            JSONObject().put("path", path).put("sink", sink.id)
                .put("size", expectedSize).put("sha256", expectedSha256),
            token = token,
            sinks = listOf(sink)
        )
        check(outcome.result.optLong("size", -1L) == expectedSize) { "MIRROR_EXPORT_SIZE_MISMATCH" }
        return outcome.sinks[sink.id] ?: error("MIRROR_SINK_MISSING")
    }
}
