package dev.wind.agent

import dev.wind.agent.build.ApkAlignmentInvocation
import dev.wind.agent.build.ApkAlignmentRequest
import dev.wind.agent.build.ApkAlignmentVerifier
import dev.wind.agent.build.ApkAligner
import dev.wind.agent.build.ApkAssembler
import dev.wind.agent.build.ApkAssemblyRequest
import dev.wind.agent.build.D8CompileRequest
import dev.wind.agent.build.D8CoreLibraryDesugaringRequest
import dev.wind.agent.build.D8DexCompiler
import dev.wind.agent.build.L8CompileRequest
import dev.wind.agent.build.L8RuntimeDexCompiler
import dev.wind.agent.build.ReflectionD8Provider
import dev.wind.agent.build.ReflectionL8Provider
import dev.wind.agent.build.ReflectionZipflingerAlignmentProvider
import dev.wind.agent.build.selectZipflingerEntries
import java.io.File
import java.io.FileOutputStream
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarFile
import java.util.jar.JarOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.util.concurrent.TimeUnit

/** In-process Zipflinger integration and optional D8/L8 minApi 23 gate. */
object ZipflingerAlignmentProviderTests {
    private const val ZIPFLINGER_JAR = "third_party/embedded/zipflinger/zipflinger-8.13.2.jar"
    private const val ZIPFLINGER_POM = "third_party/embedded/zipflinger/zipflinger-8.13.2.pom"
    private const val ANNOTATIONS_JAR = "third_party/embedded/zipflinger/annotations-31.13.2.jar"
    private const val ANNOTATIONS_POM = "third_party/embedded/zipflinger/annotations-31.13.2.pom"
    private const val NIO_CONFIGURATION_JAR = "third_party/embedded/desugar/desugar_jdk_libs_configuration_nio-2.0.1.jar"
    private const val NIO_RUNTIME_JAR = "third_party/embedded/desugar/desugar_jdk_libs_nio-2.0.1.jar"
    private const val FIXED_ZIP_TIME = 315_532_800_000L

    @JvmStatic
    fun main(args: Array<String>) {
        verifyPinnedArtifactsAndPublicApi()
        checksCoreApiAvailabilityAndFailureMapping()
        selectionLoopObservesCancellation()
        verifiesAp1ZipflingerAndOptionalHostOracle()
        realD8L8Api23GateIfConfigured(args.firstOrNull()?.let(::File))
        println("ZipflingerAlignmentProviderTests: OK")
    }

    private fun verifyPinnedArtifactsAndPublicApi() {
        val zipflinger = File(ZIPFLINGER_JAR)
        val pom = File(ZIPFLINGER_POM)
        val annotations = File(ANNOTATIONS_JAR)
        val annotationsPom = File(ANNOTATIONS_POM)
        check(zipflinger.isFile && pom.isFile && annotations.isFile && annotationsPom.isFile) {
            "Pinned Zipflinger artifact set is incomplete"
        }
        check(sha256(zipflinger) == "07060069c35e469d7c343abc15f1d6362bc1356b81bf462539db88a53ed653f1") {
            "Zipflinger SHA-256 mismatch: ${sha256(zipflinger)}"
        }
        check(sha256(pom) == "e2eed37782da4d8c799d4c4b737c35ad88d3912f5136cfdfe2c014ccd40b350c") {
            "Zipflinger POM SHA-256 mismatch: ${sha256(pom)}"
        }
        check(sha256(annotations) == "3b4bb9620c17d19e5bd91ac1988080553573b4c3b739fdd92416f42f2daf3e78") {
            "Annotations SHA-256 mismatch: ${sha256(annotations)}"
        }
        check(sha256(annotationsPom) == "eb73f215814a97af217ba71a6c6859fb1c7e57b13c5d6ccd9959de2c97424755") {
            "Annotations POM SHA-256 mismatch: ${sha256(annotationsPom)}"
        }
        val zipflingerPomText = pom.readText(StandardCharsets.UTF_8)
        check(zipflingerPomText.contains("<groupId>com.android</groupId>"))
        check(zipflingerPomText.contains("<artifactId>zipflinger</artifactId>"))
        check(zipflingerPomText.contains("<version>8.13.2</version>"))
        check(zipflingerPomText.contains("<scope>runtime</scope>"))
        check(zipflingerPomText.contains("<artifactId>annotations</artifactId>"))
        check(zipflingerPomText.contains("<version>31.13.2</version>"))
        check(zipflingerPomText.contains("<name>The Apache Software License, Version 2.0</name>"))
        check(annotationsPom.readText(StandardCharsets.UTF_8).contains("<version>31.13.2</version>"))

        val apiLoader = zipflingerLoader(includeAnnotations = true)
        apiLoader.use { loader ->
            val provider = ReflectionZipflingerAlignmentProvider(loader)
            check(provider.available) { "Pinned artifact lacks the public API required by the provider" }
        }
        val providerSource = File("src/dev/wind/agent/build/ZipflingerAlignmentProvider.kt")
        val sourceText = providerSource.readText(StandardCharsets.UTF_8)
        check(!Regex("\\bProcessBuilder\\b|\\bRuntime\\.exec\\b|\\bzipalign\\b|\\bshell\\b")
            .containsMatchIn(sourceText)) {
            "The in-process provider source contains an external-command fallback"
        }
    }

    private fun checksCoreApiAvailabilityAndFailureMapping() = withFixture { fixture ->
        val zipflinger = File(ZIPFLINGER_JAR)
        val annotations = File(ANNOTATIONS_JAR)
        val missingCoreJar = File(fixture.root, "zipflinger-without-archive.jar")
        copyJarWithout(zipflinger, missingCoreJar, "com/android/zipflinger/ZipArchive.class")
        zipflingerLoader(missingCoreJar, annotations).use { loader ->
            val provider = ReflectionZipflingerAlignmentProvider(loader)
            check(!provider.available) { "Provider must be unavailable when ZipArchive is absent" }
            val missing = provider.align(
                ApkAlignmentInvocation(fixture.input, File(fixture.root, "missing-api.apk"), 4, 1_000),
                CancellationToken()
            )
            check(!missing.success && missing.diagnostics.isNotBlank())
        }

        zipflingerLoader(includeAnnotations = true).use { loader ->
            val provider = ReflectionZipflingerAlignmentProvider(loader)
            check(provider.available)
            val cancelledToken = CancellationToken().apply { cancel() }
            val cancelled = provider.align(
                ApkAlignmentInvocation(fixture.input, File(fixture.root, "cancelled.apk"), 4, 1_000),
                cancelledToken
            )
            check(!cancelled.success && cancelled.diagnostics.contains("cancelled", ignoreCase = true))

            val missingInput = provider.align(
                ApkAlignmentInvocation(File(fixture.root, "missing-input.apk"), File(fixture.root, "failed.apk"), 4, 1_000),
                CancellationToken()
            )
            check(!missingInput.success && missingInput.diagnostics.isNotBlank()) {
                "Reflective Zipflinger invocation failure escaped the provider contract"
            }
        }

        val linkageLoader = object : ClassLoader(ClassLoader.getSystemClassLoader().parent) {
            override fun loadClass(name: String, resolve: Boolean): Class<*> {
                if (name == "com.android.zipflinger.ZipArchive") throw NoClassDefFoundError("synthetic linkage failure")
                return super.loadClass(name, resolve)
            }
        }
        val linkageProvider = ReflectionZipflingerAlignmentProvider(linkageLoader)
        check(!linkageProvider.available)
        val linkageResult = linkageProvider.align(
            ApkAlignmentInvocation(fixture.input, File(fixture.root, "linkage.apk"), 4, 1_000),
            CancellationToken()
        )
        check(!linkageResult.success && linkageResult.diagnostics.isNotBlank())
    }

    private fun selectionLoopObservesCancellation() {
        val token = CancellationToken()
        val selectedAligned = ArrayList<String>()
        val selectedUnaligned = ArrayList<String>()
        val entries = linkedMapOf(
            "assets/stored.bin" to EntryStub("assets/stored.bin", directory = false, method = ZipEntry.STORED),
            "assets/deflated.txt" to EntryStub("assets/deflated.txt", directory = false, method = ZipEntry.DEFLATED)
        )
        val allSelected = selectZipflingerEntries(
            entries = entries,
            token = token,
            readName = { (it as EntryStub).name },
            isDirectory = { (it as EntryStub).directory },
            compressionFlag = { (it as EntryStub).method },
            selectAligned = { name -> selectedAligned += name; token.cancel() },
            selectUnaligned = { selectedUnaligned += it }
        )
        check(!allSelected) { "Selection loop ignored cancellation after the first entry" }
        check(selectedAligned == listOf("assets/stored.bin"))
        check(selectedUnaligned.isEmpty())
    }

    private fun verifiesAp1ZipflingerAndOptionalHostOracle() = withFixture { fixture ->
        createAp1Input(fixture)
        val inputBefore = sha256(fixture.input)
        val before = ApkAlignmentVerifier().verify(fixture.input)
        check(before.outcome.name == "SUCCESS" && !before.aligned) {
            "AP1 fixture must be valid and unaligned: ${before.diagnostics}"
        }

        val zipflinger = File(ZIPFLINGER_JAR)
        val annotations = File(ANNOTATIONS_JAR)
        zipflingerLoader(includeAnnotations = true).use { loader ->
            val provider = ReflectionZipflingerAlignmentProvider(loader)
            check(provider.available)
            val aligner = ApkAligner(provider)
            val firstOutput = File(fixture.root, "zipflinger-1.apk")
            val secondOutput = File(fixture.root, "zipflinger-2.apk")
            val first = aligner.align(ApkAlignmentRequest(fixture.input, firstOutput), CancellationToken())
            check(first.outcome.name == "SUCCESS") { "Zipflinger alignment failed: ${first.diagnostics}" }
            val firstVerification = ApkAlignmentVerifier().verify(firstOutput)
            check(firstVerification.outcome.name == "SUCCESS" && firstVerification.aligned) {
                "Independent verifier rejected Zipflinger output: ${firstVerification.diagnostics}"
            }
            val firstFingerprint = fingerprintRows(firstVerification)
            check(fingerprintRows(before) == firstFingerprint) { "Zipflinger changed entry names, methods, sizes, or payloads" }

            val second = aligner.align(ApkAlignmentRequest(fixture.input, secondOutput), CancellationToken())
            check(second.outcome.name == "SUCCESS") { "Repeated Zipflinger alignment failed: ${second.diagnostics}" }
            val secondVerification = ApkAlignmentVerifier().verify(secondOutput)
            check(secondVerification.outcome.name == "SUCCESS" && secondVerification.aligned)
            check(fingerprintRows(secondVerification) == firstFingerprint)
            val deterministic = first.artifacts.single().sha256 == second.artifacts.single().sha256
            check(deterministic) { "Identical Zipflinger input did not produce a stable archive SHA-256" }
            check(firstOutput.readBytes().contentEquals(secondOutput.readBytes()))

            val dependencyFreeOutput = File(fixture.root, "zipflinger-no-annotations.apk")
            zipflingerLoader(File(ZIPFLINGER_JAR), null).use { noDependencyLoader ->
                val noDependencyProvider = ReflectionZipflingerAlignmentProvider(noDependencyLoader)
                check(noDependencyProvider.available) { "Source/runtime annotations artifact unexpectedly required at class load" }
                val dependencyFree = ApkAligner(noDependencyProvider).align(
                    ApkAlignmentRequest(fixture.input, dependencyFreeOutput), CancellationToken()
                )
                check(dependencyFree.outcome.name == "SUCCESS") {
                    "Zipflinger required the POM-declared annotation library at execution: ${dependencyFree.diagnostics}"
                }
                check(fingerprintRows(ApkAlignmentVerifier().verify(dependencyFreeOutput)) == firstFingerprint)
            }

            val hostOracle = runHostDifferentialIfConfigured(fixture, firstOutput, firstFingerprint)
            check(sha256(fixture.input) == inputBefore) { "Zipflinger modified the AP1 input" }
            val classes = listOf(
                "AndroidManifest.xml:STORED",
                "classes.dex:DEFLATED",
                "resources.arsc:STORED",
                "res/raw/proof.bin:STORED",
                "assets/message.txt:DEFLATED"
            )
            check(ZipFile(firstOutput).use { it.size() } == classes.size)
            println(
                "ZipflingerAlignmentProviderTests: in-process PASS " +
                    "inputSha256=$inputBefore outputSha256=${first.artifacts.single().sha256} " +
                    "deterministic=$deterministic hostOracle=$hostOracle entries=$classes fingerprints=$firstFingerprint"
            )
        }
    }

    private fun runHostDifferentialIfConfigured(
        fixture: Fixture,
        zipflingerOutput: File,
        expectedFingerprints: List<String>
    ): Boolean {
        val configured = System.getenv("VEGA_ZIPALIGN")?.takeIf { it.isNotBlank() } ?: run {
            println("ZipflingerAlignmentProviderTests: host oracle skipped (VEGA_ZIPALIGN is unset)")
            return false
        }
        val executable = File(configured)
        check(executable.isFile) { "VEGA_ZIPALIGN does not name a file: $executable" }
        val hostOutput = File(fixture.root, "host-oracle.apk")
        val host = ApkAligner(dev.wind.agent.build.HostZipalignProvider(executable)).align(
            ApkAlignmentRequest(fixture.input, hostOutput), CancellationToken()
        )
        check(host.outcome.name == "SUCCESS") { "Host oracle alignment failed: ${host.diagnostics}" }
        val hostVerification = ApkAlignmentVerifier().verify(hostOutput)
        check(hostVerification.outcome.name == "SUCCESS" && hostVerification.aligned)
        check(fingerprintRows(hostVerification) == expectedFingerprints) {
            "Host and Zipflinger payload fingerprints differ"
        }

        val checkProcess = ProcessBuilder(
            executable.absolutePath, "-c", "-v", "4", zipflingerOutput.absolutePath
        ).redirectOutput(ProcessBuilder.Redirect.INHERIT)
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        if (!checkProcess.waitFor(30, TimeUnit.SECONDS)) {
            checkProcess.destroyForcibly()
            error("Host alignment oracle timed out while checking Zipflinger output")
        }
        check(checkProcess.exitValue() == 0) { "Host oracle rejected Zipflinger output" }
        return true
    }

    private fun realD8L8Api23GateIfConfigured(androidJar: File?) {
        val r8Path = System.getenv("VEGA_R8_JAR")?.takeIf { it.isNotBlank() } ?: run {
            println("ZipflingerAlignmentProviderTests: D8/L8 minApi 23 gate skipped (VEGA_R8_JAR is unset)")
            return
        }
        val r8Jar = File(r8Path)
        check(r8Jar.isFile) { "VEGA_R8_JAR does not name an ordinary file: $r8Jar" }
        check(androidJar?.isFile == true) { "Offline runner must pass the pinned Android 35 android.jar" }
        val zipflingerJar = File(ZIPFLINGER_JAR)
        val annotationJar = File(ANNOTATIONS_JAR)
        val configurationJar = File(NIO_CONFIGURATION_JAR)
        val runtimeJar = File(NIO_RUNTIME_JAR)
        listOf(zipflingerJar, annotationJar, configurationJar, runtimeJar, androidJar!!).forEach {
            check(it.isFile) { "API23 gate input is missing: $it" }
        }

        val configurationJson = JarFile(configurationJar).use { jar ->
            val entry = jar.getJarEntry("META-INF/desugar/d8/desugar.json")
                ?: error("NIO configuration artifact lacks its D8 configuration")
            jar.getInputStream(entry).use { String(it.readAllBytes(), StandardCharsets.UTF_8) }
        }
        check(configurationJson.contains("desugar_jdk_libs_configuration_nio:2.0.1"))
        check(configurationJson.contains("\"configuration_format_version\":200"))
        check(configurationJson.contains("j$/"))

        val inputDigests = listOf(zipflingerJar, annotationJar, configurationJar, runtimeJar, androidJar!!)
            .associateWith(::sha256)
        val root = Files.createTempDirectory("vega-ap2b1-api23").toFile()
        try {
            val classDirectory = File(root, "zipflinger-classes").apply { mkdirs() }
            val classMajor = extractClassesAndReadMajor(zipflingerJar, classDirectory)
            check(classMajor == 55) { "Zipflinger classfile target changed: major=$classMajor, expected 55 (Java 11)" }

            URLClassLoader(arrayOf(r8Jar.toURI().toURL()), ReflectionD8Provider::class.java.classLoader).use { loader ->
                val d8Provider = ReflectionD8Provider(loader)
                check(d8Provider.available) { "R8 JAR does not expose the D8 API" }
                val d8Version = d8Provider.version ?: error("D8 version API returned no version")
                check(d8Version.contains("8.2.33")) { "AP2B1 requires D8 8.2.33; found $d8Version" }
                val keepRules = File(root, "desugar/l8.pg")
                val d8 = D8DexCompiler(d8Provider).compile(
                    D8CompileRequest(
                        classDirectories = listOf(classDirectory),
                        libraryFiles = listOf(androidJar, annotationJar),
                        outputDirectory = File(root, "d8-output"),
                        minApi = 23,
                        coreLibraryDesugaring = D8CoreLibraryDesugaringRequest(configurationJson, keepRules)
                    ),
                    CancellationToken()
                )
                check(d8.success) { "D8 minApi23 rejected Zipflinger classes: ${d8.diagnostics}" }
                check(d8.artifacts.map { it.kind }.toSet() == setOf("android-dex", "android-desugar-keep-rules"))
                check(keepRules.isFile && keepRules.length() > 0L) { "D8 did not emit a non-empty l8.pg" }
                val appDex = d8.artifacts.single { it.kind == "android-dex" }
                check(String(appDex.file.readBytes(), StandardCharsets.ISO_8859_1).contains("j$/nio/file")) {
                    "D8 Zipflinger classes do not show the expected j$/nio/file reference"
                }

                val l8Provider = ReflectionL8Provider(loader)
                check(l8Provider.available) { "R8 JAR does not expose the L8 API" }
                check(l8Provider.version?.contains("8.2.33") == true) { "L8 version API did not report 8.2.33" }
                val l8 = L8RuntimeDexCompiler(l8Provider).compile(
                    L8CompileRequest(
                        desugaredLibraryConfigurationJson = configurationJson,
                        keepRulesFile = keepRules,
                        programFiles = listOf(runtimeJar, configurationJar),
                        libraryFiles = listOf(androidJar),
                        outputDirectory = File(root, "l8-output"),
                        minApi = 23,
                        release = true
                    ),
                    CancellationToken()
                )
                check(l8.success) { "L8 minApi23 gate failed: ${l8.diagnostics}" }
                check(l8.artifacts.isNotEmpty() && l8.artifacts.all { it.kind == "android-desugar-runtime-dex" })
                check(l8.artifacts.any {
                    String(it.file.readBytes(), StandardCharsets.ISO_8859_1).contains("j$/nio/file")
                }) { "L8 runtime DEX lacks j$/nio/file implementation evidence" }
                val l8Evidence = l8.artifacts.map { it.file.name to it.sha256 }
                check(inputDigests == listOf(zipflingerJar, annotationJar, configurationJar, runtimeJar, androidJar)
                    .associateWith(::sha256)) { "D8/L8 modified a pinned input" }
                println(
                    "ZipflingerAlignmentProviderTests: D8/L8 minApi23 PASS " +
                        "version=$d8Version androidApi=35 classMajor=$classMajor " +
                        "annotationJarOnD8LibraryPath=true d8DexSha256=${appDex.sha256} " +
                        "l8pgSha256=${sha256(keepRules)} l8RuntimeDex=$l8Evidence"
                )
            }
        } finally {
            root.deleteRecursively()
        }
    }

    private fun extractClassesAndReadMajor(jarFile: File, outputDirectory: File): Int {
        val majorVersions = linkedSetOf<Int>()
        ZipFile(jarFile).use { zip ->
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (!entry.name.endsWith(".class")) continue
                check(!entry.name.startsWith("/") && entry.name.split('/').none { it == ".." || it.isEmpty() }) {
                    "Unsafe classpath entry in pinned Zipflinger artifact: ${entry.name}"
                }
                val target = File(outputDirectory, entry.name)
                target.parentFile.mkdirs()
                zip.getInputStream(entry).use { input -> target.outputStream().use(input::copyTo) }
                val major = target.inputStream().use { input ->
                    val data = java.io.DataInputStream(input)
                    check(data.readInt() == 0xCAFEBABE.toInt()) { "Malformed class file in pinned artifact" }
                    data.readUnsignedShort()
                    data.readUnsignedShort()
                }
                majorVersions += major
            }
        }
        check(majorVersions.isNotEmpty()) { "Pinned Zipflinger artifact contains no class files" }
        check(majorVersions.size == 1) { "Pinned Zipflinger jar mixes classfile major versions: $majorVersions" }
        return majorVersions.single()
    }

    private fun copyJarWithout(source: File, target: File, omittedEntry: String) {
        target.parentFile?.mkdirs()
        JarFile(source).use { input ->
            JarOutputStream(FileOutputStream(target)).use { output ->
                val entries = input.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    if (entry.name == omittedEntry) continue
                    output.putNextEntry(JarEntry(entry.name))
                    input.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
    }

    private data class Payload(val name: String, val bytes: ByteArray, val method: Int = ZipEntry.STORED)
    private data class EntryStub(val name: String, val directory: Boolean, val method: Int)
    private data class Fixture(val root: File, val input: File)

    private fun withFixture(block: (Fixture) -> Unit) {
        val root = Files.createTempDirectory("vega-ap2b1").toFile()
        try {
            block(Fixture(root, File(root, "unsigned-unaligned.apk")))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun createAp1Input(fixture: Fixture) {
        val base = File(fixture.root, "resources.ap_")
        writeZip(base, listOf(
            Payload("AndroidManifest.xml", "synthetic-manifest".toByteArray(StandardCharsets.UTF_8)),
            Payload("resources.arsc", "synthetic-resource-table".toByteArray(StandardCharsets.UTF_8)),
            Payload("res/raw/proof.bin", ByteArray(23) { (it * 7).toByte() }),
            Payload("assets/message.txt", "zipflinger preserves compression".toByteArray(StandardCharsets.UTF_8), ZipEntry.DEFLATED)
        ))
        val dexDirectory = File(fixture.root, "app-dex").apply { mkdirs() }
        File(dexDirectory, "classes.dex").writeBytes(validDexBytes())
        val result = ApkAssembler().assemble(
            ApkAssemblyRequest(baseResourcePackage = base, appDexDirectory = dexDirectory, outputApk = fixture.input),
            CancellationToken()
        )
        check(result.outcome.name == "SUCCESS") { "AP1 failed to create the integration input: ${result.diagnostics}" }
    }

    private fun writeZip(file: File, payloads: List<Payload>) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            payloads.forEach { payload ->
                val entry = ZipEntry(payload.name).apply {
                    time = FIXED_ZIP_TIME
                    method = payload.method
                    if (method == ZipEntry.STORED) {
                        size = payload.bytes.size.toLong()
                        compressedSize = payload.bytes.size.toLong()
                        crc = CRC32().apply { update(payload.bytes) }.value
                    }
                }
                zip.putNextEntry(entry)
                zip.write(payload.bytes)
                zip.closeEntry()
            }
        }
    }

    private fun fingerprintRows(verification: dev.wind.agent.build.ApkAlignmentVerificationResult): List<String> =
        verification.entries.map { "${it.name}:${it.method}:${it.uncompressedSize}:${it.sha256}" }

    private fun zipflingerLoader(includeAnnotations: Boolean): URLClassLoader =
        zipflingerLoader(File(ZIPFLINGER_JAR), if (includeAnnotations) File(ANNOTATIONS_JAR) else null)

    private fun zipflingerLoader(zipflingerJar: File, annotationsJar: File?): URLClassLoader {
        val urls = if (annotationsJar == null) {
            arrayOf(zipflingerJar.toURI().toURL())
        } else {
            arrayOf(zipflingerJar.toURI().toURL(), annotationsJar.toURI().toURL())
        }
        // Exclude the application's classpath while retaining the host platform classes.
        return URLClassLoader(urls, ClassLoader.getSystemClassLoader().parent)
    }

    private fun validDexBytes(): ByteArray = byteArrayOf(
        'd'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte(),
        '0'.code.toByte(), '3'.code.toByte(), '9'.code.toByte(), 0,
        0x70, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66
    )

    private fun sha256(file: File): String = MessageDigest.getInstance("SHA-256")
        .digest(file.readBytes())
        .joinToString("") { "%02x".format(it.toInt() and 0xff) }
}
