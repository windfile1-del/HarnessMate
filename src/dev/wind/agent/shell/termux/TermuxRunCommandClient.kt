package dev.wind.agent.shell.termux

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import dev.wind.agent.CancellationToken
import java.util.concurrent.atomic.AtomicInteger

class TermuxRunCommandClient(private val context: Context) {

    data class Request(
        val commandPath: String,
        val args: List<String> = emptyList(),
        val stdin: String? = null,
        val workDir: String = TERMUX_HOME,
        val timeoutMs: Long = 5_000L,
        val cancellation: CancellationToken? = null,
        /**
         * Best-effort remote cleanup after the command has been dispatched but
         * its callback is abandoned.  ACTION_RUN_COMMAND has no cancellation
         * primitive: removing the callback registration alone would leave the
         * Termux shell (and any detached descendants) running.  Callers that
         * launch a long-lived session must supply an idempotent, non-cancelled
         * cleanup operation here.
         */
        val onAbandon: (() -> Unit)? = null
    )

    data class Outcome(
        val callbackReceived: Boolean,
        val result: TermuxRawResult?,
        val timedOut: Boolean = false,
        val cancelled: Boolean = false,
        val dispatchError: String? = null,
        val elapsedMs: Long = 0L
    )

    fun run(request: Request): Outcome {
        val start = SystemClock.uptimeMillis()
        val executionId = "probe-" + java.util.UUID.randomUUID().toString().substring(0, 8)
        val promise = TermuxExecutionRegistry.register(executionId)

        val resultIntent = Intent(context, TermuxResultService::class.java).apply {
            putExtra("execution_id", executionId)
        }

        val flags = PendingIntent.FLAG_ONE_SHOT or
            if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0

        val pendingIntent = PendingIntent.getService(
            context,
            nextRequestCode(),
            resultIntent,
            flags
        )

        val intent = Intent().apply {
            setClassName(TermuxContract.TERMUX_PACKAGE, TermuxContract.TERMUX_SERVICE)
            action = TermuxContract.ACTION_RUN_COMMAND
            putExtra(TermuxContract.EXTRA_COMMAND_PATH, request.commandPath)
            putExtra(TermuxContract.EXTRA_ARGUMENTS, request.args.toTypedArray())
            putExtra(TermuxContract.EXTRA_WORKDIR, request.workDir)
            putExtra(TermuxContract.EXTRA_BACKGROUND, true)
            putExtra(TermuxContract.EXTRA_SESSION_ACTION, "0")
            request.stdin?.let { putExtra(TermuxContract.EXTRA_STDIN, it) }
            putExtra(TermuxContract.EXTRA_PENDING_INTENT, pendingIntent)
        }

        try {
            context.startService(intent)
        } catch (t: Throwable) {
            // startService may report an exception after the platform has
            // accepted the intent.  Cleanup is idempotent, so treat this as
            // an abandoned dispatch rather than assuming no child exists.
            runCatching { request.onAbandon?.invoke() }
            TermuxExecutionRegistry.remove(executionId)
            return Outcome(
                callbackReceived = false,
                result = null,
                dispatchError = t.message ?: t.javaClass.simpleName,
                elapsedMs = SystemClock.uptimeMillis() - start
            )
        }

        var abandonInvoked = false
        fun abandon() {
            if (abandonInvoked) return
            abandonInvoked = true
            runCatching { request.onAbandon?.invoke() }
        }

        val deadline = SystemClock.uptimeMillis() + request.timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (request.cancellation?.isCancelled == true) {
                abandon()
                TermuxExecutionRegistry.remove(executionId)
                return Outcome(
                    callbackReceived = false,
                    result = null,
                    cancelled = true,
                    dispatchError = "CANCELLED",
                    elapsedMs = SystemClock.uptimeMillis() - start
                )
            }
            val result = promise.await(100L)
            if (result != null) {
                return Outcome(
                    callbackReceived = true,
                    result = result,
                    elapsedMs = SystemClock.uptimeMillis() - start
                )
            }
        }

        abandon()
        TermuxExecutionRegistry.remove(executionId)
        return Outcome(
            callbackReceived = false,
            result = null,
            timedOut = true,
            elapsedMs = SystemClock.uptimeMillis() - start
        )
    }

    companion object {
        private const val TERMUX_HOME = "/data/data/com.termux/files/home"
        private val REQUEST_CODE = AtomicInteger(20_000)

        private fun nextRequestCode(): Int {
            val value = REQUEST_CODE.incrementAndGet()
            if (value >= Int.MAX_VALUE - 100) {
                REQUEST_CODE.set(20_000)
            }
            return value
        }
    }
}

interface TermuxProbeRunner {
    fun run(request: TermuxRunCommandClient.Request): TermuxRunCommandClient.Outcome
}

class AndroidTermuxProbeRunner(private val context: Context) : TermuxProbeRunner {
    private val client = TermuxRunCommandClient(context)
    override fun run(request: TermuxRunCommandClient.Request): TermuxRunCommandClient.Outcome = client.run(request)
}
