package dev.wind.agent.coding

/** What the harness decided should happen after a validation run. */
sealed class RetryDecision {
    /** Failures are code problems and the model has room to fix them. */
    data class ContinueRepair(
        val cycle: Int,
        val remaining: Int,
        val improving: Boolean
    ) : RetryDecision()

    /** Nothing failed; the work may be verified. */
    data object VerificationReady : RetryDecision()

    /** The failure is the environment, not the code. */
    data class EnvironmentBlocked(val reason: String) : RetryDecision()

    /** The same failure survived a repair without the error set changing. */
    data class NoProgress(val reason: String, val repeated: Int) : RetryDecision()

    /** The repair budget is gone. */
    data class BudgetExhausted(val cycles: Int) : RetryDecision()

    /** The user stopped the task. */
    data object Cancelled : RetryDecision()
}

/**
 * Decides whether another repair cycle is worth running.
 *
 * Not a fixed-count retry loop over the build. The questions that matter are
 * whether the failure is even the model's to fix, whether the model actually
 * changed anything, and whether the error set is moving; a model that has
 * produced the same failure twice with no edit in between is not about to
 * produce a third, better answer, and burning the remaining agent steps to prove
 * that is how a run gets stuck for ten minutes on the phone.
 */
class RetryController(
    private val maxRepairCycles: Int = MAX_REPAIR_CYCLES,
    private val maxIdenticalFailures: Int = MAX_IDENTICAL_FAILURES
) {
    private var previousKeys: Set<String> = emptySet()
    private var previousEpoch: Int = -1
    /**
     * How many consecutive validations have produced the same error set with no
     * edit in between. Starts at 1 for the first sighting, so the bound below
     * reads as "this many sightings of one unchanged failure".
     */
    private var identical: Int = 0
    private var cycles: Int = 0
    private var bestErrorCount: Int = Int.MAX_VALUE

    /** Resets per-cycle bookkeeping when a NEW user request starts. */
    fun reset() {
        previousKeys = emptySet()
        previousEpoch = -1
        identical = 0
        cycles = 0
        bestErrorCount = Int.MAX_VALUE
    }

    val cycleCount: Int get() = cycles

    /**
     * The error keys this controller saw last time.
     *
     * The runtime uses them to tell the model what it has already fixed, which is
     * the cheapest signal that a repair cycle is working.
     */
    fun seenKeys(): Set<String> = previousKeys

    /**
     * Records one validation outcome and decides what to do next.
     *
     * [mutationEpoch] is the harness's own count of source mutations observed so
     * far; when it has not moved since the last failure, the model has not
     * changed the code and the failure is definitionally the same problem.
     */
    fun onValidation(
        report: DiagnosticReport,
        mutationEpoch: Int,
        cancelled: Boolean
    ): RetryDecision {
        if (cancelled) {
            return RetryDecision.Cancelled
        }
        if (report.ok) {
            previousKeys = emptySet()
            previousEpoch = mutationEpoch
            return RetryDecision.VerificationReady
        }
        if (DiagnosticPipeline.environmentBlocked(report)) {
            previousKeys = report.errors.map { it.key() }.toSet()
            previousEpoch = mutationEpoch
            return RetryDecision.EnvironmentBlocked(
                report.errors.firstOrNull()?.message ?: "the build environment is not usable"
            )
        }

        val keys = report.errors.map { it.key() }.toSet()
        val unchanged = keys == previousKeys
        val noEdit = mutationEpoch == previousEpoch
        previousKeys = keys
        previousEpoch = mutationEpoch

        // "The same failure again, with nothing changed in between" is the only
        // combination that justifies stopping early. Same errors after an edit is
        // an honest failed attempt; different errors are progress; an edit with
        // no failure yet is just the next cycle.
        identical = if (unchanged && noEdit) identical + 1 else 1
        if (identical >= maxIdenticalFailures) {
            return RetryDecision.NoProgress(
                "the same " + keys.size + " error(s) came back without any change to the code",
                identical
            )
        }

        if (cycles >= maxRepairCycles) {
            return RetryDecision.BudgetExhausted(cycles)
        }
        cycles++
        val improving = report.errors.size < bestErrorCount
        bestErrorCount = minOf(bestErrorCount, report.errors.size)
        return RetryDecision.ContinueRepair(cycles, maxRepairCycles - cycles, improving)
    }

    companion object {
        const val MAX_REPAIR_CYCLES = 6
        const val MAX_IDENTICAL_FAILURES = 2
    }
}
