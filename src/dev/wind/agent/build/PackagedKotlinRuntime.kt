package dev.wind.agent.build

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

data class KotlinRuntimePayload(
    val version: String,
    val dexContainer: File,
    val resourceJars: List<File>,
    val kotlinStdlib: File
)

/** Extracts the APK-owned Kotlin DEX/resources into verified app-private files. */
class PackagedKotlinRuntime(
    private val storageRoot: File,
    private val openAsset: (String) -> InputStream
) {
    fun resolve(): KotlinRuntimePayload? = try {
        synchronized(extractionLock) {
            ensureDirectory(storageRoot, "KOTLIN_RUNTIME_ROOT")
            val manifestBytes = readBoundedAsset(MANIFEST_ASSET_PATH, MAX_MANIFEST_BYTES)
            check(manifestBytes.size == PackagedKotlinRuntimePins.MANIFEST_SIZE_BYTES &&
                hex(sha256(manifestBytes)) == PackagedKotlinRuntimePins.MANIFEST_SHA256) {
                "KOTLIN_RUNTIME_MANIFEST_INTEGRITY"
            }
            verifyManifest(String(manifestBytes, Charsets.UTF_8))

            val runtimeFile = extract(RUNTIME_ARTIFACT)
            val resourceFiles = RESOURCE_ARTIFACTS.map(::extract)
            val stdlibFile = extract(STDLIB_ARTIFACT)
            check(verified(runtimeFile, RUNTIME_ARTIFACT)) { "KOTLIN_RUNTIME_ACTIVATION_INVALID" }
            check(resourceFiles.zip(RESOURCE_ARTIFACTS).all { (file, artifact) -> verified(file, artifact) }) {
                "KOTLIN_RESOURCES_ACTIVATION_INVALID"
            }
            check(verified(stdlibFile, STDLIB_ARTIFACT)) { "KOTLIN_STDLIB_ACTIVATION_INVALID" }
            KotlinRuntimePayload(PROVIDER_VERSION, runtimeFile, resourceFiles, stdlibFile)
        }
    } catch (_: Exception) {
        null
    }

    private fun verifyManifest(json: String) {
        val manifest = JSONObject(json)
        check(manifest.optInt("schemaVersion", -1) == 1) { "KOTLIN_RUNTIME_MANIFEST_SCHEMA" }
        check(manifest.optString("providerVersion") == PROVIDER_VERSION) { "KOTLIN_RUNTIME_MANIFEST_VERSION" }
        check(manifest.optString("compilerVersion") == COMPILER_VERSION) { "KOTLIN_RUNTIME_MANIFEST_COMPILER" }
        check(manifest.optString("r8Version") == R8_VERSION) { "KOTLIN_RUNTIME_MANIFEST_R8_VERSION" }
        check(manifest.optInt("builderJdkMajor", -1) == 17 &&
            manifest.optString("patchBaseCompilerSha256") == PackagedKotlinProvider.UPSTREAM_COMPILER_SHA256) {
            "KOTLIN_RUNTIME_MANIFEST_BUILD_PROVENANCE"
        }
        val platform = manifest.getJSONObject("androidPlatform")
        check(platform.optInt("api", -1) == ANDROID_API &&
            platform.optString("androidJarSha256") == ANDROID_JAR_SHA256) {
            "KOTLIN_RUNTIME_MANIFEST_PLATFORM"
        }
        check(manifest.optInt("minApi", -1) == MIN_API) { "KOTLIN_RUNTIME_MANIFEST_MIN_API" }
        check(manifest.optInt("runtimeDexCount", -1) == RUNTIME_DEX_COUNT) {
            "KOTLIN_RUNTIME_MANIFEST_DEX_COUNT"
        }
        verifyArtifactJson(manifest.getJSONObject("runtimeDexContainer"), RUNTIME_ARTIFACT)
        verifyArtifactJson(manifest.getJSONObject("stdlibClasspath"), STDLIB_ARTIFACT)

        val dexEntries: JSONArray = manifest.getJSONArray("dexEntries")
        check(dexEntries.length() == RUNTIME_DEX_COUNT) { "KOTLIN_RUNTIME_MANIFEST_DEX_ENTRIES" }
        for (index in 0 until dexEntries.length()) {
            val item = dexEntries.getJSONObject(index)
            val expectedName = if (index == 0) "classes.dex" else "classes${index + 1}.dex"
            check(item.optString("name") == expectedName && item.optLong("sizeBytes", 0L) > 0L &&
                SHA256_PATTERN.matches(item.optString("sha256"))) {
                "KOTLIN_RUNTIME_MANIFEST_DEX_ENTRY"
            }
        }

        val resources = manifest.getJSONArray("resourceJars")
        check(resources.length() == RESOURCE_ARTIFACTS.size) { "KOTLIN_RUNTIME_MANIFEST_RESOURCE_COUNT" }
        RESOURCE_ARTIFACTS.forEachIndexed { index, artifact ->
            verifyArtifactJson(resources.getJSONObject(index), artifact)
        }

        val prefixes = manifest.getJSONArray("childFirstPrefixes")
        check(prefixes.length() == CHILD_FIRST_PREFIXES.size) { "KOTLIN_RUNTIME_MANIFEST_NAMESPACE_COUNT" }
        CHILD_FIRST_PREFIXES.forEachIndexed { index, prefix ->
            check(prefixes.optString(index) == prefix) { "KOTLIN_RUNTIME_MANIFEST_NAMESPACE" }
        }
        val classes = manifest.getJSONArray("childFirstClasses")
        check(classes.length() == CHILD_FIRST_CLASSES.size) { "KOTLIN_RUNTIME_MANIFEST_CLASS_COUNT" }
        CHILD_FIRST_CLASSES.forEachIndexed { index, className ->
            check(classes.optString(index) == className) { "KOTLIN_RUNTIME_MANIFEST_CLASS" }
        }
        val desugaring = manifest.getJSONObject("desugaring")
        check(desugaring.optString("runtimeSha256") == DESUGAR_RUNTIME_SHA256 &&
            desugaring.optString("configurationSha256") == DESUGAR_CONFIGURATION_SHA256 &&
            SHA256_PATTERN.matches(desugaring.optString("d8KeepRulesSha256"))) {
            "KOTLIN_RUNTIME_MANIFEST_DESUGARING"
        }

        val inputs = manifest.getJSONArray("programInputs")
        check(inputs.length() == PackagedKotlinProvider.runtimeProgramInputs.size) {
            "KOTLIN_RUNTIME_MANIFEST_INPUT_COUNT"
        }
        PackagedKotlinProvider.runtimeProgramInputs.forEachIndexed { index, input ->
            val item = inputs.getJSONObject(index)
            check(item.optString("path") == "third_party/embedded/kotlin/${input.relativePath}" &&
                item.optString("sha256") == input.sha256) { "KOTLIN_RUNTIME_MANIFEST_INPUT" }
        }
    }

    private fun verifyArtifactJson(json: JSONObject, expected: KotlinRuntimeAssetPin) {
        check(json.optString("asset") == expected.assetPath &&
            json.optString("sha256") == expected.sha256 &&
            json.optLong("sizeBytes", -1L) == expected.sizeBytes) {
            "KOTLIN_RUNTIME_MANIFEST_ARTIFACT"
        }
    }

    private fun extract(artifact: KotlinRuntimeAssetPin): File {
        val parent = File(storageRoot, artifact.directory)
        ensureDirectory(parent, "KOTLIN_RUNTIME_DESTINATION")
        val target = File(parent, artifact.fileName)
        check(target.canonicalFile == target.absoluteFile) { "KOTLIN_RUNTIME_TARGET_LINK" }
        if (verified(target, artifact)) {
            check(target.setReadOnly() && !target.canWrite()) { "KOTLIN_RUNTIME_CACHE_READ_ONLY_FAILED" }
            check(verified(target, artifact)) { "KOTLIN_RUNTIME_CACHE_INTEGRITY" }
            return target
        }

        val staged = File.createTempFile("kotlin-", ".part", parent)
        var keep = false
        try {
            val digest = MessageDigest.getInstance("SHA-256")
            var size = 0L
            staged.outputStream().use { output ->
                check(staged.setReadOnly()) { "KOTLIN_RUNTIME_STAGED_READ_ONLY_FAILED" }
                openAsset(artifact.assetPath).use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        if (count == 0) continue
                        size += count
                        check(size <= artifact.sizeBytes) { "KOTLIN_RUNTIME_ASSET_SIZE_LIMIT" }
                        digest.update(buffer, 0, count)
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            check(!staged.canWrite()) { "KOTLIN_RUNTIME_STAGED_WRITABLE" }
            check(size == artifact.sizeBytes && hex(digest.digest()) == artifact.sha256) {
                "KOTLIN_RUNTIME_ASSET_INTEGRITY"
            }
            keep = true
            if (target.exists()) check(target.isFile && target.delete()) { "KOTLIN_RUNTIME_REPLACE_FAILED" }
            check(staged.renameTo(target)) { "KOTLIN_RUNTIME_ACTIVATION_FAILED" }
            check(target.setReadOnly() && !target.canWrite()) { "KOTLIN_RUNTIME_READ_ONLY_FAILED" }
            check(verified(target, artifact)) { "KOTLIN_RUNTIME_ACTIVATION_INTEGRITY" }
            return target
        } finally {
            if (!keep && staged.exists()) staged.delete()
            if (staged.exists()) staged.delete()
        }
    }

    private fun readBoundedAsset(path: String, limit: Int): ByteArray {
        val bytes = ByteArrayOutputStream()
        openAsset(path).use { input ->
            val buffer = ByteArray(8 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                check(bytes.size() + count <= limit) { "KOTLIN_RUNTIME_MANIFEST_SIZE_LIMIT" }
                bytes.write(buffer, 0, count)
            }
        }
        return bytes.toByteArray()
    }

    private fun ensureDirectory(directory: File, code: String) {
        check(directory.canonicalFile == directory.absoluteFile) { "${code}_LINK" }
        check(directory.isDirectory || directory.mkdirs()) { "${code}_UNAVAILABLE" }
        check(directory.canonicalFile == directory.absoluteFile) { "${code}_LINK" }
    }

    private fun verified(file: File, artifact: KotlinRuntimeAssetPin): Boolean {
        if (file.canonicalFile != file.absoluteFile || !file.isFile || file.length() != artifact.sizeBytes) return false
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > artifact.sizeBytes) return false
                digest.update(buffer, 0, count)
            }
        }
        return size == artifact.sizeBytes && hex(digest.digest()) == artifact.sha256
    }

    private fun sha256(bytes: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(bytes)

    private fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        const val PROVIDER_VERSION = "1.9.20-android3"
        const val COMPILER_VERSION = "1.9.20"
        const val MIN_API = 23
        private const val MANIFEST_ASSET_PATH = "embedded/kotlin/manifest.json"
        private const val MAX_MANIFEST_BYTES = 128 * 1024
        private val RUNTIME_DEX_COUNT = PackagedKotlinRuntimePins.RUNTIME_DEX_COUNT
        private const val R8_VERSION = "8.2.33"
        private const val ANDROID_API = 35
        private const val ANDROID_JAR_SHA256 =
            "4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b"
        private const val DESUGAR_RUNTIME_SHA256 = PackagedKotlinRuntimePins.DESUGAR_RUNTIME_SHA256
        private const val DESUGAR_CONFIGURATION_SHA256 =
            "1dd26f9e2440904eab5014a244f500d8b453f3629bfd5478a3f9895838e5c35a"
        private val SHA256_PATTERN = Regex("[0-9a-f]{64}")
        private val extractionLock = Any()
        val CHILD_FIRST_PREFIXES = listOf(
            "kotlin.",
            "org.jetbrains.kotlin.",
            "org.jetbrains.org.",
            "gnu.trove.",
            "org.jetbrains.annotations.",
            "org.intellij.lang.annotations.",
            "javaslang.",
            "org.jetbrains.concurrency.",
            "org.jetbrains.jps.",
            "j$."
        ) + if (PackagedKotlinRuntimePins.RUNTIME_PROGRAM_INPUTS.any {
            it.relativePath == "upstream/kotlinx-coroutines-core-jvm-1.5.0.jar"
        }) listOf("kotlinx.coroutines.") else emptyList()
        val CHILD_FIRST_CLASSES = listOf("FirNativeForwardDeclarationGetClassCallChecker")
        private val RUNTIME_ARTIFACT = PackagedKotlinRuntimePins.RUNTIME_ARTIFACT
        private val RESOURCE_ARTIFACTS = PackagedKotlinRuntimePins.RESOURCE_ARTIFACTS
        private val STDLIB_ARTIFACT = PackagedKotlinRuntimePins.STDLIB_ARTIFACT
    }
}
