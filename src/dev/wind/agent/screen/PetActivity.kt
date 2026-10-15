package dev.wind.agent.screen

import android.os.SystemClock
import dev.wind.agent.AgentBus

/** Display-only observation of real service callbacks; never replaces the approval listener. */
internal object PetActivity {
    enum class State(val row: Int) {
        IDLE(0), THINKING(1), EDITING(2), SEARCHING(3), REPLYING(4), WAITING(5), HAPPY(6), ERROR(7),
        READING(8), WATCHING(9), LISTENING(10), GAMING(11), BROWSING(12), CHATTING(13), NAVIGATING(14), STUDYING(15)
    }

    data class Snapshot(val runId: Long, val state: State, val tool: String = "", val at: Long = SystemClock.uptimeMillis())
    @Volatile private var snapshot = Snapshot(0L, State.IDLE)

    fun update(runId: Long, state: State, tool: String = "") {
        if (runId == 0L || AgentBus.runId() != runId) return
        snapshot = Snapshot(runId, state, tool)
    }

    fun tool(runId: Long, name: String) {
        val key = name.lowercase(java.util.Locale.ROOT)
        val state = when {
            arrayOf("edit", "write", "patch", "apply", "delete", "move", "rename", "mkdir", "undo", "create").any { key.contains(it) } -> State.EDITING
            arrayOf("search", "glob", "find", "reference", "symbol", "web", "fetch", "browse", "read", "list", "inspect", "info").any { key.contains(it) } -> State.SEARCHING
            else -> State.EDITING // Laptop pose for shell/build/install/tool execution; labelled separately.
        }
        update(runId, state, name)
    }

    fun finished(runId: Long, stoppedByUser: Boolean) {
        if (AgentBus.runId() != runId) return
        val previous = snapshot
        snapshot = Snapshot(runId, if (previous.runId == runId && previous.state == State.ERROR) State.ERROR
            else if (stoppedByUser) State.IDLE else State.HAPPY)
    }

    fun current(): Snapshot {
        val current = snapshot
        if (AgentBus.isBusy()) {
            val run = AgentBus.runId()
            if (AgentBus.currentApproval() != null || AgentBus.state() == AgentBus.RunState.STOPPING) {
                return Snapshot(run, State.WAITING)
            }
            if (current.runId != run) return Snapshot(run, State.THINKING)
            if (current.state == State.HAPPY || current.state == State.IDLE) return Snapshot(run, State.REPLYING)
            return current
        }
        if (current.state == State.ERROR && SystemClock.uptimeMillis() - current.at < 10000L) return current
        if (current.state == State.HAPPY && SystemClock.uptimeMillis() - current.at < 6000L) return current
        return Snapshot(0L, PetCompanion.current())
    }

    fun label(value: Snapshot): String = when (value.state) {
        State.IDLE -> ScreenStrings.petIdle
        State.THINKING -> ScreenStrings.petThinking
        State.EDITING -> if (arrayOf("edit", "write", "patch", "apply", "delete", "move", "rename", "mkdir", "undo", "create")
            .any { value.tool.lowercase(java.util.Locale.ROOT).contains(it) }) ScreenStrings.petEditing else ScreenStrings.petWorking
        State.SEARCHING -> if (value.tool == "screen_capture") ScreenStrings.petViewing
            else if (arrayOf("read", "list", "inspect", "info").any { value.tool.contains(it) }) ScreenStrings.petReading else ScreenStrings.petSearching
        State.REPLYING -> ScreenStrings.petReplying
        State.WAITING -> if (AgentBus.state() == AgentBus.RunState.STOPPING) ScreenStrings.petStopping else ScreenStrings.petWaiting
        State.HAPPY -> ScreenStrings.petDone
        State.ERROR -> ScreenStrings.petError
        State.READING -> ScreenStrings.companionReading
        State.WATCHING -> ScreenStrings.companionWatching
        State.LISTENING -> ScreenStrings.companionListening
        State.GAMING -> ScreenStrings.companionGaming
        State.BROWSING -> ScreenStrings.companionBrowsing
        State.CHATTING -> ScreenStrings.companionChatting
        State.NAVIGATING -> ScreenStrings.companionNavigating
        State.STUDYING -> ScreenStrings.companionStudying
    }
}
