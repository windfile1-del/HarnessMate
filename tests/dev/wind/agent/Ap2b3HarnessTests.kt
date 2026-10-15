package dev.wind.agent

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/** Static isolation gates and optional real DEX-definition checks for P6-AP2B3B. */
object Ap2b3HarnessTests {
    private const val ZIP_ARCHIVE = "Lcom/android/zipflinger/ZipArchive;"
    private const val ZIP_SOURCE = "Lcom/android/zipflinger/ZipSource;"
    private const val ZIP_ENTRY = "Lcom/android/zipflinger/Entry;"
    private const val PROVIDER = "Ldev/wind/agent/build/ReflectionZipflingerAlignmentProvider;"
    private const val INSTRUMENTATION = "Ldev/wind/agent/ap2b3/ZipflingerAcceptanceInstrumentation;"
    private val FORBIDDEN_TEST_PREFIXES = listOf("Lcom/android/zipflinger/", "Lcom/android/annotations/")

    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 0 || args.size == 1 || args.size == 3) {
            "Usage: Ap2b3HarnessTests [<repository-root> [<signed-target-apk> <signed-instrumentation-apk>]]"
        }
        val root = File(args.firstOrNull() ?: ".").canonicalFile
        staticIsolationAndRunnerGates(root)
        if (args.size == 3) verifyBuiltApks(File(args[1]), File(args[2]))
        println("Ap2b3HarnessTests: PASS static isolation and runner fail-closed gates")
    }

    private fun staticIsolationAndRunnerGates(root: File) {
        val testManifest = parseXml(read(root, "device-tests/ap2b3/AndroidManifest.xml"))
        val testRoot = testManifest.documentElement
        check(testRoot.getAttribute("package") == "dev.wind.agent.ap2b3")
        val usesSdk = testManifest.getElementsByTagName("uses-sdk")
        check(usesSdk.length == 1)
        check(usesSdk.item(0).attributes.getNamedItemNS(ANDROID_NS, "minSdkVersion")?.nodeValue == "23")
        check(usesSdk.item(0).attributes.getNamedItemNS(ANDROID_NS, "targetSdkVersion")?.nodeValue == "35")
        val instrumentation = testManifest.getElementsByTagName("instrumentation")
        check(instrumentation.length == 1) { "Acceptance APK must declare exactly one instrumentation entry" }
        val instrumentationNode = instrumentation.item(0)
        check(instrumentationNode.attributes.getNamedItemNS(ANDROID_NS, "targetPackage")?.nodeValue == "dev.wind.agent")
        check(instrumentationNode.attributes.getNamedItemNS(ANDROID_NS, "name")?.nodeValue ==
            "dev.wind.agent.ap2b3.ZipflingerAcceptanceInstrumentation")
        check(testRoot.getElementsByTagName("activity").length == 0)
        check(testRoot.getElementsByTagName("activity-alias").length == 0)
        check(testRoot.getElementsByTagName("receiver").length == 0)
        check(testRoot.getElementsByTagName("service").length == 0)

        val productionManifest = read(root, "AndroidManifest.xml")
        check(!productionManifest.contains("dev.wind.agent.ap2b3")) {
            "Acceptance package leaked into the production manifest"
        }
        check(parseXml(productionManifest).getElementsByTagName("instrumentation").length == 0)
        val productionSource = File(root, "src/dev/wind/agent")
        check(productionSource.walkTopDown().none { file ->
            file.isFile && (file.name.contains("Ap2b3", ignoreCase = true) ||
                file.readText(Charsets.UTF_8).contains("ZipflingerAcceptanceInstrumentation"))
        }) { "Acceptance instrumentation leaked into production source" }

        val instrumentationSource = read(
            root,
            "device-tests/ap2b3/src/dev/wind/agent/ap2b3/ZipflingerAcceptanceInstrumentation.java"
        )
        check(instrumentationSource.contains("getTargetContext()"))
        check(instrumentationSource.contains("target.getClassLoader()"))
        check(instrumentationSource.contains("j$.nio.file.Path"))
        check(instrumentationSource.contains("target_class_loader_identity"))
        check(instrumentationSource.contains("desugared_nio_path_loaded"))
        check(instrumentationSource.contains("nio_file_io_exercised"))
        check(instrumentationSource.contains("ReflectionZipflingerAlignmentProvider"))
        check(instrumentationSource.contains("ApkAlignmentVerifier"))
        check(instrumentationSource.contains("ApkAligner"))
        check(instrumentationSource.contains("getEntries"))
        check(instrumentationSource.contains("cause_chain"))
        check(instrumentationSource.contains("getCacheDir()"))
        check(instrumentationSource.contains("targetCachePath.getCanonicalFile()"))
        check(instrumentationSource.contains("target_cache_dir_canonicalized"))
        check(instrumentationSource.contains("workDirectoryReady"))
        check(instrumentationSource.contains("O_NOFOLLOW"))
        check(instrumentationSource.contains("Os.rename("))
        check(instrumentationSource.contains("Refusing to remove an unowned file"))
        check(instrumentationSource.contains("Refusing to replace a non-regular result.json path"))
        check(instrumentationSource.contains("input_verification"))
        check(instrumentationSource.contains("output_verification"))
        check(!Regex("(?im)^\\s*import\\s+com\\.android\\.zipflinger\\.").containsMatchIn(instrumentationSource))
        check(!instrumentationSource.contains("URLClassLoader"))
        check(!instrumentationSource.contains("DexClassLoader"))
        check(!instrumentationSource.contains("org.junit"))
        check(!instrumentationSource.contains("androidx."))
        check(!Regex("(?im)^\\s*import\\s+kotlin\\.").containsMatchIn(instrumentationSource))

        val buildScript = read(root, "tools/build-ap2b3-instrumentation.ps1")
        check(buildScript.contains("--min-api", ignoreCase = true) && buildScript.contains("\"23\""))
        check(buildScript.contains("-bootclasspath"))
        check(buildScript.contains("ap2b3-acceptance.p12"))
        check(buildScript.contains("Refusing to reuse pre-existing signing material"))
        check(buildScript.contains("New-SigningMaterial \$OutputFullPath"))
        check(!buildScript.contains("New-SigningMaterial \$AcceptanceRoot"))
        check(buildScript.contains("-storepass:env"))
        check(buildScript.contains("-keypass:env"))
        check(buildScript.contains("env:\$(\$Signing.PasswordEnvironmentName)"))
        check(buildScript.contains("Invoke-WithSigningPassword"))
        check(!buildScript.contains("ap2b3-acceptance.keypass"))
        check(!buildScript.contains("pass:\$(\$Signing.Password)"))
        check(buildScript.contains(".build-ap2b3b-"))
        check(buildScript.contains("ScratchRoot"))
        check(!Regex("(?i)\\.build-ap2b3(?!b)").containsMatchIn(buildScript))
        check(buildScript.contains("keyStore = \$Signing.KeyPath"))
        check(buildScript.contains("not a release identity"))
        check(!buildScript.contains("VEPRO_KEYSTORE_PATH"))
        check(!buildScript.contains("zipflinger-8.13.2.jar"))
        check(!buildScript.contains("annotations-31.13.2.jar"))
        check(buildScript.contains("target-acceptance-unsigned.apk"))
        check(buildScript.contains("target-acceptance.apk"))
        check(buildScript.contains("ap2b3-instrumentation.apk"))

        val runner = read(root, "tools/run-ap2b3-acceptance.ps1")
        for (code in listOf(
            "AP2B3_DEVICE_UNAVAILABLE", "AP2B3_DEVICE_AMBIGUOUS", "AP2B3_DEVICE_NOT_FOUND",
            "AP2B3B_API_LEVEL_MISMATCH", "AP2B3B_EMULATOR_REQUIRED", "AP2B3_TARGET_ALREADY_INSTALLED",
            "AP2B3_TEST_ALREADY_INSTALLED", "AP2B3_INSTRUMENTATION_FAILED"
        )) check(runner.contains(code)) { "Host runner lacks fail-closed result: $code" }
        check(runner.contains("[switch]\$KeepInstalled"))
        check(!runner.contains("AllowReplace"))
        check(!runner.contains("@(\"install\", \"-r\""))
        check(runner.contains("Get-TargetInstallMode \$targetWasInstalled"))
        check(runner.contains("Resolve-PackagePathState"))
        check(runner.contains("Resolve-PackageListState"))
        check(runner.contains("malformed successful output"))
        check(runner.contains("^package:/(?!/)[^\\s:]+\\.apk$"))
        check(runner.contains("input_sha256"))
        check(runner.contains("providerClassLoader"))
        check(runner.contains("desugaredNioPathLoaded"))
        check(runner.contains("androidRelease"))
        check(runner.contains("abi = \$deviceAbi"))
        check(runner.contains("ScratchRoot"))
        check(!Regex("(?i)\\.build-ap2b3(?!b)").containsMatchIn(runner))
        check(runner.contains("INSTRUMENTATION_RESULT"))
        check(runner.contains("INSTRUMENTATION_CODE"))
        check(runner.contains("[switch]\$SelfTest"))
        check(!runner.contains("VEPRO_KEYSTORE_PATH"))
        check(!Regex("(?i)logcat.{0,20}-c").containsMatchIn(runner)) {
            "Runner must not clear the device-wide log buffer"
        }
        check(runner.contains("if (\$testInstalledThisRun)"))
        check(runner.contains("if (\$targetInstalledThisRun)"))

        val ignore = read(root, ".gitignore")
        check(Regex("(?m)^\\.build-\\*/$").containsMatchIn(ignore)) {
            ".build-ap2b3 scratch and local signing material must remain ignored"
        }
    }

    private fun verifyBuiltApks(targetApk: File, instrumentationApk: File) {
        val targetClasses = definedClasses(targetApk)
        val testClasses = definedClasses(instrumentationApk)
        for (required in listOf(ZIP_ARCHIVE, ZIP_SOURCE, ZIP_ENTRY, PROVIDER)) {
            check(required in targetClasses) { "Signed target APK lacks target DEX class definition $required" }
        }
        check(INSTRUMENTATION !in targetClasses) { "Instrumentation class leaked into target APK DEX" }
        check(INSTRUMENTATION in testClasses) { "Instrumentation APK lacks its runner class definition" }
        check(testClasses == setOf(INSTRUMENTATION)) {
            "Instrumentation APK DEX must contain only the acceptance instrumentation class: $testClasses"
        }
        check(testClasses.none { descriptor -> FORBIDDEN_TEST_PREFIXES.any(descriptor::startsWith) }) {
            "Instrumentation APK contains Zipflinger/annotations classes"
        }
        check(targetClasses.none { it.startsWith("Ldev/wind/agent/ap2b3/") }) {
            "Target APK contains AP2B3 acceptance classes"
        }
        println("Ap2b3HarnessTests: DEX class_defs PASS target=${targetClasses.size} test=${testClasses.size}")
    }

    private fun definedClasses(apk: File): Set<String> {
        check(apk.isFile && apk.length() > 0L) { "APK is missing or empty: $apk" }
        ZipFile(apk).use { zip ->
            val dexEntries = zip.entries().asSequence()
                .filter { it.name == "classes.dex" || it.name.matches(Regex("classes[0-9]+\\.dex")) }
                .toList()
            check(dexEntries.isNotEmpty()) { "APK has no DEX entries: $apk" }
            return dexEntries.flatMapTo(linkedSetOf()) { entry ->
                zip.getInputStream(entry).use { readDexClassDefinitions(it.readBytes()) }
            }
        }
    }

    private fun readDexClassDefinitions(dex: ByteArray): Set<String> {
        require(dex.size >= 112) { "DEX header is truncated" }
        require(String(dex, 0, 4, StandardCharsets.US_ASCII) == "dex\n") { "Invalid DEX magic" }
        val buffer = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        fun uint(offset: Int): Long = Integer.toUnsignedLong(buffer.getInt(offset))
        fun boundedOffset(offset: Long, size: Long, label: String): Int {
            require(offset >= 0L && size >= 0L && offset <= dex.size.toLong() - size) {
                "$label exceeds DEX bounds"
            }
            return offset.toInt()
        }

        require(uint(36) == 112L && uint(40) == 0x12345678L) { "Unsupported DEX header/endian tag" }
        val stringCount = uint(56)
        val stringIds = boundedOffset(uint(60), stringCount * 4L, "string_ids")
        val typeCount = uint(64)
        val typeIds = boundedOffset(uint(68), typeCount * 4L, "type_ids")
        val classCount = uint(96)
        val classDefs = boundedOffset(uint(100), classCount * 32L, "class_defs")

        fun readUleb128PayloadStart(start: Int): Int {
            var value = 0L
            var shift = 0
            var cursor = start
            while (cursor < dex.size && shift < 35) {
                val next = dex[cursor++].toInt() and 0xff
                value = value or ((next and 0x7f).toLong() shl shift)
                if (next and 0x80 == 0) {
                    require(value <= Int.MAX_VALUE) { "DEX string length is too large" }
                    return cursor
                }
                shift += 7
            }
            error("Malformed DEX ULEB128")
        }

        fun descriptorAt(stringIndex: Int): String {
            require(stringIndex >= 0 && stringIndex.toLong() < stringCount) { "DEX descriptor index is invalid" }
            val stringDataOffset = boundedOffset(uint(stringIds + stringIndex * 4), 1L, "string_data")
            val payloadStart = readUleb128PayloadStart(stringDataOffset)
            var end = payloadStart
            while (end < dex.size && dex[end].toInt() != 0) end++
            require(end < dex.size) { "DEX descriptor is not null-terminated" }
            return String(dex, payloadStart, end - payloadStart, StandardCharsets.UTF_8)
        }

        fun classDescriptor(typeIndex: Long): String {
            require(typeIndex < typeCount) { "DEX class type index is invalid" }
            val descriptorIndex = uint(typeIds + typeIndex.toInt() * 4)
            require(descriptorIndex < stringCount) { "DEX type descriptor string index is invalid" }
            return descriptorAt(descriptorIndex.toInt())
        }

        return buildSet {
            for (index in 0 until classCount.toInt()) {
                add(classDescriptor(uint(classDefs + index * 32)))
            }
        }
    }

    private fun parseXml(xml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
            setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeature("http://xml.org/sax/features/external-general-entities", false)
            setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        }
        return factory.newDocumentBuilder().parse(xml.byteInputStream(Charsets.UTF_8))
    }

    private fun read(root: File, relative: String): String = File(root, relative).readText(Charsets.UTF_8)

    private const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
}
