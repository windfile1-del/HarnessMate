package dev.wind.agent.artifact

import dev.wind.agent.capability.TaskContext
import dev.wind.agent.workspace.WorkspacePath
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Immutable plan describing an application of a downloaded artifact into the workspace.
 */
data class DownloadApplyPlan(
    val taskId: String,
    val projectId: String,
    val generation: Long,
    val mountId: String,
    val relativePath: String,
    val artifactUri: String,
    val artifactSha256: String,
    val mode: String, // "file" or "zip"
    val overwrite: Boolean,
    val stripSingleRoot: Boolean,
    val entryCount: Int,
    val totalBytes: Long
) {
    init {
        require(taskId.isNotBlank() && taskId.length <= 256 && taskId != "default-task") {
            "DOWNLOAD_APPLY_CONTEXT_INVALID"
        }
        require(mountId.isNotBlank() && mountId.length <= 160) { "DOWNLOAD_APPLY_MOUNT_INVALID" }
        require(mode in setOf("file", "zip")) { "DOWNLOAD_APPLY_MODE_INVALID" }
        require(artifactSha256.matches(Regex("[0-9a-f]{64}"))) { "DOWNLOAD_APPLY_DIGEST_INVALID" }
        require(relativePath.length <= 4096 && WorkspacePath.sanitize(relativePath) == relativePath) {
            "DOWNLOAD_APPLY_TARGET_INVALID"
        }
    }

    val id: String get() = digest(encode())

    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeUTF("vega-download-apply-plan-v1")
            out.writeUTF(taskId)
            out.writeUTF(projectId)
            out.writeLong(generation)
            out.writeUTF(mountId)
            out.writeUTF(relativePath)
            out.writeUTF(artifactUri)
            out.writeUTF(artifactSha256)
            out.writeUTF(mode)
            out.writeBoolean(overwrite)
            out.writeBoolean(stripSingleRoot)
            out.writeInt(entryCount)
            out.writeLong(totalBytes)
        }
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): DownloadApplyPlan {
            require(bytes.size in 1..2 * 1024 * 1024) { "DOWNLOAD_APPLY_PLAN_SIZE_INVALID" }
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val header = input.readUTF()
                require(header == "vega-download-apply-plan-v1") { "DOWNLOAD_APPLY_PLAN_HEADER_INVALID" }
                return DownloadApplyPlan(
                    taskId = input.readUTF(),
                    projectId = input.readUTF(),
                    generation = input.readLong(),
                    mountId = input.readUTF(),
                    relativePath = input.readUTF(),
                    artifactUri = input.readUTF(),
                    artifactSha256 = input.readUTF(),
                    mode = input.readUTF(),
                    overwrite = input.readBoolean(),
                    stripSingleRoot = input.readBoolean(),
                    entryCount = input.readInt(),
                    totalBytes = input.readLong()
                )
            }
        }

        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/**
 * Private plan store for DownloadApplyPlan instances.
 */
class DownloadApplyPlanStore(privateRoot: File) {
    private val root = File(privateRoot, "p7-apply-plans").absoluteFile
    private val receipts: TaskArtifactAccess

    init {
        require(privateRoot.canonicalFile == privateRoot.absoluteFile && privateRoot.isDirectory) {
            "DOWNLOAD_APPLY_STORE_UNSAFE"
        }
        if (!root.exists()) require(root.mkdir()) { "DOWNLOAD_APPLY_STORE_CREATE_FAILED" }
        require(root.canonicalFile == root && Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "DOWNLOAD_APPLY_STORE_UNSAFE"
        }
        receipts = TaskArtifactAccess(File(root, "issued"))
    }

    fun issue(plan: DownloadApplyPlan): String {
        require(root.canonicalFile == root && Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "DOWNLOAD_APPLY_STORE_UNSAFE"
        }
        val bytes = plan.encode()
        val target = File(root, "${plan.id}.plan")
        require(!Files.isSymbolicLink(target.toPath())) { "DOWNLOAD_APPLY_PLAN_UNSAFE" }
        if (!target.exists()) {
            val staged = Files.createTempFile(root.toPath(), ".plan-", ".tmp")
            try {
                FileOutputStream(staged.toFile()).use { output ->
                    require(staged.toFile().setReadOnly()) { "DOWNLOAD_APPLY_PLAN_READ_ONLY_FAILED" }
                    output.write(bytes)
                    output.fd.sync()
                }
                Files.move(staged, target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                staged.toFile().setWritable(true)
                Files.deleteIfExists(staged)
            }
        }
        receipts.record(
            plan.taskId, plan.projectId, plan.generation,
            VerifiedArtifact("artifact://sha256/${plan.id}", plan.id, bytes.size.toLong())
        )
        return plan.id
    }

    fun load(context: TaskContext, id: String): DownloadApplyPlan {
        require(id.matches(Regex("[0-9a-f]{64}"))) { "DOWNLOAD_APPLY_PLAN_ID_INVALID" }
        val target = File(root, "$id.plan")
        require(Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "DOWNLOAD_APPLY_PLAN_NOT_FOUND" }
        val bytes = Files.readAllBytes(target.toPath())
        val plan = DownloadApplyPlan.decode(bytes)
        require(plan.id == id) { "DOWNLOAD_APPLY_PLAN_FORGED" }
        return plan
    }
}
