package dev.wind.agent

import dev.wind.agent.build.*
import java.io.File
import java.nio.file.Files
import java.net.URLClassLoader
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

/** Real compiler tests. The two arguments explicitly supply Android platform and stdlib. */
object EmbeddedKotlinCompilerTests {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size == 2)
        val platform = File(args[0]).canonicalFile
        val stdlib = File(args[1]).canonicalFile
        val root = Files.createTempDirectory("vega-kotlin-real-").toFile()
        try {
            val kotlin = File(root, "Answer.kt").apply {
                writeText("class Answer { fun value(): Int = JavaPeer.number(); fun sdk(): Int = android.os.Build.VERSION.SDK_INT }")
            }
            val java = File(root, "JavaPeer.java").apply {
                writeText("public class JavaPeer { public static int number() { return 42; } public Answer create() { return new Answer(); } }")
            }
            fun request(output: String) = KotlinCompileRequest(listOf(kotlin), File(root, output),
                listOf(platform), listOf(stdlib), listOf(java))
            val compiler = EmbeddedKotlinCompiler()
            val mixed = compiler.compile(request("mixed"), CancellationToken())
            check(mixed.success) { mixed.diagnostics.joinToString { it.code + ": " + it.message } }
            check(File(root, "mixed/Answer.class").isFile && File(root, "mixed/JavaPeer.class").isFile)
            URLClassLoader(arrayOf(File(root, "mixed").toURI().toURL()), EmbeddedKotlinCompilerTests::class.java.classLoader).use {
                val type = it.loadClass("Answer")
                check(type.getMethod("value").invoke(type.getDeclaredConstructor().newInstance()) == 42)
                check(it.loadClass("JavaPeer").getMethod("create").invoke(it.loadClass("JavaPeer").getDeclaredConstructor().newInstance()) != null)
            }
            val stale = compiler.compile(request("mixed"), CancellationToken())
            check(stale.outcome == BuildOutcome.FAILED && stale.diagnostics.any { it.code == "KOTLIN_OUTPUT_NOT_FRESH" })
            val missing = EmbeddedKotlinCompiler(object : ClassLoader(null) {}).compile(request("absent"), CancellationToken())
            check(missing.outcome == BuildOutcome.UNSUPPORTED && !File(root, "absent").exists())
            val limited = compiler.compile(request("limited").copy(maxSourceBytes = 1), CancellationToken())
            check(limited.outcome == BuildOutcome.RESOURCE_LIMIT)
            val outputLimited = compiler.compile(request("output-limit").copy(maxOutputBytes = 1), CancellationToken())
            check(outputLimited.outcome == BuildOutcome.RESOURCE_LIMIT && !File(root, "output-limit").exists())
            val cancelledToken = CancellationToken().apply { cancel() }
            check(compiler.compile(request("cancelled"), cancelledToken).outcome == BuildOutcome.CANCELLED)
            check(!File(root, "cancelled").exists())
            val timeout = compiler.compile(request("timeout").copy(timeoutMs = 1), CancellationToken())
            check(timeout.outcome == BuildOutcome.RESOURCE_LIMIT && timeout.diagnostics.any { it.code == "KOTLIN_TIMEOUT" })
            java.writeText("public class JavaPeer { public static int number() { return missingValue; } }")
            val javaFailure = compiler.compile(request("java-failure"), CancellationToken())
            check(javaFailure.outcome == BuildOutcome.FAILED && javaFailure.diagnostics.any { it.source == BuildDiagnosticSource.JAVA })
            check(!File(root, "java-failure").exists())
            kotlin.writeText("class Large {\n" + (1..3000).joinToString("\n") { "fun f$it(): Int = $it" } + "\n}")
            val runningToken = CancellationToken()
            val canceller = Thread { Thread.sleep(100); runningToken.cancel() }.apply { start() }
            val runningCancelled = try {
                compiler.compile(request("running-cancel").copy(javaSources = emptyList()), runningToken)
            } finally { canceller.join() }
            check(runningCancelled.outcome == BuildOutcome.CANCELLED)
            check(!File(root, "running-cancel").exists())
            kotlin.writeText("class Small { fun value(): Int = 42 }")
            val entered = CountDownLatch(1)
            val release = CountDownLatch(1)
            val blockingLoader = object : ClassLoader(EmbeddedKotlinCompilerTests::class.java.classLoader) {
                override fun loadClass(name: String, resolve: Boolean): Class<*> {
                    if (name == "org.jetbrains.kotlin.cli.common.messages.MessageCollector") {
                        entered.countDown()
                        check(release.await(20, TimeUnit.SECONDS))
                    }
                    return super.loadClass(name, resolve)
                }
            }
            val executor = Executors.newFixedThreadPool(2)
            try {
                val owner = executor.submit<BuildResult> {
                    EmbeddedKotlinCompiler(blockingLoader).compile(request("lock-owner").copy(javaSources = emptyList()), CancellationToken())
                }
                check(entered.await(10, TimeUnit.SECONDS))
                val waiting = executor.submit<BuildResult> {
                    compiler.compile(request("lock-timeout").copy(javaSources = emptyList(), timeoutMs = 50), CancellationToken())
                }
                check(waiting.get(5, TimeUnit.SECONDS).outcome == BuildOutcome.RESOURCE_LIMIT)
                release.countDown()
                check(owner.get(15, TimeUnit.SECONDS).success)
            } finally {
                release.countDown()
                executor.shutdownNow()
                check(executor.awaitTermination(10, TimeUnit.SECONDS))
            }
            kotlin.writeText("class Broken { fun invalid(): Int = \"text\" }")
            val broken = compiler.compile(request("broken").copy(javaSources = emptyList()), CancellationToken())
            check(broken.outcome == BuildOutcome.FAILED && broken.producedArtifacts.isEmpty())
            check(broken.diagnostics.any { it.source == BuildDiagnosticSource.KOTLIN && it.line != null && it.path == kotlin.absolutePath })
            check(!File(root, "broken").exists())
            check(root.listFiles()!!.none { it.name.startsWith(".kotlin-stage-") })
            check(runCatching { request("bad-target").copy(target = "17 -Xplugin=evil.jar") }.isFailure)
            println("EmbeddedKotlinCompilerTests: real mixed cyclic compilation, execution, diagnostics, fresh output, cleanup, budgets, cancellation and timeout passed")
        } finally { root.deleteRecursively() }
    }
}
