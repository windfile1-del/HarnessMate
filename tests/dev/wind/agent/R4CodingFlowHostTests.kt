package dev.wind.agent

import dev.wind.agent.coding.*
import java.io.File
import java.security.MessageDigest

/** P4 repair loop with actual JDK processes, diagnostics and revision invalidation. No Android or APK. */
object R4CodingFlowHostTests {
    @JvmStatic fun main(args: Array<String>) {
        val root = File(args[0], "coding-flow").apply { mkdirs() }
        val source = File(root, "Main.java")
        val output = File(root, "build").apply { mkdirs() }
        val java = args[1]
        val javac = args[2]
        val bad = "public class Main { public static void main(String[] args) { System.out.println(\"ok\") } }"
        source.writeText(bad)
        val profile = ProjectProfile(kind = ProjectKind.GENERIC, root = "execution://main", confidence = Confidence.HIGH)
        val compile = ValidationCommand("compile", "Real javac", listOf(javac, "-J-Duser.language=en", "-J-Duser.country=US",
            "-encoding", "UTF-8", "-d", output.absolutePath, "Main.java"), 30_000)
        val test = ValidationCommand("test", "Execute generated class", listOf(java, "-cp", output.absolutePath, "Main"), 30_000)
        val plan = ValidationPlan(fastChecks = listOf(compile), finalChecks = listOf(compile.copy(id = "build"), test),
            confidence = Confidence.HIGH, reason = "Explicit real JDK checks")
        val host = object : CodingRuntimeHost {
            override fun detectProject(projectPath: String?) = profile
            override fun mirrorDigest(): String = MessageDigest.getInstance("SHA-256").digest(source.readBytes())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
            override fun now() = System.currentTimeMillis()
            override fun runCommand(command: ValidationCommand, token: CancellationToken): ValidationRunResult {
                token.throwIfCancelled()
                val started = System.nanoTime()
                val process = ProcessBuilder(command.argv).directory(root).redirectErrorStream(true).start()
                val text = process.inputStream.bufferedReader().readText()
                val code = process.waitFor()
                return ValidationRunResult(code, text, "", (System.nanoTime() - started) / 1_000_000)
            }
        }
        val runtime = CodingTaskRuntime(host)
        runtime.start("r4-flow", "project", 1, restore = false)
        runtime.attachProfile(profile, plan)
        runtime.beforeTool("build")
        val failed = runtime.runFastChecks(CancellationToken())
        check(failed != null && !failed.ok && runtime.currentReport()!!.errors.isNotEmpty()) { "actual compiler error must become diagnostics" }
        check(runtime.currentReport()!!.errors.any { it.line == 1 && it.message.contains("expected") }) { "preserve javac line and message" }
        check(runtime.requestFinish() is FinishDecision.Allow) { "a failed build must not suppress the reply" }
        source.writeText(bad.replace("\"ok\") }", "\"ok\"); }"))
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)
        check(runtime.runFastChecks(CancellationToken()) == null) { "actual repaired source compiles" }
        val stamp = runtime.runFinalVerification(CancellationToken())
        check(stamp.passedChecks.containsAll(listOf("build", "test"))) { "real compile and run both produce final evidence" }
        check(runtime.verificationValid(plan.fingerprint())) { "current revision is verified" }
        check(File(output, "Main.class").length() > 0) { "generated class exists" }
        source.appendText("\n// new revision\n")
        runtime.afterTool("execution_edit_file", ok = true, declaredMutation = true)
        check(!runtime.verificationValid(plan.fingerprint())) { "new source invalidates old verification" }
        check(runtime.requestFinish() is FinishDecision.Allow) { "unverified revision may still explain its status" }
        println("PASS R4CodingFlowHostTests: 9 checks; actual javac failure -> repair -> compile/run -> source invalidation")
    }
}
