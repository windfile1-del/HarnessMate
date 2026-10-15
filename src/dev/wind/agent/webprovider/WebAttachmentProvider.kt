package dev.wind.agent.webprovider

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File

/** Read-only, non-exported, grantable upload URIs. Never exposes arbitrary paths. */
class WebAttachmentProvider : ContentProvider() {
    override fun onCreate(): Boolean = true
    private fun resolve(uri: Uri): File {
        val ctx = context ?: throw IllegalStateException("UPLOAD_PROVIDER_UNAVAILABLE")
        val parts = uri.pathSegments
        require(uri.authority == ctx.packageName + ".webattachments" && parts.size == 2)
        require(parts[0].matches(Regex("[a-f0-9]{32}")))
        require(parts[1].matches(Regex("[a-zA-Z0-9_.-]{1,100}")) && parts[1] != "." && parts[1] != "..")
        val root = File(ctx.cacheDir, "web-uploads").canonicalFile
        val file = File(File(root, parts[0]), parts[1]).canonicalFile
        require(file.path.startsWith(root.path + File.separator) && file.isFile) { "UPLOAD_NOT_FOUND" }
        return file
    }
    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        require(mode == "r") { "UPLOAD_READ_ONLY" }
        return ParcelFileDescriptor.open(resolve(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }
    override fun getType(uri: Uri): String {
        val file = resolve(uri)
        return android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(file.extension.lowercase()) ?: "application/octet-stream"
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val f = resolve(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply { addRow(columns.map { when (it) {
            OpenableColumns.DISPLAY_NAME -> f.name
            OpenableColumns.SIZE -> f.length()
            else -> null
        } }.toTypedArray()) }
    }
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("UPLOAD_READ_ONLY")
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("UPLOAD_READ_ONLY")
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException("UPLOAD_READ_ONLY")
}
