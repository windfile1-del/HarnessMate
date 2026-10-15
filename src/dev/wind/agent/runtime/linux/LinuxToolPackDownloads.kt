package dev.wind.agent.runtime.linux

import android.content.Context
import dev.wind.agent.artifact.VerifiedArtifact
import dev.wind.agent.artifact.VerifiedArtifactStore
import dev.wind.agent.artifact.TaskArtifactAccess
import java.io.File

/** Explicit Settings selection reuses P7 CAS; a download digest alone grants no publisher trust. */
object LinuxToolPackDownloads {
    private fun store(context: Context) = VerifiedArtifactStore(File(context.noBackupFilesDir.canonicalFile, "p7-artifacts"))
    fun available(context: Context): List<VerifiedArtifact> = store(context).listArtifacts()
        .filter { it.byteSize in 1..(1024L * 1024 * 1024 + 65536) }

    fun installSelected(context: Context, artifactUri: String): String {
        val family = importSelected(context, artifactUri)
        LinuxRuntimeInstaller.installExtension(context, family)
        return family
    }
    fun importSelected(context: Context, artifactUri: String): String {
        LinuxInstallControl.check()
        return store(context).openVerified(artifactUri).use { LinuxToolPackStore.importPack(context, it) }
    }

    /** Task read grants permit trusted quarantine import, not runtime activation. */
    fun importForTask(context: Context, taskId: String, projectId: String, generation: Long, artifactUri: String): String {
        TaskArtifactAccess(File(context.noBackupFilesDir.canonicalFile, "p7-artifact-access"))
            .requireAccess(taskId, projectId, generation, artifactUri)
        return importSelected(context, artifactUri)
    }
}
