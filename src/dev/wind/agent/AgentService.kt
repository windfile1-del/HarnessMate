package dev.wind.agent

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import dev.wind.agent.capability.CapabilityBrokerRegistry
import dev.wind.agent.capability.CapabilityDecision
import dev.wind.agent.capability.CapabilityRequest
import org.json.JSONObject

/**
 * Foreground service that owns an agent run.
 *
 * Living in a service (not the Activity) is what lets a long task survive the
 * user leaving the app. A timed partial wake lock is re-armed both from every
 * callback boundary and from a fixed-cadence ticker, so a long silent operation
 * (e.g. a big download with no per-chunk callback) cannot let the CPU sleep and
 * stall the run.
 */
class AgentService : BaseAgentService() {

    private lateinit var store: ChatStore
    private lateinit var prefs: Prefs

    private val runThreads = java.util.concurrent.ConcurrentHashMap<Long, Thread>()

    private var wakeLock: PowerManager.WakeLock? = null

    /**
     * Guards every read/modify of [wakeLock]. `releaseWake` runs on the worker
     * thread (finishRun) *and* the main thread (onDestroy) while `renewWake`
     * runs from every stream callback: with an unsynchronized field, a renew
     * that read the reference between "release it" and "null it" re-acquired a
     * lock nothing could ever release, burning CPU for the full 10-minute
     * timeout after the run had already ended.
     */
    private val wakeLockLock = Any()

    @Volatile
    private var lastWakeRenew = 0L

    // Re-arms the wake lock on a fixed cadence, independent of stream/tool
    // callbacks — so a long, silent operation (e.g. a big download with no
    // per-chunk callback) never lets the CPU sleep and stall the run mid-task.
    private val wakeTicker = Handler(Looper.getMainLooper())

    // Gates the self-reposting pulse: if it fires exactly as releaseWake() runs,
    // this flag stops it from re-arming the ticker forever after teardown.
    @Volatile
    private var ticking = false

    private val wakePulse: Runnable = object : Runnable {
        override fun run() {
            synchronized(wakeLockLock) {
                if (!ticking) {
                    return
                }
                try {
                    wakeLock?.let {
                        it.acquire(WAKE_MS)
                        lastWakeRenew = System.currentTimeMillis()
                    }
                } catch (ignored: Exception) {
                }
                AgentBus.runningIds().forEach { id -> AgentBus.chatId(id)?.let { AgentBus.liveChatFor(it) }?.let { store.save(it) } }
                wakeTicker.postDelayed(this, WAKE_TICK_MS)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = ChatStore(this)
        prefs = Prefs(this)
        NetworkPolicy.applyPrefs(prefs)
        // The service can be the first component to run in a cold process, before
        // any Activity has touched the string table. Fa is a table of immutable
        // vals now, so this is a no-op — kept as the one obvious hook if the
        // strings ever need preparing again.
        Fa.apply(this)
        // The service can be the first component to run in a cold process, in
        // which case no Activity has applied the palette yet and every Theme
        // colour would still be 0 (transparent) in the notification.
        Theme.applyFromPrefs(this, prefs)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!startForegroundSafe()) {
            val rejected = intent?.getLongExtra(EXTRA_RUN_ID, 0L) ?: 0L
            AgentBus.chatId(rejected)?.let { id -> AgentBus.liveChatFor(id)?.let { rejectedChat ->
                val failure = Message("assistant", "Could not start the foreground task service; check Android foreground/background permissions.").also { it.isError = true }
                synchronized(rejectedChat.messages) { rejectedChat.messages.add(failure) }
                store.save(rejectedChat)
                if (AgentBus.activeChatId == id) {
                    AgentBus.listener?.onNewAssistantMessage(failure)
                    AgentBus.listener?.onStepFinalized(failure)
                    AgentBus.listener?.onComplete()
                }
            } }
            AgentBus.requestCancel(rejected)
            AgentBus.finish(rejected)
            if (runThreads.isEmpty()) stopSelfClean()
            return START_NOT_STICKY
        }

        if (intent != null && ACTION_STOP == intent.action) {
            // user tapped the notification's stop action
            if (AgentBus.requestCancel(intent.getLongExtra(EXTRA_RUN_ID, AgentBus.runId()), true)) {
                notifyText(Fa.STOPPING)
            } else {
                // Nothing was actually running to cancel (race: the run just
                // finished). Don't leave an orphaned foreground service +
                // notification behind — tear it down now.
                if (runThreads.isEmpty()) stopSelfClean()
            }
            return START_NOT_STICKY
        }

        val chatId = intent?.getStringExtra(EXTRA_CHAT_ID)
        val runId = intent?.getLongExtra(EXTRA_RUN_ID, 0L) ?: 0L

        if (runThreads.containsKey(runId)) {
            return START_NOT_STICKY
        }
        if (chatId == null || runId == 0L || AgentBus.state(runId) == AgentBus.RunState.IDLE) {
            if (runThreads.isEmpty()) stopSelfClean()
            return START_NOT_STICKY
        }

        dev.wind.agent.screen.PetActivity.update(runId, dev.wind.agent.screen.PetActivity.State.THINKING)

        acquireWake()
        val runToken = AgentBus.token(runId) ?: return START_NOT_STICKY
        val worker = Thread({
            AgentBus.bindWorker(runId)
            val cancellationRegistration = runToken.watchThread()
            try {
                runToken.throwIfCancelled()
                initializeRun(chatId, runId)
            } catch (error: Throwable) {
                val current = AgentBus.liveChat?.takeIf { it.id == chatId }
                if (!runToken.isCancelled && current != null) {
                    val failure = Message("assistant", "Could not start the run: " + (error.message ?: error.javaClass.simpleName)).also { it.isError = true }
                    synchronized(current.messages) { current.messages.add(failure) }
                    AgentBus.listener?.onNewAssistantMessage(failure)
                    AgentBus.listener?.onStepFinalized(failure)
                }
                if (current != null) finishRun(current, runId)
                else { try { AgentBus.listener?.onComplete() } finally { AgentBus.finish(runId) } }
            } finally {
                cancellationRegistration.close()
                runThreads.remove(runId)
                AgentBus.unbindWorker()
                wakeTicker.post { if (runThreads.isEmpty() && !AgentBus.hasRuns()) { releaseWake(); stopSelfClean() } }
            }
        }, "vepro-agent-$runId")
        runThreads[runId] = worker
        worker.start()
        // NOT sticky, and NOT redeliver. On Android 12+ a system-initiated
        // restart of a foreground service from the background can throw
        // ForegroundServiceStartNotAllowedException. A killed run cannot be
        // resumed cleanly anyway (its stream and UI are gone), so letting it stay
        // dead is strictly safer than risking that crash on a reclaim.
        return START_NOT_STICKY
    }

    /** Disk access and runtime initialization belong to the agent worker, never Service main callbacks. */
    private fun initializeRun(chatId: String, runId: Long) {
        var resolved = AgentBus.liveChat
        if (resolved == null || chatId != resolved.id) {
            resolved = store.load(chatId)
            AgentBus.liveChat = resolved
        }
        if (resolved == null || !AgentBus.markRunning(runId)) {
            try { AgentBus.listener?.onComplete() } finally { AgentBus.finish(runId) }
            return
        }

        val chat = resolved
        chat.runState = "running"
        val token = AgentBus.token()
        token.throwIfCancelled()
        val userTurn = synchronized(chat.messages) { chat.messages.lastOrNull()?.takeIf { it.role == "user" } }
        dev.wind.agent.memory.MemoryCoordinator.capture(this,chat)
        userTurn?.let { message ->
            if (chat.webBinding == null && message.requestedSkillPaths.isNotEmpty()) {
                val available = dev.wind.agent.skills.SkillStore(this).list().map { it.path }.toSet()
                val selected = message.requestedSkillPaths.filter { it in available }
                if (selected.isNotEmpty()) message.modelHint = "\n\n[用户指定 Skill：请按需调用 read_skill 读取，不要假定正文已加载]\n" + selected.joinToString("\n")
                message.requestedSkillPaths = emptyList()
            }
        }
        // The bus owns the live chat; persistence must not gate model startup.
        store.save(chat)
        token.throwIfCancelled()
        val runProfile=dev.wind.agent.profile.RunProfileSnapshot.capture(chat,prefs)
        val engine = AgentEngine(this, prefs.freezeModel(), initialProfile = runProfile)
        if(runProfile.profile==dev.wind.agent.profile.AgentProfile.MATE)dev.wind.agent.mate.MateTaskCoordinator.begin(this,chat,engine.currentTaskId)
        var codingCancellation: CancellationToken.Registration? = null
        // P4: the run gets a coding session. Whether it BECOMES a coding task is
        // decided by the tools it uses — the session exists for every run so the
        // three coding tools have somewhere to go, and stays passive until one of
        // them is actually called. See CodingTaskRuntime.ACTIVATING_TOOLS.
        //
        // A failure to READ an existing task's state must not pass silently.
        // Starting anyway would mean an agent editing code with no completion
        // gate at all — exactly what P4 exists to prevent — so the run stops and
        // the user is told, rather than continuing "as before".
        if (chat.webBinding == null && synchronized(chat.messages) { chat.messages.lastOrNull()?.chatAssistEventId == null }) {
        try {
            val project = dev.wind.agent.project.ProjectManager(this).getActiveProject()
            val runtime = dev.wind.agent.coding.CodingTaskRuntime(
                dev.wind.agent.coding.ShellCodingHost(this, taskId = engine.currentTaskId, cancellation = token),
                if(chat.retentionPolicy==dev.wind.agent.profile.RetentionPolicy.TEMPORARY)dev.wind.agent.coding.TemporaryCodingStateStore()
                else dev.wind.agent.coding.CodingTaskStore(this)
            )
            runtime.start(
                engine.currentTaskId,
                project?.id ?: "",
                project?.generation ?: 0L
            )
            codingCancellation = token.onCancel {
                // Cancellation callbacks run off the UI; keep their phase events in this run.
                AgentBus.bindWorker(runId)
                try { runtime.cancel() } finally { AgentBus.unbindWorker() }
            }
            // The phase line goes onto the run's own trail through the normal
            // callback, so P4 shows progress in the strip that already exists
            // instead of adding a second indicator beside it.
            engine.attachCodingSession(
                dev.wind.agent.coding.CodingSession(this, runtime, engine.currentTaskId) { phase ->
                    // The engine owns the trail, so it is the engine that shows
                    // the phase: P4 adds no second progress indicator.
                    engine.showCodingPhase(phase)
                }
            )
        } catch (error: Throwable) {
            codingCancellation?.close()
            dev.wind.agent.screen.PetActivity.update(runId, dev.wind.agent.screen.PetActivity.State.ERROR)
            val unusable = Message("assistant", Fa.P4_STATE_UNUSABLE)
            unusable.isError = true
            synchronized(chat.messages) {
                chat.messages.add(unusable)
            }
            AgentBus.listener?.onNewAssistantMessage(unusable)
            AgentBus.listener?.onStepFinalized(unusable)
            AgentBus.listener?.onComplete()
            AgentBus.finish(runId)
            return
        }
        }
        AgentBus.activeTaskId = engine.currentTaskId
        token.throwIfCancelled()
        try { runWorker(engine, chat, runId) } finally { codingCancellation?.close() }
    }

    private fun runWorker(engine: AgentEngine, chat: Chat, runId: Long) {
        val token = AgentBus.token()
        val permissions = dev.wind.agent.permissions.AgentPermissions.get(this)
        permissions.beginTask(engine.currentTaskId)
        val permissionRegistration = token.onCancel { permissions.endTask(engine.currentTaskId) }
        val userTurn = synchronized(chat.messages) { chat.messages.lastOrNull()?.takeIf { it.role == "user" } }
        try {
            if (userTurn != null && userTurn.screenSessionId != 0L) {
                dev.wind.agent.screen.PetActivity.update(runId, dev.wind.agent.screen.PetActivity.State.SEARCHING, "screen_capture")
            }
            userTurn?.let { dev.wind.agent.screen.ScreenShareSession.captureForMessage(it, token) }
            dev.wind.agent.screen.PetActivity.update(runId, dev.wind.agent.screen.PetActivity.State.THINKING)
            if (userTurn?.chatAssistEventId != null)
                dev.wind.agent.phone.ChatAssist.runVisible(this, chat, userTurn, token, callback(chat, token, runId))
            else engine.run(chat, token, callback(chat, token, runId))
        } finally {
            runCatching { permissionRegistration.close() }
            runCatching { permissions.endTask(engine.currentTaskId) }
            runCatching { dev.wind.agent.capability.CapabilityBrokerRegistry.get(this).endTask(engine.currentTaskId) }
            userTurn?.let {
                it.screenContext = null
                it.screenContextNote = null
                it.screenSessionId = 0L
            }
            finishRun(chat, runId)
        }
    }

    private fun callback(chat: Chat, token: CancellationToken, activeRunId: Long): AgentEngine.Callback =
        object : AgentEngine.Callback {

            override fun onNewAssistantMessage(message: Message) {
                dev.wind.agent.screen.PetActivity.update(activeRunId, dev.wind.agent.screen.PetActivity.State.THINKING)
                AgentBus.listener?.onNewAssistantMessage(message)
            }

            override fun onDelta(message: Message, delta: String) {
                renewWake()
                dev.wind.agent.screen.PetActivity.update(activeRunId, dev.wind.agent.screen.PetActivity.State.REPLYING)
                AgentBus.listener?.onDelta(message)
            }

            override fun onThinking(message: Message, thinking: String) {
                renewWake()
                dev.wind.agent.screen.PetActivity.update(activeRunId, dev.wind.agent.screen.PetActivity.State.THINKING)
                AgentBus.listener?.onThinking(message)
            }

            override fun onStepFinalized(message: Message) {
                store.save(chat)
                AgentBus.listener?.onStepFinalized(message)
            }

            override fun onToolRunning(tool: String, detail: String) {
                renewWake()
                dev.wind.agent.screen.PetActivity.tool(activeRunId, tool)
                updateNotification(tool)
                AgentBus.listener?.onToolRunning(tool, detail)
            }

            override fun onToolMessage(message: Message, detail: String) {
                renewWake()
                dev.wind.agent.screen.PetActivity.update(activeRunId, dev.wind.agent.screen.PetActivity.State.THINKING)
                store.save(chat)
                AgentBus.listener?.onToolMessage(message)
            }

            override fun onTrailChanged(owner: Message) {
                renewWake()
                // Deliberately NOT saved here. The trail changes several times per
                // step — a phase line, a row opening, a row closing — and writing
                // the whole chat to disk on each one would turn one web search into
                // a burst of file IO. It rides along with the next real save
                // (onStepFinalized / onToolMessage / onComplete), every one of
                // which happens within a step of any trail change.
                AgentBus.listener?.onTrailChanged(owner)
            }

            override fun onComplete() {
                store.save(chat)
                // Terminal UI notification belongs to service finalization, including cancelled startup.
            }

            override fun onError(error: String) {
                if (token.isCancelled || AgentBus.state(activeRunId) == AgentBus.RunState.IDLE) return
                dev.wind.agent.screen.PetActivity.update(activeRunId, dev.wind.agent.screen.PetActivity.State.ERROR)
                synchronized(chat.messages) {
                    val last = chat.messages.lastOrNull()
                    if (last?.isError != true || last.content != error) {
                        chat.messages.add(Message("assistant", error).also { it.isError = true })
                    }
                }
                store.save(chat)
                AgentBus.listener?.onError(error)
            }

            override fun requestApproval(tool: String, args: JSONObject?): Boolean {
                chat.runState = "approval"; store.save(chat)
                return try { AgentBus.awaitApproval(tool, args, token) } finally {
                    chat.runState = "running"; dev.wind.agent.session.TaskNotifications.clear(chat.id)
                }
            }

            override fun requestCapability(request: CapabilityRequest): CapabilityDecision {
                // Keep the existing one-sheet UI/queue, but pass the broker's
                // exact task/mount summary through a private display-only field.
                // It is never sent back to the model or persisted as authority.
                val displayArgs = JSONObject(request.args?.toString() ?: "{}")
                displayArgs.put("_capability_summary", request.summary)
                chat.runState = "approval"; store.save(chat)
                return try { AgentBus.awaitCapability(request.copy(args = displayArgs), token) } finally {
                    chat.runState = "running"; dev.wind.agent.session.TaskNotifications.clear(chat.id)
                }
            }

            override fun isCancelled(): Boolean = token.isCancelled
        }

    private fun finishRun(chat: Chat, runId: Long) {
        val stopped = AgentBus.userStopped
        val cancelled = AgentBus.token(runId)?.isCancelled == true
        chat.runState = if (stopped) "stopped" else if (cancelled) "interrupted" else
            if (synchronized(chat.messages) { chat.messages.lastOrNull()?.isError == true }) "failed" else "completed"
        val mateExecution=AgentBus.activeTaskId
        if(!mateExecution.isNullOrBlank())
            dev.wind.agent.mate.MateTaskCoordinator.finish(this,chat,mateExecution,chat.runState=="failed",stopped || cancelled)
        dev.wind.agent.session.TaskNotifications.clear(chat.id)
        if (!stopped) dev.wind.agent.session.TaskNotifications.event(chat.id,
            if(chat.mateTaskState=="WAITING_FOR_EVENT")"生活任务已进入事件等待" else if(chat.mateTaskState in setOf("WAITING_FOR_USER","UNKNOWN","NEEDS_REAUTHORIZE"))"生活任务需要你处理" else if (chat.runState == "completed") "Harness Mate 任务已完成" else "Harness Mate 任务已中断",
            if(chat.mateTaskState in setOf("WAITING_FOR_EVENT","WAITING_FOR_USER","UNKNOWN","NEEDS_REAUTHORIZE"))"打开生活任务中心查看进度" else if (chat.runState == "completed") "点击查看结果" else "点击查看原因并继续任务")
        try {
            // saveNow, not save: this is the run's LAST write and stopSelf() is
            // four lines below, so a queued async write could still be in the
            // executor when the system reclaims the process — losing the final
            // answer. It blocks the agent worker thread, never the main thread.
            store.saveNow(chat)
        } catch (ignored: Throwable) {
        }
        val taskId = AgentBus.activeTaskId
        if (!taskId.isNullOrBlankJava()) {
            // TASK grants end with the run. A crash before this point leaves the
            // durable envelope available for recovery; a clean finish closes it
            // explicitly so authority cannot linger after the task is complete.
            runCatching { CapabilityBrokerRegistry.get(this).endTask(taskId) }
        }
        runCatching { dev.wind.agent.screen.PetActivity.finished(runId, AgentBus.userStopped) }
        try { AgentBus.listener?.onComplete() } finally {
            try { AgentBus.finish(runId) } finally {
                runCatching { dev.wind.agent.phone.ChatAssist.completed(this, runId) }
            }
        }
        // The last worker tears down the service; other conversations retain their runs.
    }

    // ---- foreground / notification ----------------------------------------

    private fun startForegroundSafe(): Boolean {
        ensureChannel()
        val notification = buildNotification(Fa.SVC_TEXT)
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIF_ID, notification, if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
                )
            } else {
                startForeground(NOTIF_ID, notification)
            }
            true
        } catch (first: Throwable) {
            if (Build.VERSION.SDK_INT >= 34) return false
            // API 31+ can throw ForegroundServiceStartNotAllowedException here;
            // retry without the type, and if that also fails, report failure so
            // onStartCommand tears down cleanly instead of crashing.
            try {
                startForeground(NOTIF_ID, notification)
                true
            } catch (second: Throwable) {
                false
            }
        }
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) {
            return
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return
        // No early return when the channel exists: createNotificationChannel
        // UPDATES the name and description of an existing id. Skipping it pinned
        // the channel to the wording it was first created with, and that label is
        // what the user sees in Android's notification settings forever — so an
        // upgrade that rewords it would never take effect.
        val channel = NotificationChannel(
            CHANNEL_ID, Fa.SVC_CHANNEL, NotificationManager.IMPORTANCE_LOW
        )
        channel.description = Fa.SVC_TEXT
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(text: String): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        openIntent.flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        // FLAG_IMMUTABLE arrived in API 23. It is the app's floor, so the branch is
        // belt-and-braces rather than strictly required — but it is the same shape
        // App.kt:97 uses for the same constant, and one spelling of a version check
        // is worth more than an argument about which sites need one.
        var pendingFlags = PendingIntent.FLAG_UPDATE_CURRENT
        if (Build.VERSION.SDK_INT >= 23) {
            pendingFlags = pendingFlags or PendingIntent.FLAG_IMMUTABLE
        }
        val open = PendingIntent.getActivity(this, 0, openIntent, pendingFlags)

        val stopIntent = Intent(this, AgentService::class.java)
        stopIntent.action = ACTION_STOP
        stopIntent.putExtra(EXTRA_RUN_ID, AgentBus.runId())
        val stopAction = PendingIntent.getService(this, 1, stopIntent, pendingFlags)

        val builder = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        builder.setContentTitle(Fa.SVC_TITLE)
            .setContentText(text)
            .setSmallIcon(R.mipmap.ic_stat_vepro)
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(
                    R.mipmap.ic_stat_vepro, Fa.CANCEL, stopAction
                ).build()
            )
        try {
            // v5.7 brand violet accent on the notification (matches the logo)
            builder.setColor(Theme.ACCENT)
        } catch (e: Exception) {
        }
        return builder.build()
    }

    /** Notification text for "a tool named [text] is running". */
    fun updateNotification(text: String) {
        notifyText("Running " + text + " …")
    }

    /**
     * Notification text used VERBATIM.
     *
     * The stop path used notifyText(Fa.STOPPING), which wrapped it in the
     * running-tool template and produced "Running Stopping… …" — garbled, on the
     * path every user who cancels a long run sees.
     */
    private fun notifyText(text: String) {
        try {
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.notify(NOTIF_ID, buildNotification(text))
        } catch (e: Exception) {
        }
    }

    /**
     * Drops the foreground state and its notification.
     *
     * `stopForeground(int)` and the `STOP_FOREGROUND_REMOVE` flag it takes are
     * both API 24, so on Android 6 this raised NoSuchMethodError — on the one
     * path every finished, cancelled or timed-out run goes through, leaving an
     * undismissable notification and a service that never stopped. The
     * boolean overload is the exact pre-24 equivalent: `true` means "remove the
     * notification", which is precisely what STOP_FOREGROUND_REMOVE asks for.
     *
     * The catch is Throwable, not Exception: NoSuchMethodError is an Error, so
     * the old `catch (e: Exception)` could not have caught the very failure this
     * method existed to be safe against — it propagated out of finishRun and
     * killed the worker thread's unwind.
     */
    private fun stopForegroundSafe() {
        try {
            if (Build.VERSION.SDK_INT >= 24) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (e: Throwable) {
        }
    }

    private fun stopSelfClean() {
        stopForegroundSafe()
        stopSelf()
    }

    // ---- wake lock ---------------------------------------------------------

    private fun acquireWake() {
        synchronized(wakeLockLock) {
            try {
                if (wakeLock != null) { wakeLock?.acquire(WAKE_MS); return }
                val power = getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return
                val lock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "vepro:agent")
                wakeLock = lock
                lock.setReferenceCounted(false)
                lock.acquire(WAKE_MS)
                lastWakeRenew = System.currentTimeMillis()
                ticking = true
                wakeTicker.removeCallbacks(wakePulse)
                wakeTicker.postDelayed(wakePulse, WAKE_TICK_MS)
            } catch (e: Exception) {
            }
        }
    }

    /**
     * Re-arms the wake lock so long / heavy tasks never get suspended mid-run.
     * The lock is timed (so a crashed run can't hold the CPU forever), and this
     * is called from every callback boundary to push that deadline forward.
     * Throttled to once every 30s so per-token calls stay cheap.
     */
    private fun renewWake() {
        // Cheap unsynchronized pre-check keeps the per-token path free; the
        // actual acquire still happens under the lock.
        if (System.currentTimeMillis() - lastWakeRenew < 30000) {
            return
        }
        synchronized(wakeLockLock) {
            try {
                val now = System.currentTimeMillis()
                if (now - lastWakeRenew < 30000) {
                    return
                }
                lastWakeRenew = now
                wakeLock?.acquire(WAKE_MS)
            } catch (e: Exception) {
            }
        }
    }

    private fun releaseWake() {
        synchronized(wakeLockLock) {
            ticking = false
            try {
                wakeTicker.removeCallbacks(wakePulse)
            } catch (ignored: Exception) {
            }
            // Null the field FIRST: anything that wakes up mid-release then
            // finds no lock to re-acquire, instead of resurrecting one that is
            // about to become unreachable.
            val lock = wakeLock
            wakeLock = null
            try {
                if (lock != null && lock.isHeld) {
                    lock.release()
                }
            } catch (e: Exception) {
            }
        }
    }

    // ---- lifecycle ---------------------------------------------------------

    override fun onBind(intent: Intent?): IBinder? = null

    /**
     * BUGFIX: Android 14 (API 34) delivers the dataSync foreground-service
     * timeout through THIS single-argument callback; only API 35+ uses the
     * two-argument overload below. Without this override the default no-op
     * ran, the service never stopped, and the system killed the whole app
     * with ForegroundServiceDidNotStopInTimeException after the 6-hour cap.
     */
    override fun handleFgsTimeout(startId: Int) {
        AgentBus.runningIds().forEach { AgentBus.requestCancel(it) }
        notifyText(Fa.STOPPING)
        runThreads.values.forEach { it.interrupt() }
        stopForegroundSafe()
        stopSelf(startId)
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        AgentBus.runningIds().forEach { id -> AgentBus.chatId(id)?.let { AgentBus.liveChatFor(it) }?.let { store.save(it) } }
        super.onTaskRemoved(rootIntent)
    }

    override fun onDestroy() {
        // A new run can already be queued for the replacement Service instance.
        // Destroying this instance must cancel only the workers it actually owns.
        runThreads.keys.toList().forEach { AgentBus.requestCancel(it) }
        runThreads.values.forEach { it.interrupt() }
        releaseWake()
        super.onDestroy()
    }

    companion object {
        /**
         * The notification channel id.
         *
         * Renamed with the package, and the rename is safe because the channel is
         * created and updated from [ensureChannel] on every run: an install that
         * still has the old `vepro_agent_run` channel simply keeps a stale entry in
         * Android's notification settings that this app never writes to again. The
         * cost of leaving the old id would have been permanent — the id is what the
         * user's per-channel preferences hang off, and nothing would ever rename it.
         */
        const val CHANNEL_ID = "dev_wind_agent_run"
        const val EXTRA_CHAT_ID = "chatId"
        const val EXTRA_RUN_ID = "runId"
        /**
         * What the notification's Stop button sends back into this service.
         *
         * Built and matched by this same build, so the string is a private protocol
         * rather than an API — but it used to carry the OLD package name, which made
         * the rebrand untrue in the one place nobody looks.
         */
        const val ACTION_STOP = "dev.wind.agent.STOP_AGENT"
        const val NOTIF_ID = 4021

        private const val WAKE_MS = 600000L
        private const val WAKE_TICK_MS = 30000L // renew wake lease and checkpoint silent/streaming work

        fun start(context: Context, chatId: String, runId: Long) {
            val intent = Intent(context, AgentService::class.java)
            intent.putExtra(EXTRA_CHAT_ID, chatId)
            intent.putExtra(EXTRA_RUN_ID, runId)
            if (Build.VERSION.SDK_INT < 26) {
                context.startService(intent)
            } else {
                context.startForegroundService(intent)
            }
        }
    }
}
