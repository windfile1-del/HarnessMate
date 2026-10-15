package dev.wind.agent.coding

/**
 * What a coding task DID, as data.
 *
 * The trail in the UI and the harness's own decisions are driven from these
 * rather than from parsing what the model said about itself. A model can claim
 * anything in prose; it cannot emit [VerificationPassed] for a build that
 * failed, because only the runner emits it.
 *
 * Deliberately an internal event bus only. P4 does not open this to user-defined
 * hooks or plugins — that would turn a reliability feature into a plugin
 * framework, and the reliability is the part that has to work first.
 */
sealed class CodingEvent {
    abstract val at: Long

    data class TaskStarted(override val at: Long, val taskId: String, val projectId: String) : CodingEvent()
    data class ProjectDetected(override val at: Long, val profile: ProjectProfile) : CodingEvent()
    data class ToolStarted(override val at: Long, val tool: String) : CodingEvent()
    data class ToolCompleted(override val at: Long, val tool: String, val ok: Boolean) : CodingEvent()
    data class MutationObserved(override val at: Long, val tool: String, val epoch: Int) : CodingEvent()
    data class ValidationStarted(override val at: Long, val check: String) : CodingEvent()
    data class ValidationCompleted(override val at: Long, val summary: ValidationSummary) : CodingEvent()
    data class DiagnosticsUpdated(override val at: Long, val total: Int, val fresh: Int) : CodingEvent()
    data class RepairCycleStarted(override val at: Long, val cycle: Int, val remaining: Int) : CodingEvent()
    data class VerificationStarted(override val at: Long, val checks: List<String>) : CodingEvent()
    data class VerificationPassed(override val at: Long, val stamp: VerificationStamp) : CodingEvent()
    data class VerificationFailed(override val at: Long, val reason: String) : CodingEvent()
    data class VerificationInvalidated(override val at: Long, val reason: String) : CodingEvent()
    data class DiffReady(override val at: Long, val fingerprint: String, val changedFiles: Int) : CodingEvent()
    data class ApplyStarted(override val at: Long, val executionId: String) : CodingEvent()
    data class ApplyCompleted(override val at: Long, val executionId: String, val ok: Boolean) : CodingEvent()
    data class TaskCompleted(override val at: Long, val report: FinalTaskReport?) : CodingEvent()
    data class TaskBlocked(override val at: Long, val reason: String) : CodingEvent()
    data class TaskFailed(override val at: Long, val reason: String) : CodingEvent()
    data class TaskCancelled(override val at: Long) : CodingEvent()
}

/**
 * Sink for [CodingEvent]s.
 *
 * A list of listeners rather than one, because the UI adapter, the state store
 * and the tests all need to observe the same run and none of them may be able to
 * stop another from being told. A listener that throws is not allowed to break
 * the run either — the event stream is diagnosis, not control flow.
 */
class CodingEventBus {
    private val listeners = java.util.concurrent.CopyOnWriteArrayList<(CodingEvent) -> Unit>()

    fun subscribe(listener: (CodingEvent) -> Unit) {
        listeners.add(listener)
    }

    fun publish(event: CodingEvent) {
        for (listener in listeners) {
            try {
                listener(event)
            } catch (_: Exception) {
                // A broken observer must never take the task down with it.
            }
        }
    }
}
