package dev.wind.agent.intelligence

import dev.wind.agent.CancellationToken
import dev.wind.agent.coding.CodingTaskRuntime
import dev.wind.agent.project.WorkspaceRouter
import dev.wind.agent.shell.mirror.CodeIntelligenceMirror

class SourceRevisionOracle(
    private val mirrorManager: CodeIntelligenceMirror
) {
    fun currentRevision(
        router: WorkspaceRouter,
        codingTaskRuntime: CodingTaskRuntime? = null,
        token: CancellationToken = CancellationToken(),
        beforeSideEffect: (() -> Unit)? = null
    ): SourceRevision {
        val project = router.getActiveProject()
        // This is a read-only observation.  P3 preparation/synchronisation is
        // owned by execution_prepare and is never reachable through MirrorRead.
        val observed = mirrorManager.observeForCodeIntelligence(router, token)
        codingTaskRuntime?.refreshSourceFingerprint()
        val epoch = codingTaskRuntime?.mutationEpoch ?: 0
        return SourceRevision(
            projectId = project.id,
            rootGeneration = project.generation,
            sourceDigest = observed.liveRemoteDigest,
            mutationEpoch = epoch
        )
    }
}

