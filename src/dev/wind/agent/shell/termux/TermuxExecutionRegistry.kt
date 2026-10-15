package dev.wind.agent.shell.termux

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

data class TermuxRawResult(
    val exitCode: Int?,
    val stdout: String,
    val stderr: String,
    val internalError: Int = android.app.Activity.RESULT_OK,
    val internalErrorMessage: String = ""
) {
    val hasInternalError: Boolean
        get() = internalError != android.app.Activity.RESULT_OK
}

enum class RemoteExecutionState {
    WAITING,
    CANCEL_REQUESTED,
    COMPLETED,
    TIMED_OUT,
    CANCELLED
}

class TermuxPromise {
    val latch = CountDownLatch(1)
    @Volatile var state: RemoteExecutionState = RemoteExecutionState.WAITING
    @Volatile var result: TermuxRawResult? = null

    fun complete(res: TermuxRawResult) {
        // Section XVI: Late callback handling: do not modify already terminal state
        if (state == RemoteExecutionState.WAITING || state == RemoteExecutionState.CANCEL_REQUESTED) {
            result = res
            state = RemoteExecutionState.COMPLETED
            latch.countDown()
        }
    }

    fun markCancelled() {
        if (state != RemoteExecutionState.COMPLETED) {
            state = RemoteExecutionState.CANCELLED
            latch.countDown()
        }
    }

    fun markTimedOut() {
        if (state != RemoteExecutionState.COMPLETED) {
            state = RemoteExecutionState.TIMED_OUT
            latch.countDown()
        }
    }

    fun await(timeoutMs: Long): TermuxRawResult? {
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }
}

object TermuxExecutionRegistry {
    private val nextId = AtomicInteger(1)
    private val pending = ConcurrentHashMap<String, TermuxPromise>()

    fun register(): Pair<Int, TermuxPromise> {
        val id = nextId.getAndIncrement()
        val promise = TermuxPromise()
        pending[id.toString()] = promise
        return Pair(id, promise)
    }

    fun register(id: String): TermuxPromise {
        val promise = TermuxPromise()
        pending[id] = promise
        return promise
    }

    fun complete(id: Int, result: TermuxRawResult) {
        pending.remove(id.toString())?.complete(result)
    }

    fun complete(id: String, result: TermuxRawResult) {
        pending.remove(id)?.complete(result)
    }

    fun remove(id: Int) {
        pending.remove(id.toString())
    }

    fun remove(id: String) {
        pending.remove(id)
    }

    fun get(id: String): TermuxPromise? = pending[id]
}
