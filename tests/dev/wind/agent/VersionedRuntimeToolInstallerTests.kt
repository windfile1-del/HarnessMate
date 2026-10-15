package dev.wind.agent

import dev.wind.agent.artifact.ArtifactProvenance
import dev.wind.agent.artifact.ArtifactStoreException
import dev.wind.agent.artifact.TaskArtifactAccess
import dev.wind.agent.artifact.VerifiedArtifactStore
import dev.wind.agent.runtime.RuntimeToolId
import dev.wind.agent.runtime.RuntimeToolInstallSpec
import dev.wind.agent.runtime.VersionedRuntimeToolInstaller
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object VersionedRuntimeToolInstallerTests {
    @JvmStatic
    fun main(args: Array<String>) {
        val base = args.firstOrNull()?.let(Path::of) ?: Path.of(System.getProperty("java.io.tmpdir"))
        val root = Files.createTempDirectory(base, "p7-runtime-install-")
        try {
            run(root)
            println("VersionedRuntimeToolInstallerTests: OK")
        } finally {
            Files.walkFileTree(root, object : java.nio.file.SimpleFileVisitor<Path>() {
                override fun visitFile(file: Path, attrs: java.nio.file.attribute.BasicFileAttributes): java.nio.file.FileVisitResult {
                    if (attrs.isRegularFile && !Files.isSymbolicLink(file)) file.toFile().setWritable(true)
                    Files.deleteIfExists(file)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
                override fun postVisitDirectory(dir: Path, error: java.io.IOException?): java.nio.file.FileVisitResult {
                    Files.deleteIfExists(dir)
                    return java.nio.file.FileVisitResult.CONTINUE
                }
            })
        }
    }

    private fun run(root: Path) {
        val store = VerifiedArtifactStore(root.resolve("cas").toFile(), 1_000_000)
        val access = TaskArtifactAccess(root.resolve("grants").toFile())
        val runtime = Files.createDirectory(root.resolve("runtime"))
        var allowStagedV2 = true
        var allowActiveV2 = true
        var cancelAfterActiveV2 = false
        var cancellationRequested = false
        val stagedProbe: (Path, RuntimeToolInstallSpec) -> Boolean = { dir, spec ->
            if (spec.providerVersion == "2.0" && !allowStagedV2) false
            else Files.readString(dir.resolve(spec.entry)) == "provider-${spec.providerVersion}" &&
                !dir.resolve(spec.entry).toFile().canWrite()
        }
        val activeProbe: (Path, RuntimeToolInstallSpec) -> Boolean = { dir, spec ->
            if (spec.providerVersion == "2.0" && cancelAfterActiveV2) cancellationRequested = true
            (spec.providerVersion != "2.0" || allowActiveV2) && stagedProbe(dir, spec)
        }
        val installer = VersionedRuntimeToolInstaller(runtime, store, access, 23, "x86_64",
            stagedProbe, activeProbe)
        val (first, firstSpec) = importPackage(store, access, "1.0")
        check(firstSpec.copy(licenseId = "BSD-3-Clause OR EDL-1.0").licenseId.contains(" OR "))
        check(runCatching { firstSpec.copy(toolId = RuntimeToolId.SHELL) }.exceptionOrNull() is IllegalArgumentException)
        expect("ARTIFACT_TASK_ACCESS_DENIED") {
            installer.install("other-task", "project", 1, first.uri, firstSpec)
        }
        expect("RUNTIME_INSTALL_CANCELLED") {
            installer.install("task", "project", 1, first.uri, firstSpec) { true }
        }
        expect("RUNTIME_INSTALL_ARTIFACT_MISMATCH") {
            installer.install("task", "project", 1, first.uri, firstSpec.copy(artifactSha256 = "0".repeat(64)))
        }
        expect("RUNTIME_INSTALL_INCOMPATIBLE") {
            installer.install("task", "project", 1, first.uri, firstSpec.copy(minApi = 24))
        }
        expect("RUNTIME_INSTALL_INCOMPATIBLE") {
            installer.install("task", "project", 1, first.uri, firstSpec.copy(abi = "arm64-v8a"))
        }
        val installedFirst = installer.install("task", "project", 1, first.uri, firstSpec)
        val pointerBefore = Files.readString(runtime.resolve("git/active.properties"))
        val firstActive = installer.active(RuntimeToolId.GIT)
        check(installedFirst.spec.providerVersion == "1.0" && firstActive?.spec == firstSpec) {
            "first active=$firstActive pointer=$pointerBefore"
        }
        check(installer.install("task", "project", 1, first.uri, firstSpec).directory == installedFirst.directory)

        val (second, secondSpec) = importPackage(store, access, "2.0")
        expect("RUNTIME_INSTALL_MANIFEST_MISMATCH") {
            installer.install("task", "project", 1, second.uri,
                secondSpec.copy(capabilityVersion = "unexpected"))
        }
        allowStagedV2 = false
        expect("RUNTIME_INSTALL_PROBE_FAILED") {
            installer.install("task", "project", 1, second.uri, secondSpec)
        }
        check(installer.active(RuntimeToolId.GIT)?.spec == firstSpec)
        allowStagedV2 = true
        allowActiveV2 = false
        expect("RUNTIME_INSTALL_ACTIVE_PROBE_FAILED") {
            installer.install("task", "project", 1, second.uri, secondSpec)
        }
        check(installer.active(RuntimeToolId.GIT)?.spec == firstSpec)
        check(!Files.exists(runtime.resolve("git/versions/2.0/${second.sha256}")))
        allowActiveV2 = true
        cancelAfterActiveV2 = true
        expect("RUNTIME_INSTALL_CANCELLED") {
            installer.install("task", "project", 1, second.uri, secondSpec) { cancellationRequested }
        }
        check(installer.active(RuntimeToolId.GIT)?.spec == firstSpec)
        cancellationRequested = false
        cancelAfterActiveV2 = false
        val installedSecond = installer.install("task", "project", 1, second.uri, secondSpec)
        check(installer.active(RuntimeToolId.GIT)?.spec == secondSpec)

        val reopened = VersionedRuntimeToolInstaller(runtime, store, access, 23, "x86_64",
            stagedProbe, activeProbe)
        check(reopened.active(RuntimeToolId.GIT)?.spec == secondSpec)
        val code = installedSecond.directory.resolve("provider.jar")
        code.toFile().setWritable(true)
        Files.write(code, "tampered".toByteArray())
        check(reopened.active(RuntimeToolId.GIT)?.spec == firstSpec)
        check(reopened.active(RuntimeToolId.GIT)?.directory == installedFirst.directory)
    }

    private fun importPackage(store: VerifiedArtifactStore, access: TaskArtifactAccess,
        version: String): Pair<dev.wind.agent.artifact.VerifiedArtifact, RuntimeToolInstallSpec> {
        val fields = linkedMapOf(
            "schema" to "1", "tool_id" to "GIT", "provider_id" to "test-git",
            "provider_version" to version, "capability_version" to "test-$version",
            "license_id" to "Apache-2.0", "min_api" to "23", "max_api" to "35",
            "abi" to "any", "entry" to "provider.jar"
        )
        val manifest = fields.entries.joinToString("") { "${it.key}=${it.value}\n" }.toByteArray()
        val zip = ByteArrayOutputStream().also { sink ->
            ZipOutputStream(sink).use { output ->
                output.putNextEntry(ZipEntry("vega-tool.properties"))
                output.write(manifest)
                output.closeEntry()
                output.putNextEntry(ZipEntry("provider.jar"))
                output.write("provider-$version".toByteArray())
                output.closeEntry()
            }
        }.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(zip)
            .joinToString("") { "%02x".format(it.toInt() and 255) }
        val artifact = store.importQuarantined(ByteArrayInputStream(zip), digest, zip.size.toLong(),
            ArtifactProvenance("https://example.invalid/$version.zip"))
        access.record("task", "project", 1, artifact)
        return artifact to RuntimeToolInstallSpec(RuntimeToolId.GIT, "test-git", version,
            "test-$version", "Apache-2.0", 23, 35, "any", "provider.jar", digest)
    }

    private fun expect(code: String, block: () -> Unit) {
        val failure = runCatching(block).exceptionOrNull()
        check(failure is ArtifactStoreException && failure.message == code) {
            "expected $code, got $failure"
        }
    }
}
