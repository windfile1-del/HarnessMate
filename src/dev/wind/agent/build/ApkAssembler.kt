package dev.wind.agent.build

import dev.wind.agent.CancellationToken
import java.io.BufferedOutputStream
import java.io.File
import java.io.IOException
import java.nio.file.DirectoryStream
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest
import java.util.Collections
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import java.nio.charset.CodingErrorAction

data class ApkAssemblyRequest(
    val baseResourcePackage: File,
    val appDexDirectory: File,
    val runtimeDexDirectory: File? = null,
    val outputApk: File,
    val maxBaseEntries: Int = 16_384,
    val maxBaseUncompressedBytes: Long = 512L * 1024L * 1024L,
    val maxDexFiles: Int = 128,
    val maxDexBytes: Long = 512L * 1024L * 1024L,
    val dependencyResourceArchives: List<File> = emptyList(),
    val maxDependencyEntries: Int = 20_000,
    val maxDependencyArchiveBytes: Long = 512L * 1024L * 1024L,
    val maxDependencyUncompressedBytes: Long = 512L * 1024L * 1024L,
    val maxServiceDescriptorBytes: Int = 1024 * 1024,
    val maxMergedServiceBytes: Long = 8L * 1024L * 1024L
)

class ApkAssemblyResult(
    val outcome: BuildOutcome,
    diagnostics: List<BuildDiagnostic> = emptyList(),
    artifacts: List<BuildArtifact> = emptyList(),
    val durationMs: Long = 0L
) {
    val diagnostics: List<BuildDiagnostic> = Collections.unmodifiableList(diagnostics.toList())
    val artifacts: List<BuildArtifact> = Collections.unmodifiableList(artifacts.toList())

    init {
        require(durationMs >= 0L) { "APK_ASSEMBLY_DURATION_INVALID" }
        if (outcome == BuildOutcome.SUCCESS) {
            require(this.diagnostics.none { it.severity == BuildDiagnosticSeverity.ERROR }) {
                "APK_ASSEMBLY_SUCCESS_WITH_ERROR"
            }
            require(this.artifacts.size == 1 && this.artifacts.single().kind == "android-unsigned-apk") {
                "APK_ASSEMBLY_SUCCESS_ARTIFACT_INVALID"
            }
        }
    }
}

/** Pure JVM ZIP assembly. This deliberately produces an unsigned, unaligned APK only. */
class ApkAssembler {
    fun assemble(request: ApkAssemblyRequest, token: CancellationToken): ApkAssemblyResult {
        val started = System.nanoTime()
        var output: Path? = null
        var temporary: Path? = null
        var outputMayBeCleaned = false
        try {
            val basePath = safeAbsolutePath(request.baseResourcePackage, "APK_ASSEMBLY_INPUT_INVALID", "base resource package")
            val appDirectory = safeAbsolutePath(request.appDexDirectory, "APK_ASSEMBLY_INPUT_INVALID", "app DEX directory")
            val runtimeDirectory = request.runtimeDexDirectory?.let {
                safeAbsolutePath(it, "APK_ASSEMBLY_INPUT_INVALID", "runtime DEX directory")
            }
            val dependencyArchives = request.dependencyResourceArchives.map {
                safeAbsolutePath(it, "APK_ASSEMBLY_INPUT_INVALID", "dependency resource archive")
            }
            output = outputPath(request.outputApk)
            validateNoOverlap(output, listOfNotNull(basePath, appDirectory, runtimeDirectory) + dependencyArchives)
            ensureOutputParent(output)

            // A failed attempt must never leave an older APK looking like this run's result.
            removeSafeStaleOutput(output)
            outputMayBeCleaned = true
            token.throwIfCancelled()

            validateLimits(request)
            validateInputFile(basePath, "base resource package", requireNonEmpty = true)
            validateInputDirectory(appDirectory, "app DEX directory")
            runtimeDirectory?.let { validateInputDirectory(it, "runtime DEX directory") }

            val baseEntries = scanBasePackage(basePath, request, token)
            val resources = scanDependencyResources(basePath, baseEntries, dependencyArchives, request, token)
            val appDex = scanDexDirectory(appDirectory, "app", request.maxDexFiles, request.maxDexBytes, token)
            val appDexBytes = appDex.sumOf { it.size }
            val runtimeDex = runtimeDirectory?.let {
                scanDexDirectory(
                    it,
                    "runtime",
                    request.maxDexFiles - appDex.size,
                    request.maxDexBytes - appDexBytes,
                    token
                )
            }.orEmpty()
            val combinedCount = appDex.size.toLong() + runtimeDex.size.toLong()
            val runtimeBytes = runtimeDex.sumOf { it.size }
            val combinedBytes = appDexBytes + runtimeBytes
            if (combinedCount > request.maxDexFiles.toLong() || combinedBytes > request.maxDexBytes) {
                fail(
                    BuildOutcome.RESOURCE_LIMIT,
                    "APK_DEX_INPUT_LIMIT",
                    "Combined app and runtime DEX inputs exceed configured limits"
                )
            }
            val dexEntries = mapDexEntries(appDex, runtimeDex)
            ensureNoBaseNamespaceConflict(resources.baseEntries + resources.entries.map { it.entry }, dexEntries)

            temporary = Files.createTempFile(output.parent, ".${output.fileName}.ap1-", ".tmp")
            token.throwIfCancelled()
            verifyResourceArchives(resources, token)
            writePackage(basePath, resources.baseEntries, dexEntries, resources.entries, temporary, token)
            verifyResourceArchives(resources, token)
            token.throwIfCancelled()
            val expectedResources = resources.baseEntries + resources.entries.map { it.entry }
            validateAssembledPackage(temporary, expectedResources, dexEntries, token)
            token.throwIfCancelled()
            validateOutputStillSafe(output)
            if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
                fail(BuildOutcome.FAILED, "APK_ASSEMBLY_OUTPUT_INVALID", "A file appeared at the APK output during assembly", output)
            }
            Files.move(temporary, output, StandardCopyOption.ATOMIC_MOVE)
            temporary = null

            validateOutputStillSafe(output)
            validateAssembledPackage(output, expectedResources, dexEntries, token)
            token.throwIfCancelled()
            val artifact = BuildArtifact("android-unsigned-apk", output.toFile(), sha256(output, token))
            return ApkAssemblyResult(
                BuildOutcome.SUCCESS,
                diagnostics = listOf(info("APK_UNSIGNED_UNALIGNED", "Created unsigned, unaligned APK")),
                artifacts = listOf(artifact),
                durationMs = elapsedMs(started)
            )
        } catch (cancelled: CancellationToken.CancelledException) {
            cleanup(output, temporary, outputMayBeCleaned)
            return failure(started, BuildOutcome.CANCELLED, "BUILD_CANCELLED", "APK assembly was cancelled")
        } catch (problem: ApkAssemblyProblem) {
            cleanup(output, temporary, outputMayBeCleaned)
            return failure(started, problem.outcome, problem.code, problem.message ?: problem.code, problem.path)
        } catch (error: Exception) {
            cleanup(output, temporary, outputMayBeCleaned)
            return failure(
                started,
                BuildOutcome.FAILED,
                "APK_ASSEMBLY_FAILED",
                "APK assembly failed: ${error.message ?: error.javaClass.simpleName}"
            )
        }
    }

    private data class PackageEntry(
        val name: String,
        val method: Int,
        val size: Long,
        val crc: Long,
        val sha256: String,
        val directory: Boolean
    )

    private data class DexInput(
        val file: Path,
        val originalName: String,
        val index: Int,
        val size: Long,
        val sha256: String
    )

    private data class DexEntry(val input: DexInput, val apkName: String)

    private data class DependencyEntry(val entry: PackageEntry, val archive: Path?, val merged: ByteArray? = null)
    private data class ResourcePlan(val baseEntries: List<PackageEntry>, val entries: List<DependencyEntry>,
        val archiveHashes: Map<Path, String>)

    private data class StreamDigest(val size: Long, val crc: Long, val sha256: String, val magic: ByteArray? = null)

    private fun validateLimits(request: ApkAssemblyRequest) {
        if (request.maxBaseEntries <= 0 || request.maxBaseUncompressedBytes <= 0L ||
            request.maxDexFiles <= 0 || request.maxDexBytes <= 0L ||
            request.maxDependencyEntries <= 0 || request.maxDependencyArchiveBytes <= 0L ||
            request.maxDependencyUncompressedBytes <= 0L || request.maxServiceDescriptorBytes <= 0 || request.maxMergedServiceBytes <= 0L
        ) {
            fail(BuildOutcome.FAILED, "APK_ASSEMBLY_INPUT_INVALID", "Assembly limits must be positive")
        }
    }

    private fun scanDependencyResources(basePath: Path, base: List<PackageEntry>, archives: List<Path>,
        request: ApkAssemblyRequest, token: CancellationToken): ResourcePlan {
        if (archives.isEmpty()) return ResourcePlan(base, emptyList(), emptyMap())
        val selected = linkedMapOf<String, DependencyEntry>()
        val baseByName = base.associateBy { it.name }
        val services = linkedMapOf<String, LinkedHashSet<String>>()
        val serviceSizes = mutableMapOf<String, Long>()
        var serviceBytes = 0L
        val hashes = linkedMapOf<Path, String>()
        var archiveBytes = 0L
        var resourceBytes = 0L
        var entryCount = 0
        fun readServices(zip: ZipFile, source: ZipEntry): ByteArray {
            val bytes = zip.getInputStream(source).use { input ->
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(BUFFER_SIZE)
                while (true) {
                    token.throwIfCancelled()
                    val n = input.read(buffer)
                    if (n < 0) break
                    if (n == 0) continue
                    if (n > request.maxServiceDescriptorBytes - out.size())
                        fail(BuildOutcome.RESOURCE_LIMIT, "APK_DEPENDENCY_RESOURCE_LIMIT", "Service descriptor exceeds configured limit")
                    out.write(buffer, 0, n)
                }
                out.toByteArray()
            }
            val crc = CRC32().also { it.update(bytes) }.value
            if (source.size != bytes.size.toLong() || source.crc != crc)
                fail(BuildOutcome.FAILED, "APK_DEPENDENCY_RESOURCE_INVALID", "Service descriptor CRC/size mismatch: ${source.name}")
            return bytes
        }
        fun mergeService(name: String, bytes: ByteArray) {
            val type = name.removePrefix("META-INF/services/")
            if (!isServiceClassName(type)) fail(BuildOutcome.FAILED, "APK_DEPENDENCY_RESOURCE_INVALID", "Invalid service type: $name")
            val text = try {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
            } catch (_: Exception) { fail(BuildOutcome.FAILED, "APK_DEPENDENCY_RESOURCE_INVALID", "Service descriptor is not UTF-8: $name") }
            val providers = services.getOrPut(name) { linkedSetOf() }
            text.lineSequence().forEach { line ->
                val provider = line.substringBefore('#').trim()
                if (provider.isNotEmpty()) {
                    if (!isServiceClassName(provider)) fail(BuildOutcome.FAILED, "APK_DEPENDENCY_RESOURCE_INVALID", "Invalid service provider: $name")
                    if (provider !in providers) {
                        val delta = provider.toByteArray(Charsets.UTF_8).size.toLong() + 1L
                        val current = serviceSizes[name] ?: 0L
                        if (delta > request.maxServiceDescriptorBytes.toLong() - current || delta > request.maxMergedServiceBytes - serviceBytes)
                            fail(BuildOutcome.RESOURCE_LIMIT, "APK_DEPENDENCY_RESOURCE_LIMIT", "Merged service descriptors exceed configured limit")
                        providers.add(provider)
                        serviceSizes[name] = current + delta
                        serviceBytes += delta
                    }
                }
            }
        }
        // Base services participate first. Recheck the whole base archive when merging
        // because the original service entry is replaced rather than copied verbatim.
        hashes[basePath] = sha256(basePath, token)
        ZipFile(basePath.toFile()).use { zip ->
            base.filter { !it.directory && it.name.startsWith("META-INF/services/") }.forEach { expected ->
                val source = zip.getEntry(expected.name) ?: fail(BuildOutcome.FAILED, "APK_BASE_PACKAGE_INVALID", "Base service disappeared")
                val bytes = readServices(zip, source)
                if (MessageDigest.getInstance("SHA-256").digest(bytes).toHex() != expected.sha256)
                    fail(BuildOutcome.FAILED, "APK_BASE_PACKAGE_INVALID", "Base service changed during merge")
                mergeService(expected.name, bytes)
            }
        }
        archives.distinct().forEach { archive ->
            token.throwIfCancelled()
            validateInputFile(archive, "dependency resource archive", true)
            val size = Files.size(archive)
            if (size > request.maxDependencyArchiveBytes - archiveBytes)
                fail(BuildOutcome.RESOURCE_LIMIT, "APK_DEPENDENCY_RESOURCE_LIMIT", "Dependency archive bytes exceed configured limit")
            archiveBytes += size
            hashes[archive] = sha256(archive, token)
            ZipFile(archive.toFile()).use { zip ->
                val names = mutableSetOf<String>()
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    token.throwIfCancelled()
                    val source = entries.nextElement()
                    val name = source.name
                    if (!isSafeZipName(name) || name.length > 4096 || name.any { it < ' ' || it == ':' } || !names.add(name))
                        fail(BuildOutcome.FAILED, "APK_DEPENDENCY_RESOURCE_INVALID", "Unsafe or duplicate dependency entry: $name")
                    if (++entryCount > request.maxDependencyEntries)
                        fail(BuildOutcome.RESOURCE_LIMIT, "APK_DEPENDENCY_RESOURCE_LIMIT", "Dependency entry count exceeds configured limit")
                    if (name == "AndroidManifest.xml" || name == "resources.arsc" || name.endsWith(".dex", true) ||
                        name.startsWith("res/") || name.endsWith(".so", true))
                        fail(BuildOutcome.FAILED, "APK_DEPENDENCY_RESERVED_ENTRY", "Dependency cannot supply APK/native namespace: $name")
                    val upperName = name.uppercase(java.util.Locale.US)
                    val jarSignatureMarker = upperName.startsWith("META-INF/SIG-") && !upperName.removePrefix("META-INF/").contains('/')
                    if (source.isDirectory || name.endsWith(".class") || isOldSignatureName(name) || jarSignatureMarker) continue
                    if (source.method != ZipEntry.STORED && source.method != ZipEntry.DEFLATED)
                        fail(BuildOutcome.FAILED, "APK_DEPENDENCY_RESOURCE_INVALID", "Unsupported dependency compression: $name")
                    val measured = zip.getInputStream(source).use {
                        digestStream(it, token, maxBytes = request.maxDependencyUncompressedBytes - resourceBytes,
                            limitCode = "APK_DEPENDENCY_RESOURCE_LIMIT")
                    }
                    if (source.size != measured.size || source.crc != measured.crc)
                        fail(BuildOutcome.FAILED, "APK_DEPENDENCY_RESOURCE_INVALID", "Dependency resource CRC/size mismatch: $name")
                    resourceBytes += measured.size
                    if (name.startsWith("META-INF/services/")) {
                        mergeService(name, readServices(zip, source))
                        continue
                    }
                    val previous = baseByName[name] ?: selected[name]?.entry
                    if (previous != null) {
                        if (previous.directory || previous.size != measured.size || previous.sha256 != measured.sha256)
                            fail(BuildOutcome.FAILED, "APK_DEPENDENCY_RESOURCE_CONFLICT", "Conflicting dependency resource: $name")
                    } else selected[name] = DependencyEntry(
                        PackageEntry(name, source.method, measured.size, measured.crc, measured.sha256, false), archive)
                }
            }
        }
        services.forEach { (name, providers) ->
            val bytes = if (providers.isEmpty()) byteArrayOf() else (providers.joinToString("\n") + "\n").toByteArray(Charsets.UTF_8)
            val crc = CRC32().also { it.update(bytes) }.value
            selected[name] = DependencyEntry(PackageEntry(name, ZipEntry.DEFLATED, bytes.size.toLong(), crc,
                MessageDigest.getInstance("SHA-256").digest(bytes).toHex(), false), null, bytes)
        }
        val preserved = base.filter { it.name !in services }
        val all = preserved + selected.values.map { it.entry }
        val fileNames = all.filter { !it.directory }.map { it.name }.toSet()
        all.forEach { entry ->
            if (entry.directory && entry.name.trimEnd('/') in fileNames)
                fail(BuildOutcome.FAILED, "APK_DEPENDENCY_RESOURCE_CONFLICT", "Resource is both a file and directory")
            var parent = entry.name.trimEnd('/').substringBeforeLast('/', "")
            while (parent.isNotEmpty()) {
                if (parent in fileNames) fail(BuildOutcome.FAILED, "APK_DEPENDENCY_RESOURCE_CONFLICT", "Resource file/directory prefix collision")
                parent = parent.substringBeforeLast('/', "")
            }
        }
        val archiveOrder = archives.distinct().withIndex().associate { it.value to it.index }
        val plan = ResourcePlan(preserved, selected.values.sortedWith(
            compareBy<DependencyEntry> { archiveOrder[it.archive] ?: -1 }.thenBy { it.entry.name }), hashes)
        verifyResourceArchives(plan, token)
        return plan
    }

    private fun isServiceClassName(name: String): Boolean = name.isNotEmpty() && name.split('.').all { part ->
        part.isNotEmpty() && Character.isJavaIdentifierStart(part[0]) && part.drop(1).all { Character.isJavaIdentifierPart(it) }
    }

    private fun verifyResourceArchives(plan: ResourcePlan, token: CancellationToken) {
        plan.archiveHashes.forEach { (archive, digest) ->
            validateInputFileStillSafe(archive)
            if (sha256(archive, token) != digest)
                fail(BuildOutcome.FAILED, "APK_DEPENDENCY_RESOURCE_CHANGED", "Resource archive changed during assembly", archive)
        }
    }

    private fun scanBasePackage(path: Path, request: ApkAssemblyRequest, token: CancellationToken): List<PackageEntry> {
        val entries = mutableListOf<PackageEntry>()
        val names = mutableSetOf<String>()
        var totalBytes = 0L
        try {
            validateInputFileStillSafe(path)
            ZipFile(path.toFile()).use { zip ->
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    token.throwIfCancelled()
                    val entry = enumeration.nextElement()
                    val name = entry.name
                    if (!isSafeZipName(name) || !names.add(name)) {
                        fail(BuildOutcome.FAILED, "APK_BASE_PACKAGE_INVALID", "Base package contains an unsafe or duplicate ZIP name: $name")
                    }
                    if (entries.size >= request.maxBaseEntries) {
                        fail(BuildOutcome.RESOURCE_LIMIT, "APK_BASE_PACKAGE_LIMIT", "Base package entry count exceeds ${request.maxBaseEntries}")
                    }
                    if (isBaseDexName(name)) {
                        fail(BuildOutcome.FAILED, "APK_DEX_NAMESPACE_CONFLICT", "Base package already contains a DEX entry: $name")
                    }
                    if (isOldSignatureName(name)) {
                        fail(BuildOutcome.FAILED, "APK_BASE_PACKAGE_INVALID", "Base package contains signature metadata that is not preserved: $name")
                    }
                    if (entry.method != ZipEntry.STORED && entry.method != ZipEntry.DEFLATED) {
                        fail(BuildOutcome.FAILED, "APK_BASE_PACKAGE_INVALID", "Base package uses unsupported compression for $name")
                    }
                    val remainingBytes = request.maxBaseUncompressedBytes - totalBytes
                    val measured = zip.getInputStream(entry).use {
                        digestStream(it, token, maxBytes = remainingBytes, limitCode = "APK_BASE_PACKAGE_LIMIT")
                    }
                    if (entry.size >= 0L && measured.size != entry.size || entry.crc >= 0L && measured.crc != entry.crc) {
                        fail(BuildOutcome.FAILED, "APK_BASE_PACKAGE_INVALID", "Base package entry metadata does not match content: $name")
                    }
                    if (measured.size > request.maxBaseUncompressedBytes - totalBytes) {
                        fail(BuildOutcome.RESOURCE_LIMIT, "APK_BASE_PACKAGE_LIMIT", "Base package uncompressed size exceeds ${request.maxBaseUncompressedBytes} bytes")
                    }
                    totalBytes += measured.size
                    entries += PackageEntry(name, entry.method, measured.size, measured.crc, measured.sha256, entry.isDirectory)
                }
            }
        } catch (problem: ApkAssemblyProblem) {
            throw problem
        } catch (cancelled: CancellationToken.CancelledException) {
            throw cancelled
        } catch (error: Exception) {
            fail(BuildOutcome.FAILED, "APK_BASE_PACKAGE_INVALID", "Could not read base resource ZIP: ${error.message ?: error.javaClass.simpleName}", path)
        }
        if (entries.none { it.name == "AndroidManifest.xml" && !it.directory } ||
            entries.none { it.name == "resources.arsc" && !it.directory }
        ) {
            fail(BuildOutcome.FAILED, "APK_BASE_PACKAGE_INVALID", "Base package must contain root AndroidManifest.xml and resources.arsc", path)
        }
        return entries.sortedBy { it.name }
    }

    private fun scanDexDirectory(
        directory: Path,
        role: String,
        maxFiles: Int,
        maxBytes: Long,
        token: CancellationToken
    ): List<DexInput> {
        val byIndex = sortedMapOf<Int, DexInput>()
        try {
            validateInputDirectoryStillSafe(directory)
            Files.newDirectoryStream(directory).use { stream ->
                for (child in stream) {
                    token.throwIfCancelled()
                    val attrs = attributes(child)
                    if (attrs.isSymbolicLink || !attrs.isRegularFile) {
                        fail(BuildOutcome.FAILED, "APK_DEX_INPUT_INVALID", "$role DEX directory must contain only direct ordinary DEX files", child)
                    }
                    val name = child.fileName.toString()
                    val index = dexIndex(name)
                        ?: fail(BuildOutcome.FAILED, "APK_DEX_INPUT_INVALID", "$role DEX directory contains an unexpected file: $name", child)
                    if (byIndex.containsKey(index)) {
                        fail(BuildOutcome.FAILED, "APK_DEX_INPUT_INVALID", "$role DEX directory contains duplicate DEX index $index", child)
                    }
                    if (byIndex.size >= maxFiles) {
                        fail(BuildOutcome.RESOURCE_LIMIT, "APK_DEX_INPUT_LIMIT", "$role DEX file count exceeds $maxFiles", child)
                    }
                    val remainingBytes = maxBytes - byIndex.values.sumOf { it.size }
                    validateInputFileStillSafe(child)
                    val measured = Files.newInputStream(child, StandardOpenOption.READ).use {
                        digestStream(it, token, includeMagic = true, maxBytes = remainingBytes, limitCode = "APK_DEX_INPUT_LIMIT")
                    }
                    if (measured.size <= 8L || !validDexMagic(measured.magic)) {
                        fail(BuildOutcome.FAILED, "APK_DEX_INPUT_INVALID", "$role DEX file has invalid DEX magic or is too short: $name", child)
                    }
                    if (measured.size > maxBytes) {
                        fail(BuildOutcome.RESOURCE_LIMIT, "APK_DEX_INPUT_LIMIT", "$role DEX bytes exceed $maxBytes", child)
                    }
                    byIndex[index] = DexInput(child, name, index, measured.size, measured.sha256)
                }
            }
        } catch (problem: ApkAssemblyProblem) {
            throw problem
        } catch (cancelled: CancellationToken.CancelledException) {
            throw cancelled
        } catch (error: Exception) {
            fail(BuildOutcome.FAILED, "APK_DEX_INPUT_INVALID", "Could not scan $role DEX directory: ${error.message ?: error.javaClass.simpleName}", directory)
        }
        if (byIndex.isEmpty()) fail(BuildOutcome.FAILED, "APK_DEX_INPUT_INVALID", "$role DEX directory is empty", directory)
        var expected = 1
        for (index in byIndex.keys) {
            if (index != expected) fail(BuildOutcome.FAILED, "APK_DEX_INPUT_INVALID", "$role DEX files must be contiguous from classes.dex", directory)
            expected++
        }
        val result = byIndex.values.toList()
        var bytes = 0L
        for (dex in result) {
            if (dex.size > maxBytes - bytes) fail(BuildOutcome.RESOURCE_LIMIT, "APK_DEX_INPUT_LIMIT", "$role DEX bytes exceed $maxBytes", directory)
            bytes += dex.size
        }
        return result
    }

    private fun mapDexEntries(appDex: List<DexInput>, runtimeDex: List<DexInput>): List<DexEntry> {
        val mapped = mutableListOf<DexEntry>()
        appDex.forEach { mapped += DexEntry(it, dexName(it.index)) }
        var next = appDex.size + 1
        runtimeDex.forEach { dex -> mapped += DexEntry(dex, dexName(next++)) }
        return mapped
    }

    private fun ensureNoBaseNamespaceConflict(base: List<PackageEntry>, dex: List<DexEntry>) {
        val dexNames = dex.map { it.apkName }.toSet()
        val conflict = base.firstOrNull { it.name in dexNames || isBaseDexName(it.name) }
        if (conflict != null) fail(BuildOutcome.FAILED, "APK_DEX_NAMESPACE_CONFLICT", "DEX namespace conflicts with base entry ${conflict.name}")
    }

    private fun writePackage(
        basePath: Path,
        baseEntries: List<PackageEntry>,
        dexEntries: List<DexEntry>,
        dependencyEntries: List<DependencyEntry>,
        target: Path,
        token: CancellationToken
    ) {
        validateInputFileStillSafe(basePath)
        var dependencyZip: ZipFile? = null
        var dependencyArchive: Path? = null
        try {
            ZipFile(basePath.toFile()).use { baseZip ->
                ZipOutputStream(BufferedOutputStream(Files.newOutputStream(
                    target,
                    StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING,
                    LinkOption.NOFOLLOW_LINKS
                ))).use { out ->
                    for (expected in baseEntries) {
                        token.throwIfCancelled()
                        val source = baseZip.getEntry(expected.name)
                            ?: fail(BuildOutcome.FAILED, "APK_BASE_PACKAGE_INVALID", "Base entry disappeared during assembly: ${expected.name}")
                        val outputEntry = ZipEntry(expected.name)
                        outputEntry.time = FIXED_ZIP_TIME
                        outputEntry.method = expected.method
                        if (expected.method == ZipEntry.STORED) {
                            outputEntry.size = expected.size
                            outputEntry.compressedSize = expected.size
                            outputEntry.crc = expected.crc
                        }
                        out.putNextEntry(outputEntry)
                        val copied = baseZip.getInputStream(source).use {
                            copyAndDigest(it, out, token, expected.size, "APK_BASE_PACKAGE_INVALID")
                        }
                        out.closeEntry()
                        if (copied.size != expected.size || copied.crc != expected.crc || copied.sha256 != expected.sha256) {
                            fail(BuildOutcome.FAILED, "APK_BASE_PACKAGE_INVALID", "Base entry changed while assembling: ${expected.name}")
                        }
                    }
                    for (dependency in dependencyEntries) {
                        token.throwIfCancelled()
                        val expected = dependency.entry
                        val outputEntry = ZipEntry(expected.name).apply {
                            time = FIXED_ZIP_TIME
                            method = expected.method
                            if (method == ZipEntry.STORED) {
                                size = expected.size; compressedSize = expected.size; crc = expected.crc
                            }
                        }
                        out.putNextEntry(outputEntry)
                        val merged = dependency.merged
                        val copied = if (merged != null) merged.inputStream().use {
                            copyAndDigest(it, out, token, expected.size, "APK_DEPENDENCY_RESOURCE_CHANGED")
                        } else {
                            val archive = dependency.archive ?: error("Missing dependency resource source")
                            validateInputFileStillSafe(archive)
                            if (dependencyArchive != archive) {
                                dependencyZip?.close()
                                dependencyZip = ZipFile(archive.toFile())
                                dependencyArchive = archive
                            }
                            val zip = checkNotNull(dependencyZip)
                            val source = zip.getEntry(expected.name) ?: error("Missing dependency resource entry")
                            zip.getInputStream(source).use { copyAndDigest(it, out, token, expected.size, "APK_DEPENDENCY_RESOURCE_CHANGED") }
                        }
                        out.closeEntry()
                        if (copied.size != expected.size || copied.crc != expected.crc || copied.sha256 != expected.sha256)
                            fail(BuildOutcome.FAILED, "APK_DEPENDENCY_RESOURCE_CHANGED", "Dependency resource changed while copying")
                    }
                    for (dex in dexEntries) {
                        token.throwIfCancelled()
                        validateInputFileStillSafe(dex.input.file)
                        val outputEntry = ZipEntry(dex.apkName)
                        outputEntry.time = FIXED_ZIP_TIME
                        outputEntry.method = ZipEntry.DEFLATED
                        out.putNextEntry(outputEntry)
                        val copied = Files.newInputStream(dex.input.file, StandardOpenOption.READ).use {
                            copyAndDigest(it, out, token, dex.input.size, "APK_DEX_INPUT_INVALID")
                        }
                        out.closeEntry()
                        if (copied.size != dex.input.size || copied.sha256 != dex.input.sha256) {
                            fail(BuildOutcome.FAILED, "APK_DEX_INPUT_INVALID", "DEX input changed while assembling: ${dex.input.file.fileName}", dex.input.file)
                        }
                    }
                }
            }
        } catch (problem: ApkAssemblyProblem) {
            throw problem
        } catch (cancelled: CancellationToken.CancelledException) {
            throw cancelled
        } catch (error: Exception) {
            fail(BuildOutcome.FAILED, "APK_ASSEMBLY_FAILED", "Could not write assembled APK: ${error.message ?: error.javaClass.simpleName}", target)
        } finally {
            runCatching { dependencyZip?.close() }
        }
    }

    private fun validateAssembledPackage(
        path: Path,
        baseEntries: List<PackageEntry>,
        dexEntries: List<DexEntry>,
        token: CancellationToken
    ) {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path) || Files.size(path) <= 0L) {
            fail(BuildOutcome.FAILED, "APK_OUTPUT_MISSING", "Assembled APK is missing or is not an ordinary non-empty file", path)
        }
        val expectedBase = baseEntries.associateBy { it.name }
        val expectedDex = dexEntries.associateBy { it.apkName }
        val seen = mutableSetOf<String>()
        var hasManifest = false
        var hasResources = false
        try {
            ZipFile(path.toFile()).use { zip ->
                val enumeration = zip.entries()
                while (enumeration.hasMoreElements()) {
                    token.throwIfCancelled()
                    val entry = enumeration.nextElement()
                    if (!isSafeZipName(entry.name) || !seen.add(entry.name) || isOldSignatureName(entry.name) ||
                        (isBaseDexName(entry.name) && entry.name !in expectedDex)
                    ) {
                        fail(BuildOutcome.FAILED, "APK_OUTPUT_INVALID", "Assembled APK contains an unsafe, duplicate, signed, or conflicting entry: ${entry.name}", path)
                    }
                    if (entry.name == "AndroidManifest.xml" && !entry.isDirectory) hasManifest = true
                    if (entry.name == "resources.arsc" && !entry.isDirectory) hasResources = true
                    val expectedBaseEntry = expectedBase[entry.name]
                    val expectedDexEntry = expectedDex[entry.name]
                    if (expectedBaseEntry == null && expectedDexEntry == null) {
                        fail(BuildOutcome.FAILED, "APK_OUTPUT_INVALID", "Assembled APK contains an unexpected entry: ${entry.name}", path)
                    }
                    val expectedMethod = expectedBaseEntry?.method ?: ZipEntry.DEFLATED
                    if (entry.method != expectedMethod) {
                        fail(BuildOutcome.FAILED, "APK_OUTPUT_INVALID", "Assembled APK compression method changed for ${entry.name}", path)
                    }
                    val expectedHash = expectedBaseEntry?.sha256 ?: checkNotNull(expectedDexEntry).input.sha256
                    val expectedSize = expectedBaseEntry?.size ?: checkNotNull(expectedDexEntry).input.size
                    val measured = zip.getInputStream(entry).use {
                        digestStream(
                            it,
                            token,
                            maxBytes = expectedSize,
                            limitCode = "APK_OUTPUT_INVALID",
                            limitOutcome = BuildOutcome.FAILED
                        )
                    }
                    if (measured.sha256 != expectedHash || measured.size != expectedSize ||
                        (entry.size >= 0L && entry.size != measured.size) ||
                        (entry.crc >= 0L && entry.crc != measured.crc) ||
                        (expectedBaseEntry != null && measured.crc != expectedBaseEntry.crc)
                    ) {
                        fail(BuildOutcome.FAILED, "APK_OUTPUT_INVALID", "Assembled APK content differs for ${entry.name}", path)
                    }
                }
            }
        } catch (problem: ApkAssemblyProblem) {
            throw problem
        } catch (cancelled: CancellationToken.CancelledException) {
            throw cancelled
        } catch (error: Exception) {
            fail(BuildOutcome.FAILED, "APK_OUTPUT_INVALID", "Assembled APK is not a readable ZIP: ${error.message ?: error.javaClass.simpleName}", path)
        }
        if (seen.size != baseEntries.size + dexEntries.size || !hasManifest || !hasResources) {
            fail(BuildOutcome.FAILED, "APK_OUTPUT_INVALID", "Assembled APK entry set or required resources are incomplete", path)
        }
    }

    private fun digestStream(
        input: java.io.InputStream,
        token: CancellationToken,
        includeMagic: Boolean = false,
        maxBytes: Long? = null,
        limitCode: String = "APK_BASE_PACKAGE_LIMIT",
        limitOutcome: BuildOutcome = BuildOutcome.RESOURCE_LIMIT
    ): StreamDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        val buffer = ByteArray(BUFFER_SIZE)
        val magic = if (includeMagic) ByteArray(8) else null
        var magicSize = 0
        var size = 0L
        while (true) {
            token.throwIfCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (maxBytes != null && count.toLong() > maxBytes - size) {
                fail(limitOutcome, limitCode, "Input exceeds configured byte limit")
            }
            if (magic != null && magicSize < magic.size) {
                val take = minOf(count, magic.size - magicSize)
                buffer.copyInto(magic, magicSize, 0, take)
                magicSize += take
            }
            if (count.toLong() > Long.MAX_VALUE - size) fail(BuildOutcome.RESOURCE_LIMIT, "APK_BASE_PACKAGE_LIMIT", "Input size overflowed")
            size += count
            digest.update(buffer, 0, count)
            crc.update(buffer, 0, count)
        }
        return StreamDigest(size, crc.value, digest.digest().toHex(), magic?.copyOf(magicSize))
    }

    private fun copyAndDigest(
        input: java.io.InputStream,
        output: java.io.OutputStream,
        token: CancellationToken,
        maxBytes: Long,
        limitCode: String
    ): StreamDigest {
        val digest = MessageDigest.getInstance("SHA-256")
        val crc = CRC32()
        val buffer = ByteArray(BUFFER_SIZE)
        var size = 0L
        while (true) {
            token.throwIfCancelled()
            val count = input.read(buffer)
            if (count < 0) break
            if (count == 0) continue
            if (count.toLong() > maxBytes - size) {
                fail(BuildOutcome.FAILED, limitCode, "Input changed beyond its verified byte limit")
            }
            if (count.toLong() > Long.MAX_VALUE - size) fail(BuildOutcome.RESOURCE_LIMIT, "APK_BASE_PACKAGE_LIMIT", "Input size overflowed")
            size += count
            digest.update(buffer, 0, count)
            crc.update(buffer, 0, count)
            output.write(buffer, 0, count)
        }
        return StreamDigest(size, crc.value, digest.digest().toHex())
    }

    private fun validateInputFile(path: Path, label: String, requireNonEmpty: Boolean) {
        inspectComponents(path, allowMissingLeaf = false, allowMissingParents = false, code = "APK_ASSEMBLY_INPUT_INVALID")
        val attrs = try { attributes(path) } catch (error: Exception) {
            fail(BuildOutcome.FAILED, "APK_ASSEMBLY_INPUT_INVALID", "$label is missing or unreadable", path)
        }
        if (attrs.isSymbolicLink || !attrs.isRegularFile || (requireNonEmpty && attrs.size() <= 0L)) {
            fail(BuildOutcome.FAILED, "APK_ASSEMBLY_INPUT_INVALID", "$label must be an ordinary${if (requireNonEmpty) " non-empty" else ""} file", path)
        }
    }

    private fun validateInputDirectory(path: Path, label: String) {
        inspectComponents(path, allowMissingLeaf = false, allowMissingParents = false, code = "APK_ASSEMBLY_INPUT_INVALID")
        val attrs = try { attributes(path) } catch (error: Exception) {
            fail(BuildOutcome.FAILED, "APK_ASSEMBLY_INPUT_INVALID", "$label is missing or unreadable", path)
        }
        if (attrs.isSymbolicLink || !attrs.isDirectory) {
            fail(BuildOutcome.FAILED, "APK_ASSEMBLY_INPUT_INVALID", "$label must be an ordinary directory", path)
        }
    }

    private fun outputPath(file: File): Path {
        val path = safeAbsolutePath(file, "APK_ASSEMBLY_OUTPUT_INVALID", "APK output")
        if (path.root == path || path.fileName == null || path.parent == null) {
            fail(BuildOutcome.FAILED, "APK_ASSEMBLY_OUTPUT_INVALID", "APK output must not be a filesystem root", path)
        }
        inspectComponents(path, allowMissingLeaf = true, allowMissingParents = true, code = "APK_ASSEMBLY_OUTPUT_INVALID")
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) {
            val attrs = attributes(path)
            if (attrs.isSymbolicLink || !attrs.isRegularFile) {
                fail(BuildOutcome.FAILED, "APK_ASSEMBLY_OUTPUT_INVALID", "APK output must be an ordinary file, not a symlink or directory", path)
            }
        }
        return path
    }

    private fun safeAbsolutePath(file: File, code: String, label: String): Path {
        val absolute = try { file.toPath().toAbsolutePath() } catch (error: Exception) {
            fail(BuildOutcome.FAILED, code, "$label path is invalid", file.toPath())
        }
        if (absolute.any { it.toString() == ".." }) {
            fail(BuildOutcome.FAILED, code, "$label path must not contain parent traversal", absolute)
        }
        val raw = absolute.normalize()
        if (raw.root == null) fail(BuildOutcome.FAILED, code, "$label path has no filesystem root", raw)
        inspectComponents(raw, allowMissingLeaf = false, allowMissingParents = false, code = code, permitMissing = true)
        return raw
    }

    private fun inspectComponents(
        path: Path,
        allowMissingLeaf: Boolean,
        allowMissingParents: Boolean,
        code: String,
        permitMissing: Boolean = false
    ) {
        val root = path.root ?: fail(BuildOutcome.FAILED, code, "Path has no filesystem root", path)
        var current = root
        try {
            for ((index, component) in root.relativize(path).withIndex()) {
                current = current.resolve(component)
                if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    val isLeaf = current == path
                    if ((isLeaf && allowMissingLeaf) || (!isLeaf && allowMissingParents) || permitMissing) continue
                    fail(BuildOutcome.FAILED, code, "Path component does not exist: $current", current)
                }
                val attrs = attributes(current)
                if (attrs.isSymbolicLink) fail(BuildOutcome.FAILED, code, "Path contains a symbolic link: $current", current)
                val isLeaf = current == path
                if (!isLeaf && !attrs.isDirectory) fail(BuildOutcome.FAILED, code, "Path parent is not a directory: $current", current)
                if (isLeaf && !allowMissingLeaf && !attrs.isDirectory && !attrs.isRegularFile) {
                    fail(BuildOutcome.FAILED, code, "Path target is not an ordinary file or directory: $current", current)
                }
            }
        } catch (problem: ApkAssemblyProblem) {
            throw problem
        } catch (error: Exception) {
            fail(BuildOutcome.FAILED, code, "Could not inspect path components: ${error.message ?: error.javaClass.simpleName}", current)
        }
    }

    private fun validateNoOverlap(output: Path, inputs: List<Path>) {
        if (inputs.any { input -> output.startsWith(input) || input.startsWith(output) }) {
            fail(BuildOutcome.FAILED, "APK_ASSEMBLY_OUTPUT_INVALID", "APK output path overlaps one of its inputs", output)
        }
    }

    private fun removeSafeStaleOutput(path: Path) {
        validateOutputStillSafe(path)
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) Files.delete(path)
    }

    private fun ensureOutputParent(output: Path) {
        val parent = output.parent ?: fail(BuildOutcome.FAILED, "APK_ASSEMBLY_OUTPUT_INVALID", "APK output has no parent", output)
        val root = parent.root ?: fail(BuildOutcome.FAILED, "APK_ASSEMBLY_OUTPUT_INVALID", "APK output parent has no filesystem root", parent)
        var current = root
        try {
            for (component in root.relativize(parent)) {
                current = current.resolve(component)
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                    val attrs = attributes(current)
                    if (attrs.isSymbolicLink || !attrs.isDirectory) {
                        fail(BuildOutcome.FAILED, "APK_ASSEMBLY_OUTPUT_INVALID", "APK output parent is not an ordinary directory: $current", current)
                    }
                } else {
                    Files.createDirectory(current)
                }
            }
        } catch (problem: ApkAssemblyProblem) {
            throw problem
        } catch (error: Exception) {
            fail(BuildOutcome.FAILED, "APK_ASSEMBLY_OUTPUT_INVALID", "Could not prepare APK output parent: ${error.message ?: error.javaClass.simpleName}", parent)
        }
        validateOutputStillSafe(output)
    }

    private fun validateOutputStillSafe(output: Path) {
        inspectComponents(output.parent, allowMissingLeaf = false, allowMissingParents = false, code = "APK_ASSEMBLY_OUTPUT_INVALID")
        if (Files.exists(output, LinkOption.NOFOLLOW_LINKS)) {
            val attrs = attributes(output)
            if (attrs.isSymbolicLink || !attrs.isRegularFile) {
                fail(BuildOutcome.FAILED, "APK_ASSEMBLY_OUTPUT_INVALID", "APK output changed to a symlink or non-file", output)
            }
        }
    }

    private fun validateInputFileStillSafe(path: Path) {
        inspectComponents(path, allowMissingLeaf = false, allowMissingParents = false, code = "APK_ASSEMBLY_INPUT_INVALID")
        val attrs = attributes(path)
        if (attrs.isSymbolicLink || !attrs.isRegularFile) {
            fail(BuildOutcome.FAILED, "APK_ASSEMBLY_INPUT_INVALID", "Input changed to a symlink or non-file", path)
        }
    }

    private fun validateInputDirectoryStillSafe(path: Path) {
        inspectComponents(path, allowMissingLeaf = false, allowMissingParents = false, code = "APK_ASSEMBLY_INPUT_INVALID")
        val attrs = attributes(path)
        if (attrs.isSymbolicLink || !attrs.isDirectory) {
            fail(BuildOutcome.FAILED, "APK_ASSEMBLY_INPUT_INVALID", "Input changed to a symlink or non-directory", path)
        }
    }

    private fun cleanup(output: Path?, temporary: Path?, cleanOutput: Boolean) {
        if (temporary != null) runCatching { Files.deleteIfExists(temporary) }
        if (cleanOutput && output != null) {
            runCatching {
                if (Files.exists(output, LinkOption.NOFOLLOW_LINKS) && !Files.isSymbolicLink(output) && Files.isRegularFile(output, LinkOption.NOFOLLOW_LINKS)) {
                    Files.deleteIfExists(output)
                }
            }
        }
    }

    private fun dexIndex(name: String): Int? {
        if (name == "classes.dex") return 1
        val match = DEX_NAME.matchEntire(name) ?: return null
        val digits = match.groupValues[1]
        if (digits.startsWith('0')) return null
        val index = digits.toIntOrNull() ?: return null
        return index.takeIf { it >= 2 }
    }

    private fun dexName(index: Int): String = if (index == 1) "classes.dex" else "classes$index.dex"

    private fun validDexMagic(magic: ByteArray?): Boolean = magic != null && magic.size == 8 &&
        magic[0] == 'd'.code.toByte() && magic[1] == 'e'.code.toByte() &&
        magic[2] == 'x'.code.toByte() && magic[3] == '\n'.code.toByte() &&
        magic[4] in '0'.code.toByte()..'9'.code.toByte() &&
        magic[5] in '0'.code.toByte()..'9'.code.toByte() &&
        magic[6] in '0'.code.toByte()..'9'.code.toByte() && magic[7] == 0.toByte()

    private fun isSafeZipName(name: String): Boolean {
        if (name.isEmpty() || name.indexOf('\u0000') >= 0 || name.indexOf('\\') >= 0 || name.startsWith('/')) return false
        if (name.length >= 2 && name[0].isLetter() && name[1] == ':') return false
        val body = if (name.endsWith('/')) name.dropLast(1) else name
        if (body.isEmpty()) return false
        if (body.endsWith('/')) return false
        return body.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun isBaseDexName(name: String): Boolean = !name.contains('/') &&
        name.lowercase().startsWith("classes") && name.lowercase().endsWith(".dex")

    private fun isOldSignatureName(name: String): Boolean {
        val upper = name.uppercase()
        if (upper == "META-INF/MANIFEST.MF") return true
        if (!upper.startsWith("META-INF/") || upper.drop("META-INF/".length).contains('/')) return false
        return listOf(".SF", ".RSA", ".DSA", ".EC").any { upper.endsWith(it) }
    }

    private fun attributes(path: Path): BasicFileAttributes =
        Files.readAttributes(path, BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS)

    private fun sha256(path: Path, token: CancellationToken): String =
        Files.newInputStream(path, StandardOpenOption.READ).use { digestStream(it, token).sha256 }

    private fun info(code: String, message: String) = BuildDiagnostic(
        BuildDiagnosticSeverity.INFO, code, message, BuildDiagnosticSource.PACKAGING
    )

    private fun failure(
        started: Long,
        outcome: BuildOutcome,
        code: String,
        message: String,
        path: Path? = null
    ) = ApkAssemblyResult(
        outcome = outcome,
        diagnostics = listOf(BuildDiagnostic(
            BuildDiagnosticSeverity.ERROR,
            code,
            message,
            BuildDiagnosticSource.PACKAGING,
            path?.toString()
        )),
        durationMs = elapsedMs(started)
    )

    private fun elapsedMs(started: Long): Long = ((System.nanoTime() - started).coerceAtLeast(0L) / 1_000_000L)

    private fun ByteArray.toHex(): String = joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

    private fun fail(outcome: BuildOutcome, code: String, message: String, path: Path? = null): Nothing =
        throw ApkAssemblyProblem(outcome, code, message, path)

    private class ApkAssemblyProblem(
        val outcome: BuildOutcome,
        val code: String,
        override val message: String,
        val path: Path? = null
    ) : Exception(message)

    private companion object {
        private const val BUFFER_SIZE = 32 * 1024
        private const val FIXED_ZIP_TIME = 315_532_800_000L // 1980-01-01 ZIP epoch.
        private val DEX_NAME = Regex("classes([0-9]+)\\.dex")
    }
}
