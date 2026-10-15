package dev.wind.agent

import dev.wind.agent.runtime.vcs.GitService
import dev.wind.agent.runtime.vcs.JGitGitService
import java.io.File
import java.nio.file.Files

/** Real local-repository contract for the vendored JGit reflection adapter. */
object JGitGitServiceTests {
    @JvmStatic
    fun main(args: Array<String>) {
        val root = Files.createTempDirectory("vega-jgit-c1b1").toFile()
        var fixtureGit: Any? = null
        try {
            fixtureGit = initializeRepository(root)
            val service: GitService = JGitGitService()
            check(service is JGitGitService && service.available) {
                "vendored JGit provider is unavailable"
            }

            val fresh = service.status(root)
            check(fresh.branch.isNotBlank()) { "fresh repository status has no branch" }
            val originalBranch = fresh.branch

            File(root, "alpha.txt").writeText("alpha\n")
            val untracked = service.status(root)
            check("alpha.txt" in untracked.untracked) {
                "fresh alpha.txt was not reported as untracked: $untracked"
            }

            service.add(root, listOf("alpha.txt"))
            val staged = service.status(root)
            check("alpha.txt" in staged.added) {
                "added alpha.txt was not reported as staged/added"
            }

            val commit = service.commit(root, "initial")
            check(commit.id.isNotBlank()) { "initial commit returned a blank id" }
            check(commit.message == "initial") {
                "initial commit message changed: ${commit.message}"
            }
            check(service.log(root, 20).any {
                it.id == commit.id && it.message == "initial"
            }) { "initial commit was missing from JGit log" }

            File(root, "alpha.txt").writeText("alpha changed\n")
            val changed = service.status(root)
            check("alpha.txt" in changed.changed) {
                "modified alpha.txt was not reported as changed"
            }
            check(service.diff(root).any { it.contains("alpha.txt") }) {
                "JGit diff did not report the alpha.txt path transition"
            }

            val secondBranch = "c1b1-second"
            createBranch(root, secondBranch)
            val branches = service.branches(root)
            check(originalBranch in branches && secondBranch in branches) {
                "JGit branches did not contain both local branches: $branches"
            }
            service.checkout(root, secondBranch)
            check(service.status(root).branch == secondBranch) {
                "checkout did not select the second local branch"
            }
            service.checkout(root, originalBranch)
            check(service.status(root).branch == originalBranch) {
                "checkout did not return to the original branch"
            }

            expectIllegalArgument("GIT_PATH_INVALID") {
                service.add(root, listOf("../escape"))
            }
            expectIllegalArgument("GIT_BRANCH_INVALID") {
                service.checkout(root, "bad branch")
            }
            expectIllegalArgument("GIT_LOG_LIMIT") {
                service.log(root, 0)
            }
            println("JGitGitServiceTests: OK")
        } finally {
            fixtureGit?.let { runCatching { call(it, "close") } }
            root.deleteRecursively()
        }
    }

    /** Fixture-only repository creation through JGit reflection; no native git. */
    private fun initializeRepository(root: File): Any {
        val loader = Thread.currentThread().contextClassLoader
            ?: JGitGitServiceTests::class.java.classLoader
        val gitClass = Class.forName("org.eclipse.jgit.api.Git", true, loader)
        val init = gitClass.getMethod("init").invoke(null)
            ?: error("JGit init returned null")
        val git = call(init, "setDirectory", root)?.let { call(it, "call") }
            ?: error("JGit init call returned null")
        val repository = call(git, "getRepository")
            ?: error("JGit repository was null")
        val config = call(repository, "getConfig")
            ?: error("JGit config was null")
        call(config, "setString", "user", null, "name", "Vega C1B1")
        call(config, "setString", "user", null, "email", "vega-c1b1@example.invalid")
        call(config, "save")
        return git
    }

    private fun createBranch(root: File, name: String) {
        val loader = Thread.currentThread().contextClassLoader
            ?: JGitGitServiceTests::class.java.classLoader
        val gitClass = Class.forName("org.eclipse.jgit.api.Git", true, loader)
        val git = gitClass.getMethod("open", File::class.java).invoke(null, root)
            ?: error("JGit open returned null")
        try {
            val command = call(git, "branchCreate") ?: error("branchCreate returned null")
            val named = call(command, "setName", name) ?: error("setName returned null")
            call(named, "call")
        } finally {
            runCatching { call(git, "close") }
        }
    }

    private fun call(target: Any, method: String, vararg args: Any?): Any? {
        val candidates = target.javaClass.methods.filter {
            it.name == method && it.parameterTypes.size == args.size
        }
        val selected = candidates.firstOrNull { candidate ->
            candidate.parameterTypes.withIndex().all { (index, type) ->
                val value = args[index]
                value == null || type.isAssignableFrom(value.javaClass) ||
                    (type.isPrimitive && value is Number)
            }
        } ?: error("JGit fixture method missing: ${target.javaClass.name}.$method")
        return selected.invoke(target, *args)
    }

    private fun expectIllegalArgument(expected: String, action: () -> Unit) {
        var actual: String? = null
        try {
            action()
        } catch (error: IllegalArgumentException) {
            actual = error.message
        }
        check(actual == expected) { "expected $expected, got $actual" }
    }
}
