package dev.wind.agent.shell.mirror

import dev.wind.agent.CancellationToken
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** File payloads stay outside the control/text plane and are checked before publication. */
object MirrorFileTransfer {
    fun copyVerified(input: InputStream, output: OutputStream, expectedSize: Long,
                     expectedSha256: String, token: CancellationToken? = null): Long {
        require(expectedSize >= 0) { "MIRROR_REMOTE_SIZE_INVALID" }
        require(expectedSha256.matches(Regex("[0-9a-f]{64}"))) { "MIRROR_REMOTE_HASH_INVALID" }
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            token?.throwIfCancelled()
            val read = input.read(buffer)
            if (read < 0) break
            if (read == 0) continue
            check(read.toLong() <= expectedSize - count) { "MIRROR_EXPORT_SIZE_MISMATCH" }
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            count += read
        }
        check(count == expectedSize) { "MIRROR_EXPORT_SIZE_MISMATCH" }
        val hash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
        check(hash == expectedSha256) { "MIRROR_EXPORT_HASH_MISMATCH" }
        return count
    }

    /** One private, verified staging file at a time; never open a canonical output on export failure. */
    fun <T> withStagedFile(directory: File, client: MirrorClient, path: String,
                           size: Long, sha256: String, action: (File) -> T): T {
        val staging = File.createTempFile("mirror-writeback-", ".tmp", directory)
        try {
            FileOutputStream(staging).use { output ->
                client.copyFileTo(path, size, sha256, output)
                output.fd.sync()
            }
            return action(staging)
        } finally {
            staging.delete()
        }
    }
}
