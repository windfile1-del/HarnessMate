package dev.wind.agent.project

import dev.wind.agent.workspace.WorkspaceEntry
import java.io.InputStream
import java.io.OutputStream

/**
 * 跨多 Root 的统一虚拟文件系统接口
 * 抽象出 project://<mount-alias>/<path> 与兼容别名 workspace://<path>
 */
interface ProjectFs {
    val activeProject: VegaProject?
    
    fun resolveMount(virtualPath: String): Pair<WorkspaceMount, String>
    
    fun list(virtualPath: String): List<WorkspaceEntry>
    fun readText(virtualPath: String, charset: String = "UTF-8"): String
    fun writeText(virtualPath: String, text: String, charset: String = "UTF-8")
    fun exists(virtualPath: String): Boolean
    fun stat(virtualPath: String): WorkspaceEntry?
    fun openInputStream(virtualPath: String): InputStream
    fun openOutputStream(virtualPath: String): OutputStream
    fun delete(virtualPath: String): Boolean
    fun mkdir(virtualPath: String): Boolean
}
