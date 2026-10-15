package dev.wind.agent.workspace

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.ByteArrayOutputStream
import java.io.FileNotFoundException
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * SAF implementation of WorkspaceFs using Storage Access Framework tree URI.
 * Guarantees zero access outside the persistable tree permission granted by user.
 */
class SafWorkspaceFs(
    private val context: Context,
    private val rootUri: Uri
) : WorkspaceFs {

    private val resolver: ContentResolver = context.contentResolver

    override fun isConfigured(): Boolean = true

    override fun getRootDisplayName(): String {
        return try {
            val docId = DocumentsContract.getTreeDocumentId(rootUri)
            val docUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, docId)
            val cursor = resolver.query(docUri, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
            try {
                if (cursor != null && cursor.moveToFirst()) cursor.getString(0) else "Workspace"
            } finally {
                cursor?.close()
            }
        } catch (_: Exception) {
            "Workspace"
        }
    }

    override fun list(path: String): List<WorkspaceEntry> {
        val cleanPath = WorkspacePath.sanitize(path)
        val dirUri = resolveDirUri(cleanPath) ?: throw FileNotFoundException("Directory not found: $path")
        val out = mutableListOf<WorkspaceEntry>()

        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
            rootUri,
            DocumentsContract.getDocumentId(dirUri)
        )

        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )

        val cursor = resolver.query(childrenUri, projection, null, null, null)
        try {
            if (cursor != null) {
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val mimeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                val sizeIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
                val modIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)

                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx) ?: continue
                    val mime = cursor.getString(mimeIdx)
                    val isDir = DocumentsContract.Document.MIME_TYPE_DIR == mime
                    val size = cursor.getLong(sizeIdx)
                    val mod = cursor.getLong(modIdx)
                    val entryPath = if (cleanPath.isEmpty()) name else "$cleanPath/$name"

                    out.add(WorkspaceEntry(entryPath, name, isDir, size, mod, mime))
                }
            }
        } finally {
            cursor?.close()
        }
        return out
    }

    override fun read(path: String): ByteArray {
        val stat = stat(path)
        if (stat != null && stat.size > MAX_FILE_SIZE_BYTES) {
            throw IllegalStateException("File size (" + stat.size + " bytes) exceeds safety limit of 16MB")
        }
        openInputStream(path).use { input ->
            val buffer = ByteArrayOutputStream()
            val chunk = ByteArray(8192)
            var read: Int
            while (input.read(chunk).also { read = it } != -1) {
                buffer.write(chunk, 0, read)
            }
            return buffer.toByteArray()
        }
    }

    override fun readText(path: String, charset: String): String {
        return String(read(path), Charset.forName(charset))
    }

    override fun write(path: String, data: ByteArray) {
        val cleanPath = WorkspacePath.sanitize(path)
        if (cleanPath.isEmpty()) throw IllegalArgumentException("Cannot write to workspace root")

        val parentPath = getParentPath(cleanPath)
        val fileName = getFileName(cleanPath)
        val parentUri = resolveDirUri(parentPath)
            ?: throw FileNotFoundException("Parent directory does not exist: $parentPath. Create directories with make_dir first.")

        var docUri = findChildDocUri(parentUri, fileName)
        if (docUri == null) {
            val parentDocId = DocumentsContract.getDocumentId(parentUri)
            val parentTreeUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, parentDocId)
            docUri = DocumentsContract.createDocument(resolver, parentTreeUri, "application/octet-stream", fileName)
                ?: throw IllegalStateException("Failed to create document: $cleanPath")
        }

        // SAF's default "w" mode is provider-defined and need not truncate.
        // All workspace writes replace the complete document, including WAL
        // rollback; an old suffix must never survive a shorter replacement.
        resolver.openOutputStream(docUri, "wt")?.use { out ->
            out.write(data)
            out.flush()
        } ?: throw IllegalStateException("Failed to open output stream: $cleanPath")
    }

    override fun writeText(path: String, text: String, charset: String) {
        write(path, text.toByteArray(Charset.forName(charset)))
    }

    override fun mkdir(path: String): Boolean {
        val cleanPath = WorkspacePath.sanitize(path)
        if (cleanPath.isEmpty()) return true
        if (resolveDocUri(cleanPath) != null) return true
        val parent = getParentPath(cleanPath)
        val name = getFileName(cleanPath)
        val parentUri = resolveDirUri(parent) ?: return false
        val parentDocId = DocumentsContract.getDocumentId(parentUri)
        val parentDocUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, parentDocId)
        val created = DocumentsContract.createDocument(
            resolver, parentDocUri, DocumentsContract.Document.MIME_TYPE_DIR, name
        )
        return created != null
    }

    override fun delete(path: String): Boolean {
        val cleanPath = WorkspacePath.sanitize(path)
        if (cleanPath.isEmpty()) throw IllegalArgumentException("Cannot delete workspace root")
        val docUri = resolveDocUri(cleanPath) ?: return false
        return DocumentsContract.deleteDocument(resolver, docUri)
    }

    override fun move(from: String, to: String): Boolean {
        val cleanFrom = WorkspacePath.sanitize(from)
        val cleanTo = WorkspacePath.sanitize(to)
        val srcUri = resolveDocUri(cleanFrom) ?: return false

        val fromParent = getParentPath(cleanFrom)
        val toParent = getParentPath(cleanTo)
        val newName = getFileName(cleanTo)
        val oldName = getFileName(cleanFrom)

        if (cleanFrom != cleanTo && resolveDocUri(cleanTo) != null) {
            return false
        }

        if (fromParent == toParent) {
            if (newName != oldName) {
                val renamedUri = DocumentsContract.renameDocument(resolver, srcUri, newName)
                return renamedUri != null
            }
            return true
        }

        val srcParentUri = resolveDirUri(fromParent) ?: return false
        val dstParentUri = resolveDirUri(toParent) ?: return false

        val movedUri = DocumentsContract.moveDocument(resolver, srcUri, srcParentUri, dstParentUri) ?: return false
        if (newName != oldName) {
            val renamedUri = DocumentsContract.renameDocument(resolver, movedUri, newName)
            if (renamedUri == null) return false
        }
        return true
    }

    override fun exists(path: String): Boolean {
        val cleanPath = WorkspacePath.sanitize(path)
        if (cleanPath.isEmpty()) return true
        return resolveDocUri(cleanPath) != null
    }

    override fun stat(path: String): WorkspaceEntry? {
        val cleanPath = WorkspacePath.sanitize(path)
        if (cleanPath.isEmpty()) {
            return WorkspaceEntry("", getRootDisplayName(), true, 0L, 0L)
        }
        val docUri = resolveDocUri(cleanPath) ?: return null
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED
        )
        val cursor = resolver.query(docUri, projection, null, null, null)
        try {
            if (cursor != null && cursor.moveToFirst()) {
                val name = cursor.getString(0) ?: getFileName(cleanPath)
                val mime = cursor.getString(1)
                val size = cursor.getLong(2)
                val mod = cursor.getLong(3)
                val isDir = DocumentsContract.Document.MIME_TYPE_DIR == mime
                return WorkspaceEntry(cleanPath, name, isDir, size, mod, mime)
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    override fun search(query: String, maxResults: Int, scopePath: String): List<WorkspaceEntry> {
        val results = mutableListOf<WorkspaceEntry>()
        val q = query.lowercase()

        fun walk(dirPath: String) {
            if (results.size >= maxResults) return
            val entries = try { list(dirPath) } catch (_: Exception) { return }
            for (entry in entries) {
                if (results.size >= maxResults) break
                var matched = entry.name.lowercase().contains(q) || entry.path.lowercase().contains(q)
                if (!matched && !entry.isDirectory && entry.size in 1..256000) {
                    try {
                        val txt = readText(entry.path)
                        if (txt.lowercase().contains(q)) matched = true
                    } catch (_: Exception) {
                    }
                }
                if (matched) results.add(entry)
                if (entry.isDirectory) walk(entry.path)
            }
        }

        walk(scopePath)
        return results
    }

    override fun glob(pattern: String, maxResults: Int, scopePath: String): List<WorkspaceEntry> {
        val results = mutableListOf<WorkspaceEntry>()
        val regex = globToRegex(pattern)

        fun walk(dirPath: String) {
            if (results.size >= maxResults) return
            val entries = try { list(dirPath) } catch (_: Exception) { return }
            for (entry in entries) {
                if (results.size >= maxResults) break
                if (regex.matches(entry.path) || regex.matches(entry.name)) results.add(entry)
                if (entry.isDirectory) walk(entry.path)
            }
        }

        walk(scopePath)
        return results
    }

    override fun openInputStream(path: String): InputStream {
        val cleanPath = WorkspacePath.sanitize(path)
        val docUri = resolveDocUri(cleanPath) ?: throw FileNotFoundException("File not found: $path")
        return resolver.openInputStream(docUri) ?: throw FileNotFoundException("Could not open input stream: $path")
    }

    override fun openOutputStream(path: String): OutputStream {
        val cleanPath = WorkspacePath.sanitize(path)
        if (cleanPath.isEmpty()) throw IllegalArgumentException("Cannot write to workspace root")
        val parentPath = getParentPath(cleanPath)
        val fileName = getFileName(cleanPath)
        val parentUri = resolveDirUri(parentPath)
            ?: throw FileNotFoundException("Parent directory does not exist: $parentPath. Create directories with make_dir first.")

        var docUri = findChildDocUri(parentUri, fileName)
        if (docUri == null) {
            val parentDocId = DocumentsContract.getDocumentId(parentUri)
            val parentTreeUri = DocumentsContract.buildDocumentUriUsingTree(rootUri, parentDocId)
            docUri = DocumentsContract.createDocument(resolver, parentTreeUri, "application/octet-stream", fileName)
                ?: throw IllegalStateException("Failed to create document: $cleanPath")
        }
        return resolver.openOutputStream(docUri, "wt") ?: throw IllegalStateException("Failed to open output stream: $cleanPath")
    }

    private fun resolveDirUri(dirPath: String): Uri? {
        if (dirPath.isEmpty()) {
            val rootDocId = DocumentsContract.getTreeDocumentId(rootUri)
            return DocumentsContract.buildDocumentUriUsingTree(rootUri, rootDocId)
        }
        val segments = dirPath.split('/')
        var currentUri = DocumentsContract.buildDocumentUriUsingTree(
            rootUri, DocumentsContract.getTreeDocumentId(rootUri)
        )
        for (seg in segments) {
            if (seg.isEmpty()) continue
            val child = findChildDocUri(currentUri, seg) ?: return null
            currentUri = child
        }
        return currentUri
    }

    private fun resolveDocUri(filePath: String): Uri? {
        if (filePath.isEmpty()) return resolveDirUri("")
        val parent = getParentPath(filePath)
        val name = getFileName(filePath)
        val parentUri = resolveDirUri(parent) ?: return null
        return findChildDocUri(parentUri, name)
    }

    private fun findChildDocUri(parentUri: Uri, childName: String): Uri? {
        val parentDocId = DocumentsContract.getDocumentId(parentUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(rootUri, parentDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME
        )
        val cursor = resolver.query(childrenUri, projection, null, null, null)
        try {
            if (cursor != null) {
                val idIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val nameIdx = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIdx)
                    if (name == childName) {
                        val childDocId = cursor.getString(idIdx)
                        return DocumentsContract.buildDocumentUriUsingTree(rootUri, childDocId)
                    }
                }
            }
        } finally {
            cursor?.close()
        }
        return null
    }

    private fun getParentPath(p: String): String {
        val idx = p.lastIndexOf('/')
        return if (idx <= 0) "" else p.substring(0, idx)
    }

    private fun getFileName(p: String): String {
        val idx = p.lastIndexOf('/')
        return if (idx < 0) p else p.substring(idx + 1)
    }

    companion object {
        const val MAX_FILE_SIZE_BYTES = 16L * 1024 * 1024
    }

    private fun globToRegex(glob: String): Regex {
        val pattern = glob.replace(".", "\\.")
            .replace("*", ".*")
            .replace("?", ".")
        return Regex("^" + pattern + "$", RegexOption.IGNORE_CASE)
    }
}
