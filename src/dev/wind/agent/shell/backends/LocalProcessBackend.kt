package dev.wind.agent.shell.backends

import dev.wind.agent.CancellationToken
import dev.wind.agent.shell.ShellBackend
import dev.wind.agent.shell.ShellPolicy
import dev.wind.agent.shell.ShellRequest
import dev.wind.agent.shell.ShellResult
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
class LocalProcessBackend : ShellBackend {

    override val id: String = "local"
    override val displayName: String = "Local Staging Process (Development Fallback)"

    override fun isAvailable(): Boolean = true

    override fun execute(request: ShellRequest, target: dev.wind.agent.shell.ExecutionTarget, token: CancellationToken): ShellResult {
        val workDir = File(target.projectPath)
        val start = System.currentTimeMillis()
        ShellPolicy.validateRequest(request)

        val cmd = mutableListOf<String>()
        cmd.add(request.executable)
        cmd.addAll(request.args)

        val pb = ProcessBuilder(cmd)
        pb.directory(workDir)

        // Set isolated environment
        val env = pb.environment()
        env.clear()
        env["HOME"] = workDir.absolutePath
        env["TMPDIR"] = workDir.absolutePath
        env["LANG"] = "en_US.UTF-8"
        env["PATH"] = System.getenv("PATH") ?: "/usr/bin:/bin"
        env["VEGA_WORKSPACE"] = workDir.absolutePath
        for ((k, v) in request.env) {
            if (!k.contains("KEY") && !k.contains("TOKEN") && !k.contains("SECRET")) {
                env[k] = v
            }
        }

        var process: Process? = null
        try {
            process = pb.start()
            val p = process ?: throw IllegalStateException("Failed to launch process")

            val stdoutBuf = StringBuilder()
            val stderrBuf = StringBuilder()

            val outThread = Thread({
                try {
                    BufferedReader(InputStreamReader(p.inputStream, Charsets.UTF_8)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (stdoutBuf.length < ShellPolicy.MAX_OUTPUT_BYTES) {
                                stdoutBuf.append(line).append('\n')
                            }
                        }
                    }
                } catch (_: Exception) {}
            }, "shell-stdout")

            val errThread = Thread({
                try {
                    BufferedReader(InputStreamReader(p.errorStream, Charsets.UTF_8)).use { reader ->
                        var line: String?
                        while (reader.readLine().also { line = it } != null) {
                            if (stderrBuf.length < ShellPolicy.MAX_OUTPUT_BYTES) {
                                stderrBuf.append(line).append('\n')
                            }
                        }
                    }
                } catch (_: Exception) {}
            }, "shell-stderr")

            outThread.start()
            errThread.start()

            var timedOut = false
            var cancelled = false

            val deadline = System.currentTimeMillis() + request.timeoutMs
            while (p.isAlive) {
                if (token.isCancelled) {
                    cancelled = true
                    p.destroyForcibly()
                    break
                }
                if (System.currentTimeMillis() > deadline) {
                    timedOut = true
                    p.destroyForcibly()
                    break
                }
                Thread.sleep(50L)
            }

            p.waitFor(3, TimeUnit.SECONDS)
            outThread.join(500L)
            errThread.join(500L)

            val exit = if (p.isAlive) null else p.exitValue()
            val elapsed = System.currentTimeMillis() - start

            return ShellResult(
                exitCode = exit,
                stdout = ShellPolicy.clampOutput(stdoutBuf.toString().trimEnd()),
                stderr = ShellPolicy.clampOutput(stderrBuf.toString().trimEnd()),
                elapsedMs = elapsed,
                timedOut = timedOut,
                cancelled = cancelled
            )
        } catch (e: Exception) {
            process?.destroyForcibly()
            return ShellResult(
                exitCode = null,
                stdout = "",
                stderr = "",
                elapsedMs = System.currentTimeMillis() - start,
                error = e.message ?: e.javaClass.simpleName
            )
        }
    }

    override fun detectCapabilities(): Map<String, String> {
        val capabilities = mutableMapOf<String, String>()
        val commonBinaries = listOf("git", "python", "python3", "node", "npm", "java", "javac", "gradle", "clang", "cargo")
        for (bin in commonBinaries) {
            val version = probeVersion(bin)
            if (version != null) {
                capabilities[bin] = version
            }
        }
        return capabilities
    }

    private fun probeVersion(binary: String): String? {
        return try {
            val pb = ProcessBuilder(listOf(binary, "--version"))
            pb.redirectErrorStream(true)
            val p = pb.start()
            val line = BufferedReader(InputStreamReader(p.inputStream)).readLine()
            p.waitFor(1, TimeUnit.SECONDS)
            line?.trim()
        } catch (_: Exception) {
            null
        }
    }
}
