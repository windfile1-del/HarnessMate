package dev.wind.agent.runtime

import org.json.JSONObject
import java.util.concurrent.*

/** Binder itself ignores Thread.interrupt; read-only RPCs must not pin an agent worker. */
internal object InterruptibleRuntimeRead {
    private val workers = ThreadPoolExecutor(0, 8, 30, TimeUnit.SECONDS, SynchronousQueue(),
        ThreadFactory { job -> Thread(job, "runtime-read").apply { isDaemon = true } })

    fun <T> call(block: () -> T): T {
        val future = workers.submit(Callable { block() })
        try { return future.get() }
        catch (interrupted: InterruptedException) {
            future.cancel(true)
            Thread.currentThread().interrupt()
            throw java.io.InterruptedIOException("RUNTIME_READ_CANCELLED")
        } catch (failed: ExecutionException) {
            throw failed.cause ?: failed
        }
    }

    fun isRead(code: Int, payload: JSONObject): Boolean =
        code == CompanionRuntimeClient.TRANSACTION_PROBE || code == CompanionRuntimeClient.TRANSACTION_QUERY ||
        code == CompanionRuntimeClient.TRANSACTION_POLL_COMMAND ||
        code == CompanionRuntimeClient.TRANSACTION_MIRROR_CALL && payload.optString("operation") in setOf(
            "manifest", "manifest_page", "execution_list_dir", "execution_read_file", "execution_file_info",
            "execution_search_files", "execution_glob")
}
