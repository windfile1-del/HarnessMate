package dev.wind.agent.webprovider

import android.content.Context
import android.net.Uri
import android.util.Base64
import dev.wind.agent.CancellationToken
import dev.wind.agent.Message
import java.io.File
import java.util.UUID

class WebUploadStager(private val context: Context) {
    data class Upload(val uri: Uri, val name: String, val mime: String, val size: Long)
    private val root = File(context.cacheDir, "web-uploads")
    private var requestDir: File? = null
    fun stage(inputs: List<Message.Attachment>, token: CancellationToken): List<Upload> {
        require(inputs.size <= 8) { "TOO_MANY_WEB_ATTACHMENTS" }
        if (inputs.isEmpty()) return emptyList()
        val dir = File(root, UUID.randomUUID().toString().replace("-", ""))
        require(dir.mkdirs()) { "UPLOAD_STAGING_FAILED" }
        requestDir = dir
        var total = 0L
        return inputs.mapIndexed { index, attachment ->
            token.throwIfCancelled()
            val originalName = attachment.name ?: "attachment"
            val name = "${index + 1}_" + originalName.replace(Regex("[^a-zA-Z0-9_.-]"), "_").take(80).ifEmpty { "attachment" }
            val mime = attachment.mime ?: attachment.dataUri?.substringAfter("data:")?.substringBefore(';') ?: "application/octet-stream"
            val suffix = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(mime)?.let { ".$it" } ?: ""
            val filename = if (suffix.isNotEmpty() && !name.endsWith(suffix, true)) name + suffix else name
            val target = File(dir, filename)
            val data = attachment.dataUri
            if (data != null) {
                require(data.length <= 34_000_000) { "WEB_ATTACHMENT_TOO_LARGE" }
                val match = Regex("^data:(image/(?:png|jpeg|webp|gif));base64,(.*)$", RegexOption.DOT_MATCHES_ALL).matchEntire(data)
                    ?: throw IllegalArgumentException("UNSUPPORTED_IMAGE_DATA")
                target.outputStream().use { it.write(Base64.decode(match.groupValues[2], Base64.DEFAULT)) }
            } else {
                val path = attachment.path ?: throw IllegalArgumentException("ATTACHMENT_UNAVAILABLE")
                val input = if (path.startsWith("content://")) context.contentResolver.openInputStream(Uri.parse(path))
                    ?: throw IllegalArgumentException("ATTACHMENT_UNAVAILABLE") else File(path).inputStream()
                input.use { source -> target.outputStream().use { sink ->
                    val buffer = ByteArray(32768)
                    var copied = 0L
                    while (true) {
                        token.throwIfCancelled()
                        val n = source.read(buffer)
                        if (n < 0) break
                        copied += n
                        require(copied <= 24_000_000) { "WEB_ATTACHMENT_TOO_LARGE" }
                        sink.write(buffer, 0, n)
                    }
                } }
            }
            total += target.length()
            require(target.length() <= 24_000_000 && total <= 48_000_000) { "WEB_ATTACHMENTS_TOO_LARGE" }
            require(mime != "text/html" && mime != "image/svg+xml") { "UNSUPPORTED_WEB_ATTACHMENT_TYPE" }
            Upload(Uri.Builder().scheme("content").authority(context.packageName + ".webattachments")
                .appendPath(dir.name).appendPath(filename).build(), filename, mime, target.length())
        }
    }
    fun close() {
        val dir = requestDir ?: return
        dir.listFiles()?.forEach { if (it.isFile) it.delete() }
        dir.delete(); requestDir = null
    }
    companion object {
        fun clearExpired(context: Context) {
            val root = File(context.cacheDir, "web-uploads")
            val deadline = System.currentTimeMillis() - 30 * 60 * 1000L
            root.listFiles()?.filter { it.isDirectory && it.name.matches(Regex("[a-f0-9]{32}")) && it.lastModified() < deadline }
                ?.forEach { dir -> dir.listFiles()?.forEach { if (it.isFile) it.delete() }; dir.delete() }
        }
    }
}
