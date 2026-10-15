package dev.wind.agent.build

import dev.wind.agent.CancellationToken
import java.io.File
import java.io.PrintWriter
import org.eclipse.jdt.core.compiler.CompilationProgress
import org.eclipse.jdt.internal.compiler.batch.FileSystem
import org.eclipse.jdt.internal.compiler.batch.Main

/**
 * Pinned ECJ adapter for a non-JDK platform. Java 9+ CLI bootclasspath is rejected
 * by ECJ; supply the platform via its batch environment instead. Never scan the
 * host VM's modules, extensions or implicit application classpath.
 */
internal class AndroidEcjMain(
    out: PrintWriter,
    err: PrintWriter,
    private val platform: List<File>,
    token: CancellationToken
) : Main(out, err, false, null, object : CompilationProgress() {
    override fun begin(remainingWork: Int) = Unit
    override fun done() = Unit
    override fun isCanceled(): Boolean = token.isCancelled || Thread.currentThread().isInterrupted
    override fun setTaskName(name: String?) = Unit
    override fun worked(workIncrement: Int, remainingWork: Int) = Unit
}) {
    override fun handleBootclasspath(paths: ArrayList<String>?, encoding: String?): ArrayList<FileSystem.Classpath> =
        ArrayList(platform.map { file ->
            require(file.isFile) { "ECJ_PLATFORM_MISSING" }
            checkNotNull(FileSystem.getClasspath(file.absolutePath, encoding, null)) { "ECJ_PLATFORM_INVALID" }
        })

    override fun handleClasspath(paths: ArrayList<String>?, encoding: String?): ArrayList<FileSystem.Classpath> =
        if (paths.isNullOrEmpty()) ArrayList() else super.handleClasspath(paths, encoding)

    override fun handleEndorseddirs(paths: ArrayList<String>?): ArrayList<FileSystem.Classpath> = ArrayList()
    override fun handleExtdirs(paths: ArrayList<String>?): ArrayList<FileSystem.Classpath> = ArrayList()

    fun compileCommand(commandLine: String): Boolean = compile(Main.tokenize(commandLine))
}
