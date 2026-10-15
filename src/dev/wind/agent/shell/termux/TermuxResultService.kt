package dev.wind.agent.shell.termux

import android.app.Service
import android.content.Intent
import android.os.IBinder

class TermuxResultService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent != null) {
            handleResult(intent)
        }
        stopSelf(startId)
        return START_NOT_STICKY
    }

    private fun handleResult(intent: Intent) {
        val bundle = intent.getBundleExtra(TermuxContract.EXTRA_PLUGIN_RESULT_BUNDLE)
            ?: intent.extras ?: return
        val executionId = intent.getStringExtra("execution_id")
            ?: intent.getIntExtra("execution_id", -1).takeIf { it != -1 }?.toString()
            ?: ""
        if (executionId.isNotEmpty()) {
            val stdout = bundle.getString(TermuxContract.EXTRA_RESULT_STDOUT, "")
            val stderr = bundle.getString(TermuxContract.EXTRA_RESULT_STDERR, "")
            val exitCode = if (bundle.containsKey(TermuxContract.EXTRA_RESULT_EXIT_CODE)) {
                bundle.getInt(TermuxContract.EXTRA_RESULT_EXIT_CODE)
            } else null
            val errCode = if (bundle.containsKey(TermuxContract.EXTRA_RESULT_ERR)) {
                bundle.getInt(TermuxContract.EXTRA_RESULT_ERR)
            } else {
                android.app.Activity.RESULT_OK
            }
            val errMsg = bundle.getString(TermuxContract.EXTRA_RESULT_ERRMSG, "")
            TermuxExecutionRegistry.complete(
                executionId,
                TermuxRawResult(exitCode, stdout, stderr, errCode, errMsg)
            )
        }
    }
}
