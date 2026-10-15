package dev.wind.agent

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Latest pending job per key; serial within a key, independent keys never wait behind its I/O. */
internal class KeyedWorkQueue(private val executor: Executor = Executors.newCachedThreadPool { job ->
    Thread(job, "harness-chat-store").apply { isDaemon = true }
}) {
    class Ticket {
        private val done = CountDownLatch(1)
        @Volatile private var error: Throwable? = null
        internal fun complete(failure: Throwable?) { error = failure; done.countDown() }
        fun await(timeoutMs: Long) {
            check(done.await(timeoutMs, TimeUnit.MILLISECONDS)) { "CHAT_SAVE_PENDING" }
            error?.let { throw IllegalStateException("CHAT_SAVE_FAILED", it) }
        }
    }
    private class Work(val action: () -> Unit, val tickets: MutableList<Ticket>, val coalesce: Boolean)
    private class Lane { val pending = java.util.ArrayDeque<Work>() }
    private val lock = Any()
    private val lanes = HashMap<String, Lane>()
    fun submit(key: String, coalesce: Boolean = true, action: () -> Unit): Ticket {
        val ticket = Ticket()
        var launch: Lane? = null
        synchronized(lock) {
            val lane = lanes[key]
            if (lane == null) {
                launch = Lane().also { it.pending.add(Work(action, mutableListOf(ticket), coalesce)); lanes[key] = it }
            } else {
                val previous = lane.pending.peekLast()?.takeIf { coalesce && it.coalesce }
                val tickets = previous?.tickets ?: mutableListOf()
                if (previous != null) lane.pending.removeLast()
                tickets.add(ticket)
                lane.pending.add(Work(action, tickets, coalesce))
            }
        }
        launch?.let { lane ->
            try { executor.execute { drain(key, lane) } }
            catch (failure: Throwable) {
                val pending = synchronized(lock) { lanes.remove(key)?.pending?.toList() }
                pending?.forEach { work -> work.tickets.forEach { it.complete(failure) } }
            }
        }
        return ticket
    }
    private fun drain(key: String, lane: Lane) {
        while (true) {
            val work = synchronized(lock) {
                val next = lane.pending.pollFirst()
                if (next == null) { if (lanes[key] === lane) lanes.remove(key); return }
                next
            }
            val failure = try { work.action(); null } catch (failure: Throwable) { failure }
            work.tickets.forEach { it.complete(failure) }
        }
    }
    fun flush(timeoutMs: Long): Boolean {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (synchronized(lock) { lanes.isNotEmpty() }) {
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(10)
        }
        return true
    }
}
