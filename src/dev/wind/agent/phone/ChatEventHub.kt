package dev.wind.agent.phone

import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

/** Bounded debounce: event storms cannot postpone a page read forever. No model work on this thread. */
internal class ChatEventHub(private val read: ()->Unit): AutoCloseable {
    private val executor=Executors.newSingleThreadScheduledExecutor { r->Thread(r,"chat-event-reader").apply { isDaemon=true } }
    private var pending: ScheduledFuture<*>?=null
    @Volatile private var closed=false
    @Synchronized fun signal() {
        if(closed || pending?.isDone==false) return
        pending=executor.schedule({ synchronized(this) { pending=null }; if(!closed) read() },600,TimeUnit.MILLISECONDS)
    }
    init { executor.scheduleWithFixedDelay({ if(!closed) read() },ChatEventStreamPolicy.POLL_INTERVAL_MS,ChatEventStreamPolicy.POLL_INTERVAL_MS,TimeUnit.MILLISECONDS) }
    override fun close() { closed=true; synchronized(this) { pending?.cancel(false) }; executor.shutdownNow() }
}
