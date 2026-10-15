package dev.wind.agent.build

import dev.wind.agent.artifact.TaskArtifactAccess
import dev.wind.agent.artifact.VerifiedArtifact
import dev.wind.agent.capability.TaskContext
import dev.wind.agent.workspace.WorkspaceFs
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
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** App-issued intent, not a model-authored grant or publisher attestation. */
data class MavenDependencyInstallPlan(
    val taskId: String, val projectId: String, val generation: Long,
    val mountId: String, val relativeRoot: String, val previousLockSha256: String?,
    val lock: MavenDependencyLock
) {
    init {
        require(listOf(taskId, projectId, mountId).all {
            it.isNotBlank() && it.length <= 256 && it.none { char -> char < ' ' }
        } && taskId != "default-task" && generation >= 0L) { "MAVEN_INSTALL_CONTEXT_INVALID" }
        require(relativeRoot.length <= 4096 && WorkspacePath.sanitize(relativeRoot) == relativeRoot &&
            relativeRoot.none { it < ' ' }) { "MAVEN_INSTALL_TARGET_INVALID" }
        require(previousLockSha256 == null || previousLockSha256.matches(DIGEST)) {
            "MAVEN_INSTALL_PRECONDITION_INVALID"
        }
    }
    val metadataPath: String get() = if (relativeRoot.isEmpty()) ".vega" else "$relativeRoot/.vega"
    val lockPath: String get() = "$metadataPath/maven-dependencies.lock"
    val targetId: String get() = "maven:" + digest("$mountId\n$relativeRoot".toByteArray(Charsets.UTF_8))
    val id: String get() = digest(encode())

    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output ->
            output.writeUTF("vega-maven-install-plan-v1")
            output.writeUTF(taskId)
            output.writeUTF(projectId)
            output.writeLong(generation)
            output.writeUTF(mountId)
            output.writeUTF(relativeRoot)
            output.writeUTF(previousLockSha256.orEmpty())
            val lockBytes = lock.encode()
            require(lockBytes.size in 1..1024 * 1024) { "MAVEN_LOCK_SIZE_INVALID" }
            output.writeInt(lockBytes.size)
            output.write(lockBytes)
        }
    }.toByteArray()

    companion object {
        private val DIGEST = Regex("[0-9a-f]{64}")
        fun decode(bytes: ByteArray): MavenDependencyInstallPlan {
            require(bytes.size in 1..2 * 1024 * 1024) { "MAVEN_INSTALL_PLAN_SIZE_INVALID" }
            val plan = DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                require(input.readUTF() == "vega-maven-install-plan-v1") { "MAVEN_INSTALL_PLAN_FORMAT_INVALID" }
                val task = input.readUTF()
                val project = input.readUTF()
                val generation = input.readLong()
                val mount = input.readUTF()
                val root = input.readUTF()
                val previous = input.readUTF().ifEmpty { null }
                val count = input.readInt()
                require(count in 1..1024 * 1024 && count == input.available()) { "MAVEN_INSTALL_PLAN_FORMAT_INVALID" }
                val lockBytes = ByteArray(count)
                input.readFully(lockBytes)
                MavenDependencyInstallPlan(task, project, generation, mount, root, previous,
                    MavenDependencyLock.decode(lockBytes))
            }
            require(plan.encode().contentEquals(bytes)) { "MAVEN_INSTALL_PLAN_NONCANONICAL" }
            return plan
        }
        fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it.toInt() and 255) }

        /** Access errors and oversized/directory targets are not interpreted as absence. */
        fun previousDigest(fs: WorkspaceFs, lockPath: String): String? {
            if (!fs.exists(lockPath)) return null
            val stat = fs.stat(lockPath) ?: error("MAVEN_INSTALL_LOCK_STAT_FAILED")
            require(!stat.isDirectory && stat.size in 0..1024L * 1024L) { "MAVEN_INSTALL_LOCK_UNSAFE" }
            val bytes = fs.openInputStream(lockPath).use { input ->
                val output = ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    if (count == 0) continue
                    require(count <= 1024 * 1024 - output.size()) { "MAVEN_INSTALL_LOCK_UNSAFE" }
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            }
            require(bytes.size.toLong() == stat.size) { "MAVEN_INSTALL_LOCK_CHANGED" }
            return digest(bytes)
        }
    }
}

/** Distinct private issuance receipts prevent download_file from forging an install plan. */
internal class MavenDependencyPlanStore(privateRoot: File) {
    private val root = File(privateRoot, "p7-maven-plans").absoluteFile
    private val receipts: TaskArtifactAccess
    init {
        require(privateRoot.canonicalFile == privateRoot.absoluteFile && privateRoot.isDirectory) {
            "MAVEN_INSTALL_STORE_UNSAFE"
        }
        if (!root.exists()) require(root.mkdir()) { "MAVEN_INSTALL_STORE_CREATE_FAILED" }
        require(root.canonicalFile == root && Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "MAVEN_INSTALL_STORE_UNSAFE"
        }
        receipts = TaskArtifactAccess(File(root, "issued"))
    }

    fun issue(plan: MavenDependencyInstallPlan): String {
        require(root.canonicalFile == root && Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "MAVEN_INSTALL_STORE_UNSAFE"
        }
        val bytes = plan.encode()
        val target = File(root, "${plan.id}.plan")
        require(!Files.isSymbolicLink(target.toPath())) { "MAVEN_INSTALL_PLAN_UNSAFE" }
        if (!target.exists()) {
            val staged = Files.createTempFile(root.toPath(), ".plan-", ".tmp")
            try {
                FileOutputStream(staged.toFile()).use { output ->
                    require(staged.toFile().setReadOnly()) { "MAVEN_INSTALL_PLAN_READ_ONLY_FAILED" }
                    output.write(bytes)
                    output.fd.sync()
                }
                Files.move(staged, target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } finally {
                staged.toFile().setWritable(true)
                Files.deleteIfExists(staged)
            }
        }
        require(readBytes(plan.id).contentEquals(bytes)) { "MAVEN_INSTALL_PLAN_DIGEST_MISMATCH" }
        receipts.record(plan.taskId, plan.projectId, plan.generation,
            VerifiedArtifact("artifact://sha256/${plan.id}", plan.id, bytes.size.toLong()))
        return plan.id
    }

    fun load(context: TaskContext, id: String): MavenDependencyInstallPlan {
        require(id.matches(Regex("[0-9a-f]{64}"))) { "MAVEN_INSTALL_PLAN_ID_INVALID" }
        receipts.requireAccess(context.taskId, context.projectId, context.projectGeneration, "artifact://sha256/$id")
        val plan = MavenDependencyInstallPlan.decode(readBytes(id))
        require(plan.taskId == context.taskId && plan.projectId == context.projectId &&
            plan.generation == context.projectGeneration) { "MAVEN_INSTALL_CONTEXT_MISMATCH" }
        return plan
    }

    private fun readBytes(id: String): ByteArray {
        require(id.matches(Regex("[0-9a-f]{64}"))) { "MAVEN_INSTALL_PLAN_ID_INVALID" }
        val file = File(root, "$id.plan")
        require(root.canonicalFile == root && file.canonicalFile == file &&
            Files.isRegularFile(file.toPath(), LinkOption.NOFOLLOW_LINKS)) { "MAVEN_INSTALL_PLAN_UNSAFE" }
        val bytes = Files.newByteChannel(file.toPath(), setOf(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)).use { channel ->
            require(channel.size() in 1..2L * 1024L * 1024L) { "MAVEN_INSTALL_PLAN_SIZE_INVALID" }
            val buffer = java.nio.ByteBuffer.allocate(channel.size().toInt())
            while (buffer.hasRemaining()) require(channel.read(buffer) >= 0) { "MAVEN_INSTALL_PLAN_TRUNCATED" }
            buffer.array()
        }
        require(MavenDependencyInstallPlan.digest(bytes) == id) { "MAVEN_INSTALL_PLAN_DIGEST_MISMATCH" }
        return bytes
    }
}
