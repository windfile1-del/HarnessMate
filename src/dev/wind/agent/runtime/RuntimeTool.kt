package dev.wind.agent.runtime

/** Stable capability names exposed to Agent tools instead of Unix paths. */
enum class RuntimeToolId {
    SHELL,
    PYTHON,
    NODE,
    JAVA_COMPILE,
    KOTLIN_COMPILE,
    GIT,
    AAPT2,
    DEX,
    APK_SIGN
}

enum class RuntimeToolImplementation {
    IN_PROCESS,
    NATIVE_EXECUTABLE,
    SHELL_ESCAPE_HATCH
}

data class RuntimeTool(
    val id: RuntimeToolId,
    val implementation: RuntimeToolImplementation,
    val executable: String? = null,
    val capabilityVersion: String = "p6.4"
) {
    init {
        if (implementation == RuntimeToolImplementation.NATIVE_EXECUTABLE ||
            implementation == RuntimeToolImplementation.SHELL_ESCAPE_HATCH
        ) {
            require(!executable.isNullOrBlank()) { "RUNTIME_TOOL_EXECUTABLE_MISSING" }
        }
        if (implementation == RuntimeToolImplementation.IN_PROCESS) {
            require(executable == null) { "RUNTIME_TOOL_IN_PROCESS_HAS_EXECUTABLE" }
        }
    }
}

private val NATIVE_TOOL_IDS = setOf(
    RuntimeToolId.PYTHON,
    RuntimeToolId.NODE,
    RuntimeToolId.AAPT2
)

/**
 * Maps logical runtime capabilities to an implementation.  The mapping is
 * intentionally deterministic and side-effect free: resolving a tool never
 * installs packages, probes the network, or forks a process.
 */
class RuntimeToolResolver(
    private val nativeExecutables: Map<RuntimeToolId, String> = emptyMap(),
    private val shellExecutable: String = "/system/bin/sh",
    private val catalog: EmbeddedToolCatalog = EmbeddedToolCatalog.EMPTY,
    private val providerProbe: (EmbeddedToolDescriptor) -> Boolean = { false }
) {
    private fun providerAvailable(descriptor: EmbeddedToolDescriptor): Boolean = try {
        providerProbe(descriptor)
    } catch (_: Throwable) {
        false
    }

    companion object {
        /** One functional inventory snapshot. Create again after install/restart. */
        fun packaged(probeRoot: java.io.File, bootClasspath: List<java.io.File> = emptyList(),
            requireJavaBootstrap: Boolean = false,
            applicationClassLoader: ClassLoader = Thread.currentThread().contextClassLoader
                ?: RuntimeToolResolver::class.java.classLoader,
            kotlinProviderClassLoader: ClassLoader? = null,
            kotlinStdlibJar: java.io.File? = null): RuntimeToolResolver {
            val probe = EmbeddedProviderProbe(
                classLoader = applicationClassLoader,
                probeRoot = probeRoot,
                bootClasspath = bootClasspath,
                kotlinProviderClassLoader = kotlinProviderClassLoader,
                kotlinStdlibJar = kotlinStdlibJar
            )
            val catalog = EmbeddedToolCatalog.C1A1
            val verified = catalog.entries.filter {
                !(requireJavaBootstrap && it.toolId == RuntimeToolId.JAVA_COMPILE && bootClasspath.isEmpty()) &&
                    probe.probe(it)
            }.toSet()
            return RuntimeToolResolver(catalog = catalog, providerProbe = { it in verified })
        }
    }

    fun resolve(id: RuntimeToolId): RuntimeTool = when (id) {
        // These are the first ART-native targets.  They are only resolvable
        // when their descriptor is catalogued and its provider is usable.
        RuntimeToolId.GIT,
        RuntimeToolId.JAVA_COMPILE,
        RuntimeToolId.KOTLIN_COMPILE,
        RuntimeToolId.DEX,
        RuntimeToolId.APK_SIGN -> {
            val descriptor = catalog[id]
            check(descriptor != null && providerAvailable(descriptor)) {
                "RUNTIME_TOOL_UNAVAILABLE:${id.name}"
            }
            RuntimeTool(
                id,
                RuntimeToolImplementation.IN_PROCESS,
                capabilityVersion = descriptor.capabilityVersion
            )
        }

        RuntimeToolId.SHELL -> RuntimeTool(
            id,
            RuntimeToolImplementation.SHELL_ESCAPE_HATCH,
            shellExecutable
        )

        RuntimeToolId.PYTHON,
        RuntimeToolId.NODE,
        RuntimeToolId.AAPT2 -> RuntimeTool(
            id,
            RuntimeToolImplementation.NATIVE_EXECUTABLE,
            nativeExecutables[id]
                ?: throw IllegalStateException("RUNTIME_TOOL_UNAVAILABLE:${id.name}")
        )
    }

    fun resolve(command: RuntimeCommandSpec): RuntimeTool =
        command.toolId?.let(::resolve)
            ?: RuntimeTool(
                RuntimeToolId.SHELL,
                RuntimeToolImplementation.SHELL_ESCAPE_HATCH,
                command.executable
            )

    fun available(): Set<RuntimeToolId> = buildSet {
        add(RuntimeToolId.SHELL)
        catalog.entries
            .filter { providerAvailable(it) }
            .mapTo(this) { it.toolId }
        nativeExecutables
            .filterKeys { it in NATIVE_TOOL_IDS }
            .filterValues { it.isNotBlank() }
            .keys
            .forEach { add(it) }
    }
}
