package dev.wind.agent

import dev.wind.agent.build.EcjCompileRequest
import dev.wind.agent.build.EcjJavaCompiler
import dev.wind.agent.build.PackagedAndroidPlatform
import dev.wind.agent.runtime.EmbeddedProviderProbe
import dev.wind.agent.runtime.EmbeddedToolCatalog
import dev.wind.agent.runtime.RuntimeToolId
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files

object PackagedAndroidPlatformTests {
    @JvmStatic
    fun main(args: Array<String>) {
        val asset = File(args.singleOrNull() ?: "assets/${PackagedAndroidPlatform.ASSET_PATH}")
        check(asset.isFile) { "Real packaged Android platform is required" }
        val root = Files.createTempDirectory("vega-platform").toFile().canonicalFile
        try {
            var opens = 0
            val provider = PackagedAndroidPlatform(File(root, "cache")) { path ->
                check(path == PackagedAndroidPlatform.ASSET_PATH)
                opens++
                asset.inputStream()
            }
            check(provider.resolve(34) == null && opens == 0)
            val jar = checkNotNull(provider.resolve(35))
            check(jar.length() == PackagedAndroidPlatform.SIZE_BYTES && opens == 1)
            check(provider.resolve(35) == jar && opens == 1) { "Verified cache was not reused" }

            // Real corruption must be detected even though the file length is unchanged.
            check(jar.setWritable(true))
            RandomAccessFile(jar, "rw").use { it.writeInt(0) }
            check(provider.resolve(35) == jar && opens == 2) { "Corrupt cache was reused" }

            val badRoot = File(root, "bad")
            val truncated = PackagedAndroidPlatform(badRoot) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
            check(truncated.resolve(35) == null)
            check(badRoot.listFiles()?.isEmpty() == true) { "Failed extraction left active/staging bytes" }
            val wrongRoot = File(root, "file-root").apply { writeText("sentinel") }
            check(PackagedAndroidPlatform(wrongRoot) { asset.inputStream() }.resolve(35) == null)
            check(wrongRoot.readText() == "sentinel")

            val compiler = EcjJavaCompiler()
            val source = File(root, "AndroidInput.java").apply {
                writeText("public class AndroidInput { public int sdk() { return android.os.Build.VERSION.SDK_INT; } }")
            }
            val compiled = compiler.compile(EcjCompileRequest(listOf(source), File(root, "classes"),
                bootClasspath = listOf(jar)), CancellationToken())
            check(compiled.success) { "Real Android bootstrap compilation failed: ${compiled.diagnostics}" }
            check(File(root, "classes/AndroidInput.class").isFile)
            val desktopOnly = File(root, "DesktopOnly.java").apply {
                writeText("public class DesktopOnly { javax.tools.JavaCompiler compiler; }")
            }
            val rejected = compiler.compile(EcjCompileRequest(listOf(desktopOnly), File(root, "bad-classes"),
                bootClasspath = listOf(jar)), CancellationToken())
            check(!rejected.success) { "Android compilation leaked desktop JDK classes" }

            val vmName = System.getProperty("java.vm.name")
            try {
                System.setProperty("java.vm.name", "Dalvik")
                val probe = EmbeddedProviderProbe(probeRoot = File(root, "probe"), bootClasspath = listOf(jar))
                check(probe.probe(checkNotNull(EmbeddedToolCatalog.C1A1[RuntimeToolId.JAVA_COMPILE])))
            } finally {
                if (vmName == null) System.clearProperty("java.vm.name") else System.setProperty("java.vm.name", vmName)
            }
            println("PackagedAndroidPlatformTests: OK (real Android 35 bootstrap; not device acceptance)")
        } finally {
            root.walkTopDown().filter { it.isFile }.forEach { it.setWritable(true) }
            root.deleteRecursively()
        }
    }
}
