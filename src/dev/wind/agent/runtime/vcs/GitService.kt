package dev.wind.agent.runtime.vcs

import java.io.File

data class GitStatus(
    val branch: String,
    val added: Set<String> = emptySet(),
    val changed: Set<String> = emptySet(),
    val removed: Set<String> = emptySet(),
    val untracked: Set<String> = emptySet()
)

data class GitCommit(
    val id: String,
    val message: String
)

/** Provider-neutral VCS surface for Agent git_* tools. */
interface GitService {
    val implementation: String
    fun status(worktree: File): GitStatus
    fun diff(worktree: File): List<String>
    fun add(worktree: File, paths: List<String>)
    fun commit(worktree: File, message: String): GitCommit
    fun branches(worktree: File): List<String>
    fun checkout(worktree: File, branch: String)
    fun log(worktree: File, limit: Int = 20): List<GitCommit>
}

/**
 * JGit adapter loaded through reflection so the host remains buildable before
 * the JGit AAR is packaged.  Once the dependency is present, all operations
 * execute in ART; no `git` executable or shell is consulted.
 */
class JGitGitService(
    private val classLoader: ClassLoader = JGitGitService::class.java.classLoader
) : GitService {
    override val implementation: String = "JGit"

    val available: Boolean
        get() = try {
            REQUIRED_CLASSES.forEach { name ->
                Class.forName(name, false, classLoader)
            }
            true
        } catch (_: Throwable) {
            false
        }

    override fun status(worktree: File): GitStatus = withRepository(worktree) { repo ->
        val result = call(call(repo, "status"), "call")
        GitStatus(
            branch = call(call(repo, "getRepository"), "getBranch").toString(),
            added = stringSet(result, "getAdded"),
            changed = stringSet(result, "getChanged") + stringSet(result, "getModified"),
            removed = stringSet(result, "getRemoved"),
            untracked = stringSet(result, "getUntracked")
        )
    }

    override fun diff(worktree: File): List<String> = withRepository(worktree) { repo ->
        val entries = call(call(repo, "diff"), "call") as? Iterable<*> ?: emptyList<Any>()
        entries.mapNotNull { entry ->
            if (entry == null) null else {
                val oldPath = call(entry, "getOldPath").toString()
                val newPath = call(entry, "getNewPath").toString()
                "$oldPath -> $newPath"
            }
        }
    }

    override fun add(worktree: File, paths: List<String>) {
        require(paths.isNotEmpty()) { "GIT_PATHS_EMPTY" }
        require(paths.all(::isSafeRelativePath)) { "GIT_PATH_INVALID" }
        withRepository(worktree) { repo ->
            paths.forEach { path ->
                val command = call(repo, "add")
                call(command, "addFilepattern", path)
                call(command, "call")
            }
        }
    }

    override fun commit(worktree: File, message: String): GitCommit = withRepository(worktree) { repo ->
        require(message.isNotBlank()) { "GIT_COMMIT_MESSAGE_EMPTY" }
        val commit = call(call(call(repo, "commit"), "setMessage", message), "call")
        GitCommit(call(commit, "getName").toString(), call(commit, "getFullMessage").toString())
    }

    override fun branches(worktree: File): List<String> = withRepository(worktree) { repo ->
        val refs = call(call(repo, "branchList"), "call") as? Iterable<*> ?: emptyList<Any>()
        refs.map { ref ->
            val name = runCatching { call(ref!!, "getName").toString() }
                .getOrElse { ref.toString() }
            name.removePrefix("refs/heads/")
        }.sorted()
    }

    override fun checkout(worktree: File, branch: String) {
        require(branch.isNotBlank() && !branch.startsWith("/") &&
            branch.matches(Regex("[A-Za-z0-9._/-]+"))) { "GIT_BRANCH_INVALID" }
        withRepository(worktree) { repo -> call(call(call(repo, "checkout"), "setName", branch), "call") }
    }

    override fun log(worktree: File, limit: Int): List<GitCommit> = withRepository(worktree) { repo ->
        require(limit in 1..200) { "GIT_LOG_LIMIT" }
        val commits = call(call(repo, "log"), "call") as? Iterable<*> ?: emptyList<Any>()
        commits.take(limit).map { commit ->
            GitCommit(call(commit!!, "getName").toString(), call(commit, "getFullMessage").toString())
        }
    }

    private fun stringSet(target: Any, method: String): Set<String> =
        (call(target, method) as? Iterable<*>)?.mapNotNull { it?.toString() }?.toSet() ?: emptySet()

    private fun isSafeRelativePath(path: String): Boolean {
        if (path.isBlank()) return false
        val normalized = path.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.contains(":/")) return false
        return normalized.split('/').none { it.isEmpty() || it == "." || it == ".." }
    }

    private fun <T> withRepository(worktree: File, block: (Any) -> T): T {
        require(worktree.isDirectory) { "GIT_WORKTREE_INVALID" }
        check(available) { "GIT_JGIT_UNAVAILABLE" }
        val builderClass = Class.forName("org.eclipse.jgit.storage.file.FileRepositoryBuilder", true, classLoader)
        val builder = builderClass.getConstructor().newInstance()
        val repository = call(
            call(call(builder, "setWorkTree", worktree), "readEnvironment"),
            "findGitDir",
            worktree
        )
        val repo = call(repository, "build")
        val gitClass = Class.forName("org.eclipse.jgit.api.Git", false, classLoader)
        val repositoryClass = Class.forName("org.eclipse.jgit.lib.Repository", false, classLoader)
        val git = gitClass.getMethod("wrap", repositoryClass).invoke(null, repo)
            ?: throw IllegalStateException("JGit facade unavailable")
        return try {
            block(git)
        } finally {
            runCatching { call(repo, "close") }
        }
    }

    private fun call(target: Any, method: String, vararg args: Any?): Any {
        val candidates = target.javaClass.methods.filter { it.name == method && it.parameterTypes.size == args.size }
        val selected = candidates.firstOrNull { candidate ->
            candidate.parameterTypes.withIndex().all { (index, type) ->
                val value = args[index]
                value == null || type.isAssignableFrom(value.javaClass) ||
                    (type.isPrimitive && value is Number)
            }
        } ?: throw IllegalStateException("JGit method missing: ${target.javaClass.name}.$method")
        return selected.invoke(target, *args)
    }

    private companion object {
        val REQUIRED_CLASSES = listOf(
            "org.eclipse.jgit.api.Git",
            "org.eclipse.jgit.storage.file.FileRepositoryBuilder",
            "com.googlecode.javaewah.EWAHCompressedBitmap",
            "org.slf4j.Logger",
            "org.apache.commons.codec.binary.Hex"
        )
    }
}
