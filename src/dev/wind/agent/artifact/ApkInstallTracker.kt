package dev.wind.agent.artifact

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption

data class ApkInstallRecord(
    val operationId: String,
    val packageName: String,
    val artifactUri: String,
    val state: String,
    val message: String,
    val updatedAt: Long
) {
    fun toJson(): JSONObject = JSONObject()
        .put("operation_id", operationId)
        .put("package_name", packageName)
        .put("artifact_uri", artifactUri)
        .put("status", state)
        .put("message", message)
        .put("updated_at", updatedAt)

    companion object {
        fun fromJson(json: JSONObject): ApkInstallRecord = ApkInstallRecord(
            operationId = json.optString("operation_id", ""),
            packageName = json.optString("package_name", ""),
            artifactUri = json.optString("artifact_uri", ""),
            state = json.optString("status", "UNKNOWN"),
            message = json.optString("message", ""),
            updatedAt = json.optLong("updated_at", 0L)
        )
    }
}

class ApkInstallTracker(private val storageDir: File) {
    init {
        if (!storageDir.exists()) storageDir.mkdirs()
    }

    fun recordOperation(operationId: String, packageName: String, artifactUri: String, initialState: String, message: String): ApkInstallRecord {
        val record = ApkInstallRecord(operationId, packageName, artifactUri, initialState, message, System.currentTimeMillis())
        save(record)
        return record
    }

    fun updateStatus(operationId: String, state: String, message: String) {
        val existing = getStatus(operationId)
        val updated = if (existing != null) {
            existing.copy(state = state, message = message, updatedAt = System.currentTimeMillis())
        } else {
            ApkInstallRecord(operationId, "", "", state, message, System.currentTimeMillis())
        }
        save(updated)
    }

    fun getStatus(operationId: String): ApkInstallRecord? {
        val file = File(storageDir, "$operationId.json")
        if (!file.exists() || !file.isFile) return null
        return try {
            val content = String(Files.readAllBytes(file.toPath()), Charsets.UTF_8)
            ApkInstallRecord.fromJson(JSONObject(content))
        } catch (_: Exception) {
            null
        }
    }

    private fun save(record: ApkInstallRecord) {
        val file = File(storageDir, "${record.operationId}.json")
        val temp = File(storageDir, ".${record.operationId}.tmp")
        try {
            Files.write(temp.toPath(), record.toJson().toString().toByteArray(Charsets.UTF_8))
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: Exception) {
            Files.deleteIfExists(temp.toPath())
        }
    }

    companion object {
        @Volatile
        private var instance: ApkInstallTracker? = null

        fun get(context: Context): ApkInstallTracker =
            instance ?: synchronized(this) {
                instance ?: ApkInstallTracker(File(context.noBackupFilesDir, "p7-apk-installs")).also {
                    instance = it
                }
            }

        fun forDirectory(dir: File): ApkInstallTracker = ApkInstallTracker(dir)
    }
}
