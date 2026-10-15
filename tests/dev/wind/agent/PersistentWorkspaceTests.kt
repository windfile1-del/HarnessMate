package dev.wind.agent

import dev.wind.agent.shell.mirror.*

/** Pure JVM checks for the P3 persistent-state and digest contracts. */
object PersistentWorkspaceTests {
    private var assertions = 0

    private fun check(value: Boolean, message: String) {
        assertions++
        if (!value) throw AssertionError(message)
    }

    @JvmStatic
    fun main(args: Array<String>) {
        val hash = "a".repeat(64)
        val entries = linkedMapOf(
            "z.txt" to ManifestEntry("z.txt", false, 3, 1, hash),
            "src" to ManifestEntry("src", true, 0, 1, null),
            "src/a.kt" to ManifestEntry("src/a.kt", false, 7, 1, "b".repeat(64))
        )
        val first = ManifestDigest.of(entries)
        check(ManifestDigest.isDigest(first), "manifest digest must be SHA-256")
        check(first == ManifestDigest.of(entries.toList().reversed().toMap()), "digest ordering is not deterministic")
        check(first == ManifestDigest.of(entries.mapValues { (_, e) -> e.copy(modified = e.modified + 1000) }),
            "provider mtime leaked into mirror identity")

        val state = MirrorState(
            projectId = "p",
            projectGeneration = 4,
            topologyHash = "c".repeat(64),
            phase = MirrorPhase.PENDING,
            canonicalBaselineDigest = first,
            remoteDigest = first,
            sessionMounts = listOf(MirrorSessionMount("m", "content://m", "READ_WRITE", "main")),
            pending = PendingExecution("e", "t", "root", first, true, 100),
            lastOperation = MirrorOperationStats("e", "execute", 3, 1, 7, 1, 0, 0, 2, "OK")
        )
        val json = state.toJson().toString()
        check(!json.contains("baselineCanonical") && !json.contains("expectedRemote"),
            "persistent state must not contain full manifest payloads")
        val restored = MirrorState.fromJson(state.toJson(), "p")
        check(restored == state, "state round trip lost lifecycle metadata")

        check(runCatching { MirrorState.fromJson(state.toJson(), "other") }.isFailure,
            "state can be loaded into another project")
        check(runCatching {
            MirrorState.fromJson(state.toJson().put("remoteDigest", "not-a-digest"), "p")
        }.isFailure, "invalid remote digest was silently dropped")
        check(runCatching {
            MirrorState.fromJson(state.toJson().put("schemaVersion", "2"), "p")
        }.isFailure, "string schema version was coerced into the current schema")
        check(runCatching {
            MirrorState.fromJson(state.toJson().put("schemaVersion", 2.9), "p")
        }.isFailure, "fractional schema version was coerced into the current schema")
        check(runCatching {
            MirrorState.fromJson(state.toJson().put("schemaVersion", 4), "p")
        }.isFailure, "unknown schema version was accepted")
        check(runCatching { MirrorSessionMount("m", "content://m", "READ_WRITE", "../escape") }.isFailure,
            "session mount accepted traversal")
        check(runCatching { MirrorSink("bad/id", 1) }.isFailure, "sink route accepted a path")

        val entered = java.util.concurrent.CountDownLatch(2)
        val release = java.util.concurrent.CountDownLatch(1)
        val t1 = Thread { MirrorLockRegistry.withProject("one") { entered.countDown(); release.await() } }
        val t2 = Thread { MirrorLockRegistry.withProject("two") { entered.countDown(); release.await() } }
        t1.start(); t2.start()
        check(entered.await(2, java.util.concurrent.TimeUnit.SECONDS), "different projects were globally serialized")
        release.countDown(); t1.join(); t2.join()
        MirrorLockRegistry.clearForTests()

        val sameEntered = java.util.concurrent.CountDownLatch(1)
        val sameRelease = java.util.concurrent.CountDownLatch(1)
        val sameFinished = java.util.concurrent.CountDownLatch(1)
        val sameFirst = Thread {
            MirrorLockRegistry.withProject("same") {
                sameEntered.countDown()
                sameRelease.await()
            }
        }
        val sameSecond = Thread {
            MirrorLockRegistry.withProject("same") { sameFinished.countDown() }
        }
        sameFirst.start()
        check(sameEntered.await(2, java.util.concurrent.TimeUnit.SECONDS), "same-project lock did not enter")
        sameSecond.start()
        check(!sameFinished.await(100, java.util.concurrent.TimeUnit.MILLISECONDS),
            "same-project operations were not serialized")
        sameRelease.countDown()
        check(sameFinished.await(2, java.util.concurrent.TimeUnit.SECONDS),
            "same-project lock did not release")
        sameFirst.join(); sameSecond.join()
        MirrorLockRegistry.clearForTests()
        println("PersistentWorkspaceTests: $assertions checks passed")
    }
}
