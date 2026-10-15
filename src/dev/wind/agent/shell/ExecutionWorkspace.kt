package dev.wind.agent.shell

import android.content.Context
import dev.wind.agent.history.MutationJournal
import dev.wind.agent.workspace.WorkspaceFs
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest

/** Legacy V0.1 staging model, reachable only by process-start compatibility recovery. */
@Deprecated("The active execution path uses ProjectMirrorManager")
data class FileDiffItem(
    val relativePath: String,
    val isDirectory: Boolean,
    val type: DiffType
)

enum class DiffType {
    CREATED,
    MODIFIED,
    DELETED
}

data class SyncConflict(
    val path: String,
    val reason: String
)

data class SyncPreflightResult(
    val allowed: Boolean,
    val conflicts: List<SyncConflict>,
    val unsupported: List<String>
)

data class ExecutionDiff(
    val items: List<FileDiffItem>
) {
    fun isEmpty(): Boolean = items.isEmpty()

    fun summary(): String {
        if (isEmpty()) return "No workspace changes detected."
        val sb = StringBuilder("Workspace changes detected:\n")
        for (item in items) {
            val prefix = when (item.type) {
                DiffType.CREATED -> "+ [CREATED]  "
                DiffType.MODIFIED -> "M [MODIFIED] "
                DiffType.DELETED -> "- [DELETED]  "
            }
            sb.append(prefix).append(item.relativePath)
            if (item.isDirectory) sb.append('/')
            sb.append('\n')
        }
        return sb.toString().trim()
    }
}

class ExecutionWorkspace(private val context: Context, private val taskId: String) {

    val stagingDir: File = File(context.filesDir, "execution/$taskId/staging").apply {
        if (!isDirectory) mkdirs()
    }
    val returnedDir: File = File(context.filesDir, "execution/$taskId/returned")

    data class BaselineEntry(
        val path: String,
        val isDirectory: Boolean,
        val size: Long,
        val sha256: String
    )

    private val baselineManifest = mutableMapOf<String, BaselineEntry>()

    fun isIgnored(relPath: String): Boolean =
        dev.wind.agent.shell.mirror.WorkspaceIgnorePolicy.isIgnored(relPath)

    /** Mirrors files from SAF Workspace to staging directory and establishes baseline manifest. */
    fun syncIn(fs: WorkspaceFs) {
        baselineManifest.clear()
        val failures = mutableListOf<String>()
        fun walk(dirPath: String) {
            val entries = try { fs.list(dirPath) } catch (e: Exception) {
                failures.add("list $dirPath: ${e.message}")
                return
            }
            for (entry in entries) {
                if (isIgnored(entry.path)) continue
                val targetFile = File(stagingDir, entry.path)
                if (entry.isDirectory) {
                    if (!targetFile.isDirectory && !targetFile.mkdirs()) {
                        failures.add("mkdir ${entry.path}")
                    }
                    baselineManifest[entry.path] = BaselineEntry(entry.path, true, 0L, "")
                    walk(entry.path)
                } else {
                    targetFile.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
                    try {
                        fs.openInputStream(entry.path).use { input ->
                            FileOutputStream(targetFile).use { out ->
                                input.copyTo(out)
                            }
                        }
                        val sha = sha256(targetFile)
                        baselineManifest[entry.path] = BaselineEntry(entry.path, false, targetFile.length(), sha)
                    } catch (e: Exception) {
                        failures.add("${entry.path}: ${e.message}")
                    }
                }
            }
        }
        walk("")
        if (failures.isNotEmpty()) {
            throw IllegalStateException("Workspace staging incomplete (fail-closed): " + failures.joinToString("; "))
        }
        persistBaseline()
    }

    fun persistBaseline() {
        val jsonArr = org.json.JSONArray()
        for ((_, entry) in baselineManifest) {
            val obj = org.json.JSONObject()
                obj.put("path", entry.path)
                obj.put("directory", entry.isDirectory)
                obj.put("size", entry.size)
                obj.put("sha256", entry.sha256)
                jsonArr.put(obj)
            }
            val baseFile = File(stagingDir.parentFile, "baseline.json")
            baseFile.parentFile?.let { if (!it.isDirectory) it.mkdirs() }
            FileOutputStream(baseFile).use { fos ->
                fos.write(jsonArr.toString().toByteArray(Charsets.UTF_8))
                fos.flush()
                fos.fd.sync()
            }
    }

    fun loadBaseline(): Boolean {
        return try {
            val baseFile = File(stagingDir.parentFile, "baseline.json")
            if (!baseFile.isFile) return false
            val text = baseFile.readText(Charsets.UTF_8)
            val jsonArr = org.json.JSONArray(text)
            baselineManifest.clear()
            for (i in 0 until jsonArr.length()) {
                val obj = jsonArr.getJSONObject(i)
                val path = obj.getString("path")
                val isDir = obj.getBoolean("directory")
                val size = obj.getLong("size")
                val sha = obj.getString("sha256")
                baselineManifest[path] = BaselineEntry(path, isDir, size, sha)
            }
            true
        } catch (_: Exception) { false }
    }

    /** Computes differences by comparing post-execution staging directory against baseline manifest. */
    fun detectDiff(fs: WorkspaceFs? = null): ExecutionDiff {
        val items = mutableListOf<FileDiffItem>()
        val targetDir = if (returnedDir.exists() && (returnedDir.listFiles()?.isNotEmpty() == true)) returnedDir else stagingDir
        val prefixLen = targetDir.absolutePath.length + 1

        // 1. Check local staging files against baseline
        fun walkLocal(f: File) {
            val files = f.listFiles() ?: return
            for (file in files) {
                val rel = file.absolutePath.substring(prefixLen).replace('\\', '/')
                if (isIgnored(rel)) continue
                if (file.isDirectory) {
                    if (!baselineManifest.containsKey(rel)) {
                        items.add(FileDiffItem(rel, true, DiffType.CREATED))
                    }
                    walkLocal(file)
                } else {
                    val base = baselineManifest[rel]
                    if (base == null) {
                        items.add(FileDiffItem(rel, false, DiffType.CREATED))
                    } else {
                        val localSha = sha256(file)
                        if (localSha != base.sha256) {
                            items.add(FileDiffItem(rel, false, DiffType.MODIFIED))
                        }
                    }
                }
            }
        }
        walkLocal(targetDir)

        // 2. Check baseline files against staging to identify deletions
        for ((rel, base) in baselineManifest) {
            if (isIgnored(rel)) continue
            if (!base.isDirectory) {
                val targetFile = File(targetDir, rel)
                if (!targetFile.exists()) {
                    items.add(FileDiffItem(rel, false, DiffType.DELETED))
                }
            }
        }

        return ExecutionDiff(items)
    }

    /** Writes approved changed files back to SAF Workspace with baseline conflict detection. */
    fun syncBack(fs: WorkspaceFs, diff: ExecutionDiff, journal: MutationJournal) {
        applySyncBack(fs, diff, journal, taskId)
    }

    fun isBinary(bytes: ByteArray): Boolean {
        // 1. NUL byte check
        for (b in bytes) {
            if (b == 0.toByte()) return true
        }
        // 2. Strict UTF-8 decode
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
            decoder.onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            decoder.onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
            decoder.decode(java.nio.ByteBuffer.wrap(bytes))
            false
        } catch (_: Exception) {
            true
        }
    }

    fun preflightSyncBack(fs: WorkspaceFs, diff: ExecutionDiff): SyncPreflightResult {
        val conflicts = mutableListOf<SyncConflict>()
        val unsupported = mutableListOf<String>()
        val sourceDir = if (returnedDir.exists() && (returnedDir.listFiles()?.isNotEmpty() == true)) returnedDir else stagingDir

        for (item in diff.items) {
            if (isIgnored(item.relativePath)) continue
            val localFile = File(sourceDir, item.relativePath)
            val base = baselineManifest[item.relativePath]

            if (item.type == DiffType.CREATED || item.type == DiffType.MODIFIED) {
                if (localFile.isFile && isBinary(localFile.readBytes())) {
                    unsupported.add(item.relativePath)
                }
            }

            when (item.type) {
                DiffType.MODIFIED -> {
                    if (!fs.exists(item.relativePath)) {
                        conflicts.add(SyncConflict(item.relativePath, "File was deleted externally in workspace"))
                    } else {
                        val liveSha = sha256Fs(fs, item.relativePath)
                        if (base != null && liveSha != base.sha256) {
                            conflicts.add(SyncConflict(item.relativePath, "File was modified externally in workspace (live SHA != baseline SHA)"))
                        }
                    }
                }
                DiffType.DELETED -> {
                    if (!fs.exists(item.relativePath)) {
                        conflicts.add(SyncConflict(item.relativePath, "File is already missing or deleted in workspace"))
                    } else {
                        val liveSha = sha256Fs(fs, item.relativePath)
                        if (base != null && liveSha != base.sha256) {
                            conflicts.add(SyncConflict(item.relativePath, "File was modified externally in workspace before deletion"))
                        }
                    }
                }
                DiffType.CREATED -> {
                    if (fs.exists(item.relativePath)) {
                        conflicts.add(SyncConflict(item.relativePath, "File already exists in workspace"))
                    } else {
                        val parentRel = File(item.relativePath).parent?.replace('\\', '/') ?: ""
                        if (parentRel.isNotEmpty() && !fs.exists(parentRel) && diff.items.none { it.relativePath == parentRel && it.type == DiffType.CREATED }) {
                            conflicts.add(SyncConflict(item.relativePath, "Parent directory '$parentRel' does not exist in workspace"))
                        }
                    }
                }
            }
        }

        val allowed = conflicts.isEmpty() && unsupported.isEmpty()
        return SyncPreflightResult(allowed, conflicts, unsupported)
    }

    /** Atomically applies approved changed files back to SAF Workspace within a transactional batch. */
    fun applySyncBack(fs: WorkspaceFs, diff: ExecutionDiff, journal: MutationJournal, rootTaskId: String) {
        synchronized(MutationJournal.MUTATION_LOCK) {
        val preflight = preflightSyncBack(fs, diff)
        if (!preflight.allowed) {
            val reasons = preflight.conflicts.map { "${it.path}: ${it.reason}" } + preflight.unsupported.map { "$it: unsupported binary" }
            throw IllegalStateException("Preflight failed (0 files mutated): " + reasons.joinToString("; "))
        }

        val sourceDir = if (returnedDir.exists() && (returnedDir.listFiles()?.isNotEmpty() == true)) returnedDir else stagingDir
        val batchId = "shell-apply-$taskId"
        val batch = journal.createBatch(batchId, rootTaskId)

        try {
            for (item in diff.items) {
                if (isIgnored(item.relativePath)) continue
                val localFile = File(sourceDir, item.relativePath)

                when (item.type) {
                    DiffType.CREATED -> {
                        if (item.isDirectory) {
                            journal.transactionalMkdir(rootTaskId, item.relativePath, batchId = batchId) {
                                fs.mkdir(item.relativePath)
                            }
                        } else if (localFile.isFile) {
                            val text = localFile.readText(Charsets.UTF_8)
                            journal.transactionalWrite(rootTaskId, item.relativePath, text, overwrite = false, batchId = batchId) {
                                fs.writeText(item.relativePath, text)
                            }
                        }
                    }
                    DiffType.MODIFIED -> {
                        if (localFile.isFile) {
                            val text = localFile.readText(Charsets.UTF_8)
                            journal.transactionalEdit(rootTaskId, item.relativePath, text, batchId = batchId) {
                                fs.writeText(item.relativePath, text)
                            }
                        }
                    }
                    DiffType.DELETED -> {
                        if (!item.isDirectory) {
                            journal.transactionalDelete(rootTaskId, item.relativePath, batchId = batchId) {
                                fs.delete(item.relativePath)
                            }
                        }
                    }
                }
            }
            journal.commitBatch(batch)
        } catch (e: Exception) {
            journal.rollbackBatch(batch, fs)
            throw e
        }
        }
    }

    fun cleanup() {
        try {
            File(context.filesDir, "execution/$taskId").deleteRecursively()
        } catch (_: Exception) {
        }
    }

    private fun sha256(file: File): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
    }

    private fun sha256Fs(fs: WorkspaceFs, path: String): String {
        return try {
            val md = MessageDigest.getInstance("SHA-256")
            fs.openInputStream(path).use { input ->
                val buf = ByteArray(8192)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    md.update(buf, 0, n)
                }
            }
            md.digest().joinToString("") { "%02x".format(it) }
        } catch (_: Exception) { "" }
    }
}
