package dev.wind.agent.project

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import dev.wind.agent.workspace.SafWorkspaceFs
import dev.wind.agent.workspace.WorkspaceManager

/**
 * Tiny capability-grant trampoline.
 *
 * The Agent can request a mount, but only Android's system picker can grant it.
 * This Activity owns that user gesture and contains no model-controlled file access.
 */
class WorkspaceGrantActivity : Activity() {

    companion object {
        private const val REQ_TREE = 7001
    }

    private var requestId: String = ""
    private var launched = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestId = WorkspaceAccessRequestStore.oldestPending()?.requestId ?: ""
        if (requestId.isBlank()) {
            finish()
            return
        }
        if (!launched) launchPicker()
    }

    private fun launchPicker() {
        val req = WorkspaceAccessRequestStore.getRequest(requestId) ?: run {
            finish()
            return
        }
        launched = true
        try {
            startActivityForResult(
                WorkspaceManager.createOpenDocumentTreeIntent(write = req.mode == MountMode.READ_WRITE),
                REQ_TREE
            )
        } catch (_: Exception) {
            Toast.makeText(this, "No system folder picker is available.", Toast.LENGTH_LONG).show()
            finish()
        }
    }

    private fun finishAndContinueQueue() {
        finish()
        WorkspaceAccessRequestStore.launchNextPending()
    }

    @Deprecated("startActivityForResult is retained for minSdk 23 compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQ_TREE) return

        if (resultCode != RESULT_OK || data?.data == null) {
            try {
                WorkspaceAccessRequestStore.removeRequest(requestId)
            } catch (e: Exception) {
                Toast.makeText(this, e.message ?: "Could not update capability request queue", Toast.LENGTH_LONG).show()
                finish()
                return
            }
            finishAndContinueQueue()
            return
        }

        val uri: Uri = data.data ?: run {
            finish()
            return
        }
        val request = WorkspaceAccessRequestStore.getRequest(requestId)
        if (request == null) {
            finishAndContinueQueue()
            return
        }

        var granted = false
        try {
            val displayName = try {
                SafWorkspaceFs(applicationContext, uri).getRootDisplayName()
            } catch (_: Exception) {
                request.alias
            }
            ProjectManager(this).grantWorkspaceRequest(request, uri, displayName)
            WorkspaceAccessRequestStore.removeRequest(requestId)
            granted = true
            Toast.makeText(this, "Project folder authorized: ${request.alias}", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            // Keep the durable request so it can be retried after the pending
            // execution session or provider permission problem is resolved.
            Toast.makeText(this, e.message ?: "Could not authorize project folder", Toast.LENGTH_LONG).show()
        } finally {
            if (granted) finishAndContinueQueue() else finish()
        }
    }
}
