package dev.wind.agent.project

import dev.wind.agent.workspace.WorkspacePath

data class ProjectPath(
    val mountAlias: String,
    val relativePath: String,
    val isPrimaryWorkspaceAlias: Boolean = false
) {
    fun toVirtualUri(): String {
        return if (isPrimaryWorkspaceAlias) {
            if (relativePath.isEmpty()) "workspace://" else "workspace://$relativePath"
        } else {
            if (relativePath.isEmpty()) "project://$mountAlias/" else "project://$mountAlias/$relativePath"
        }
    }
}

object ProjectPathParser {
    const val SCHEME_WORKSPACE = "workspace://"
    const val SCHEME_PROJECT = "project://"

    fun parse(raw: String?, project: VegaProject): ProjectPath {
        if (raw.isNullOrBlank()) {
            val primary = project.primaryMount() ?: throw IllegalStateException("No primary mount in project")
            return ProjectPath(primary.alias, "", isPrimaryWorkspaceAlias = true)
        }

        val trimmed = raw.trim().replace('\\', '/')

        if (trimmed.startsWith(SCHEME_WORKSPACE, ignoreCase = true)) {
            val rel = trimmed.substring(SCHEME_WORKSPACE.length)
            val clean = WorkspacePath.sanitize(rel)
            val primary = project.primaryMount() ?: throw IllegalStateException("No primary mount in project")
            return ProjectPath(primary.alias, clean, isPrimaryWorkspaceAlias = true)
        }

        if (trimmed.startsWith(SCHEME_PROJECT, ignoreCase = true)) {
            val withoutScheme = trimmed.substring(SCHEME_PROJECT.length).trimStart('/')
            val firstSlash = withoutScheme.indexOf('/')
            val alias: String
            val rel: String
            if (firstSlash < 0) {
                alias = withoutScheme
                rel = ""
            } else {
                alias = withoutScheme.substring(0, firstSlash)
                rel = withoutScheme.substring(firstSlash + 1)
            }

            if (alias.isEmpty()) {
                throw SecurityException("Invalid project URI: missing mount alias in '$raw'")
            }

            val mount = project.findMount(alias)
                ?: throw IllegalArgumentException("Mount alias not found in project: '$alias'")

            val clean = WorkspacePath.sanitize(rel)
            return ProjectPath(mount.alias, clean, isPrimaryWorkspaceAlias = false)
        }

        // 无 Scheme 前缀的普通相对路径，默认指向 Primary Mount
        val clean = WorkspacePath.sanitize(trimmed)
        val primary = project.primaryMount() ?: throw IllegalStateException("No primary mount in project")
        return ProjectPath(primary.alias, clean, isPrimaryWorkspaceAlias = true)
    }
}
