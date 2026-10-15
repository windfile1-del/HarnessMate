package dev.wind.agent.runtime

import dev.wind.agent.build.BuildBackendId
import dev.wind.agent.build.BuildDiagnostic
import dev.wind.agent.build.BuildDiagnosticSeverity
import dev.wind.agent.build.BuildDiagnosticSource
import java.io.File
import dev.wind.agent.CancellationToken
import dev.wind.agent.build.EcjCompileRequest
import dev.wind.agent.build.EcjJavaCompiler

data class EmbeddedJavaCompileRequest(
    val sources: List<File>,
    val outputDirectory: File,
    val requireDex: Boolean = false
) {
    init {
        require(sources.isNotEmpty()) { "EMBEDDED_BUILD_SOURCES_EMPTY" }
        require(sources.all { it.isFile }) { "EMBEDDED_BUILD_SOURCE_MISSING" }
    }
}

enum class DexStatus {
    NOT_REQUESTED,
    UNAVAILABLE,
    PRODUCED
}

data class EmbeddedJavaCompileResult(
    val backend: BuildBackendId = BuildBackendId.EMBEDDED,
    val compiled: Boolean,
    val requireDex: Boolean = false,
    val dexStatus: DexStatus = if (requireDex) DexStatus.UNAVAILABLE else DexStatus.NOT_REQUESTED,
    val diagnostics: List<BuildDiagnostic> = emptyList(),
    val classFiles: List<File> = emptyList()
) {
    /** Compatibility view for callers that only need the old boolean. */
    val dexed: Boolean get() = dexStatus == DexStatus.PRODUCED

    val success: Boolean get() = compiled && (!requireDex || dexStatus == DexStatus.PRODUCED)
}

/** Compatibility name retained for callers; compilation now uses packaged ECJ only. */
class EmbeddedJavaCompileSpike(
    private val compiler: EcjJavaCompiler = EcjJavaCompiler()
) {
    fun compile(request: EmbeddedJavaCompileRequest): EmbeddedJavaCompileResult {
        val result = compiler.compile(EcjCompileRequest(request.sources, request.outputDirectory),
            CancellationToken())
        val dexStatus = if (request.requireDex) DexStatus.UNAVAILABLE else DexStatus.NOT_REQUESTED
        val diagnostics = result.diagnostics + if (result.success && request.requireDex) {
            listOf(runtimeDiagnostic("EMBEDDED_DEX_UNAVAILABLE",
                "Embedded D8 output is not implemented; class presence is not dex output"))
        } else emptyList()
        return EmbeddedJavaCompileResult(
            compiled = result.success,
            requireDex = request.requireDex,
            dexStatus = dexStatus,
            diagnostics = diagnostics,
            classFiles = if (result.success) request.outputDirectory.walkTopDown()
                .filter { it.isFile && it.extension == "class" }.toList() else emptyList()
        )
    }
}

/**
 * Compatibility adapter for the P6.4-A Java spike. The canonical provider
 * boundary is [dev.wind.agent.build.BuildBackend]; this class is deliberately
 * not another BuildBackend implementation and is not selected by the build
 * registry. It delegates to the same ECJ provider as the build pipeline, with no
 * desktop javac fallback.
 */
class EmbeddedAndroidBackend(
    private val spike: EmbeddedJavaCompileSpike = EmbeddedJavaCompileSpike()
) {
    val id: BuildBackendId = BuildBackendId.EMBEDDED

    fun compileJava(request: EmbeddedJavaCompileRequest): EmbeddedJavaCompileResult =
        spike.compile(request)
}

private fun runtimeDiagnostic(code: String, message: String) = BuildDiagnostic(
    severity = BuildDiagnosticSeverity.ERROR,
    code = code,
    message = message,
    source = BuildDiagnosticSource.TOOLCHAIN
)
