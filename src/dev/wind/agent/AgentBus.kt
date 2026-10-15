package dev.wind.agent

import android.os.Handler
import android.os.Looper
import dev.wind.agent.capability.CapabilityDecision
import dev.wind.agent.capability.CapabilityRequest
import dev.wind.agent.capability.GrantScope
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.json.JSONObject

/**
 * Global run coordinator shared by the UI and the foreground service.
 *
 * One run per conversation, claimed before startForegroundService. Worker
 * ownership is independent of the conversation currently displayed by the UI.
 * All run transitions and approval ownership are guarded by RUN_LOCK.
 */
object AgentBus {

    enum class RunState {
        IDLE,
        STARTING,
        RUNNING,
        STOPPING
    }

    private val RUN_LOCK = Any()
    private var nextRunId = 1L

    /**
     * Process-scoped main-thread handler for callbacks that must outlive any one
     * Activity — chiefly the "did the service actually start?" watchdog, which
     * releases the global run slot. An Activity's own handler is cleared in
     * onDestroy, and losing that callback wedged the app as permanently busy.
     *
     * Created lazily: this object is also loaded by the off-device test suite,
     * where the Looper stub throws, and a handler in the initializer would take
     * the whole class down with it.
     */
    val watchdog: Handler by lazy { Handler(Looper.getMainLooper()) }

    private class Run(val id: Long, var chatId: String?, var chat: Chat?) {
        val token = CancellationToken()
        var state = RunState.STARTING
        var taskId: String? = null
        var userStopped = false
    }
    private val runs = LinkedHashMap<Long, Run>()
    private val workerRun = InheritableThreadLocal<Long>()
    @Volatile private var selectedChatId: String? = null
    private val idleToken = CancellationToken()
    private fun selectedLocked(): Run? {
        val worker = workerRun.get()
        return if (worker != null) runs[worker] else runs.values.firstOrNull { it.chatId == selectedChatId }
    }
    fun selectChat(chatId: String?) { selectedChatId = chatId; publishHead() }
    fun bindWorker(runId: Long) { workerRun.set(runId) }
    fun unbindWorker() { workerRun.remove() }
    fun liveChatFor(chatId: String?): Chat? = synchronized(RUN_LOCK) { runs.values.firstOrNull { it.chatId == chatId }?.chat }
    fun hasRuns(): Boolean = synchronized(RUN_LOCK) { runs.isNotEmpty() }
    fun pendingApprovalRunIds(): List<Long> = synchronized(RUN_LOCK) { approvals.filter { !it.decided }.map { it.runId }.distinct() }
    fun runningIds(): List<Long> = synchronized(RUN_LOCK) { runs.keys.toList() }
    fun chatId(runId: Long): String? = synchronized(RUN_LOCK) { runs[runId]?.chatId }
    fun state(runId: Long): RunState = synchronized(RUN_LOCK) { runs[runId]?.state ?: RunState.IDLE }
    fun token(runId: Long): CancellationToken? = synchronized(RUN_LOCK) { runs[runId]?.token }
    var activeChatId: String?
        get() = synchronized(RUN_LOCK) { selectedLocked()?.chatId }
        set(value) { synchronized(RUN_LOCK) { selectedLocked()?.chatId = value } }

    @Volatile private var uiListener: UiListener? = null
    var listener: UiListener?
        get() = if (workerRun.get() != null && activeChatId != selectedChatId) null else uiListener
        set(value) { uiListener = value }

    var liveChat: Chat?
        get() = synchronized(RUN_LOCK) { selectedLocked()?.chat }
        set(value) { synchronized(RUN_LOCK) { selectedLocked()?.chat = value } }

    /** Root capability task currently owned by the foreground service, if any. */
    var activeTaskId: String?
        get() = synchronized(RUN_LOCK) { selectedLocked()?.taskId }
        set(value) { synchronized(RUN_LOCK) { selectedLocked()?.taskId = value } }

    /**
     * Every approval still waiting for an answer, oldest first. Guarded by
     * [RUN_LOCK] — see [awaitApproval] for why this is a queue and not one field.
     *
     * Small by construction: one entry per live agent thread, and the run caps
     * those at the lead plus its sub-agents. An ArrayList beats a deque here
     * because the hot operations are "read the head" and "remove by identity".
     */
    private val approvals = ArrayList<PendingApproval>()

    private var nextApprovalId = 1L

    /**
     * Label applied to approvals raised on THIS thread, so a queued request can
     * name who asked ("Agent 2 wants to edit …").
     *
     * Thread-scoped rather than a parameter because the ask travels through
     * `AgentEngine.Callback.requestApproval(tool, args)`, which has no room for
     * an identity and is implemented in files this change does not touch. A
     * sub-agent worker sets it once when it starts and clears it when it ends;
     * everything raised in between is attributed automatically. The explicit
     * parameter on [awaitApproval] still wins when a caller has the label to
     * hand.
     */
    private val threadAgentLabel = ThreadLocal<String>()

    /**
     * True when the current/most-recent cancel was initiated by the USER (stop
     * button / notification action); false when the run was cut by the system
     * (service killed, start watchdog). Lets the UI offer a "continue" card only
     * for system interruptions. Reset at the start of each run.
     */
    var userStopped: Boolean
        get() = synchronized(RUN_LOCK) { selectedLocked()?.userStopped ?: false }
        set(value) { synchronized(RUN_LOCK) { selectedLocked()?.userStopped = value } }
    private val cancellation: CancellationToken get() = token()
    private val runState: RunState get() = state()

    /** Orphan/non-run asks stay bounded; a live owned run waits until the user answers or cancels.
     * finish/requestCancel drain its queue, so locking the screen cannot expire a live approval.
     */
    const val APPROVAL_TIMEOUT_MS = 10L * 60L * 1000L

    /** Outcome text for a granted request. English: it is read by the model. */
    const val REASON_APPROVED = "approved by the user"

    /** Outcome text for a request the user actively rejected. */
    const val REASON_DECLINED = "declined by the user"

    /** Outcome text when the run was cancelled while the ask was outstanding. */
    const val REASON_CANCELLED = "the run was cancelled before the user answered"

    /** Outcome text when the user pressed stop while the ask was outstanding. */
    const val REASON_STOPPED = "the user stopped the run before answering"

    /** Outcome text when the run ended while the ask was still queued. */
    const val REASON_RUN_ENDED = "the run ended before the user answered"

    /** Outcome text when no answer arrived inside [APPROVAL_TIMEOUT_MS]. */
    const val REASON_TIMED_OUT =
        "no answer arrived within the approval timeout, so it was treated as declined"

    interface UiListener {
        fun onChatAssistTurn(chatId: String) {}
        fun onApprovalRequested(approval: PendingApproval)
        fun onComplete()
        fun onDelta(message: Message)
        fun onError(error: String)
        fun onNewAssistantMessage(message: Message)
        fun onStepFinalized(message: Message)
        fun onThinking(message: Message)
        fun onToolMessage(message: Message)
        fun onToolRunning(tool: String, detail: String)

        /** The run's activity strip changed; re-read [Message.trail]. */
        fun onTrailChanged(owner: Message)

        /**
         * How many approvals are still outstanding, including the one on screen.
         *
         * Optional: a screen that shows one sheet at a time and nothing else can
         * ignore it, which is why it has a body. A concurrent workflow uses it to
         * say "2 more waiting" on the sheet the user is looking at.
         */
        fun onApprovalQueueChanged(outstanding: Int) {
        }
    }

    /**
     * A tool call blocked on the user's approval.
     *
     * Each instance owns its own latch, so several may be outstanding at once and
     * each waiter is released by ITS decision and nothing else. [id] and [agent]
     * exist so the sheet can label the ask — with three sub-agents running, "edit
     * a file" without a name is not a question anyone can answer.
     */
    class PendingApproval internal constructor(
        val id: Long,
        val agent: String,
        val tool: String,
        val args: JSONObject?,
        /** Present for P2 approvals; null keeps the V0.1 Boolean path intact. */
        val capabilityRequest: CapabilityRequest? = null,
        val runId: Long = 0L
    ) {
        internal val latch = CountDownLatch(1)

        @Volatile
        internal var approved = false

        /**
         * Why this resolved the way it did, in English — it is reported back into
         * the model's conversation, not shown as interface prose.
         */
        @Volatile
        internal var reason = ""

        @Volatile
        internal var capabilityScope = GrantScope.TASK

        @Volatile
        var decided = false
            private set

        fun decide(approved: Boolean) {
            decide(approved, if (approved) REASON_APPROVED else REASON_DECLINED)
        }

        /** Resolves a P2 approval while preserving the user's selected scope. */
        fun decideCapability(decision: CapabilityDecision) {
            decide(
                decision is CapabilityDecision.Allow,
                if (decision is CapabilityDecision.Allow) REASON_APPROVED else REASON_DECLINED,
                if (decision is CapabilityDecision.Allow) decision.scope else GrantScope.TASK
            )
        }

        /**
         * Resolves this approval exactly once and hands the queue back to the UI.
         *
         * The state change is done under this object's own monitor; retiring from
         * the queue happens AFTER that monitor is released, because retiring takes
         * [RUN_LOCK] and the cancel paths take [RUN_LOCK] first and then decide.
         * Doing both in one order everywhere is what keeps the two locks acyclic.
         */
        internal fun decide(approved: Boolean, why: String, scope: GrantScope? = null) {
            val changed = synchronized(this) {
                if (decided) {
                    false
                } else {
                    if (scope != null) capabilityScope = scope
                    this.approved = approved
                    this.reason = why
                    decided = true
                    latch.countDown()
                    true
                }
            }
            if (changed) {
                retire(this)
            }
        }
    }

    /** The answer to one approval: whether it was granted, and why. */
    class Decision internal constructor(
        val approved: Boolean,
        val reason: String
    )

    /** Claims the global run slot before startForegroundService is called. */
    fun beginStarting(chatId: String?, chat: Chat?): Long {
        return synchronized(RUN_LOCK) {
            if (runs.values.any { it.chatId == chatId }) {
                0L
            } else {
                val run = Run(nextRunId++, chatId, chat)
                runs[run.id] = run
                if (selectedChatId == null) selectedChatId = chatId
                run.id
            }
        }
    }

    fun markRunning(runId: Long): Boolean {
        return synchronized(RUN_LOCK) {
            val run = runs[runId]
            if (run == null || run.state == RunState.STOPPING) {
                false
            } else {
                run.state = RunState.RUNNING
                true
            }
        }
    }

    fun isBusy(): Boolean = state() != RunState.IDLE

    fun isStopping(): Boolean = runState == RunState.STOPPING

    fun isRunningFor(chatId: String?): Boolean =
        synchronized(RUN_LOCK) { chatId != null && runs.values.any { it.chatId == chatId } }

    fun state(): RunState = synchronized(RUN_LOCK) { selectedLocked()?.state ?: RunState.IDLE }

    fun runId(): Long = synchronized(RUN_LOCK) { selectedLocked()?.id ?: 0L }

    fun token(): CancellationToken = synchronized(RUN_LOCK) { selectedLocked()?.token ?: idleToken }

    /**
     * Names the agent behind every approval raised on the calling thread.
     *
     * Call with a label when a sub-agent worker starts and with null when it
     * finishes; the value is thread-scoped, so concurrent workers cannot see each
     * other's. Passing null (or "") restores the unlabelled lead-agent case.
     */
    fun setAgentLabel(label: String?) {
        if (label.isNullOrEmpty()) {
            threadAgentLabel.remove()
        } else {
            threadAgentLabel.set(label)
        }
    }

    /** The approval the UI should be showing, or null when nothing is waiting. */
    fun currentApproval(): PendingApproval? {
        return synchronized(RUN_LOCK) { headLocked() }
    }

    /** How many approvals are outstanding, including the one on screen. */
    fun outstandingApprovals(): Int {
        return synchronized(RUN_LOCK) { val id=runs.values.firstOrNull { it.chatId==selectedChatId }?.id; approvals.count { !it.decided && it.runId==id } }
    }

    /** Idempotent and effective even while the foreground service is still starting. */
    fun requestCancel(): Boolean = requestCancel(false)

    /**
     * @param byUser true when the user pressed stop (suppresses the "continue"
     * card); false when the system cut the run (offers "continue").
     */
    fun requestCancel(byUser: Boolean): Boolean = requestCancel(runId(), byUser)
    fun requestCancel(runId: Long, byUser: Boolean = false): Boolean {
        var token: CancellationToken? = null
        val orphaned = synchronized(RUN_LOCK) {
            val run = runs[runId] ?: return false
            if (byUser) run.userStopped = true
            run.state = RunState.STOPPING
            token = run.token
            drainLocked(runId)
        }
        token?.cancel()
        // EVERY outstanding approval, not just the head. Rejecting only the one
        // on screen is what left the other agent threads parked on their latches.
        rejectAll(orphaned, if (byUser) REASON_STOPPED else REASON_CANCELLED)
        return true
    }

    /** Only the owner of this run may return the global state to IDLE. */
    fun finish(runId: Long) {
        val orphaned = synchronized(RUN_LOCK) {
            val finished = runs.remove(runId) ?: return
            if (selectedChatId == finished.chatId) selectedChatId = null
            drainLocked(runId)
        }
        rejectAll(orphaned, REASON_RUN_ENDED)
    }

    /**
     * Blocks the agent thread until the user approves or rejects [tool].
     * Returns false if cancelled, timed out, or rejected while waiting.
     *
     * ### The deadlock this replaces
     *
     * This used to be one `@Volatile pendingApproval` field. `awaitApproval`
     * OVERWROTE it and then blocked on that approval's own latch with no timeout,
     * which was sound for exactly as long as one agent thread existed.
     *
     * With concurrent sub-agents it stopped being sound. Two threads asking at
     * once meant the second assignment clobbered the first, and the clobbered
     * approval was then unreachable from the bus: `requestCancel()` and
     * `finish()` both read that single field, so they resolved the SURVIVOR and
     * only the survivor. The orphaned thread stayed parked on an untimed
     * `latch.await()` with nothing left in the process holding a reference that
     * could ever count it down. It never returned, so the run never finished, so
     * `finish()` was never reached, so the global run slot was never released and
     * the app was permanently "busy" until the process died. The user's stop
     * button, the notification action and the service teardown were all equally
     * powerless, because all three funnel into the same two methods.
     *
     * Three things fix it, and all three are needed:
     *
     *  1. [approvals] is a QUEUE, so no ask can displace another. Each waiter
     *     blocks on its own latch and is released by its own decision.
     *  2. [requestCancel] and [finish] drain the whole queue and reject every
     *     entry, so teardown reaches threads that were never on screen.
     *  3. The wait below is BOUNDED. Even if a decision is lost outright — an
     *     Activity destroyed mid-tap, a listener swapped, a sheet dismissed by
     *     the window manager — an orphan ask leaves after [APPROVAL_TIMEOUT_MS] and
     *     reports the timeout as a rejection, rather than holding the run slot.
     *
     * The UI still sees ONE ask at a time: only the head of the queue is
     * published through [UiListener.onApprovalRequested], and the next is
     * published as soon as the head is answered.
     */
    fun awaitApproval(
        tool: String,
        args: JSONObject?,
        token: CancellationToken?,
        agent: String? = null
    ): Boolean = awaitApprovalDetailed(tool, args, token, agent).approved

    /**
     * [awaitApproval] with the reason attached, so a caller can tell a rejection
     * apart from a cancellation or a dropped decision and say so to the model.
     */
    fun awaitApprovalDetailed(
        tool: String,
        args: JSONObject?,
        token: CancellationToken?,
        agent: String? = null
    ): Decision {
        if (token == null || token.isCancelled) {
            return Decision(false, REASON_CANCELLED)
        }
        val label = if (agent.isNullOrEmpty()) {
            threadAgentLabel.get() ?: ""
        } else {
            agent
        }
        val approval = synchronized(RUN_LOCK) {
            // A run already tearing down must never gain a new waiter: the drain
            // has been and gone, so nothing would ever resolve this one.
            if (token.isCancelled) {
                return Decision(false, REASON_CANCELLED)
            }
            val owner = runs.values.firstOrNull { it.token === token }?.id ?: runId()
            val queued = PendingApproval(nextApprovalId++, label, tool, args, runId = owner)
            approvals.add(queued)
            queued
        }
        dev.wind.agent.session.TaskNotifications.approval(approval.runId)
        val registration = token.onCancel { approval.decide(false, REASON_CANCELLED) }
        try {
            publishHead()
            if (!approval.latch.await(if (state(approval.runId) == RunState.IDLE) APPROVAL_TIMEOUT_MS else Long.MAX_VALUE, TimeUnit.MILLISECONDS)) {
                approval.decide(false, REASON_TIMED_OUT)
            }
            if (approval.approved && token.isCancelled) {
                return Decision(false, REASON_CANCELLED)
            }
            return Decision(approval.approved, approval.reason)
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
            approval.decide(false, REASON_CANCELLED)
            return Decision(false, REASON_CANCELLED)
        } finally {
            registration.close()
            // Belt and braces: decide() retires on the deciding thread, but a
            // waiter that leaves by any other route must not leave a corpse at
            // the head of the queue blocking the next ask.
            retire(approval)
        }
    }

    /**
     * P2 counterpart to [awaitApprovalDetailed]. The queue and timeout rules are
     * shared with legacy approvals, but the UI can return ONCE/TASK/APP_SESSION
     * instead of collapsing every positive answer into a Boolean.
     */
    fun awaitCapability(
        request: CapabilityRequest,
        token: CancellationToken?,
        agent: String? = null
    ): CapabilityDecision {
        if (token == null || token.isCancelled) {
            return CapabilityDecision.Deny
        }
        val label = if (agent.isNullOrEmpty()) threadAgentLabel.get() ?: "" else agent
        val approval = synchronized(RUN_LOCK) {
            if (token.isCancelled) {
                return CapabilityDecision.Deny
            }
            val queued = PendingApproval(
                nextApprovalId++, label, request.toolName, request.args, request,
                runs.values.firstOrNull { it.token === token }?.id ?: runId()
            )
            approvals.add(queued)
            queued
        }
        dev.wind.agent.session.TaskNotifications.approval(approval.runId)
        val registration = token.onCancel { approval.decide(false, REASON_CANCELLED) }
        try {
            publishHead()
            if (!approval.latch.await(if (state(approval.runId) == RunState.IDLE) APPROVAL_TIMEOUT_MS else Long.MAX_VALUE, TimeUnit.MILLISECONDS)) {
                approval.decide(false, REASON_TIMED_OUT)
            }
            if (approval.approved && token.isCancelled) {
                return CapabilityDecision.Deny
            }
            return if (approval.approved) {
                CapabilityDecision.Allow(approval.capabilityScope)
            } else {
                CapabilityDecision.Deny
            }
        } catch (ignored: InterruptedException) {
            Thread.currentThread().interrupt()
            approval.decide(false, REASON_CANCELLED)
            return CapabilityDecision.Deny
        } finally {
            registration.close()
            retire(approval)
        }
    }

    /** Re-shows the approval sheet after the Activity is recreated. */
    fun redeliverPendingApproval() {
        publishHead()
    }

    // ---- queue internals ---------------------------------------------------

    /** Head of the queue. Caller must hold [RUN_LOCK]. */
    private fun headLocked(): PendingApproval? {
        for (approval in approvals) {
            if (!approval.decided && approval.runId == runs.values.firstOrNull { it.chatId==selectedChatId }?.id) {
                return approval
            }
        }
        return null
    }

    /** Empties the queue and returns what was in it. Caller must hold [RUN_LOCK]. */
    private fun drainLocked(runId: Long): List<PendingApproval> {
        if (approvals.isEmpty()) {
            return emptyList()
        }
        val copy = approvals.filter { it.runId == runId }
        approvals.removeAll(copy.toSet())
        return copy
    }

    /**
     * Drops [approval] from the queue and hands the next one to the UI.
     *
     * `internal` rather than private because [PendingApproval.decide] calls it,
     * and decisions arrive on the UI thread while waiters retire on agent
     * threads. Idempotent: removing an entry that is already gone is a no-op.
     */
    internal fun retire(approval: PendingApproval) {
        // Publish on any real removal rather than trying to detect a head change
        // here. By the time a decision reaches this point the entry is already
        // marked decided, so [headLocked] has ALREADY skipped past it — a
        // before/after comparison sees no change and the next ask never reaches
        // the screen. Re-publishing an unchanged head is harmless: the UI
        // deduplicates by approval identity.
        val removed = synchronized(RUN_LOCK) { approvals.remove(approval) }
        if (removed) {
            chatId(approval.runId)?.let { dev.wind.agent.session.TaskNotifications.clear(it) }
            if (pendingApprovalRunIds().contains(approval.runId)) dev.wind.agent.session.TaskNotifications.approval(approval.runId)
            publishHead()
        }
    }

    /**
     * Rejects a drained batch. Called with [RUN_LOCK] RELEASED: each `decide`
     * re-enters the bus to retire itself, and deciding under the lock would make
     * the two monitors cyclic the moment a decision also arrives from the UI.
     */
    private fun rejectAll(batch: List<PendingApproval>, reason: String) {
        for (approval in batch) {
            approval.decide(false, reason)
        }
        publishHead()
    }

    /**
     * Publishes the head of the queue, and only the head — a person answers one
     * question at a time. Never called while holding [RUN_LOCK].
     */
    private fun publishHead() {
        val ui = uiListener ?: return
        val outstanding = outstandingApprovals()
        ui.onApprovalQueueChanged(outstanding)
        val head = currentApproval() ?: return
        if (head.decided || token(head.runId)?.isCancelled != false) {
            return
        }
        ui.onApprovalRequested(head)
    }
}
