package dev.wind.agent

import dev.wind.agent.build.D8CompileRequest
import dev.wind.agent.build.D8CoreLibraryDesugaringRequest
import dev.wind.agent.build.D8DexCompiler
import dev.wind.agent.build.EcjCompileRequest
import dev.wind.agent.build.EcjJavaCompiler
import dev.wind.agent.build.L8CompileRequest
import dev.wind.agent.build.L8RuntimeDexCompiler
import dev.wind.agent.build.PackagedDesugarInputs
import dev.wind.agent.build.ReflectionD8Provider
import dev.wind.agent.build.ReflectionEcjCompilerProvider
import dev.wind.agent.build.ReflectionL8Provider
import dev.wind.agent.runtime.EmbeddedProviderProbe
import dev.wind.agent.runtime.EmbeddedToolCatalog
import dev.wind.agent.runtime.EmbeddedToolDescriptor
import dev.wind.agent.runtime.RuntimeToolId
import dev.wind.agent.runtime.RuntimeToolImplementation
import dev.wind.agent.runtime.RuntimeToolResolver
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** P6-SC1A: real default-loader R8, app-private desugar inputs, and minApi 23 D8/L8. */
object PackagedR8ToolchainTests {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: PackagedR8ToolchainTests <repository-root>" }
        check(System.getenv("VEGA_R8_JAR").isNullOrBlank()) {
            "SC1A must prove the default ClassLoader path with VEGA_R8_JAR unset"
        }
        val repository = File(args.single()).canonicalFile
        check(repository.isDirectory)
        val androidJar = File(repository, "assets/embedded/android-35/android.jar")
        check(androidJar.isFile && androidJar.length() == ANDROID_JAR_SIZE_BYTES)
        check(sha256(androidJar) == ANDROID_JAR_SHA256)

        val loader = Thread.currentThread().contextClassLoader ?: javaClass.classLoader
        val d8Class = Class.forName("com.android.tools.r8.D8", false, loader)
        val l8Class = Class.forName("com.android.tools.r8.L8", false, loader)
        val vendoredR8 = File(repository, "third_party/embedded/r8/r8-8.2.33.jar").canonicalFile
        check(vendoredR8.isFile && sha256(vendoredR8) == R8_SHA256)
        check(d8Class.classLoader === loader) { "D8 was not loaded by the default application ClassLoader" }
        check(l8Class.classLoader === loader) { "L8 was not loaded by the default application ClassLoader" }
        val d8Origin = File(d8Class.protectionDomain.codeSource.location.toURI()).canonicalFile
        val l8Origin = File(l8Class.protectionDomain.codeSource.location.toURI()).canonicalFile
        check(d8Origin == vendoredR8 && l8Origin == vendoredR8) {
            "D8/L8 did not come from the pinned vendored R8 jar: D8=$d8Origin L8=$l8Origin"
        }
        val d8Provider = ReflectionD8Provider()
        val l8Provider = ReflectionL8Provider()
        check(d8Provider.available && isPinnedVersion(d8Provider.version)) {
            "Default ClassLoader D8 is unavailable or not 8.2.33: ${d8Provider.version}"
        }
        check(l8Provider.available && isPinnedVersion(l8Provider.version)) {
            "Default ClassLoader L8 is unavailable or not 8.2.33: ${l8Provider.version}"
        }

        val scratch = Files.createTempDirectory("vega-sc1a-r8").toFile().canonicalFile
        try {
            val desugar = resolveDesugarInputs(repository, scratch)
            val configurationJson = checkNotNull(desugar.configurationJson)
            check(configurationJson.contains("com.tools.android:desugar_jdk_libs_configuration_nio:2.0.1"))
            check(configurationJson.contains("\"configuration_format_version\":200"))
            check(configurationJson.contains("j$/"))
            check(sha256(desugar.runtimeJar) == PackagedDesugarInputs.RUNTIME_SHA256)
            check(sha256(desugar.configurationJar) == PackagedDesugarInputs.CONFIGURATION_SHA256)

            val source = File(scratch, "src/example/NioReference.java").apply {
                parentFile.mkdirs()
                writeText(
                    "package example; public final class NioReference { " +
                        "public static boolean exists(String path) { " +
                        "return java.nio.file.Files.exists(java.nio.file.Paths.get(path)); } }"
                )
            }
            val classes = File(scratch, "classes")
            val ecj = EcjJavaCompiler(ReflectionEcjCompilerProvider()).compile(
                EcjCompileRequest(listOf(source), classes, bootClasspath = listOf(androidJar)),
                CancellationToken()
            )
            check(ecj.success) { "ECJ NIO fixture failed: ${ecj.diagnostics}" }
            check(File(classes, "example/NioReference.class").isFile)

            val keepRules = File(scratch, "app-desugar/l8.pg")
            val d8Output = File(scratch, "app-dex")
            val appDex = D8DexCompiler(d8Provider).compile(
                D8CompileRequest(
                    classDirectories = listOf(classes),
                    libraryFiles = listOf(androidJar),
                    outputDirectory = d8Output,
                    minApi = 23,
                    coreLibraryDesugaring = D8CoreLibraryDesugaringRequest(
                        configurationJson = configurationJson,
                        keepRulesOutput = keepRules
                    )
                ),
                CancellationToken()
            )
            check(appDex.success) { "Real default-loader D8 failed: ${appDex.diagnostics}" }
            val classesDex = File(d8Output, "classes.dex")
            assertDex(classesDex)
            check(keepRules.isFile && keepRules.length() > 0L) { "D8 did not emit L8 keep rules" }
            check(String(classesDex.readBytes(), StandardCharsets.ISO_8859_1).contains("j$/nio/file")) {
                "D8 output lacks core-library NIO desugaring evidence"
            }

            val l8InputsBefore = listOf(desugar.runtimeJar, desugar.configurationJar, androidJar, keepRules)
                .associateWith(::sha256)
            val l8Output = File(scratch, "runtime-dex")
            val runtimeDex = L8RuntimeDexCompiler(l8Provider).compile(
                L8CompileRequest(
                    desugaredLibraryConfigurationJson = configurationJson,
                    keepRulesFile = keepRules,
                    programFiles = listOf(desugar.runtimeJar, desugar.configurationJar),
                    libraryFiles = listOf(androidJar),
                    outputDirectory = l8Output,
                    minApi = 23
                ),
                CancellationToken()
            )
            check(runtimeDex.success) { "Real default-loader L8 failed: ${runtimeDex.diagnostics}" }
            check(runtimeDex.artifacts.isNotEmpty() && runtimeDex.artifacts.all {
                it.kind == "android-desugar-runtime-dex"
            }) { "L8 did not report runtime DEX artifacts" }
            runtimeDex.artifacts.forEach { assertDex(it.file) }
            check(runtimeDex.artifacts.any {
                String(it.file.readBytes(), StandardCharsets.ISO_8859_1).contains("j$/nio/file")
            }) { "L8 runtime DEX lacks j$/nio/file evidence" }
            check(listOf(desugar.runtimeJar, desugar.configurationJar, androidJar, keepRules)
                .associateWith(::sha256) == l8InputsBefore) { "L8 modified one of its inputs" }

            verifyKotlinMetadataD8(androidJar, scratch, loader, d8Provider)
            verifyManualResourcePackaging(repository, scratch, loader, vendoredR8)
            verifyDexRuntimeAdvertisement(androidJar, scratch, loader)
            verifyPackagingDefinitions(repository)
            println(
                "PackagedR8ToolchainTests: PASS " +
                    "(D8=${d8Provider.version}, L8=${l8Provider.version}, minApi=23, " +
                    "appDex=${sha256(classesDex)}, KotlinMetadataD8=PASS, R8ResourceParity=PASS, runtimeDex=" +
                    runtimeDex.artifacts.map { it.file.name to it.sha256 } + ")"
            )
        } finally {
            scratch.walkTopDown().filter { it.isFile }.forEach { it.setWritable(true) }
            scratch.deleteRecursively()
        }
    }

    private fun resolveDesugarInputs(repository: File, scratch: File): dev.wind.agent.build.DesugarInputs {
        val runtimeAsset = File(repository, "assets/${PackagedDesugarInputs.RUNTIME_ASSET_PATH}")
        val configurationAsset = File(repository, "assets/${PackagedDesugarInputs.CONFIGURATION_ASSET_PATH}")
        check(runtimeAsset.isFile && configurationAsset.isFile)
        check(runtimeAsset.length() == PackagedDesugarInputs.RUNTIME_SIZE_BYTES)
        check(configurationAsset.length() == PackagedDesugarInputs.CONFIGURATION_SIZE_BYTES)
        check(sha256(runtimeAsset) == PackagedDesugarInputs.RUNTIME_SHA256)
        check(sha256(configurationAsset) == PackagedDesugarInputs.CONFIGURATION_SHA256)

        var openedAssets = 0
        val provider = PackagedDesugarInputs(File(scratch, "desugar-private")) { path ->
            openedAssets++
            when (path) {
                PackagedDesugarInputs.RUNTIME_ASSET_PATH -> runtimeAsset.inputStream()
                PackagedDesugarInputs.CONFIGURATION_ASSET_PATH -> configurationAsset.inputStream()
                else -> error("Unexpected packaged asset: $path")
            }
        }
        val resolved = checkNotNull(provider.resolve()) { "App-private desugar asset extraction failed" }
        check(resolved.runtimeJar.parentFile.canonicalFile == File(scratch, "desugar-private").canonicalFile)
        check(resolved.configurationJar.parentFile.canonicalFile == resolved.runtimeJar.parentFile.canonicalFile)
        check(openedAssets == 2)
        check(provider.resolve() == resolved && openedAssets == 2) { "Verified desugar cache was not reused" }

        check(resolved.runtimeJar.setWritable(true))
        RandomAccessFile(resolved.runtimeJar, "rw").use { it.writeInt(0) }
        val repaired = checkNotNull(provider.resolve()) { "Corrupt desugar cache was not repaired" }
        check(sha256(repaired.runtimeJar) == PackagedDesugarInputs.RUNTIME_SHA256)
        check(sha256(repaired.configurationJar) == PackagedDesugarInputs.CONFIGURATION_SHA256)
        check(openedAssets == 4) { "Corrupt cache did not re-extract both pinned assets" }

        val rejectedRoot = File(scratch, "rejected-desugar")
        val rejected = PackagedDesugarInputs(rejectedRoot) { ByteArrayInputStream(byteArrayOf(1, 2, 3)) }
        check(rejected.resolve() == null)
        check(rejectedRoot.listFiles().orEmpty().isEmpty()) { "Failed asset extraction left staged bytes" }
        return repaired
    }

    private fun verifyDexRuntimeAdvertisement(androidJar: File, scratch: File, loader: ClassLoader) {
        val descriptor = checkNotNull(EmbeddedToolCatalog.C1A1[RuntimeToolId.DEX])
        check(descriptor == EmbeddedToolDescriptor(
            toolId = RuntimeToolId.DEX,
            providerId = "google-r8",
            providerVersion = "8.2.33",
            licenseId = "BSD-3-Clause / Apache-2.0 / BSD / MIT / Public Domain",
            artifactSha256 = R8_SHA256,
            capabilityVersion = "p6-sc1-r8-8.2.33"
        ))
        val probe = EmbeddedProviderProbe(
            classLoader = loader,
            probeRoot = File(scratch, "dex-probes"),
            bootClasspath = listOf(androidJar)
        )
        val resolver = RuntimeToolResolver(
            nativeExecutables = mapOf(RuntimeToolId.DEX to "must-not-be-used"),
            catalog = EmbeddedToolCatalog(listOf(descriptor)),
            providerProbe = { candidate -> candidate == descriptor && probe.probe(candidate) }
        )
        val tool = resolver.resolve(RuntimeToolId.DEX)
        check(tool.implementation == RuntimeToolImplementation.IN_PROCESS)
        check(tool.executable == null)
        check(tool.capabilityVersion == descriptor.capabilityVersion)
    }

    private fun verifyPackagingDefinitions(repository: File) {
        val gradle = File(repository, "build.gradle.kts").readText(Charsets.UTF_8)
        check(gradle.contains("third_party/embedded/r8/r8-8.2.33.jar"))
        check(gradle.contains("assets/embedded/desugar/desugar_jdk_libs_nio-2.0.1.jar"))
        check(gradle.contains("assets/embedded/desugar/desugar_jdk_libs_configuration_nio-2.0.1.jar"))

        val shell = File(repository, "mkapk.sh").readText(Charsets.UTF_8)
        check(shell.contains("PACKAGED_TOOLCHAIN_JARS=(") &&
            shell.contains("${'$'}ROOT/third_party/embedded/r8/r8-8.2.33.jar"))
        check(shell.contains("PACKAGED_TOOLCHAIN_RESOURCE_JARS=(") &&
            shell.contains("\"${'$'}{PACKAGED_TOOLCHAIN_RESOURCE_JARS[@]}\""))
        check(shell.contains("${'$'}{SANITIZED_TOOLCHAIN_JARS[@]}"))
        check(shell.substringAfter("PackageJarResources.java").substringBefore("zipalign")
            .contains("PACKAGED_TOOLCHAIN_RESOURCE_JARS") &&
            !shell.substringAfter("PackageJarResources.java").substringBefore("zipalign")
                .contains("PACKAGED_TOOLCHAIN_JARS[@]")) {
            "R8 resource packaging must use its separate resource role, not the D8 program list"
        }

        val powershell = File(repository, "mkapk.ps1").readText(Charsets.UTF_8)
        check(powershell.contains("${'$'}PackagedToolchainJars") &&
            powershell.contains("third_party\\embedded\\r8\\r8-8.2.33.jar"))
        check(powershell.contains("${'$'}PackagedToolchainResourceJars") &&
            powershell.contains("@ProviderJars @PackagedToolchainResourceJars"))
        check(powershell.contains("${'$'}d8Args += ${'$'}sanitizedToolchainJars"))
        check(!powershell.substringAfter("PackageJarResources.java").substringBefore("zipalign.exe")
            .contains("@PackagedToolchainJars")) {
            "PowerShell resource packaging must not reuse the D8 program list"
        }

        val offline = File(repository, "tools/build-offline.sh").readText(Charsets.UTF_8)
        check(offline.contains("${'$'}ROOT/third_party/embedded/r8/r8-8.2.33.jar"))
        check(gradle.contains("merges += setOf(\"META-INF/services/**\")")) {
            "Gradle must preserve and merge R8 service descriptors"
        }
    }

    private fun verifyManualResourcePackaging(
        repository: File,
        scratch: File,
        loader: ClassLoader,
        r8Jar: File
    ) {
        val staging = File(scratch, "r8-resource-parity").apply { mkdirs() }
        val apk = File(staging, "unsigned-resource-probe.apk")
        val originalDex = byteArrayOf('d'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte(),
            '0'.code.toByte(), '3'.code.toByte(), '5'.code.toByte(), 0)
        ZipOutputStream(apk.outputStream()).use { zip ->
            listOf(
                "AndroidManifest.xml" to byteArrayOf(0x03, 0x00, 0x08, 0x00),
                "classes.dex" to originalDex
            ).forEach { (name, data) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(data)
                zip.closeEntry()
            }
        }

        val kotlinStdlib = File(Unit::class.java.protectionDomain.codeSource.location.toURI()).canonicalFile
        check(kotlinStdlib.isFile) { "Kotlin stdlib must resolve to the local JAR used by the manual packager" }
        val providerJars = listOf(
            "third_party/embedded/jgit/org.eclipse.jgit-7.7.1.202607240634-r.jar",
            "third_party/embedded/jgit/JavaEWAH-1.2.3.jar",
            "third_party/embedded/jgit/slf4j-api-2.0.18.jar",
            "third_party/embedded/jgit/commons-codec-1.22.0.jar",
            "third_party/embedded/ecj/ecj-3.46.100-android1.jar",
            "third_party/embedded/zipflinger/zipflinger-8.13.2.jar"
        ).map { File(repository, it).canonicalFile }
        check(providerJars.all(File::isFile)) { "One or more manual provider resource inputs are missing" }
        val resourceInputs = listOf(kotlinStdlib) + providerJars + r8Jar

        val merger = Class.forName("PackageJarResources", true, loader)
        val packageResources = merger.getDeclaredMethod("packageResources", java.nio.file.Path::class.java, List::class.java)
            .apply { isAccessible = true }
        packageResources.invoke(null, apk.toPath(), resourceInputs.map(File::toPath))

        val requiredNames = listOf(
            "resources/new_api_database.ser",
            "r8-version.properties",
            "META-INF/services/com.android.tools.r8.internal.XE",
            "com/android/tools/r8/jetbrains/kotlin/kotlin.kotlin_builtins"
        )
        ZipFile(r8Jar).use { source ->
            ZipFile(apk).use { output ->
                val outputNames = output.entries().asSequence().map { it.name }.toList()
                requiredNames.forEach { name ->
                    val inputEntry = checkNotNull(source.getEntry(name)) { "Pinned R8 JAR lacks required resource $name" }
                    val outputEntry = checkNotNull(output.getEntry(name)) {
                        "PackageJarResources omitted pinned R8 runtime resource $name"
                    }
                    val sourceBytes = source.getInputStream(inputEntry).use { it.readBytes() }
                    val outputBytes = output.getInputStream(outputEntry).use { it.readBytes() }
                    check(sourceBytes.contentEquals(outputBytes)) { "Packaged R8 resource bytes changed: $name" }
                }
                val builtins = source.entries().asSequence()
                    .map { it.name }
                    .filter { it.startsWith("com/android/tools/r8/jetbrains/kotlin/") && it.endsWith(".kotlin_builtins") }
                    .toList()
                check(builtins.size == 7) { "Expected all seven pinned Kotlin builtins resources, found ${builtins.size}" }
                builtins.forEach { name ->
                    val sourceBytes = source.getInputStream(checkNotNull(source.getEntry(name))).use { it.readBytes() }
                    val outputBytes = output.getInputStream(checkNotNull(output.getEntry(name)))
                        .use { it.readBytes() }
                    check(sourceBytes.contentEquals(outputBytes)) { "Kotlin builtin resource was omitted or changed: $name" }
                }
                val service = String(
                    output.getInputStream(checkNotNull(output.getEntry("META-INF/services/com.android.tools.r8.internal.XE")))
                        .use { it.readBytes() },
                    StandardCharsets.UTF_8
                )
                check(service.lineSequence().any { it.trim() == "com.android.tools.r8.internal.Ju" }) {
                    "Packaged Kotlin metadata service descriptor lost the R8 extension provider"
                }
                check(outputNames.none { it.endsWith(".class", ignoreCase = true) }) {
                    "PackageJarResources copied class files into the APK resource set"
                }
                check(output.getEntry("com/android/tools/r8/D8.class") == null)
                check(output.getEntry("classes.dex") != null)
                check(output.getInputStream(checkNotNull(output.getEntry("classes.dex"))).use { it.readBytes() }
                    .contentEquals(originalDex)) { "PackageJarResources changed the existing app DEX" }
            }
        }
    }

    private fun verifyKotlinMetadataD8(
        androidJar: File,
        scratch: File,
        loader: ClassLoader,
        provider: ReflectionD8Provider
    ) {
        val resourcePath = "dev/wind/agent/CancellationToken.class"
        val resource = checkNotNull(loader.getResource(resourcePath)) {
            "Focused Kotlin metadata fixture is not visible from the default ClassLoader"
        }
        check(resource.protocol == "file") { "Focused Kotlin fixture must be a local compiled class: $resource" }
        val sourceClass = File(resource.toURI()).canonicalFile
        val packageDirectory = sourceClass.parentFile
        val fixtures = packageDirectory.listFiles().orEmpty().filter {
            it.name.startsWith("CancellationToken") && it.name.endsWith(".class")
        }
        check(fixtures.any { it.name == "CancellationToken.class" })
        val inputDirectory = File(scratch, "kotlin-metadata-input/dev/wind/agent").apply { mkdirs() }
        fixtures.forEach { fixture ->
            Files.copy(fixture.toPath(), File(inputDirectory, fixture.name).toPath())
        }
        val capture = ByteArrayOutputStream()
        val savedOut = System.out
        val savedErr = System.err
        val d8Output = File(scratch, "kotlin-metadata-dex")
        val result = try {
            PrintStream(capture, true, "UTF-8").use { stream ->
                System.setOut(stream)
                System.setErr(stream)
                D8DexCompiler(provider).compile(
                    D8CompileRequest(
                        classDirectories = listOf(File(scratch, "kotlin-metadata-input")),
                        libraryFiles = listOf(androidJar),
                        outputDirectory = d8Output,
                        minApi = 23
                    ),
                    CancellationToken()
                )
            }
        } finally {
            System.setOut(savedOut)
            System.setErr(savedErr)
        }
        val log = capture.toString("UTF-8")
        check(result.success) { "D8 failed on actual Kotlin class files: ${result.diagnostics}; log=$log" }
        check(!log.contains("No MetadataExtensions instances found", ignoreCase = true)) {
            "D8 did not discover the packaged R8 Kotlin metadata service: $log"
        }
        assertDex(File(d8Output, "classes.dex"))
    }

    private fun assertDex(file: File) {
        check(file.isFile && file.length() >= 8L) { "Missing or truncated DEX output: $file" }
        file.inputStream().use { input ->
            check(input.read() == 'd'.code && input.read() == 'e'.code && input.read() == 'x'.code &&
                input.read() == '\n'.code && input.read() in '0'.code..'9'.code &&
                input.read() in '0'.code..'9'.code && input.read() in '0'.code..'9'.code && input.read() == 0) {
                "Invalid DEX magic in $file"
            }
        }
    }

    private fun isPinnedVersion(version: String?): Boolean =
        version?.matches(Regex("^8\\.2\\.33(?:\\s.*)?$")) == true

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private const val R8_SHA256 = "80c39a28bb66b64efc232d68ae554d59fe3838808738b4fe584884691cb1e5a3"
    private const val ANDROID_JAR_SIZE_BYTES = 27_092_450L
    private const val ANDROID_JAR_SHA256 = "4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b"
}
