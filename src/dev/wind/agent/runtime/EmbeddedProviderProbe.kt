package dev.wind.agent.runtime

import dev.wind.agent.CancellationToken
import dev.wind.agent.build.EcjCompileRequest
import dev.wind.agent.build.EcjJavaCompiler
import dev.wind.agent.build.D8CompileRequest
import dev.wind.agent.build.D8DexCompiler
import dev.wind.agent.build.EmbeddedKotlinCompiler
import dev.wind.agent.build.KotlinCompileRequest
import dev.wind.agent.build.L8RuntimeDexCompiler
import dev.wind.agent.build.ReflectionD8Provider
import dev.wind.agent.build.ReflectionEcjCompilerProvider
import dev.wind.agent.build.ReflectionL8Provider
import dev.wind.agent.runtime.vcs.JGitGitService
import java.io.DataInputStream
import java.io.File
import java.util.UUID

/**
 * Functional checks in caller-owned private storage, never a user worktree.
 * Class presence alone is insufficient to advertise a packaged provider.
 */
class EmbeddedProviderProbe(
    private val classLoader: ClassLoader =
        Thread.currentThread().contextClassLoader ?: EmbeddedProviderProbe::class.java.classLoader,
    private val probeRoot: File? = null,
    private val bootClasspath: List<File> = emptyList(),
    private val kotlinProviderClassLoader: ClassLoader? = null,
    private val kotlinStdlibJar: File? = null
) {
    @Volatile
    var lastFailure: String? = null
        private set

    fun probe(descriptor: EmbeddedToolDescriptor): Boolean {
        lastFailure = null
        if (descriptor != EmbeddedToolCatalog.C1A1[descriptor.toolId]) return fail("PROBE_DESCRIPTOR_NOT_CATALOGUED")
        val root = probeRoot ?: return fail("PROBE_ROOT_MISSING")
        var scratch: File? = null
        return try {
            check(root.canonicalFile == root.absoluteFile) { "PROBE_ROOT_LINK" }
            check(root.isDirectory || root.mkdirs()) { "PROBE_ROOT_UNAVAILABLE" }
            val directory = File(root, "probe-${UUID.randomUUID()}")
            check(directory.mkdir()) { "PROBE_DIRECTORY_UNAVAILABLE" }
            scratch = directory
            when (descriptor.toolId) {
                RuntimeToolId.GIT -> gitProbe(directory)
                RuntimeToolId.JAVA_COMPILE -> javaProbe(directory)
                RuntimeToolId.KOTLIN_COMPILE -> kotlinProbe(directory)
                RuntimeToolId.DEX -> dexProbe(directory)
                else -> fail("PROBE_TOOL_UNSUPPORTED")
            }
        } catch (failure: Exception) {
            fail(describeFailure(failure))
        } catch (failure: LinkageError) {
            fail(describeFailure(failure))
        } finally {
            scratch?.deleteRecursively()
        }
    }

    private fun javaProbe(directory: File): Boolean {
        // ART has no JDK modules: a packaged platform bootstrap is mandatory.
        if (System.getProperty("java.vm.name", "").contains("Dalvik", ignoreCase = true) &&
            bootClasspath.isEmpty()) return false
        if (bootClasspath.any { !it.isFile }) return false
        val source = File(directory, "VegaCompilerProbe.java").apply {
            writeText("public final class VegaCompilerProbe { public static int value() { return 42; } }")
        }
        val output = File(directory, "classes")
        val compiler = EcjJavaCompiler(ReflectionEcjCompilerProvider(classLoader))
        val result = compiler.compile(EcjCompileRequest(listOf(source), output,
            bootClasspath = bootClasspath), CancellationToken())
        if (!result.success) return false
        val artifact = File(output, "VegaCompilerProbe.class")
        if (!artifact.isFile || artifact.length() < 10) return false
        return DataInputStream(artifact.inputStream()).use {
            it.readInt() == 0xCAFEBABE.toInt() && it.readUnsignedShort() == 0 && it.readUnsignedShort() == 61
        }
    }

    private fun gitProbe(directory: File): Boolean {
        val type = Class.forName("org.eclipse.jgit.api.Git", true, classLoader)
        val init = type.getMethod("init").invoke(null)
        init.javaClass.getMethod("setDirectory", File::class.java).invoke(init, directory)
        val git = init.javaClass.getMethod("call").invoke(init)
        try {
            val repo = type.getMethod("getRepository").invoke(git)
            val config = repo.javaClass.getMethod("getConfig").invoke(repo)
            val set = config.javaClass.getMethod("setString", String::class.java,
                String::class.java, String::class.java, String::class.java)
            set.invoke(config, "user", null, "name", "Vega provider probe")
            set.invoke(config, "user", null, "email", "probe@vega.invalid")
            config.javaClass.getMethod("save").invoke(config)
            val service = JGitGitService(classLoader)
            File(directory, "probe.txt").writeText("probe\n")
            if ("probe.txt" !in service.status(directory).untracked) return false
            service.add(directory, listOf("probe.txt"))
            if ("probe.txt" !in service.status(directory).added) return false
            val commit = service.commit(directory, "provider probe")
            if (service.log(directory, 1).singleOrNull()?.id != commit.id) return false
            File(directory, "probe.txt").appendText("modified\n")
            return "probe.txt" in service.status(directory).changed &&
                service.diff(directory).any { "probe.txt" in it }
        } finally {
            type.getMethod("close").invoke(git)
        }
    }

    private fun kotlinProbe(directory: File): Boolean {
        val providerLoader = kotlinProviderClassLoader ?: return false
        val stdlib = kotlinStdlibJar?.takeIf { it.isFile } ?: return false
        if (bootClasspath.isEmpty() || bootClasspath.any { !it.isFile }) return fail("KOTLIN_BOOTCLASSPATH_UNAVAILABLE")

        val compilerClass = Class.forName("org.jetbrains.kotlin.cli.jvm.K2JVMCompiler", true, providerLoader)
        val troveClass = Class.forName("gnu.trove.TObjectHash", true, providerLoader)
        val desugaredFilesClass = Class.forName("j$.nio.file.Files", true, providerLoader)
        if (compilerClass.classLoader !== providerLoader || troveClass.classLoader !== providerLoader ||
            desugaredFilesClass.classLoader !== providerLoader) return fail("KOTLIN_PROVIDER_CLASSLOADER_MISMATCH")

        val source = File(directory, "VegaKotlinProviderProbe.kt").apply {
            writeText("package dev.wind.agent.providerprobe\nclass VegaKotlinProviderProbe { fun answer(): Int = 42 }\n")
        }
        val output = File(directory, "kotlin-classes")
        val result = EmbeddedKotlinCompiler(providerLoader).compile(
            KotlinCompileRequest(
                sources = listOf(source),
                outputDirectory = output,
                bootClasspath = bootClasspath,
                classpath = listOf(stdlib),
                target = "17",
                timeoutMs = 45_000
            ),
            CancellationToken()
        )
        if (!result.success) {
            val messages = result.diagnostics.joinToString(" | ") { "${it.code}:${it.message}" }
            return fail(messages.ifBlank { "KOTLIN_PROVIDER_COMPILE_PROBE_FAILED" }.take(4096))
        }
        val compiledClass = File(output, "dev/wind/agent/providerprobe/VegaKotlinProviderProbe.class")
        if (!compiledClass.isFile || compiledClass.length() < 10L) return fail("KOTLIN_PROVIDER_CLASS_OUTPUT_MISSING")
        return DataInputStream(compiledClass.inputStream()).use {
            it.readInt() == 0xCAFEBABE.toInt() && it.readUnsignedShort() == 0 && it.readUnsignedShort() == 61
        }
    }

    private fun dexProbe(directory: File): Boolean {
        val androidJar = bootClasspath.singleOrNull()?.takeIf { it.isFile } ?: return false
        val d8Provider = ReflectionD8Provider(classLoader)
        val l8Provider = ReflectionL8Provider(classLoader)
        if (!d8Provider.available || !l8Provider.available) return false
        if (!isPinnedVersion(d8Provider.version) || !isPinnedVersion(l8Provider.version)) return false

        val source = File(directory, "VegaDexProbe.java").apply {
            writeText("public final class VegaDexProbe { public static int value() { return 42; } }")
        }
        val classes = File(directory, "classes")
        val compiled = EcjJavaCompiler(ReflectionEcjCompilerProvider(classLoader)).compile(
            EcjCompileRequest(listOf(source), classes, bootClasspath = listOf(androidJar)),
            CancellationToken()
        )
        if (!compiled.success || !File(classes, "VegaDexProbe.class").isFile) return false

        val output = File(directory, "dex")
        val dexed = D8DexCompiler(d8Provider).compile(
            D8CompileRequest(
                classDirectories = listOf(classes),
                libraryFiles = listOf(androidJar),
                outputDirectory = output,
                minApi = 23
            ),
            CancellationToken()
        )
        if (!dexed.success) return false
        val classesDex = File(output, "classes.dex")
        if (!classesDex.isFile || classesDex.length() < 8L) return false
        return classesDex.inputStream().use { input ->
            input.read() == 'd'.code && input.read() == 'e'.code && input.read() == 'x'.code &&
                input.read() == '\n'.code && input.read() in '0'.code..'9'.code &&
                input.read() in '0'.code..'9'.code && input.read() in '0'.code..'9'.code && input.read() == 0
        }
    }

    private fun isPinnedVersion(version: String?): Boolean =
        version?.matches(Regex("^8\\.2\\.33(?:\\s.*)?$")) == true

    private fun fail(reason: String): Boolean {
        lastFailure = reason
        return false
    }

    private fun describeFailure(failure: Throwable): String = buildString {
        generateSequence(failure as Throwable?) { it.cause }.take(4).forEach { cause ->
            if (isNotEmpty()) append(" <- ")
            append(cause.javaClass.name).append(':').append(cause.message.orEmpty())
            cause.stackTrace.take(10).forEach { frame -> append(" @ ").append(frame.toString()) }
        }
    }.take(2048)

    operator fun invoke(descriptor: EmbeddedToolDescriptor): Boolean = probe(descriptor)
}
