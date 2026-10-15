package dev.wind.agent.shell.mirror

import dev.wind.agent.CancellationToken
import dev.wind.agent.runtime.CompanionRuntimeClient
import dev.wind.agent.runtime.RuntimeBinding
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.security.MessageDigest
import java.util.UUID

/**
 * Provider-neutral MirrorClient implementation that dispatches P3 mirror operations
 * to the self-contained Companion runtime over Protocol v2.
 *
 * The companion is the same mirror the Termux helper is, reached differently:
 * enumerations carry the host's ignore policy, and payloads never travel as
 * Base64 inside a Binder string.
 *
 * * Downloads (mirror -> SAF apply) receive a ParcelFileDescriptor from the
 *   companion and are verified as they are read.
 * * Uploads (SAF -> mirror) go through a sync transaction: BEGIN compares the
 *   remote digest the plan was built from, the plan pages and file payloads are
 *   streamed into a staging tree, and COMMIT re-checks the digest before
 *   anything under project/ is touched.
 */
class CompanionMirrorClient(
    val binding: RuntimeBinding,
    private val client: CompanionRuntimeClient,
    override val mirrorStreamProtocolVersion: Int =
        CompanionRuntimeClient.MIRROR_STREAM_PROTOCOL_VERSION
) : MirrorClient {
    override val workspaceId: String
        get() = binding.workspaceId

    override fun openArtifactStream(path: String, expectedSize: Long, token: CancellationToken?): InputStream {
        token?.throwIfCancelled()
        return client.openFileStream(binding, path, "artifact-" + UUID.randomUUID().toString().replace("-", ""),
            expectedSize, expectedSize, null)
    }

    companion object {
        /**
         * One plan page, read with an explicit ceiling. Metadata is allowed to
         * be materialized - file payloads are not - but only up to one byte past
         * the budget, so an oversized page is detected instead of allocated.
         */
        private const val PLAN_PAGE_READ_LIMIT = MirrorSyncPageLimits.PAGE_BYTES + 1

        /** Buffered callers only; canonical publication uses verified descriptor streams. */
        private const val MAX_DOWNLOAD_BYTES = 16L * 1024 * 1024
    }

    private fun ignorePolicyJson(): JSONObject = JSONObject()
        .put("ignoredSegments", WorkspaceIgnorePolicy.sortedSegmentsJson())
        .put("ignoredSuffixes", WorkspaceIgnorePolicy.sortedSuffixesJson())

    /** Operations that enumerate the mirror and therefore need the host policy. */
    private fun needsIgnorePolicy(operation: String): Boolean = when (operation) {
        "manifest", "manifest_page",
        "execution_list_dir", "execution_glob", "execution_search_files" -> true
        else -> false
    }

    override fun call(
        operation: String,
        args: JSONObject,
        sources: List<() -> InputStream>,
        token: CancellationToken?,
        timeoutMs: Long,
        sinks: List<MirrorSink>
    ): JSONObject {
        return callDetailed(operation, args, sources, token, timeoutMs, sinks).result
    }

    override fun callDetailed(
        operation: String,
        args: JSONObject,
        sources: List<() -> InputStream>,
        token: CancellationToken?,
        timeoutMs: Long,
        sinks: List<MirrorSink>
    ): MirrorCallResult {
        token?.throwIfCancelled()
        if (operation == "sync") return runSync(args, sources, token)
        val policy = if (needsIgnorePolicy(operation)) ignorePolicyJson() else null
        val resp = client.mirrorCall(binding, operation, args, policy)
        token?.throwIfCancelled()
        return MirrorCallResult(resp, emptyMap())
    }

    /**
     * Prepares the mirror in one transaction.
     *
     * The order is the contract. Both digest checks compare against the digest
     * the plan was built from, so a remote that moved between the plan and
     * either checkpoint is rejected with the mirror untouched: staging is the
     * only thing a sync writes before the second check passes.
     */
    private fun runSync(
        args: JSONObject,
        sources: List<() -> InputStream>,
        token: CancellationToken?
    ): MirrorCallResult {
        val plan = MirrorSyncPlan.from(args)
            ?: throw IllegalArgumentException("MIRROR_SYNC_PLAN_REQUIRED")
        val policy = ignorePolicyJson()
        val syncId = "sync-" + UUID.randomUUID().toString().replace("-", "").take(24)
        client.syncBegin(
            binding = binding,
            syncId = syncId,
            expectedRemoteDigest = plan.expectedRemoteDigest,
            ignorePolicy = policy,
            plannedFiles = plan.plannedFiles,
            plannedBytes = plan.plannedBytes
        )
        try {
            // Phase 1: plan pages. A page is metadata; the file streams it
            // references are untouched until the page that names them lands.
            val files = ArrayList<Pair<String, Int>>()
            for (index in plan.pageSources) {
                token?.throwIfCancelled()
                val stream = sources.getOrNull(index)?.invoke()
                    ?: throw IllegalStateException("MIRROR_SOURCE_MISSING: index $index")
                val page = readBounded(stream)
                val decoded = decodeMirrorSyncPage(page)
                client.syncPlanPage(binding, syncId, page, token)
                if (decoded is MirrorSyncPage.Entries) {
                    for (entry in decoded.entries) {
                        if (!entry.directory) files.add(entry.path to entry.sourceIndex)
                    }
                }
            }
            // Phase 2: payloads. Each one is streamed from its own lambda, so a
            // source can only ever be consumed once and cannot be replayed.
            for ((path, sourceIndex) in files) {
                token?.throwIfCancelled()
                val source = sources.getOrNull(sourceIndex)
                    ?: throw IllegalStateException("MIRROR_FILE_SOURCE_MISSING: $path at $sourceIndex")
                client.syncFile(binding, syncId, path, source, token)
            }
            token?.throwIfCancelled()
            val committed = client.syncCommit(binding, syncId)
            return MirrorCallResult(committed, emptyMap())
        } catch (error: Exception) {
            // Abort is idempotent by contract, so it is also safe from a path
            // where the failure was the abort of an earlier step.
            runCatching { client.syncAbort(binding, syncId) }
            throw error
        }
    }

    private fun readBounded(stream: InputStream): ByteArray {
        val buffer = ByteArrayOutputStream()
        val block = ByteArray(64 * 1024)
        stream.use { input ->
            var total = 0
            while (true) {
                val read = input.read(block)
                if (read < 0) break
                if (read == 0) continue
                total += read
                if (total > PLAN_PAGE_READ_LIMIT - 1) {
                    throw IllegalStateException("MIRROR_SYNC_PLAN_PAGE_TOO_LARGE")
                }
                buffer.write(block, 0, read)
            }
        }
        return buffer.toByteArray()
    }

    /**
     * Reads one mirror file through the descriptor the companion transfers.
     *
     * The bytes are hashed and counted as they arrive: the descriptor is a live
     * view of the companion's file, so only the received stream can prove that
     * what arrived is what the manifest promised.
     */
    override fun downloadFile(
        path: String,
        expectedSize: Long,
        expectedSha256: String,
        token: CancellationToken?
    ): ByteArray {
        require(expectedSize in -1L..MAX_DOWNLOAD_BYTES) { "UNSUPPORTED_APPLY_FILE_SIZE" }
        require(expectedSha256.isBlank() || expectedSha256.matches(Regex("[0-9a-fA-F]{64}"))) { "MIRROR_REMOTE_HASH_INVALID" }
        token?.throwIfCancelled()
        val operationId = "export-" + UUID.randomUUID().toString().replace("-", "").take(16)
        val stream = client.openFileStream(
            binding = binding,
            path = path,
            operationId = operationId,
            expectedSize = expectedSize,
            maxBytes = MAX_DOWNLOAD_BYTES,
            expectedSha256 = expectedSha256.takeIf { it.isNotBlank() }
        )
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        val output = ByteArrayOutputStream()
        var totalRead = 0L
        stream.use { input ->
            while (true) {
                token?.throwIfCancelled()
                val read = input.read(buffer)
                if (read == -1) break
                if (read == 0) continue
                totalRead += read
                if (totalRead > MAX_DOWNLOAD_BYTES) {
                    throw IllegalStateException("MIRROR_FILE_SIZE_EXCEEDS_MAX_BYTES: limit $MAX_DOWNLOAD_BYTES")
                }
                output.write(buffer, 0, read)
                digest.update(buffer, 0, read)
            }
        }
        if (expectedSize >= 0L && totalRead != expectedSize) {
            throw IllegalStateException("MIRROR_FILE_TRUNCATED: expected $expectedSize, got $totalRead")
        }
        val computedSha = digest.digest().joinToString("") { "%02x".format(it) }
        if (expectedSha256.isNotBlank() && !computedSha.equals(expectedSha256, ignoreCase = true)) {
            throw IllegalStateException("MIRROR_FILE_HASH_MISMATCH: expected $expectedSha256, got $computedSha")
        }
        return output.toByteArray()
    }
}
