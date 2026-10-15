package dev.wind.agent.history

import android.content.Context
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.UUID

class SnapshotStore(context: Context) {

    private val snapshotDir = File(context.filesDir, "snapshots").apply {
        if (!isDirectory) mkdirs()
    }

    fun createSnapshot(data: ByteArray): String {
        val id = UUID.randomUUID().toString()
        val tmpFile = File(snapshotDir, id + ".tmp")
        val finalFile = File(snapshotDir, id + ".bin")
        FileOutputStream(tmpFile).use { fos ->
            fos.write(data)
            fos.fd.sync()
        }
        if (!tmpFile.renameTo(finalFile)) {
            tmpFile.copyTo(finalFile, overwrite = true)
            tmpFile.delete()
        }
        return id
    }

    fun createSnapshot(input: java.io.InputStream): String {
        val id = UUID.randomUUID().toString()
        val tmpFile = File(snapshotDir, id + ".tmp")
        val finalFile = File(snapshotDir, id + ".bin")
        try {
            FileOutputStream(tmpFile).use { fos ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    fos.write(buf, 0, n)
                }
                fos.fd.sync()
            }
            check(tmpFile.renameTo(finalFile)) { "Cannot persist streaming snapshot" }
            return id
        } finally {
            tmpFile.delete()
        }
    }

    fun readSnapshot(id: String): ByteArray? {
        val file = File(snapshotDir, id + ".bin")
        if (!file.isFile) return null
        return FileInputStream(file).use { it.readBytes() }
    }

    fun openSnapshotStream(id: String): java.io.InputStream? {
        val file = File(snapshotDir, id + ".bin")
        if (!file.isFile) return null
        return FileInputStream(file)
    }

    fun deleteSnapshot(id: String): Boolean {
        return File(snapshotDir, id + ".bin").delete()
    }

    fun clearAll() {
        snapshotDir.listFiles()?.forEach { it.delete() }
    }
}
