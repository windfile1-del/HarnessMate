package dev.wind.agent.build

import android.content.Context
import dev.wind.agent.CancellationToken
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption

/** Offline-only, explicitly selected cache. Never treats a SAF URI as File. */
class EmbeddedMavenDependencySupport(val cacheRoot: File) {
    init {
        require(cacheRoot.absoluteFile == cacheRoot.canonicalFile) { "MAVEN_CACHE_PATH_UNSAFE" }
    }

    internal fun resolve(project: EmbeddedProjectModel, module: EmbeddedModule,
        token: CancellationToken): LockedMavenBuildInputs {
        token.throwIfCancelled()
        require(module.dependencies.all { it.projectPath == null && it.configuration in setOf("implementation", "api") }) {
            "MAVEN_BUILD_CONFIGURATION_UNSUPPORTED"
        }
        val moduleLock = File(module.directory, ".vega/maven-dependencies.lock")
        val owner = if (Files.exists(moduleLock.toPath(), LinkOption.NOFOLLOW_LINKS)) module.directory else project.root
        val lock = MavenDependencyLockFile(owner).read() ?: error("DEPENDENCY_UNAVAILABLE")
        val roots = module.dependencies.map { it.notation }
        val artifacts = MavenLockedArtifactStore(owner, cacheRoot).resolveGraph(roots)
            ?: error("MAVEN_BUILD_LOCK_ROOTS_MISMATCH")
        token.throwIfCancelled()
        return LockedMavenBuildInputs(owner, roots, lock.fingerprint, artifacts.map { it.file })
    }

    internal fun verify(inputs: LockedMavenBuildInputs, token: CancellationToken): List<File> {
        token.throwIfCancelled()
        val lock = MavenDependencyLockFile(inputs.lockOwner).read() ?: error("DEPENDENCY_UNAVAILABLE")
        require(lock.fingerprint == inputs.lockSha256) { "MAVEN_BUILD_LOCK_CHANGED" }
        val files = MavenLockedArtifactStore(inputs.lockOwner, cacheRoot).resolveGraph(inputs.roots)
            ?.map { it.file } ?: error("DEPENDENCY_UNAVAILABLE")
        require(files == inputs.jars) { "MAVEN_BUILD_INPUTS_CHANGED" }
        token.throwIfCancelled()
        return files
    }

    companion object {
        /** Canonicalize the trusted framework boundary once; lower-level paths remain strict. */
        fun packaged(context: Context): EmbeddedMavenDependencySupport =
            EmbeddedMavenDependencySupport(File(context.noBackupFilesDir.canonicalFile, "p7-maven-cache"))
    }
}

internal data class LockedMavenBuildInputs(
    val lockOwner: File, val roots: List<String>, val lockSha256: String, val jars: List<File>
)
