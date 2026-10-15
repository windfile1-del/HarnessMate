package dev.wind.agent.intelligence

import dev.wind.agent.project.ProjectPathParser
import dev.wind.agent.project.VegaProject
import dev.wind.agent.workspace.WorkspacePath

class CodePathMapper(
    private val project: VegaProject
) {
    fun toExecutionRelative(virtualPath: String): String {
        validateVirtualPath(virtualPath)
        val parsed = ProjectPathParser.parse(virtualPath, project)
        val mount = project.findMount(parsed.mountAlias)
            ?: throw IllegalArgumentException("Mount alias not found: ${parsed.mountAlias}")
        val execPrefix = mount.executionPath.trim('/').trim()
        val rel = parsed.relativePath.trim('/').trim()
        return when {
            execPrefix.isEmpty() && rel.isEmpty() -> ""
            execPrefix.isEmpty() -> rel
            rel.isEmpty() -> execPrefix
            else -> "$execPrefix/$rel"
        }
    }

    fun toVirtualPath(executionRelative: String): String {
        val sanitized = WorkspacePath.sanitize(executionRelative.trim().replace('\\', '/'))
        if (sanitized.isEmpty()) {
            return "workspace://"
        }
        val primary = project.primaryMount()
        val primaryExec = primary?.executionPath?.trim('/') ?: ""

        for (mount in project.mounts) {
            val exec = mount.executionPath.trim('/')
            if (exec.isNotEmpty()) {
                if (sanitized == exec) {
                    return if (mount.isPrimary) "workspace://" else "project://${mount.alias}/"
                }
                if (sanitized.startsWith("$exec/")) {
                    val sub = sanitized.substring(exec.length + 1)
                    return if (mount.isPrimary) {
                        "workspace://$sub"
                    } else {
                        "project://${mount.alias}/$sub"
                    }
                }
            }
        }

        if (primary != null && primaryExec.isEmpty()) {
            return "workspace://$sanitized"
        }

        throw IllegalArgumentException("UNKNOWN_EXECUTION_PATH: Path '$sanitized' does not belong to any mount in project '${project.id}'")
    }

    private fun validateVirtualPath(path: String) {
        val raw = path.trim()
        if (raw.contains('\u0000') || raw.contains('\r') || raw.contains('\n')) {
            throw SecurityException("CODE_INTEL_INVALID_PATH: Path contains control characters")
        }
        val lower = raw.lowercase()
        if (!lower.startsWith("workspace://") && !lower.startsWith("project://")) {
            throw SecurityException("CODE_INTEL_INVALID_PATH: Path must start with 'workspace://' or 'project://'")
        }
        if (lower.startsWith("execution://")) {
            throw SecurityException("CODE_INTEL_INVALID_PATH: execution:// paths are rejected in Code Intelligence")
        }
        if (lower.startsWith("content://") || lower.startsWith("file://")) {
            throw SecurityException("CODE_INTEL_INVALID_PATH: URI schemes content:// and file:// are prohibited")
        }
        if (raw.startsWith("/") || raw.startsWith("\\")) {
            throw SecurityException("CODE_INTEL_INVALID_PATH: Absolute paths are prohibited")
        }
        if (raw.length >= 2 && raw[1] == ':' && raw[0].isLetter()) {
            throw SecurityException("CODE_INTEL_INVALID_PATH: Windows drive paths are prohibited")
        }
        if (raw.split('/', '\\').any { it == ".." }) {
            throw SecurityException("CODE_INTEL_INVALID_PATH: Directory traversal '..' is prohibited")
        }
    }
}
