package dev.wind.agent.artifact

import dev.wind.agent.capability.TaskContext
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
 * Immutable plan for installing an APK.
 */
data class ApkInstallPlan(
    val taskId: String,
    val projectId: String,
    val generation: Long,
    val artifactUri: String,
    val artifactSha256: String,
    val packageName: String,
    val versionCode: Long,
    val versionName: String,
    val label: String,
    val byteSize: Long
) {
    init {
        require(taskId.isNotBlank() && taskId.length <= 256 && taskId != "default-task") {
            "APK_INSTALL_CONTEXT_INVALID"
        }
        require(artifactSha256.matches(Regex("[0-9a-f]{64}"))) { "APK_INSTALL_DIGEST_INVALID" }
        require(packageName.matches(Regex("""[a-zA-Z0-9_]+(?:.[a-zA-Z0-9_]+)+"""))) {
            "APK_INSTALL_PACKAGE_NAME_INVALID"
        }
    }

    val id: String get() = digest(encode())

    fun encode(): ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { out ->
            out.writeUTF("vega-apk-install-plan-v1")
            out.writeUTF(taskId)
            out.writeUTF(projectId)
            out.writeLong(generation)
            out.writeUTF(artifactUri)
            out.writeUTF(artifactSha256)
            out.writeUTF(packageName)
            out.writeLong(versionCode)
            out.writeUTF(versionName)
            out.writeUTF(label)
            out.writeLong(byteSize)
        }
    }.toByteArray()

    companion object {
        fun decode(bytes: ByteArray): ApkInstallPlan {
            require(bytes.size in 1..2 * 1024 * 1024) { "APK_INSTALL_PLAN_SIZE_INVALID" }
            DataInputStream(ByteArrayInputStream(bytes)).use { input ->
                val header = input.readUTF()
                require(header == "vega-apk-install-plan-v1") { "APK_INSTALL_PLAN_HEADER_INVALID" }
                return ApkInstallPlan(
                    taskId = input.readUTF(),
                    projectId = input.readUTF(),
                    generation = input.readLong(),
                    artifactUri = input.readUTF(),
                    artifactSha256 = input.readUTF(),
                    packageName = input.readUTF(),
                    versionCode = input.readLong(),
                    versionName = input.readUTF(),
                    label = input.readUTF(),
                    byteSize = input.readLong()
                )
            }
        }

        private fun digest(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256")
            .digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}

/**
 * Private plan store for ApkInstallPlan instances.
 */
class ApkInstallPlanStore(privateRoot: File) {
    private val root = File(privateRoot, "p7-apk-plans").absoluteFile
    private val receipts: TaskArtifactAccess

    init {
        require(privateRoot.canonicalFile == privateRoot.absoluteFile && privateRoot.isDirectory) {
            "APK_INSTALL_STORE_UNSAFE"
        }
        if (!root.exists()) require(root.mkdir()) { "APK_INSTALL_STORE_CREATE_FAILED" }
        require(root.canonicalFile == root && Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "APK_INSTALL_STORE_UNSAFE"
        }
        receipts = TaskArtifactAccess(File(root, "issued"))
    }

    fun issue(plan: ApkInstallPlan): String {
        require(root.canonicalFile == root && Files.isDirectory(root.toPath(), LinkOption.NOFOLLOW_LINKS)) {
            "APK_INSTALL_STORE_UNSAFE"
        }
        val bytes = plan.encode()
        val target = File(root, "${plan.id}.plan")
        require(!Files.isSymbolicLink(target.toPath())) { "APK_INSTALL_PLAN_UNSAFE" }
        if (!target.exists()) {
            val staged = Files.createTempFile(root.toPath(), ".plan-", ".tmp")
            try {
                FileOutputStream(staged.toFile()).use { output ->
                    require(staged.toFile().setReadOnly()) { "APK_INSTALL_PLAN_READ_ONLY_FAILED" }
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

    fun load(context: TaskContext, id: String): ApkInstallPlan {
        require(id.matches(Regex("[0-9a-f]{64}"))) { "APK_INSTALL_PLAN_ID_INVALID" }
        val target = File(root, "$id.plan")
        require(Files.isRegularFile(target.toPath(), LinkOption.NOFOLLOW_LINKS)) { "APK_INSTALL_PLAN_NOT_FOUND" }
        val bytes = Files.readAllBytes(target.toPath())
        val plan = ApkInstallPlan.decode(bytes)
        require(plan.id == id) { "APK_INSTALL_PLAN_FORGED" }
        return plan
    }
}
