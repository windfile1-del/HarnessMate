package dev.wind.agent.coding

import dev.wind.agent.Fa

/**
 * Turns coding events into the one line the activity strip shows.
 *
 * P4 does not get its own progress UI. The trail already exists, already
 * survives a reopened conversation, and already knows how to look running and
 * settled; a second indicator beside it would be a second thing to keep in step
 * with the run. This adapter is the whole of the coupling: an event in, a phase
 * string out.
 */
object CodingTrailAdapter {

    /** The phase line for [event], or null when it should not change the strip. */
    fun phaseFor(event: CodingEvent): String? = when (event) {
        is CodingEvent.TaskStarted -> Fa.RUN_CONNECTING
        is CodingEvent.ProjectDetected -> Fa.P4_INSPECTED
        is CodingEvent.ToolStarted -> null
        is CodingEvent.ToolCompleted -> null
        is CodingEvent.MutationObserved -> Fa.P4_EDITING
        is CodingEvent.ValidationStarted -> Fa.P4_BUILDING.format(event.check)
        is CodingEvent.ValidationCompleted -> if (event.summary.ok) {
            Fa.P4_CHECK_PASSED.format(event.summary.check)
        } else {
            Fa.P4_FIXING.format(event.summary.errors.toString())
        }
        is CodingEvent.DiagnosticsUpdated -> if (event.fresh > 0) {
            Fa.P4_FIXING.format(event.fresh.toString())
        } else {
            null
        }
        is CodingEvent.RepairCycleStarted -> Fa.P4_REPAIR_N.format(event.cycle.toString())
        is CodingEvent.VerificationStarted -> Fa.P4_VERIFYING
        is CodingEvent.VerificationPassed -> Fa.P4_VERIFIED
        is CodingEvent.VerificationFailed -> Fa.P4_VERIFY_FAILED
        is CodingEvent.VerificationInvalidated -> Fa.P4_EDITING
        is CodingEvent.DiffReady -> Fa.P4_DIFF_READY
        is CodingEvent.ApplyStarted -> Fa.P4_APPLYING
        is CodingEvent.ApplyCompleted -> if (event.ok) Fa.P4_APPLIED else Fa.RUN_FAILED_STEP
        is CodingEvent.TaskCompleted -> when (event.report?.status) {
            TaskStatus.SUCCESS -> Fa.P4_DONE
            TaskStatus.VERIFIED_NOT_APPLIED -> Fa.P4_VERIFIED
            TaskStatus.APPLIED_UNVERIFIED -> Fa.P4_APPLIED
            else -> null
        }
        is CodingEvent.TaskBlocked -> Fa.P4_BLOCKED
        is CodingEvent.TaskFailed -> Fa.RUN_FAILED_STEP
        is CodingEvent.TaskCancelled -> null
    }
}
