package dev.wind.agent.runtime.linux

/** One app-owned worker survives Settings recreation; it never owns an Activity. */
object LinuxToolPackJobs {
    data class Status(val busy: Boolean, val phase: String, val message: String = "")
    private var worker: Thread? = null
    private var listener: ((Status) -> Unit)? = null
    private var state = Status(false, "IDLE")
    @Synchronized fun observe(observer: (Status) -> Unit) { listener = observer; deliver(state) }
    @Synchronized fun removeObserver(observer: (Status) -> Unit) { if (listener === observer) listener = null }
    @Synchronized fun isBusy(): Boolean = worker != null
    @Synchronized fun start(operation: () -> String): Boolean {
        if (worker != null) return false
        state = Status(true, "STARTING")
        val thread = Thread({
            LinuxInstallControl.setListener { phase -> update(Status(true, phase)) }
            val result = try {
                Status(false, "READY", operation())
            } catch (failure: Exception) {
                Status(false, if (failure is InterruptedException || failure is java.io.InterruptedIOException ||
                    Thread.currentThread().isInterrupted) "CANCELLED" else "FAILED", failure.message ?: failure.javaClass.simpleName)
            } catch (failure: Throwable) {
                Status(false, "FAILED", failure.toString())
            } finally { LinuxInstallControl.clearListener() }
            synchronized(this) {
                worker = null
                state = result
                deliver(result)
            }
        }, "vega-tool-pack-operation")
        worker = thread
        deliver(state)
        try { thread.start() }
        catch (failure: Throwable) { worker = null; update(Status(false, "FAILED", failure.toString())); throw failure }
        return true
    }
    @Synchronized fun cancel() {
        worker?.interrupt()
        if (worker != null) update(Status(true, "CANCELLING"))
    }
    @Synchronized private fun update(status: Status) { state = status; deliver(status) }
    private fun deliver(status: Status) { try { listener?.invoke(status) } catch (_: Exception) { } }
}
