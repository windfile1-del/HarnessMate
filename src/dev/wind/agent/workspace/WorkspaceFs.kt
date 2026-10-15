package dev.wind.agent.workspace

import java.io.InputStream
import java.io.OutputStream

/**
 * File operations interface abstracting the user-authorized Workspace.
 * All paths accepted and returned are virtual workspace paths.
 */
interface WorkspaceFs {
    fun isConfigured(): Boolean
    fun getRootDisplayName(): String
    fun list(path: String = ""): List<WorkspaceEntry>
    fun read(path: String): ByteArray
    fun readText(path: String, charset: String = "UTF-8"): String
    fun write(path: String, data: ByteArray)
    fun writeText(path: String, text: String, charset: String = "UTF-8")
    fun mkdir(path: String): Boolean
    fun delete(path: String): Boolean
    fun move(from: String, to: String): Boolean
    fun exists(path: String): Boolean
    fun stat(path: String): WorkspaceEntry?
    fun search(query: String, maxResults: Int = 100, scopePath: String = ""): List<WorkspaceEntry>
    fun glob(pattern: String, maxResults: Int = 100, scopePath: String = ""): List<WorkspaceEntry>
    fun openInputStream(path: String): InputStream
    fun openOutputStream(path: String): OutputStream
}
