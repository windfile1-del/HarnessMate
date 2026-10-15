package dev.wind.agent.shell.mirror

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock

/** Serializes operations per project while allowing independent projects to run in parallel. */
object MirrorLockRegistry {
    private val locks = ConcurrentHashMap<String, ReentrantLock>()

    fun <T> withProject(projectId: String, block: () -> T): T {
        require(projectId.isNotBlank()) { "Missing project id" }
        // ConcurrentHashMap.computeIfAbsent is API 24+; Vega supports minSdk 23.
        val lock = locks[projectId] ?: synchronized(locks) {
            locks[projectId] ?: ReentrantLock().also { locks[projectId] = it }
        }
        lock.lockInterruptibly()
        return try { block() } finally { lock.unlock() }
    }

    fun clearForTests() = locks.clear()
}
