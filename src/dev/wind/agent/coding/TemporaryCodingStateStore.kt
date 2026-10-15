package dev.wind.agent.coding

/** Temporary conversations keep harness control state in this run's memory. */
class TemporaryCodingStateStore : CodingStateStore {
    private val states=java.util.concurrent.ConcurrentHashMap<String,CodingTaskState>()
    override fun load(taskId: String, now: Long): CodingTaskState?=states[taskId]
    override fun save(state: CodingTaskState) { states[state.taskId]=state }
}
