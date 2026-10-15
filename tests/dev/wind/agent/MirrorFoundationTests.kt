package dev.wind.agent

import dev.wind.agent.shell.mirror.*

object MirrorFoundationTests {
    @JvmStatic fun main(args: Array<String>) {
        val id = WorkspaceIdentity.fromUri("content://provider/tree/project")
        check(id.length == 24 && id == WorkspaceIdentity.fromUri("content://provider/tree/project"))
        check(id != WorkspaceIdentity.fromUri("content://provider/tree/other"))
        check(runCatching { WorkspaceIdentity.remoteRoot("../../escape") }.isFailure)
        check(WorkspaceIgnorePolicy.isIgnored("src/__pycache__/foo.pyc"))
        check(WorkspaceIgnorePolicy.isIgnored("nested\\node_modules\\a.js"))
        check(!WorkspaceIgnorePolicy.isIgnored("gradle/wrapper/gradle-wrapper.jar"))
        val hash = "a".repeat(64)
        fun file(path: String, sha: String? = hash) = ManifestEntry(path, false, 10, 0, sha)
        fun manifest(vararg entries: ManifestEntry) = WorkspaceManifest(id, 1, entries.associateBy { it.path })
        val baseline = manifest(file("one"), file("two"), file("gone"))
        val delta = WorkspaceSyncPlanner.plan(baseline, manifest(file("one"), file("two", "b".repeat(64)), file("new")))
        check(delta.created.map { it.path } == listOf("new"))
        check(delta.modified.map { it.path } == listOf("two"))
        check(delta.deleted == listOf("gone"))
        check(WorkspaceSyncPlanner.plan(baseline, baseline).isEmpty())
        check(WorkspaceSyncPlanner.plan(manifest(file("x", null)), manifest(file("x", null))).modified.size == 1)
        check(WorkspaceSyncPlanner.plan(baseline, manifest()).deleted.size == 3)
        val replacement = WorkspaceSyncPlanner.plan(manifest(ManifestEntry("dir", true, 0, 0, null), file("dir/child")), manifest(file("dir")))
        check(replacement.deleted == listOf("dir/child", "dir"))
        check(replacement.created.single().path == "dir")
        check(WorkspaceSyncPlanner.plan(manifest(file("build/output")), manifest()).isEmpty())
        check(runCatching { file("../escape") }.isFailure)
        check(runCatching { WorkspaceSyncPlanner.plan(baseline, baseline.copy(workspaceId = "b".repeat(24))) }.isFailure)
        val digestBefore = ManifestDigest.of(baseline.entries)
        val digestWithDifferentMtime = ManifestDigest.of(
            baseline.entries.mapValues { (_, value) -> value.copy(modified = value.modified + 99) }
        )
        check(ManifestDigest.isDigest(digestBefore) && digestBefore == digestWithDifferentMtime)
        val topology = "b".repeat(64)
        val clean = MirrorState.clean("project", 3, topology)
        val restored = MirrorState.fromJson(clean.toJson(), "project")
        check(restored == clean && restored.phase == MirrorPhase.CLEAN)
        val recovered = clean.copy(
            phase = MirrorPhase.MUTATING,
            canonicalBaselineDigest = digestBefore,
            remoteDigest = digestBefore,
            sessionMounts = listOf(MirrorSessionMount("mount", "content://mount", "READ_WRITE", "main")),
            pending = PendingExecution("exec", "task", "root", digestBefore, false, 1L)
        )
        check(MirrorState.fromJson(recovered.toJson(), "project").pending?.executionId == "exec")
        check(runCatching {
            MirrorState.fromJson(recovered.toJson().put("canonicalBaselineDigest", "invalid"), "project")
        }.isFailure) { "invalid non-null digest was silently treated as absent" }
        check(runCatching {
            MirrorState(
                "project", 3, topology, MirrorPhase.PENDING, null, digestBefore,
                recovered.sessionMounts, recovered.pending, null
            )
        }.isFailure) { "pending state accepted a missing canonical baseline digest" }
        var first = false
        var second = false
        val t1 = Thread { MirrorLockRegistry.withProject("p1") { Thread.sleep(20); first = true } }
        val t2 = Thread { MirrorLockRegistry.withProject("p2") { second = true } }
        t1.start(); t2.start(); t1.join(); t2.join()
        check(first && second)
        println("MirrorFoundationTests: digest/state/lock checks passed")
    }
}
