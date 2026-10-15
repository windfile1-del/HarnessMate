package dev.wind.agent

import dev.wind.agent.runtime.RuntimeBinding
import dev.wind.agent.runtime.DexStatus
import dev.wind.agent.runtime.EmbeddedJavaCompileRequest
import dev.wind.agent.runtime.EmbeddedJavaCompileSpike
import dev.wind.agent.runtime.EmbeddedProviderProbe
import dev.wind.agent.runtime.EmbeddedToolCatalog
import dev.wind.agent.runtime.EmbeddedToolDescriptor
import dev.wind.agent.runtime.RuntimeCommandSpec
import dev.wind.agent.runtime.RuntimeProviderId
import dev.wind.agent.runtime.RuntimeToolId
import dev.wind.agent.runtime.RuntimeToolImplementation
import dev.wind.agent.runtime.RuntimeToolResolver
import dev.wind.agent.build.ReflectionEcjCompilerProvider
import dev.wind.agent.build.EcjJavaCompiler
import dev.wind.agent.build.PackagedKotlinRuntimePins
import dev.wind.agent.runtime.vcs.JGitGitService
import java.io.File
import java.nio.file.Files
import org.json.JSONObject

/** Production-boundary contract tests for the provider-neutral P6.0 model. */
object RuntimeContractTests {
    @JvmStatic
    fun main(args: Array<String>) {
        bindingRoundTrip()
        generationAndWorkspacePin()
        reservedEnvironmentFailsClosed()
        commandDefaultsAreStable()
        logicalToolResolutionIsNotExecutableResolution()
        embeddedProviderCatalogAndProbe()
        descriptorValidationAdversarialCases()
        catalogInvariantAdversarialCases()
        resolverProbeAdversarialCases()
        embeddedJavaCompileSpikeProducesClassFiles()
        jgitBoundaryFailsClosedWithoutExecutableFallback()
        println("RuntimeContractTests: OK")
    }

    private fun embeddedProviderCatalogAndProbe() {
        val expectedGit = EmbeddedToolDescriptor(
            toolId = RuntimeToolId.GIT,
            providerId = "jgit-core",
            providerVersion = "7.7.1.202607240634-r",
            licenseId = "BSD-3-Clause / EDL-1.0",
            artifactSha256 = "591abcc84955a6ad3363ec904bda79cf14427706efb4e06ffeb7bc3601503980",
            capabilityVersion = "p6.4-c1-jgit-7.7.1"
        )
        val expectedJava = EmbeddedToolDescriptor(
            toolId = RuntimeToolId.JAVA_COMPILE,
            providerId = "eclipse-ecj",
            providerVersion = "3.46.100-android1",
            licenseId = "EPL-2.0",
            artifactSha256 = "acda57158970a77135daf6e676e5208d0515f7c51f009f4a4370fc8186280254",
            capabilityVersion = "p6.4-c1-ecj-3.46.100-android1"
        )
        val expectedKotlin = EmbeddedToolDescriptor(
            toolId = RuntimeToolId.KOTLIN_COMPILE,
            providerId = "kotlin-compiler-embeddable",
            providerVersion = "1.9.20-android3",
            licenseId = "Apache-2.0 / LGPL-2.1",
            artifactSha256 = "3d20ba3e83f04c0c81d38bc33124f81083278535d91266b89fa9b675954d5222",
            capabilityVersion = "p6-sc1b2b-kotlin-1.9.20-android3"
        )
        check(expectedKotlin.artifactSha256 == PackagedKotlinRuntimePins.MANIFEST_SHA256) {
            "Kotlin catalog digest diverged from the packaged runtime manifest pin"
        }
        val expectedDex = EmbeddedToolDescriptor(
            toolId = RuntimeToolId.DEX,
            providerId = "google-r8",
            providerVersion = "8.2.33",
            licenseId = "BSD-3-Clause / Apache-2.0 / BSD / MIT / Public Domain",
            artifactSha256 = "80c39a28bb66b64efc232d68ae554d59fe3838808738b4fe584884691cb1e5a3",
            capabilityVersion = "p6-sc1-r8-8.2.33"
        )
        val catalog = EmbeddedToolCatalog.C1A1
        check(catalog.entries == listOf(expectedDex, expectedGit, expectedJava, expectedKotlin)) {
            "C1A1 catalog did not contain the exact candidate descriptors"
        }

        val probeDirectory = Files.createTempDirectory("vega-provider-probe").toFile().canonicalFile
        try {
        val probe = EmbeddedProviderProbe(probeRoot = probeDirectory)
        check(probe.probe(expectedGit)) { "real JGit provider probe failed" }
        check(probe.probe(expectedJava)) { "real ECJ provider probe failed" }
        check(!probe.probe(expectedKotlin)) { "Kotlin provider passed without its isolated runtime loader" }
        check(!probe.probe(expectedGit.copy(providerVersion = "changed"))) {
            "changed JGit providerVersion passed the exact provider probe"
        }
        check(!probe.probe(expectedJava.copy(
            artifactSha256 = "b".repeat(64)
        ))) { "changed ECJ artifactSha256 passed the exact provider probe" }

        val resolver = RuntimeToolResolver(
            catalog = catalog,
            providerProbe = probe::probe
        )
        check(RuntimeToolId.GIT in resolver.available())
        check(RuntimeToolId.JAVA_COMPILE in resolver.available())
        val git = resolver.resolve(RuntimeToolId.GIT)
        check(git.implementation == RuntimeToolImplementation.IN_PROCESS)
        check(git.executable == null)
        check(git.capabilityVersion == expectedGit.capabilityVersion)
        val java = resolver.resolve(RuntimeToolId.JAVA_COMPILE)
        check(java.implementation == RuntimeToolImplementation.IN_PROCESS)
        check(java.executable == null)
        check(java.capabilityVersion == expectedJava.capabilityVersion)
        check(RuntimeToolId.KOTLIN_COMPILE !in resolver.available())
        check(RuntimeToolId.DEX !in resolver.available())
        check(RuntimeToolId.APK_SIGN !in resolver.available())

        val defaultAvailable = RuntimeToolResolver().available()
        check(RuntimeToolId.GIT !in defaultAvailable)
        check(RuntimeToolId.JAVA_COMPILE !in defaultAvailable)
        check(RuntimeToolId.KOTLIN_COMPILE !in defaultAvailable)
        check(RuntimeToolId.DEX !in defaultAvailable)
        check(RuntimeToolId.APK_SIGN !in defaultAvailable)
        check(JGitGitService().available) { "default JGit provider probe was unavailable" }
        check(ReflectionEcjCompilerProvider().available) {
            "default ECJ provider probe was unavailable"
        }
        check(probeDirectory.listFiles()?.isEmpty() == true) { "probe left scratch files" }
        check(!EmbeddedProviderProbe().probe(expectedGit)) { "probe without private storage succeeded" }
        check(!EmbeddedProviderProbe(object : ClassLoader(null) {}, probeDirectory).probe(expectedGit))
        check(!EmbeddedProviderProbe(object : ClassLoader(null) {}, probeDirectory).probe(expectedJava))
        val unavailableRoot = File(probeDirectory, "not-a-directory").apply { writeText("sentinel") }
        check(!EmbeddedProviderProbe(probeRoot = unavailableRoot).probe(expectedGit))
        check(unavailableRoot.readText() == "sentinel")
        val packaged = RuntimeToolResolver.packaged(probeDirectory)
        check(RuntimeToolId.GIT in packaged.available())
        check(RuntimeToolId.JAVA_COMPILE in packaged.available())
        val vmName = System.getProperty("java.vm.name")
        try {
            System.setProperty("java.vm.name", "Dalvik")
            check(!probe.probe(expectedJava)) { "ART probe borrowed the host JDK bootstrap" }
        } finally {
            if (vmName == null) System.clearProperty("java.vm.name") else System.setProperty("java.vm.name", vmName)
        }
        } finally {
            probeDirectory.deleteRecursively()
        }
    }

    private fun bindingRoundTrip() {
        val original = RuntimeBinding(
            RuntimeProviderId.TERMUX, "0123456789abcdef01234567", "project-a", 4L,
            "termux:0.118", protocolVersion = 2, layoutVersion = 1
        )
        val restored = RuntimeBinding.fromJson(JSONObject(original.toJson().toString()))
        check(original == restored) { "runtime binding JSON round trip changed identity" }
    }

    private fun generationAndWorkspacePin() {
        val b = RuntimeBinding(RuntimeProviderId.VEGA_COMPANION, "0123456789abcdef01234567", "p", 8L, "instance-a")
        check(b.matches("p", 8L, "0123456789abcdef01234567"))
        check(!b.matches("p", 7L, "0123456789abcdef01234567"))
        check(!b.matches("p", 8L, "fedcba9876543210fedcba98"))
    }

    private fun reservedEnvironmentFailsClosed() {
        var rejected = false
        try {
            RuntimeCommandSpec("python3", env = mapOf("VEGA_RUNTIME_WORKSPACE_BASE" to "escape"))
        } catch (e: IllegalArgumentException) {
            rejected = e.message == "RUNTIME_RESERVED_ENV"
        }
        check(rejected) { "reserved runtime environment was accepted" }
    }

    private fun commandDefaultsAreStable() {
        val spec = RuntimeCommandSpec("git", args = listOf("--version"))
        check(spec.timeoutMs == 60_000L)
        check(!spec.network && spec.networkDomains.isEmpty())
    }

    private fun logicalToolResolutionIsNotExecutableResolution() {
        val defaultResolver = RuntimeToolResolver(
            nativeExecutables = mapOf(RuntimeToolId.PYTHON to "python3")
        )
        check(RuntimeToolId.GIT !in defaultResolver.available())
        check(RuntimeToolId.JAVA_COMPILE !in defaultResolver.available())
        check(RuntimeToolId.KOTLIN_COMPILE !in defaultResolver.available())
        check(RuntimeToolId.DEX !in defaultResolver.available())
        check(RuntimeToolId.APK_SIGN !in defaultResolver.available())

        val gitDescriptor = EmbeddedToolDescriptor(
            toolId = RuntimeToolId.GIT,
            providerId = "synthetic-git",
            providerVersion = "0.0.0-test",
            licenseId = "TEST",
            artifactSha256 = "a".repeat(64),
            capabilityVersion = "c0-test"
        )
        val catalog = EmbeddedToolCatalog(listOf(gitDescriptor))
        val resolver = RuntimeToolResolver(
            nativeExecutables = mapOf(RuntimeToolId.PYTHON to "python3"),
            catalog = catalog,
            providerProbe = { it == gitDescriptor }
        )
        val git = resolver.resolve(RuntimeToolId.GIT)
        check(git.implementation == RuntimeToolImplementation.IN_PROCESS)
        check(git.executable == null)
        check(git.capabilityVersion == gitDescriptor.capabilityVersion)
        check(RuntimeToolId.GIT in resolver.available())
        check(RuntimeToolId.DEX !in resolver.available())

        val failedProbeResolver = RuntimeToolResolver(
            catalog = catalog,
            providerProbe = { false }
        )
        check(RuntimeToolId.GIT !in failedProbeResolver.available())
        var failedProbeRejected = false
        try {
            failedProbeResolver.resolve(RuntimeToolId.GIT)
        } catch (error: IllegalStateException) {
            failedProbeRejected = error.message == "RUNTIME_TOOL_UNAVAILABLE:GIT"
        }
        check(failedProbeRejected) { "failed provider probe did not fail closed" }

        val nativeFallbackCannotAdvertiseGit = RuntimeToolResolver(
            nativeExecutables = mapOf(RuntimeToolId.GIT to "git")
        )
        check(RuntimeToolId.GIT !in nativeFallbackCannotAdvertiseGit.available()) {
            "native executable incorrectly advertised as an in-process Git capability"
        }
        var unavailableRejected = false
        try {
            resolver.resolve(RuntimeToolId.DEX)
        } catch (error: IllegalStateException) {
            unavailableRejected = error.message == "RUNTIME_TOOL_UNAVAILABLE:DEX"
        }
        check(unavailableRejected) { "unavailable in-process tool did not fail closed" }
        val python = resolver.resolve(RuntimeToolId.PYTHON)
        check(python.implementation == RuntimeToolImplementation.NATIVE_EXECUTABLE)
        check(python.executable == "python3")
        check(resolver.resolve(RuntimeCommandSpec("echo")).implementation ==
            RuntimeToolImplementation.SHELL_ESCAPE_HATCH)

        val javaDescriptor = EmbeddedToolDescriptor(
            toolId = RuntimeToolId.JAVA_COMPILE,
            providerId = "synthetic-java",
            providerVersion = "0.0.0-test",
            licenseId = "TEST",
            artifactSha256 = "b".repeat(64),
            capabilityVersion = "c0-java"
        )
        val orderedCatalog = EmbeddedToolCatalog(listOf(javaDescriptor, gitDescriptor))
        check(orderedCatalog.entries.map { it.toolId } ==
            listOf(RuntimeToolId.GIT, RuntimeToolId.JAVA_COMPILE)) {
            "catalog enumeration was not deterministic"
        }
    }

    /** C0B-A: every descriptor field and tool-id boundary fails closed. */
    private fun descriptorValidationAdversarialCases() {
        expectIllegalArgument(
            "EMBEDDED_TOOL_CATALOG_TOOL_UNSUPPORTED:SHELL"
        ) { syntheticDescriptor(RuntimeToolId.SHELL) }
        expectIllegalArgument(
            "EMBEDDED_TOOL_CATALOG_TOOL_UNSUPPORTED:PYTHON"
        ) { syntheticDescriptor(RuntimeToolId.PYTHON) }
        expectIllegalArgument(
            "EMBEDDED_TOOL_CATALOG_TOOL_UNSUPPORTED:AAPT2"
        ) { syntheticDescriptor(RuntimeToolId.AAPT2) }

        expectIllegalArgument(
            "EMBEDDED_TOOL_PROVIDER_ID_MISSING"
        ) { syntheticDescriptor(providerId = "") }
        expectIllegalArgument(
            "EMBEDDED_TOOL_PROVIDER_VERSION_MISSING"
        ) { syntheticDescriptor(providerVersion = "") }
        expectIllegalArgument(
            "EMBEDDED_TOOL_LICENSE_ID_MISSING"
        ) { syntheticDescriptor(licenseId = "") }
        expectIllegalArgument(
            "EMBEDDED_TOOL_CAPABILITY_VERSION_MISSING"
        ) { syntheticDescriptor(capabilityVersion = "") }
        expectIllegalArgument(
            "EMBEDDED_TOOL_ARTIFACT_SHA256_INVALID"
        ) { syntheticDescriptor(artifactSha256 = "a".repeat(63)) }
        expectIllegalArgument(
            "EMBEDDED_TOOL_ARTIFACT_SHA256_INVALID"
        ) { syntheticDescriptor(artifactSha256 = "a".repeat(65)) }
        expectIllegalArgument(
            "EMBEDDED_TOOL_ARTIFACT_SHA256_INVALID"
        ) { syntheticDescriptor(artifactSha256 = "z".repeat(64)) }

        val uppercaseSha = "ABCDEF0123456789".repeat(4)
        val uppercaseDescriptor = syntheticDescriptor(artifactSha256 = uppercaseSha)
        check(uppercaseDescriptor.artifactSha256 == uppercaseSha) {
            "valid uppercase SHA-256 was normalized or rejected"
        }
    }

    /** C0B-B: catalog uniqueness, copying, immutability and ordering. */
    private fun catalogInvariantAdversarialCases() {
        val first = syntheticDescriptor(RuntimeToolId.GIT)
        val duplicate = syntheticDescriptor(RuntimeToolId.GIT, capabilityVersion = "duplicate")
        expectIllegalArgument(
            "EMBEDDED_TOOL_CATALOG_DUPLICATE:GIT"
        ) { EmbeddedToolCatalog(listOf(first, duplicate)) }

        val supplied = mutableListOf(first)
        val copied = EmbeddedToolCatalog(supplied)
        supplied.clear()
        supplied += syntheticDescriptor(RuntimeToolId.DEX)
        check(copied.entries.map { it.toolId } == listOf(RuntimeToolId.GIT)) {
            "catalog changed after its supplied iterable was mutated"
        }

        var listMutationRejected = false
        try {
            @Suppress("UNCHECKED_CAST")
            val mutableEntries = copied.entries as MutableList<EmbeddedToolDescriptor>
            mutableEntries.add(syntheticDescriptor(RuntimeToolId.JAVA_COMPILE))
        } catch (error: UnsupportedOperationException) {
            listMutationRejected = true
        }
        check(listMutationRejected) { "catalog.entries was externally mutable" }

        val shuffled = EmbeddedToolCatalog(
            listOf(
                syntheticDescriptor(RuntimeToolId.KOTLIN_COMPILE),
                syntheticDescriptor(RuntimeToolId.GIT),
                syntheticDescriptor(RuntimeToolId.APK_SIGN),
                syntheticDescriptor(RuntimeToolId.JAVA_COMPILE),
                syntheticDescriptor(RuntimeToolId.DEX)
            )
        )
        check(shuffled.entries.map { it.toolId } == listOf(
            RuntimeToolId.APK_SIGN,
            RuntimeToolId.DEX,
            RuntimeToolId.GIT,
            RuntimeToolId.JAVA_COMPILE,
            RuntimeToolId.KOTLIN_COMPILE
        )) { "catalog ordering changed from deterministic toolId.name ordering" }

        check(EmbeddedToolCatalog.EMPTY.entries.isEmpty()) {
            "EMPTY catalog unexpectedly contains entries"
        }
        RuntimeToolId.values().forEach { id ->
            check(EmbeddedToolCatalog.EMPTY[id] == null) {
                "EMPTY catalog returned a descriptor for ${id.name}"
            }
        }
    }

    /** C0B-C: provider probes and native mappings cannot bypass the catalog. */
    private fun resolverProbeAdversarialCases() {
        val git = syntheticDescriptor(RuntimeToolId.GIT, capabilityVersion = "c0b-exact")
        val gitCatalog = EmbeddedToolCatalog(listOf(git))
        val throwingProbeResolver = RuntimeToolResolver(
            catalog = gitCatalog,
            providerProbe = { throw AssertionError("synthetic probe failure") }
        )
        check(RuntimeToolId.GIT !in throwingProbeResolver.available()) {
            "throwing provider probe advertised GIT"
        }
        expectUnavailable(throwingProbeResolver, RuntimeToolId.GIT)

        val orphan = syntheticDescriptor(RuntimeToolId.JAVA_COMPILE)
        val orphanProbeResolver = RuntimeToolResolver(
            catalog = gitCatalog,
            providerProbe = { it == orphan }
        )
        check(RuntimeToolId.JAVA_COMPILE !in orphanProbeResolver.available()) {
            "probe success for an uncatalogued descriptor advertised JAVA_COMPILE"
        }
        expectUnavailable(orphanProbeResolver, RuntimeToolId.JAVA_COMPILE)

        val inProcessIds = listOf(
            RuntimeToolId.GIT,
            RuntimeToolId.JAVA_COMPILE,
            RuntimeToolId.KOTLIN_COMPILE,
            RuntimeToolId.DEX,
            RuntimeToolId.APK_SIGN
        )
        val nativeMappings = inProcessIds.associateWith { "native-${it.name.lowercase()}" }
        val nativeOnlyResolver = RuntimeToolResolver(
            nativeExecutables = nativeMappings,
            providerProbe = { true }
        )
        inProcessIds.forEach { id ->
            check(id !in nativeOnlyResolver.available()) {
                "native executable mapping advertised in-process ${id.name}"
            }
            expectUnavailable(nativeOnlyResolver, id)
        }

        val blankNativeResolver = RuntimeToolResolver(
            nativeExecutables = mapOf(
                RuntimeToolId.PYTHON to "",
                RuntimeToolId.NODE to " ",
                RuntimeToolId.AAPT2 to ""
            )
        )
        check(RuntimeToolId.PYTHON !in blankNativeResolver.available())
        check(RuntimeToolId.NODE !in blankNativeResolver.available())
        check(RuntimeToolId.AAPT2 !in blankNativeResolver.available())

        val successful = RuntimeToolResolver(
            catalog = gitCatalog,
            providerProbe = { it == git }
        ).resolve(RuntimeToolId.GIT)
        check(successful.id == RuntimeToolId.GIT)
        check(successful.implementation == RuntimeToolImplementation.IN_PROCESS)
        check(successful.executable == null)
        check(successful.capabilityVersion == "c0b-exact") {
            "catalog capabilityVersion was not propagated exactly"
        }
    }

    private fun syntheticDescriptor(
        toolId: RuntimeToolId = RuntimeToolId.GIT,
        providerId: String = "synthetic-${toolId.name}",
        providerVersion: String = "0.0.0-test",
        licenseId: String = "TEST",
        artifactSha256: String = "a".repeat(64),
        capabilityVersion: String = "c0-${toolId.name}"
    ): EmbeddedToolDescriptor = EmbeddedToolDescriptor(
        toolId = toolId,
        providerId = providerId,
        providerVersion = providerVersion,
        licenseId = licenseId,
        artifactSha256 = artifactSha256,
        capabilityVersion = capabilityVersion
    )

    private fun expectIllegalArgument(expectedMessage: String, action: () -> Unit) {
        var actualMessage: String? = null
        try {
            action()
        } catch (error: IllegalArgumentException) {
            actualMessage = error.message
        }
        check(actualMessage == expectedMessage) {
            "expected IllegalArgumentException $expectedMessage, got $actualMessage"
        }
    }

    private fun expectUnavailable(resolver: RuntimeToolResolver, id: RuntimeToolId) {
        var actualMessage: String? = null
        try {
            resolver.resolve(id)
        } catch (error: IllegalStateException) {
            actualMessage = error.message
        }
        check(actualMessage == "RUNTIME_TOOL_UNAVAILABLE:${id.name}") {
            "expected unavailable ${id.name}, got $actualMessage"
        }
    }

    /**
     * The compatibility entry point uses ECJ, including when javac is present.
     * Android/D8 remain explicit capability boundaries.
     */
    private fun embeddedJavaCompileSpikeProducesClassFiles() {
        val root = Files.createTempDirectory("vega-java-spike").toFile()
        try {
            val source = File(root, "Spike.java").apply {
                writeText("public final class Spike { public static int answer() { return 42; } }")
            }
            val output = File(root, "classes")
            val result = EmbeddedJavaCompileSpike().compile(
                EmbeddedJavaCompileRequest(listOf(source), output)
            )
            check(result.compiled && result.success) {
                "Java compile spike failed: ${result.diagnostics}"
            }
            check(result.classFiles.any { it.name == "Spike.class" }) {
                "Java compile spike emitted no Spike.class"
            }
            val absent = EmbeddedJavaCompileSpike(EcjJavaCompiler(
                ReflectionEcjCompilerProvider(object : ClassLoader(null) {})))
                .compile(EmbeddedJavaCompileRequest(listOf(source), File(root, "absent")))
            check(!absent.success && !absent.compiled && absent.classFiles.isEmpty()) {
                "missing ECJ silently fell back to host javac"
            }
            check(absent.diagnostics.any { it.code == "ECJ_UNAVAILABLE" })

            val dexRequired = EmbeddedJavaCompileSpike().compile(
                EmbeddedJavaCompileRequest(listOf(source), File(root, "dex-required"), requireDex = true)
            )
            check(dexRequired.compiled) { "D8 boundary must not hide a successful Java compile" }
            check(dexRequired.dexStatus == DexStatus.UNAVAILABLE) {
                "D8 status was not truthful: ${dexRequired.dexStatus}"
            }
            check(!dexRequired.dexed && !dexRequired.success) {
                "requireDex reported success without produced dex output"
            }
            check(dexRequired.diagnostics.any { it.code == "EMBEDDED_DEX_UNAVAILABLE" }) {
                "missing D8 did not produce a structured diagnostic"
            }
        } finally {
            root.deleteRecursively()
        }
    }

    /**
     * Git operations must fail closed when the in-process JGit provider is not
     * packaged; no shell/git executable fallback is permitted.
     */
    private fun jgitBoundaryFailsClosedWithoutExecutableFallback() {
        val service = JGitGitService()
        val root = Files.createTempDirectory("vega-jgit-boundary").toFile()
        try {
            var invalidPathRejected = false
            try {
                service.add(root, listOf("../escape"))
            } catch (error: IllegalArgumentException) {
                invalidPathRejected = error.message == "GIT_PATH_INVALID"
            }
            check(invalidPathRejected) { "JGit path boundary accepted traversal" }

            if (!service.available) {
                var unavailableRejected = false
                try {
                    service.status(root)
                } catch (error: IllegalStateException) {
                    unavailableRejected = error.message == "GIT_JGIT_UNAVAILABLE"
                }
                check(unavailableRejected) {
                    "missing JGit did not fail closed without an executable fallback"
                }
            }
        } finally {
            root.deleteRecursively()
        }
    }
}
