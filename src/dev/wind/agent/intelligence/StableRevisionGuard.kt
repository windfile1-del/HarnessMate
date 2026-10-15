package dev.wind.agent.intelligence

/**
 * Re-checks the P3 manifest digest and P4 mutation epoch around a read-only
 * index/query operation.  A candidate is publishable only when both values
 * still describe the revision used to build it.
 */
class StableRevisionGuard(
    private val liveDigest: () -> String?,
    private val liveEpoch: () -> Int
) {
    data class Observation(val digest: String?, val mutationEpoch: Int)

    fun observe(): Observation = Observation(liveDigest(), liveEpoch())

    fun isStable(expected: SourceRevision): Boolean {
        val current = observe()
        return current.digest != null && current.digest == expected.sourceDigest &&
            current.mutationEpoch == expected.mutationEpoch
    }
}

