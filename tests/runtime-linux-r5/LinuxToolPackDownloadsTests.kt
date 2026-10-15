package dev.wind.agent.runtime.linux

import android.content.Context
import android.content.ContextWrapper
import android.content.res.AssetManager
import dev.wind.agent.artifact.ArtifactProvenance
import dev.wind.agent.artifact.ArtifactStoreException
import dev.wind.agent.artifact.TaskArtifactAccess
import dev.wind.agent.artifact.VerifiedArtifactStore
import org.json.JSONObject
import java.io.*
import java.nio.file.*
import java.security.MessageDigest
import java.util.*
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

private var assertions = 0
private fun verify(condition: Boolean, message: String) {
    assertions++
    if (!condition) {
        throw AssertionError(message)
    }
}

private fun hex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

private fun sha256(data: ByteArray): String =
    hex(MessageDigest.getInstance("SHA-256").digest(data))

private fun metadata(payload: ByteArray): ByteArray =
    JSONObject().put("schema", 1).put("payload", "tools.tar.gz")
        .put("compressedBytes", payload.size.toLong())
        .put("sha256", sha256(payload)).toString().toByteArray(Charsets.UTF_8)

private fun storedZip(names: Array<String>, data: Array<ByteArray>): ByteArray {
    val raw = ByteArrayOutputStream()
    ZipOutputStream(raw).use { zip ->
        for (i in names.indices) {
            val entry = ZipEntry(names[i])
            val crc = CRC32()
            crc.update(data[i])
            entry.method = ZipEntry.STORED
            entry.size = data[i].size.toLong()
            entry.crc = crc.value
            zip.putNextEntry(entry)
            zip.write(data[i])
            zip.closeEntry()
        }
    }
    return raw.toByteArray()
}

class FakeAssetManager(private val assets: Map<String, ByteArray>) : AssetManager() {
    override fun open(fileName: String): InputStream {
        val data = assets[fileName] ?: throw FileNotFoundException("Asset not found: " + fileName)
        return ByteArrayInputStream(data)
    }
}

class FakeContext(
    private val baseDir: File,
    private val assetMap: Map<String, ByteArray>
) : ContextWrapper(null) {
    private val noBackupDir = File(baseDir, "no_backup").apply { mkdirs() }
    private val filesDir = File(baseDir, "files").apply { mkdirs() }
    private val assetMgr = FakeAssetManager(assetMap)

    override fun getNoBackupFilesDir(): File = noBackupDir
    override fun getFilesDir(): File = filesDir
    override fun getAssets(): AssetManager = assetMgr
}

private fun testLinuxOfflineBundleHelpers() {
    println("Running testLinuxOfflineBundleHelpers...")
    val shaA = "a".repeat(64)
    val uuid1 = UUID.randomUUID().toString()
    val uuid2 = UUID.randomUUID().toString()

    // 1. Same SHA with different repair UUIDs produces the same baseIdentity
    val rootfsPlain = File("/tmp/" + shaA)
    val rootfsV1 = File("/tmp/" + shaA + "-v" + uuid1)
    val rootfsV2 = File("/tmp/" + shaA + "-v" + uuid2)
    verify(LinuxOfflineBundle.baseIdentity(rootfsPlain) == shaA, "plain 64hex baseIdentity extracted")
    verify(LinuxOfflineBundle.baseIdentity(rootfsV1) == shaA, "versioned v1 baseIdentity matches sha")
    verify(LinuxOfflineBundle.baseIdentity(rootfsV2) == shaA, "versioned v2 baseIdentity matches sha")
    verify(
        LinuxOfflineBundle.baseIdentity(rootfsV1) == LinuxOfflineBundle.baseIdentity(rootfsV2),
        "same sha with different repair UUIDs yields identical baseIdentity"
    )

    // 2. Invalid directory name rejected with TOOL_PACK_BASE_IDENTITY_INVALID
    val invalidNames = listOf("short", "g".repeat(64), shaA + "-invalid", shaA + "-v123", "")
    for (name in invalidNames) {
        var caught = false
        try {
            LinuxOfflineBundle.baseIdentity(File("/tmp/" + name))
        } catch (e: IOException) {
            caught = e.message?.contains("TOOL_PACK_BASE_IDENTITY_INVALID") == true
        }
        verify(caught, "invalid rootfs name rejected with TOOL_PACK_BASE_IDENTITY_INVALID")
    }

    // 3. legacyName fixed as manifestSha256-baseIdentity
    val manifestSha = "b".repeat(64)
    val manifestObj = JSONObject().put("sha256", manifestSha)
    val expectedLegacy = manifestSha + "-" + shaA
    verify(LinuxOfflineBundle.legacyName(manifestObj, rootfsPlain) == expectedLegacy, "legacyName plain fixed sha-sha")
    verify(LinuxOfflineBundle.legacyName(manifestObj, rootfsV1) == expectedLegacy, "legacyName versioned fixed sha-sha")
}

fun main() {
    val tempDir = Files.createTempDirectory("linux-toolpack-downloads-test-").toFile()
    try {
        testLinuxOfflineBundleHelpers()

        println("Running LinuxToolPackDownloads P7 integration tests...")
        // Setup 3 synthetic trusted catalogs
        val jvmPayload = "synthetic-jvm-pack-payload".toByteArray(Charsets.UTF_8)
        val jvmManifest = metadata(jvmPayload)
        val androidPayload = "synthetic-android-pack-payload".toByteArray(Charsets.UTF_8)
        val androidManifest = metadata(androidPayload)
        val lspPayload = "synthetic-lsp-pack-payload".toByteArray(Charsets.UTF_8)
        val lspManifest = metadata(lspPayload)

        val assetMap = mapOf(
            "embedded/linux-jvm/manifest.json" to jvmManifest,
            "embedded/linux-android/manifest.json" to androidManifest,
            "embedded/linux-lsp/manifest.json" to lspManifest
        )

        val context = FakeContext(tempDir, assetMap)
        val p7Store = VerifiedArtifactStore(File(context.noBackupFilesDir.canonicalFile, "p7-artifacts"))
        val p7Access = TaskArtifactAccess(File(context.noBackupFilesDir.canonicalFile, "p7-artifact-access"))

        // Create a good storedZip .hm-pack for linux-jvm
        val jvmPackZip = storedZip(arrayOf("manifest.json", "tools.tar.gz"), arrayOf(jvmManifest, jvmPayload))
        val jvmPackSha = sha256(jvmPackZip)
        val provenance = ArtifactProvenance("https://example.com/harnessmate-jvm-build.hm-pack")
        val jvmArtifact = p7Store.importQuarantined(
            ByteArrayInputStream(jvmPackZip),
            jvmPackSha,
            jvmPackZip.size.toLong() + 1024,
            provenance
        )
        verify(jvmArtifact.uri == "artifact://sha256/" + jvmPackSha, "jvm artifact imported to CAS")

        // 1. Good CAS import via importSelected outputs correct family and cached bytes
        val importedFamily = LinuxToolPackDownloads.importSelected(context, jvmArtifact.uri)
        verify(importedFamily == "linux-jvm", "importSelected returns linux-jvm family")
        val cachedBlob = ToolPackArchive.payloadFile(
            File(context.filesDir, "tool-pack-imports"),
            "linux-jvm",
            sha256(jvmPayload)
        )
        verify(cachedBlob.isFile, "cached blob file exists in tool-pack-imports")
        verify(cachedBlob.readBytes().contentEquals(jvmPayload), "cached blob matches expected jvmPayload bytes")

        // 2. CAS blob corrupt is refused
        val casObjectDir = File(context.noBackupFilesDir.canonicalFile, "p7-artifacts/objects/sha256/" + jvmPackSha.substring(0, 2) + "/" + jvmPackSha)
        val casBlobFile = File(casObjectDir, "blob")
        val origCasBytes = casBlobFile.readBytes()
        try {
            val tamperedCas = origCasBytes.clone()
            tamperedCas[0] = (tamperedCas[0].toInt() xor 1).toByte()
            Files.write(casBlobFile.toPath(), tamperedCas)
            var caughtCasCorrupt = false
            try {
                LinuxToolPackDownloads.importSelected(context, jvmArtifact.uri)
            } catch (e: ArtifactStoreException) {
                caughtCasCorrupt = e.message?.contains("ARTIFACT_SHA256_MISMATCH") == true
            }
            verify(caughtCasCorrupt, "tampered CAS blob refused with ARTIFACT_SHA256_MISMATCH")
        } finally {
            Files.write(casBlobFile.toPath(), origCasBytes)
        }

        // 3. Correct SHA in pack manifest, but manifest bytes do not match any trusted app catalog -> rejected
        val unknownManifestPayload = "unknown-payload".toByteArray(Charsets.UTF_8)
        val unknownManifest = metadata(unknownManifestPayload)
        val unknownPackZip = storedZip(arrayOf("manifest.json", "tools.tar.gz"), arrayOf(unknownManifest, unknownManifestPayload))
        val unknownPackSha = sha256(unknownPackZip)
        val unknownArtifact = p7Store.importQuarantined(
            ByteArrayInputStream(unknownPackZip),
            unknownPackSha,
            unknownPackZip.size.toLong() + 1024,
            provenance
        )
        var caughtReleaseMismatch = false
        try {
            LinuxToolPackDownloads.importSelected(context, unknownArtifact.uri)
        } catch (e: IOException) {
            caughtReleaseMismatch = e.message?.contains("TOOL_PACK_RELEASE_MISMATCH") == true
        }
        verify(caughtReleaseMismatch, "unmatched manifest in pack rejected with TOOL_PACK_RELEASE_MISMATCH")

        // 4. importForTask access control:
        val taskId = "task-alpha-123"
        val projectId = "project-xyz"
        val generation = 100L

        // 4a. No task grant -> rejected and no cache written
        var caughtNoGrant = false
        try {
            LinuxToolPackDownloads.importForTask(context, taskId, projectId, generation, jvmArtifact.uri)
        } catch (e: ArtifactStoreException) {
            caughtNoGrant = e.message?.contains("ARTIFACT_TASK_ACCESS_DENIED") == true
        }
        verify(caughtNoGrant, "importForTask without grant rejected with ARTIFACT_TASK_ACCESS_DENIED")

        // Record valid grant
        p7Access.record(taskId, projectId, generation, jvmArtifact)

        // 4b. Wrong task ID -> rejected
        var caughtWrongTask = false
        try {
            LinuxToolPackDownloads.importForTask(context, "task-wrong", projectId, generation, jvmArtifact.uri)
        } catch (e: ArtifactStoreException) {
            caughtWrongTask = e.message?.contains("ARTIFACT_TASK_ACCESS_DENIED") == true
        }
        verify(caughtWrongTask, "importForTask with wrong taskId rejected")

        // 4c. Wrong project ID -> rejected
        var caughtWrongProject = false
        try {
            LinuxToolPackDownloads.importForTask(context, taskId, "project-wrong", generation, jvmArtifact.uri)
        } catch (e: ArtifactStoreException) {
            caughtWrongProject = e.message?.contains("ARTIFACT_TASK_ACCESS_DENIED") == true
        }
        verify(caughtWrongProject, "importForTask with wrong projectId rejected")

        // 4d. Wrong generation -> rejected
        var caughtWrongGen = false
        try {
            LinuxToolPackDownloads.importForTask(context, taskId, projectId, 999L, jvmArtifact.uri)
        } catch (e: ArtifactStoreException) {
            caughtWrongGen = e.message?.contains("ARTIFACT_TASK_ACCESS_DENIED") == true
        }
        verify(caughtWrongGen, "importForTask with wrong generation rejected")

        // 4e. Correct grant for good pack: successfully imports pack via importForTask
        val taskImportedFamily = LinuxToolPackDownloads.importForTask(context, taskId, projectId, generation, jvmArtifact.uri)
        verify(taskImportedFamily == "linux-jvm", "importForTask with valid grant imports successfully")

        // 4f. Correct grant with unknown/mismatched manifest: passes task grant check and throws TOOL_PACK_RELEASE_MISMATCH
        p7Access.record(taskId, projectId, generation, unknownArtifact)
        var caughtTaskReleaseMismatch = false
        try {
            LinuxToolPackDownloads.importForTask(context, taskId, projectId, generation, unknownArtifact.uri)
        } catch (e: IOException) {
            caughtTaskReleaseMismatch = e.message?.contains("TOOL_PACK_RELEASE_MISMATCH") == true
        }
        verify(caughtTaskReleaseMismatch, "importForTask with valid grant but mismatched manifest reaches importPack and reports TOOL_PACK_RELEASE_MISMATCH")

        // 5. Cancellation preserves existing cached blob
        val cachedBytesBeforeCancel = cachedBlob.readBytes()
        val cancelFailure = AtomicReference<Throwable>()
        val cancelThread = Thread {
            Thread.currentThread().interrupt()
            try {
                LinuxToolPackDownloads.importSelected(context, jvmArtifact.uri)
                cancelFailure.set(AssertionError("should have failed on cancellation"))
            } catch (_: InterruptedIOException) {
                // expected cancellation
            } catch (t: Throwable) {
                cancelFailure.set(t)
            }
        }
        cancelThread.start()
        cancelThread.join()
        verify(cancelFailure.get() == null, "cancellation cleanly handled without unexpected errors: " + cancelFailure.get())
        verify(cachedBlob.readBytes().contentEquals(cachedBytesBeforeCancel), "cancelled import preserves existing cached blob")

        println("PASS LinuxToolPackDownloadsTests: " + assertions + " checks")
    } finally {
        tempDir.deleteRecursively()
    }
}
