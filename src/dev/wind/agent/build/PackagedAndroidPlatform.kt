package dev.wind.agent.build

import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/** APK-owned compiler/resource-linker input. Never put this jar on an ART class loader. */
class PackagedAndroidPlatform(
    private val storageRoot: File,
    private val openAsset: (String) -> InputStream
) : AndroidPlatformProvider {
    override val approvedRoots: List<File> = listOf(storageRoot.absoluteFile)

    override fun resolve(compileSdk: Int): File? {
        if (compileSdk != COMPILE_SDK) return null
        return try {
            synchronized(extractionLock) {
                check(storageRoot.canonicalFile == storageRoot.absoluteFile) { "PLATFORM_ROOT_LINK" }
                check(storageRoot.isDirectory || storageRoot.mkdirs()) { "PLATFORM_ROOT_UNAVAILABLE" }
                val target = File(storageRoot, "android-$COMPILE_SDK-$SHA256.jar")
                check(target.canonicalFile == target.absoluteFile) { "PLATFORM_TARGET_LINK" }
                if (verified(target)) return@synchronized target
                val staged = File.createTempFile("platform-", ".part", storageRoot)
                try {
                    val digest = MessageDigest.getInstance("SHA-256")
                    var size = 0L
                    openAsset(ASSET_PATH).use { source ->
                        staged.outputStream().use { output ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val count = source.read(buffer)
                                if (count < 0) break
                                if (count == 0) continue
                                size += count
                                check(size <= SIZE_BYTES) { "PLATFORM_SIZE_LIMIT" }
                                digest.update(buffer, 0, count)
                                output.write(buffer, 0, count)
                            }
                            output.fd.sync()
                        }
                    }
                    check(size == SIZE_BYTES && hex(digest.digest()) == SHA256) { "PLATFORM_INTEGRITY" }
                    // A failed extraction never replaces an existing cache entry.
                    if (target.exists()) {
                        check(target.isFile && target.delete()) { "PLATFORM_REPLACE_FAILED" }
                    }
                    check(staged.renameTo(target)) { "PLATFORM_ACTIVATION_FAILED" }
                    check(target.setReadOnly()) { "PLATFORM_READ_ONLY_FAILED" }
                    check(verified(target)) { "PLATFORM_ACTIVATION_INTEGRITY" }
                    target
                } finally {
                    if (staged.exists()) staged.delete()
                }
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun verified(file: File): Boolean {
        if (!file.isFile || file.length() != SIZE_BYTES) return false
        val digest = MessageDigest.getInstance("SHA-256")
        var size = 0L
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                size += count
                if (size > SIZE_BYTES) return false
                digest.update(buffer, 0, count)
            }
        }
        return size == SIZE_BYTES && hex(digest.digest()) == SHA256
    }

    private fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it.toInt() and 255) }

    companion object {
        const val COMPILE_SDK = 35
        const val ASSET_PATH = "embedded/android-35/android.jar"
        const val SIZE_BYTES = 27092450L
        const val SHA256 = "4566663c3876e022b4fa4ced8c8697c4ab1688267f090114fd92d027b32e619b"
        private val extractionLock = Any()
    }
}
