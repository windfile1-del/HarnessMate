package dev.wind.agent

import dev.wind.agent.build.ApkAligner
import dev.wind.agent.build.ApkAlignmentRequest
import dev.wind.agent.build.ApkAlignmentVerifier
import dev.wind.agent.build.ApkAssembler
import dev.wind.agent.build.ApkAssemblyRequest
import dev.wind.agent.build.BuildOutcome
import java.io.File
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.security.MessageDigest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

/** AP2B2 default-loader/runtime and canonical APK DEX membership gates. */
object PackagedZipflingerAlignmentTests {
    private const val ZIP_ARCHIVE = "Lcom/android/zipflinger/ZipArchive;"
    private const val ZIP_SOURCE = "Lcom/android/zipflinger/ZipSource;"
    private const val ZIP_ENTRY = "Lcom/android/zipflinger/Entry;"
    private const val PROVIDER = "Ldev/wind/agent/build/ReflectionZipflingerAlignmentProvider;"
    private const val NIO_DESUGAR = "j$/nio/file"
    private const val FIXED_ZIP_TIME = 315_532_800_000L // 1980-01-01 00:00:00 UTC
    private const val ZIPFLINGER_JAR_NAME = "zipflinger-8.13.2.jar"
    private const val ANNOTATIONS_JAR_NAME = "annotations-31.13.2.jar"
    private val ANNOTATION_CLASSES = listOf(
        "com/android/annotations/NonNull.class",
        "com/android/annotations/Nullable.class",
        "com/android/annotations/TestOnly.class"
    )

    @JvmStatic
    fun main(args: Array<String>) {
        when (args.size) {
            0 -> defaultClassLoaderCanAlignAp1()
            2 -> verifyCanonicalApkDexMembership(File(args[0]), File(args[1]))
            else -> error("Usage: PackagedZipflingerAlignmentTests [<unsigned-apk> <annotations-jar>]")
        }
    }

    private fun defaultClassLoaderCanAlignAp1() {
        val classPath = System.getProperty("java.class.path")
            .split(File.pathSeparatorChar)
            .map { File(it).name }
        check(classPath.any { it == ZIPFLINGER_JAR_NAME }) {
            "Offline/test runtime must include the pinned Zipflinger program JAR"
        }
        check(classPath.none { it == ANNOTATIONS_JAR_NAME }) {
            "POM-declared annotations library must not be on the test/runtime classpath"
        }

        val loader = Thread.currentThread().contextClassLoader
            ?: PackagedZipflingerAlignmentTests::class.java.classLoader
        check(Class.forName("com.android.zipflinger.ZipArchive", false, loader) != null)

        val root = Files.createTempDirectory("vega-ap2b2-default-loader").toFile()
        try {
            val base = File(root, "resources.ap_")
            writeZip(base, listOf(
                Payload("AndroidManifest.xml", "synthetic-manifest".toByteArray(StandardCharsets.UTF_8)),
                Payload("resources.arsc", "synthetic-resource-table".toByteArray(StandardCharsets.UTF_8)),
                Payload("res/raw/default-loader-proof.bin", ByteArray(29) { (it * 11).toByte() }),
                Payload("assets/default-loader.txt", "default classloader preserves DEFLATED entries".toByteArray(), ZipEntry.DEFLATED)
            ))
            val appDex = File(root, "app-dex").apply { mkdirs() }
            File(appDex, "classes.dex").writeBytes(validDexBytes())
            val input = File(root, "unsigned-unaligned.apk")
            val assembled = ApkAssembler().assemble(
                ApkAssemblyRequest(baseResourcePackage = base, appDexDirectory = appDex, outputApk = input),
                CancellationToken()
            )
            check(assembled.outcome == BuildOutcome.SUCCESS) {
                "AP1 fixture assembly failed: ${assembled.diagnostics}"
            }

            val verifier = ApkAlignmentVerifier()
            val before = verifier.verify(input)
            check(before.outcome == BuildOutcome.SUCCESS && !before.aligned) {
                "AP1 must produce a valid unaligned input: ${before.diagnostics}"
            }

            // Deliberately use the provider's default app/test class loader. No URLClassLoader
            // injection and no host zipalign provider participate in this proof.
            val provider = dev.wind.agent.build.ReflectionZipflingerAlignmentProvider()
            check(provider.available) { "Default class loader cannot resolve the packaged Zipflinger API" }
            val output = File(root, "unsigned-aligned.apk")
            val result = ApkAligner(provider).align(ApkAlignmentRequest(input, output), CancellationToken())
            check(result.outcome == BuildOutcome.SUCCESS) {
                "Default-loader Zipflinger alignment failed: ${result.diagnostics}"
            }
            val after = verifier.verify(output)
            check(after.outcome == BuildOutcome.SUCCESS && after.aligned) {
                "Independent AP2A verifier rejected the default-loader result: ${after.diagnostics}"
            }
            check(fingerprints(before) == fingerprints(after)) {
                "Default-loader alignment changed entry names, methods, sizes, or payload SHA-256 values"
            }
            println(
                "PackagedZipflingerAlignmentTests: default-loader PASS " +
                    "alignment=ALIGNED entries=${after.entries.size} annotationsRuntime=false"
            )
        } finally {
            root.deleteRecursively()
        }
    }

    private fun verifyCanonicalApkDexMembership(apk: File, annotationsJar: File) {
        check(apk.isFile && apk.length() > 0L) { "Canonical unsigned APK is missing or empty: $apk" }
        check(annotationsJar.isFile) { "Pinned annotations JAR is missing: $annotationsJar" }
        var dexNames: List<String> = emptyList()
        ZipFile(apk).use { archive ->
            val names = archive.entries().toList().map { it.name }
            val dexEntries = archive.entries().toList()
                .filter { it.name == "classes.dex" || it.name.matches(Regex("classes[0-9]+\\.dex")) }
            dexNames = dexEntries.map { it.name }
            check(dexEntries.isNotEmpty()) { "Canonical Vega APK contains no app/L8 DEX" }
            check(dexEntries.map { it.name }.toSet().size == dexEntries.size) { "APK has duplicate DEX names" }

            fun dexContainsBytes(needle: String): Boolean {
                val bytes = needle.toByteArray(StandardCharsets.UTF_8)
                return dexEntries.any { entry ->
                    archive.getInputStream(entry).use { containsBytes(it, bytes) }
                }
            }

            // Inspect the class_defs tables, not just strings: a referenced type or dead
            // metadata string is not proof that the provider classes are packaged.
            val definedClasses = dexEntries.flatMapTo(linkedSetOf()) { entry ->
                archive.getInputStream(entry).use { readDefinedClassDescriptors(it.readBytes()) }
            }
            for (descriptor in listOf(ZIP_ARCHIVE, ZIP_SOURCE, ZIP_ENTRY, PROVIDER)) {
                check(descriptor in definedClasses) {
                    "Canonical Vega APK DEX has no class definition for $descriptor"
                }
            }

            ZipFile(annotationsJar).use { pinnedAnnotations ->
                for (entryName in ANNOTATION_CLASSES) {
                    check(pinnedAnnotations.getEntry(entryName) != null) {
                        "Expected annotation class is not present in the pinned library: $entryName"
                    }
                    val descriptor = "L${entryName.removeSuffix(".class")};"
                    check(descriptor !in definedClasses) {
                        "Annotations dependency was emitted as a DEX class definition: $descriptor"
                    }
                }
            }
            check(dexContainsBytes(NIO_DESUGAR)) {
                "Canonical Vega APK DEX lacks core-library-desugared NIO string: $NIO_DESUGAR"
            }

            check(names.none { name ->
                val normalized = name.lowercase()
                normalized.contains("zipflinger") &&
                    (normalized.startsWith("assets/") || normalized.startsWith("lib/"))
            }) { "Raw Zipflinger JAR/library payload must not be copied into APK assets or lib" }
        }

        println(
            "PackagedZipflingerAlignmentTests: APK DEX membership PASS " +
                "apk=${apk.absolutePath} bytes=${apk.length()} sha256=${sha256(apk)} " +
                "dexCount=${dexNames.size} dex=$dexNames " +
                "ZipArchive=present ZipSource=present Entry=present provider=present " +
                "annotationDescriptors=absent j$/nio/file=present"
        )
    }

    private fun readDefinedClassDescriptors(dex: ByteArray): Set<String> {
        require(dex.size >= 112) { "DEX header is truncated" }
        require(String(dex, 0, 4, StandardCharsets.US_ASCII) == "dex\n") { "Invalid DEX magic" }
        val buffer = ByteBuffer.wrap(dex).order(ByteOrder.LITTLE_ENDIAN)
        fun uint(offset: Int): Long = Integer.toUnsignedLong(buffer.getInt(offset))
        fun checkedOffset(offset: Long, size: Long, label: String): Int {
            require(offset >= 0L && size >= 0L && offset <= dex.size.toLong() - size) {
                "$label is outside the DEX file"
            }
            return offset.toInt()
        }

        require(uint(36) == 112L) { "Unexpected DEX header size" }
        require(uint(40) == 0x12345678L) { "Unsupported DEX endian tag" }
        val stringCount = uint(56)
        val stringIdsOffset = uint(60)
        val typeCount = uint(64)
        val typeIdsOffset = uint(68)
        val classCount = uint(96)
        val classDefsOffset = uint(100)
        val stringIds = checkedOffset(stringIdsOffset, stringCount * 4L, "DEX string_ids")
        val typeIds = checkedOffset(typeIdsOffset, typeCount * 4L, "DEX type_ids")
        val classDefs = checkedOffset(classDefsOffset, classCount * 32L, "DEX class_defs")

        fun readUleb128(start: Int): Pair<Int, Int> {
            var value = 0L
            var shift = 0
            var cursor = start
            while (cursor < dex.size && shift < 35) {
                val byte = dex[cursor++].toInt() and 0xff
                value = value or ((byte and 0x7f).toLong() shl shift)
                if (byte and 0x80 == 0) {
                    require(value <= Int.MAX_VALUE) { "DEX string length is too large" }
                    return value.toInt() to cursor
                }
                shift += 7
            }
            error("Malformed DEX ULEB128 string length")
        }

        fun stringAt(stringIndex: Int): String {
            require(stringIndex in 0 until stringCount.toInt()) { "DEX descriptor string index is invalid" }
            val stringIdPosition = stringIds + stringIndex * 4
            val dataOffset = checkedOffset(uint(stringIdPosition), 1L, "DEX string data")
            val (_, bytesStart) = readUleb128(dataOffset)
            var end = bytesStart
            while (end < dex.size && dex[end].toInt() != 0) end++
            require(end < dex.size) { "DEX string is not null-terminated" }
            return String(dex, bytesStart, end - bytesStart, StandardCharsets.UTF_8)
        }

        val descriptors = linkedSetOf<String>()
        for (classIndex in 0 until classCount.toInt()) {
            val classDefPosition = classDefs + classIndex * 32
            val typeIndex = uint(classDefPosition)
            require(typeIndex < typeCount) { "DEX class_def type index is invalid" }
            val descriptorStringIndex = uint(typeIds + typeIndex.toInt() * 4)
            require(descriptorStringIndex < stringCount) { "DEX descriptor string index is invalid" }
            descriptors += stringAt(descriptorStringIndex.toInt())
        }
        return descriptors
    }

    private fun containsBytes(input: InputStream, needle: ByteArray): Boolean {
        require(needle.isNotEmpty())
        val failure = IntArray(needle.size)
        var prefix = 0
        for (index in 1 until needle.size) {
            while (prefix > 0 && needle[index] != needle[prefix]) prefix = failure[prefix - 1]
            if (needle[index] == needle[prefix]) prefix++
            failure[index] = prefix
        }

        val buffer = ByteArray(32 * 1024)
        var matched = 0
        while (true) {
            val read = input.read(buffer)
            if (read < 0) return false
            for (index in 0 until read) {
                val value = buffer[index]
                while (matched > 0 && value != needle[matched]) matched = failure[matched - 1]
                if (value == needle[matched]) matched++
                if (matched == needle.size) return true
            }
        }
    }

    private fun fingerprints(result: dev.wind.agent.build.ApkAlignmentVerificationResult): List<String> =
        result.entries.map { "${it.name}:${it.method}:${it.uncompressedSize}:${it.sha256}" }

    private fun writeZip(file: File, entries: List<Payload>) {
        file.parentFile?.mkdirs()
        ZipOutputStream(file.outputStream().buffered()).use { zip ->
            entries.forEach { payload ->
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

    private fun validDexBytes(): ByteArray = byteArrayOf(
        'd'.code.toByte(), 'e'.code.toByte(), 'x'.code.toByte(), '\n'.code.toByte(),
        '0'.code.toByte(), '3'.code.toByte(), '9'.code.toByte(), 0,
        0x70, 0x00, 0x11, 0x22, 0x33, 0x44, 0x55, 0x66
    )

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                if (read > 0) digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private data class Payload(val name: String, val bytes: ByteArray, val method: Int = ZipEntry.STORED)
}
