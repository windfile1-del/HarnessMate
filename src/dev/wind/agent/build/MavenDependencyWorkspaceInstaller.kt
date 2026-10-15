package dev.wind.agent.build

import dev.wind.agent.CancellationToken
import dev.wind.agent.artifact.VerifiedArtifactStore
import dev.wind.agent.artifact.ZipDirectoryInspector
import dev.wind.agent.workspace.WorkspaceFs
import java.io.File

/** SAF is never converted to File. Only JAR bytes live in the private cache. */
internal class MavenDependencyWorkspaceInstaller(
    private val privateRoot: File, private val cas: VerifiedArtifactStore
) {
    fun install(plan: MavenDependencyInstallPlan, fs: WorkspaceFs, token: CancellationToken,
        beforeSideEffect: () -> Unit,
        /** Must journal mkdir/write, recheck preconditions, and verify the lease at each write. */
        publish: (MavenDependencyInstallPlan, ByteArray) -> Unit
    ) {
        token.throwIfCancelled()
        require(fs.isConfigured()) { "MAVEN_INSTALL_WORKSPACE_UNAVAILABLE" }
        if (plan.relativeRoot.isNotEmpty()) require(fs.stat(plan.relativeRoot)?.isDirectory == true) {
            "MAVEN_INSTALL_TARGET_NOT_DIRECTORY"
        }
        require(MavenDependencyInstallPlan.previousDigest(fs, plan.lockPath) == plan.previousLockSha256) {
            "MAVEN_INSTALL_LOCK_CONFLICT"
        }
        plan.lock.modules.forEach { module ->
            token.throwIfCancelled()
            cas.withVerifiedChannel(module.jarUri) { channel, artifact ->
                require(artifact.byteSize == module.jarBytes) { "MAVEN_CACHE_SIZE_MISMATCH" }
                val zip = ZipDirectoryInspector().inspect(channel)
                require(!zip.apkCandidate) { "MAVEN_INSTALL_APK_NOT_DEPENDENCY" }
            }
        }
        MavenProjectDependencyInstaller(privateRoot, cas, File(privateRoot, "p7-maven-cache"))
            .materialize(plan.lock, token, verifyModelGraph = true, beforeSideEffect = beforeSideEffect)
        token.throwIfCancelled()
        beforeSideEffect()
        token.throwIfCancelled()
        require(MavenDependencyInstallPlan.previousDigest(fs, plan.lockPath) == plan.previousLockSha256) {
            "MAVEN_INSTALL_LOCK_CONFLICT"
        }
        publish(plan, plan.lock.encode())
    }
}
