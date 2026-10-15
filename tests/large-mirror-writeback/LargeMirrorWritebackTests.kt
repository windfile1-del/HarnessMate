package dev.wind.agent

import android.content.Context
import dev.wind.agent.capability.*
import dev.wind.agent.history.*
import dev.wind.agent.project.*
import dev.wind.agent.runtime.*
import dev.wind.agent.shell.mirror.*
import dev.wind.agent.workspace.*
import org.json.JSONObject
import java.io.*
import java.nio.file.Files
import java.security.MessageDigest
import java.util.UUID

/**
 * Large mirror writeback tests (>16MiB and >32MiB) under strict memory constraints (-Xmx96m / -Xmx128m).
 * Uses StreamingWorkspaceFs rejecting all whole-buffer reads and writes.
 */
object LargeMirrorWritebackTests {
    private var assertions = 0

    private fun verify(condition: Boolean, message: String) {
        assertions++
        if (!condition) throw AssertionError(message)
    }

    /**
     * File-backed workspace filesystem that strictly prohibits non-streaming whole-buffer operations.
     */
    class StreamingWorkspaceFs(val root: File) : WorkspaceFs {
        init { if (!root.exists()) root.mkdirs() }

        var openOutputStreamCount = 0
        var openInputStreamCount = 0

        private fun resolve(path: String): File {
            val clean = path.trim('/').replace('\\', '/')
            return File(root, clean)
        }

        override fun isConfigured(): Boolean = true
        override fun getRootDisplayName(): String = "streaming-fixture"

        override fun read(path: String): ByteArray =
            throw UnsupportedOperationException("NON_STREAMING_READ_PROHIBITED: buffer-less streaming required")

        override fun readText(path: String, charset: String): String =
            throw UnsupportedOperationException("NON_STREAMING_READ_TEXT_PROHIBITED: buffer-less streaming required")

        override fun write(path: String, data: ByteArray) =
            throw UnsupportedOperationException("NON_STREAMING_WRITE_PROHIBITED: buffer-less streaming required")

        override fun writeText(path: String, text: String, charset: String) =
            throw UnsupportedOperationException("NON_STREAMING_WRITE_TEXT_PROHIBITED: buffer-less streaming required")

        override fun openInputStream(path: String): InputStream {
            openInputStreamCount++
            val file = resolve(path)
            if (!file.isFile) throw FileNotFoundException("File not found: " + path)
            return FileInputStream(file)
        }

        override fun openOutputStream(path: String): OutputStream {
            openOutputStreamCount++
            val file = resolve(path)
            file.parentFile?.mkdirs()
            return FileOutputStream(file)
        }

        override fun exists(path: String): Boolean = resolve(path).exists()

        override fun stat(path: String): WorkspaceEntry? {
            val file = resolve(path)
            if (!file.exists()) return null
            val isDir = file.isDirectory
            return WorkspaceEntry(
                path = path.trim('/'),
                name = file.name,
                isDirectory = isDir,
                size = if (isDir) 0L else file.length(),
                lastModified = file.lastModified()
            )
        }

        override fun list(path: String): List<WorkspaceEntry> {
            val file = resolve(path)
            if (!file.isDirectory) return emptyList()
            val files = file.listFiles() ?: return emptyList()
            val prefix = path.trim('/').let { if (it.isEmpty()) "" else "$it/" }
            return files.map { f ->
                WorkspaceEntry(
                    path = prefix + f.name,
                    name = f.name,
                    isDirectory = f.isDirectory,
                    size = if (f.isDirectory) 0L else f.length(),
                    lastModified = f.lastModified()
                )
            }
        }

        override fun mkdir(path: String): Boolean = resolve(path).mkdirs()
        override fun delete(path: String): Boolean = resolve(path).delete()
        override fun move(from: String, to: String): Boolean {
            val src = resolve(from)
            val dst = resolve(to)
            dst.parentFile?.mkdirs()
            return src.renameTo(dst)
        }

        override fun search(query: String, maxResults: Int, scopePath: String): List<WorkspaceEntry> = emptyList()
        override fun glob(pattern: String, maxResults: Int, scopePath: String): List<WorkspaceEntry> = emptyList()
    }

    fun writeDeterministicData(file: File, size: Long): String {
        file.parentFile?.mkdirs()
        val md = MessageDigest.getInstance("SHA-256")
        FileOutputStream(file).use { out ->
            val buf = ByteArray(64 * 1024)
            var written = 0L
            val seed = 0x5A.toByte()
            while (written < size) {
                val toWrite = Math.min(buf.size.toLong(), size - written).toInt()
                for (i in 0 until toWrite) {
                    buf[i] = if ((written + i) % 17L == 0L) 0.toByte() else (seed + (written + i).toInt()).toByte()
                }
                out.write(buf, 0, toWrite)
                md.update(buf, 0, toWrite)
                written += toWrite
            }
            out.fd.sync()
        }
        return md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val root = Files.createTempDirectory("large-writeback-test-").toFile()
        try {
            println("--- Starting LargeMirrorWritebackTests ---")
            testMirrorFileTransferAndStagingCleanup(File(root, "t1_transfer").apply { mkdirs() })
            testTransactionalBinaryStreamGuardAndConflicts(File(root, "t2_guard").apply { mkdirs() })
            testLargeFileCreateEditRollbackUndo(File(root, "t3_ops").apply { mkdirs() })
            testLargeFileDeleteAndUndo(File(root, "t4_delete").apply { mkdirs() })
            println("PASS LargeMirrorWritebackTests: $assertions assertions passed")
        } finally {
            root.deleteRecursively()
        }
    }

    /** 1. Test MirrorFileTransfer copyVerified, size/hash guards, and withStagedFile cleanup. */
    private fun testMirrorFileTransferAndStagingCleanup(dir: File) {
        val size18MiB = 18L * 1024 * 1024
        val size34MiB = 34L * 1024 * 1024

        val src18 = File(dir, "src18.bin")
        val sha18 = writeDeterministicData(src18, size18MiB)
        val dst18 = File(dir, "dst18.bin")

        // 1a. Verified copy of 18MiB
        val copied18 = FileInputStream(src18).use { input ->
            FileOutputStream(dst18).use { output ->
                MirrorFileTransfer.copyVerified(input, output, size18MiB, sha18)
            }
        }
        verify(copied18 == size18MiB, "copyVerified must copy exactly 18MiB")
        verify(dst18.length() == size18MiB, "output file must match 18MiB")

        // 1b. Truncation rejection
        val truncatedDst = File(dir, "dst_trunc.bin")
        var truncFailed = false
        try {
            FileInputStream(src18).use { input ->
                val limitedInput = object : FilterInputStream(input) {
                    var remaining = 10L * 1024 * 1024
                    override fun read(): Int = if (remaining > 0) { remaining--; super.read() } else -1
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (remaining <= 0) return -1
                        val toRead = Math.min(len.toLong(), remaining).toInt()
                        val n = super.read(b, off, toRead)
                        if (n > 0) remaining -= n
                        return n
                    }
                }
                FileOutputStream(truncatedDst).use { output ->
                    MirrorFileTransfer.copyVerified(limitedInput, output, size18MiB, sha18)
                }
            }
        } catch (e: Exception) {
            truncFailed = e.message?.contains("MIRROR_EXPORT_SIZE_MISMATCH") == true
        }
        verify(truncFailed, "truncated stream must fail with MIRROR_EXPORT_SIZE_MISMATCH")

        // 1c. Oversize stream rejection
        var oversizeFailed = false
        try {
            FileInputStream(src18).use { input ->
                FileOutputStream(truncatedDst).use { output ->
                    MirrorFileTransfer.copyVerified(input, output, 16L * 1024 * 1024, sha18)
                }
            }
        } catch (e: Exception) {
            oversizeFailed = e.message?.contains("MIRROR_EXPORT_SIZE_MISMATCH") == true
        }
        verify(oversizeFailed, "oversize stream must fail with MIRROR_EXPORT_SIZE_MISMATCH")

        // 1d. Wrong hash rejection
        var hashFailed = false
        try {
            FileInputStream(src18).use { input ->
                FileOutputStream(truncatedDst).use { output ->
                    MirrorFileTransfer.copyVerified(input, output, size18MiB, "0".repeat(64))
                }
            }
        } catch (e: Exception) {
            hashFailed = e.message?.contains("MIRROR_EXPORT_HASH_MISMATCH") == true
        }
        verify(hashFailed, "corrupted hash must fail with MIRROR_EXPORT_HASH_MISMATCH")

        // 1e. withStagedFile cleans up temp staging file on success and failure (>32MiB)
        val stagingDir = File(dir, "staging").apply { mkdirs() }
        val src34 = File(dir, "src34.bin")
        val sha34 = writeDeterministicData(src34, size34MiB)

        val mockClient = object : MirrorClient {
            override val workspaceId: String = "ws-mock"
            override fun call(op: String, a: JSONObject, s: List<() -> InputStream>, t: CancellationToken?, to: Long, sk: List<MirrorSink>): JSONObject = JSONObject()
            override fun callDetailed(op: String, a: JSONObject, s: List<() -> InputStream>, t: CancellationToken?, to: Long, sk: List<MirrorSink>): MirrorCallResult = MirrorCallResult(JSONObject(), emptyMap())
            override fun downloadFile(p: String, s: Long, h: String, t: CancellationToken?): ByteArray = throw UnsupportedOperationException()
            override fun openArtifactStream(path: String, expectedSize: Long, token: CancellationToken?): InputStream = FileInputStream(src34)
        }

        var observedStagedExists = false
        val actionResult = MirrorFileTransfer.withStagedFile(stagingDir, mockClient, "path34", size34MiB, sha34) { staged ->
            observedStagedExists = staged.exists() && staged.length() == size34MiB
            "SUCCESS_34"
        }
        verify(observedStagedExists, "staging file must exist during action with 34MiB")
        verify(actionResult == "SUCCESS_34", "action must return result")
        verify(stagingDir.listFiles()?.isEmpty() == true, "staging directory must be completely clean after success")

        // Staging cleanup on wrong hash failure
        var stagedCleanOnWrongHash = false
        try {
            MirrorFileTransfer.withStagedFile(stagingDir, mockClient, "path34", size34MiB, "f".repeat(64)) { _ -> }
        } catch (e: Exception) {
            stagedCleanOnWrongHash = stagingDir.listFiles()?.isEmpty() == true
        }
        verify(stagedCleanOnWrongHash, "staging directory must be clean after wrong hash")

        // Staging cleanup on truncation failure
        val mockTruncatedClient = object : MirrorClient {
            override val workspaceId: String = "ws-mock-trunc"
            override fun call(op: String, a: JSONObject, s: List<() -> InputStream>, t: CancellationToken?, to: Long, sk: List<MirrorSink>): JSONObject = JSONObject()
            override fun callDetailed(op: String, a: JSONObject, s: List<() -> InputStream>, t: CancellationToken?, to: Long, sk: List<MirrorSink>): MirrorCallResult = MirrorCallResult(JSONObject(), emptyMap())
            override fun downloadFile(p: String, s: Long, h: String, t: CancellationToken?): ByteArray = throw UnsupportedOperationException()
            override fun openArtifactStream(path: String, expectedSize: Long, token: CancellationToken?): InputStream =
                object : FilterInputStream(FileInputStream(src34)) {
                    var rem = 10L * 1024 * 1024
                    override fun read(): Int = if (rem > 0) { rem--; super.read() } else -1
                    override fun read(b: ByteArray, off: Int, len: Int): Int {
                        if (rem <= 0) return -1
                        val n = super.read(b, off, Math.min(len.toLong(), rem).toInt())
                        if (n > 0) rem -= n
                        return n
                    }
                }
        }
        var stagedCleanOnTrunc = false
        try {
            MirrorFileTransfer.withStagedFile(stagingDir, mockTruncatedClient, "path34", size34MiB, sha34) { _ -> }
        } catch (e: Exception) {
            stagedCleanOnTrunc = stagingDir.listFiles()?.isEmpty() == true
        }
        verify(stagedCleanOnTrunc, "staging directory must be clean after truncated stream")

        // Staging cleanup on oversize failure
        var stagedCleanOnOversize = false
        try {
            MirrorFileTransfer.withStagedFile(stagingDir, mockClient, "path34", 16L * 1024 * 1024, sha34) { _ -> }
        } catch (e: Exception) {
            stagedCleanOnOversize = stagingDir.listFiles()?.isEmpty() == true
        }
        verify(stagedCleanOnOversize, "staging directory must be clean after oversize stream")
    }

    /** 2. Test beforeWrite guard: auth rejection, external modification conflict, no output opened. */
    private fun testTransactionalBinaryStreamGuardAndConflicts(dir: File) {
        val contextClass = Class.forName("dev.wind.agent.CompanionMirrorIntegrationTests\$TestContext")
        val context = contextClass.getDeclaredConstructor(File::class.java).apply { isAccessible = true }.newInstance(File(dir, "ctx")) as Context
        val journalConstructor = MutationJournal::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val journal = journalConstructor.newInstance(context)
        val fs = StreamingWorkspaceFs(File(dir, "canonical"))
        val scope = MutationScope("proj-1", "mount-1", "content://fixture")
        val taskId = "task-guard"

        val canonicalFile = File(fs.root, "target.bin")
        writeDeterministicData(canonicalFile, 1024L)

        // 2a. beforeWrite throws ordinary authorization error
        val initialStreamCount = fs.openOutputStreamCount
        var authErrorThrown = false
        try {
            journal.transactionalBinaryStream(
                scope = scope, fs = fs, taskId = taskId, path = "target.bin",
                expectedSha256 = "a".repeat(64), expectedSize = 2048L, overwrite = true,
                beforeWrite = {
                    throw SecurityException("CAPABILITY_REVOKED: authorization denied")
                }
            ) { out ->
                out.write(ByteArray(2048))
            }
        } catch (e: SecurityException) {
            authErrorThrown = true
        }
        verify(authErrorThrown, "authorization error must propagate")
        verify(fs.openOutputStreamCount == initialStreamCount, "output stream must NOT be opened when beforeWrite fails")
        verify(canonicalFile.length() == 1024L, "canonical file must remain untouched when auth rejected")

        // 2b. beforeWrite detects external canonical modification -> MutationPreconditionConflict
        var conflictThrown = false
        try {
            journal.transactionalBinaryStream(
                scope = scope, fs = fs, taskId = taskId, path = "target.bin",
                expectedSha256 = "b".repeat(64), expectedSize = 2048L, overwrite = true,
                beforeWrite = {
                    FileOutputStream(canonicalFile, true).use { it.write(42) }
                }
            ) { out ->
                out.write(ByteArray(2048))
            }
        } catch (e: MutationPreconditionConflict) {
            conflictThrown = true
        }
        verify(conflictThrown, "pre-open canonical change must throw MutationPreconditionConflict")
    }

    /** 3. Test >32MiB create, edit, stream rollback on failure, and undo. */
    private fun testLargeFileCreateEditRollbackUndo(dir: File) {
        val contextClass = Class.forName("dev.wind.agent.CompanionMirrorIntegrationTests\$TestContext")
        val context = contextClass.getDeclaredConstructor(File::class.java).apply { isAccessible = true }.newInstance(File(dir, "ctx")) as Context
        val journalConstructor = MutationJournal::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val journal = journalConstructor.newInstance(context)
        val fs = StreamingWorkspaceFs(File(dir, "canonical"))
        val scope = MutationScope("proj-ops", "mount-ops", "content://fixture")
        val taskId = "task-large-ops"

        val size34MiB = 34L * 1024 * 1024
        val tempSource = File(dir, "source34.bin")
        val sha34 = writeDeterministicData(tempSource, size34MiB)

        // 3a. Create >32MiB file via transactionalBinaryStream
        val created = journal.transactionalBinaryStream(
            scope = scope, fs = fs, taskId = taskId, path = "large34.bin",
            expectedSha256 = sha34, expectedSize = size34MiB, overwrite = false
        ) { out ->
            FileInputStream(tempSource).use { it.copyTo(out, 64 * 1024) }
        }
        verify(created, "transactionalBinaryStream must successfully create 34MiB file")
        val targetFile = File(fs.root, "large34.bin")
        verify(targetFile.exists() && targetFile.length() == size34MiB, "file on disk must match 34MiB")

        // 3b. Edit >32MiB file with new 34MiB content, snapshotting pre-image
        val tempSource2 = File(dir, "source34_v2.bin")
        val sha34_v2 = writeDeterministicData(tempSource2, size34MiB)
        val edited = journal.transactionalBinaryStream(
            scope = scope, fs = fs, taskId = taskId, path = "large34.bin",
            expectedSha256 = sha34_v2, expectedSize = size34MiB, overwrite = true
        ) { out ->
            FileInputStream(tempSource2).use { it.copyTo(out, 64 * 1024) }
        }
        verify(edited, "transactionalBinaryStream must edit 34MiB file")

        // 3c. Injected failure during stream write -> rollback restores pre-image from snapshot
        var streamErrorOccurred = false
        try {
            journal.transactionalBinaryStream(
                scope = scope, fs = fs, taskId = taskId, path = "large34.bin",
                expectedSha256 = sha34, expectedSize = size34MiB, overwrite = true
            ) { out ->
                out.write(ByteArray(1024 * 1024))
                throw IOException("SIMULATED_STREAM_WRITE_ABORT")
            }
        } catch (e: IOException) {
            streamErrorOccurred = true
        }
        verify(streamErrorOccurred, "simulated stream write failure must be thrown")
        verify(targetFile.length() == size34MiB, "rollback must restore 34MiB size")
        val restoredSha = FileInputStream(targetFile).use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val b = ByteArray(64 * 1024)
            var n: Int
            while (input.read(b).also { n = it } != -1) md.update(b, 0, n)
            md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        verify(restoredSha == sha34_v2, "rollback must restore exact v2 content")

        // 3d. Undo record restores to v1 (original 34MiB)
        val records = journal.getTaskRecords(taskId)
        val editRecord = records.find { it.type == MutationType.EDIT && it.state == TransactionState.COMMITTED }
        verify(editRecord != null && editRecord.snapshotId != null, "committed edit must have snapshotId")
        val undone = journal.undoRecord(editRecord!!, fs)
        verify(undone, "undoRecord on 34MiB edit must succeed")
        val undoneSha = FileInputStream(targetFile).use { input ->
            val md = MessageDigest.getInstance("SHA-256")
            val b = ByteArray(64 * 1024)
            var n: Int
            while (input.read(b).also { n = it } != -1) md.update(b, 0, n)
            md.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        }
        verify(undoneSha == sha34, "undoRecord must restore original v1 34MiB content")
    }

    /** 4. Test transactionalDelete and undo on >32MiB file. */
    private fun testLargeFileDeleteAndUndo(dir: File) {
        val contextClass = Class.forName("dev.wind.agent.CompanionMirrorIntegrationTests\$TestContext")
        val context = contextClass.getDeclaredConstructor(File::class.java).apply { isAccessible = true }.newInstance(File(dir, "ctx")) as Context
        val journalConstructor = MutationJournal::class.java.getDeclaredConstructor(Context::class.java).apply { isAccessible = true }
        val journal = journalConstructor.newInstance(context)
        val fs = StreamingWorkspaceFs(File(dir, "canonical"))
        val scope = MutationScope("proj-del", "mount-del", "content://fixture")
        val taskId = "task-large-del"

        val size34MiB = 34L * 1024 * 1024
        val target = File(fs.root, "to_delete.bin")
        writeDeterministicData(target, size34MiB)

        val deleted = journal.transactionalDelete(scope, fs, taskId, "to_delete.bin") {
            fs.delete("to_delete.bin")
        }
        verify(deleted, "transactionalDelete of 34MiB file must succeed")
        verify(!target.exists(), "file must no longer exist after delete")

        val deleteRecord = journal.getTaskRecords(taskId).last()
        verify(deleteRecord.type == MutationType.DELETE && deleteRecord.snapshotId != null, "delete record must retain snapshot")
        val undoResult = journal.undoRecord(deleteRecord, fs)
        verify(undoResult, "undoRecord of 34MiB deletion must succeed")
        verify(target.exists() && target.length() == size34MiB, "restored file must exist and match 34MiB")
    }

}
