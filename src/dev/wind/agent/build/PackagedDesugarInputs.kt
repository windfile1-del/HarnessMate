package dev.wind.agent.build

import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.zip.ZipFile

data class DesugarInputs(
    val runtimeJar: File,
    val configurationJar: File,
    val configurationJson: String
)

/** Resolves APK-owned L8 inputs into verified, app-private ordinary files. */
class PackagedDesugarInputs(
    private val storageRoot: File,
    private val openAsset: (String) -> InputStream
) {
    fun resolve(): DesugarInputs? = try {
        synchronized(extractionLock) {
            check(storageRoot.canonicalFile == storageRoot.absoluteFile) { "DESUGAR_ROOT_LINK" }
            check(storageRoot.isDirectory || storageRoot.mkdirs()) { "DESUGAR_ROOT_UNAVAILABLE" }
            check(storageRoot.canonicalFile == storageRoot.absoluteFile) { "DESUGAR_ROOT_LINK" }

            val runtime = File(storageRoot, RUNTIME_FILE_NAME)
            val configuration = File(storageRoot, CONFIGURATION_FILE_NAME)
            check(runtime.canonicalFile == runtime.absoluteFile) { "DESUGAR_RUNTIME_LINK" }
            check(configuration.canonicalFile == configuration.absoluteFile) { "DESUGAR_CONFIGURATION_LINK" }

            if (!verified(runtime, RUNTIME_SIZE_BYTES, RUNTIME_SHA256) ||
                !verified(configuration, CONFIGURATION_SIZE_BYTES, CONFIGURATION_SHA256)
            ) {
                val runtimeStage = stageAsset(RUNTIME_ASSET_PATH, RUNTIME_SIZE_BYTES, RUNTIME_SHA256)
                var configurationStage: File? = null
                try {
                    configurationStage = stageAsset(
                        CONFIGURATION_ASSET_PATH,
                        CONFIGURATION_SIZE_BYTES,
                        CONFIGURATION_SHA256
                    )
                    activate(runtimeStage, runtime, RUNTIME_SIZE_BYTES, RUNTIME_SHA256)
                    activate(configurationStage, configuration, CONFIGURATION_SIZE_BYTES, CONFIGURATION_SHA256)
                } finally {
                    if (runtimeStage.exists()) runtimeStage.delete()
                    configurationStage?.let { if (it.exists()) it.delete() }
                }
            }

            check(verified(runtime, RUNTIME_SIZE_BYTES, RUNTIME_SHA256)) { "DESUGAR_RUNTIME_ACTIVATION_INVALID" }
            check(verified(configuration, CONFIGURATION_SIZE_BYTES, CONFIGURATION_SHA256)) {
                "DESUGAR_CONFIGURATION_ACTIVATION_INVALID"
            }
            val json = readConfiguration(configuration)
            DesugarInputs(runtime, configuration, json)
        }
    } catch (_: Exception) {
        null
    }

    private fun stageAsset(assetPath: String, expectedSize: Long, expectedSha256: String): File {
        val staged = File.createTempFile("desugar-", ".part", storageRoot)
        var keep = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            openAsset(assetPath).use { input ->
                staged.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        check(size <= expectedSize) { "DESUGAR_ASSET_SIZE_LIMIT" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(size == expectedSize && hex(digest.digest()) == expectedSha256) {
                "DESUGAR_ASSET_INTEGRITY"
            }
            keep = true
            return staged
        } finally {
            if (!keep && staged.exists()) staged.delete()
        }
    }

    private fun activate(staged: File, target: File, expectedSize: Long, expectedSha256: String) {
        check(staged.canonicalFile == staged.absoluteFile) { "DESUGAR_STAGE_LINK" }
        check(target.canonicalFile == target.absoluteFile) { "DESUGAR_TARGET_LINK" }
        if (target.exists()) {
            check(target.isFile && target.delete()) { "DESUGAR_REPLACE_FAILED" }
        }
        check(staged.renameTo(target)) { "DESUGAR_ACTIVATION_FAILED" }
        check(target.setReadOnly()) { "DESUGAR_READ_ONLY_FAILED" }
        check(verified(target, expectedSize, expectedSha256)) { "DESUGAR_ACTIVATION_INTEGRITY" }
    }

    private fun verified(file: File, expectedSize: Long, expectedSha256: String): Boolean {
        if (file.canonicalFile != file.absoluteFile || !file.isFile || file.length() != expectedSize) return false
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > expectedSize) return false
                digest.update(buffer, 0, count)
            }
        }
        return size == expectedSize && hex(digest.digest()) == expectedSha256
    }

    private fun readConfiguration(file: File): String = ZipFile(file).use { archive ->
        val entry = archive.getEntry(CONFIGURATION_JSON_ENTRY)
            ?: error("DESUGAR_CONFIGURATION_JSON_MISSING")
        check(!entry.isDirectory && entry.size in 1..MAX_CONFIGURATION_JSON_BYTES) {
            "DESUGAR_CONFIGURATION_JSON_INVALID"
        }
        val bytes = ByteArrayOutputStream()
        archive.getInputStream(entry).use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(bytes.size().toLong() + count <= MAX_CONFIGURATION_JSON_BYTES) {
                    "DESUGAR_CONFIGURATION_JSON_LIMIT"
                }
                bytes.write(buffer, 0, count)
            }
        }
        val json = bytes.toByteArray().toString(Charsets.UTF_8)
        val parsed = JSONObject(json)
        check(parsed.optString("identifier") == CONFIGURATION_IDENTIFIER) {
            "DESUGAR_CONFIGURATION_IDENTIFIER_INVALID"
        }
        check(parsed.optInt("configuration_format_version", -1) == 200) {
            "DESUGAR_CONFIGURATION_VERSION_INVALID"
        }
        check(parsed.optString("synthesized_library_classes_package_prefix") == "j$/") {
            "DESUGAR_CONFIGURATION_PREFIX_INVALID"
        }
        json
    }

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        const val RUNTIME_ASSET_PATH = "embedded/desugar/desugar_jdk_libs_nio-2.0.1.jar"
        const val CONFIGURATION_ASSET_PATH = "embedded/desugar/desugar_jdk_libs_configuration_nio-2.0.1.jar"
        const val CONFIGURATION_JSON_ENTRY = "META-INF/desugar/d8/desugar.json"
        const val RUNTIME_SIZE_BYTES = 5_955_054L
        const val RUNTIME_SHA256 = "25ddad0d96cf7ba284cfa7866f54aa3508468a1df80e79cb44733a66f755d330"
        const val CONFIGURATION_SIZE_BYTES = 1_097_805L
        const val CONFIGURATION_SHA256 = "1dd26f9e2440904eab5014a244f500d8b453f3629bfd5478a3f9895838e5c35a"

        private const val RUNTIME_FILE_NAME = "desugar_jdk_libs_nio-2.0.1-$RUNTIME_SHA256.jar"
        private const val CONFIGURATION_FILE_NAME =
            "desugar_jdk_libs_configuration_nio-2.0.1-$CONFIGURATION_SHA256.jar"
        private const val CONFIGURATION_IDENTIFIER =
            "com.tools.android:desugar_jdk_libs_configuration_nio:2.0.1"
        private const val MAX_CONFIGURATION_JSON_BYTES = 2L * 1024L * 1024L
        private val extractionLock = Any()
    }
}
