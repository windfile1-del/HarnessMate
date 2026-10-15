package dev.wind.agent.artifact

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import java.io.InputStream

interface ApkInstaller {
    fun install(
        context: Context?,
        artifactStream: InputStream,
        operationId: String,
        plan: ApkInstallPlan
    ): String
}

object ApkInstallerHolder {
    var defaultInstaller: ApkInstaller = SystemPackageInstaller()
}

class SystemPackageInstaller : ApkInstaller {
    override fun install(
        context: Context?,
        artifactStream: InputStream,
        operationId: String,
        plan: ApkInstallPlan
    ): String {
        if (context == null) throw IllegalStateException("Android Context required for installation")
        val pm = context.packageManager
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            if (!pm.canRequestPackageInstalls()) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                    setData(android.net.Uri.parse("package:${context.packageName}"))
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
                return "NEEDS_PERMISSION: Please allow 'Install unknown apps' for Vega Safe in system settings."
            }
        }
        val installer = pm.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(plan.packageName)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)
        try {
            session.openWrite("package.apk", 0L, plan.byteSize).use { out: java.io.OutputStream ->
                val buf = ByteArray(64 * 1024)
                var n: Int
                while (artifactStream.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                }
                out.flush()
            }
            val broadcastIntent = Intent(context, ApkInstallReceiver::class.java).apply {
                action = ApkInstallReceiver.ACTION_INSTALL_STATUS
                putExtra(ApkInstallReceiver.EXTRA_OPERATION_ID, operationId)
            }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val pendingIntent = PendingIntent.getBroadcast(context, sessionId, broadcastIntent, flags)
            session.commit(pendingIntent.intentSender)
        } finally {
            session.close()
        }
        ApkInstallTracker.get(context).updateStatus(operationId, "PENDING_USER_CONFIRMATION", "Waiting for system user confirmation")
        return "INSTALL_SUBMITTED"
    }
}

class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val operationId = intent.getStringExtra(EXTRA_OPERATION_ID) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""
        val tracker = ApkInstallTracker.get(context)
        when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = if (android.os.Build.VERSION.SDK_INT >= 33) {
                    intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(Intent.EXTRA_INTENT)
                }
                if (confirmIntent != null) {
                    confirmIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(confirmIntent)
                    tracker.updateStatus(operationId, "PENDING_USER_CONFIRMATION", "User confirmation dialog opened")
                } else {
                    tracker.updateStatus(operationId, "FAILED", "System confirmation intent missing")
                }
            }
            PackageInstaller.STATUS_SUCCESS -> {
                tracker.updateStatus(operationId, "SUCCESS", "Application installed successfully")
            }
            else -> {
                val reason = when (status) {
                    PackageInstaller.STATUS_FAILURE_ABORTED -> "Installation cancelled by user"
                    PackageInstaller.STATUS_FAILURE_BLOCKED -> "Installation blocked by policy"
                    PackageInstaller.STATUS_FAILURE_CONFLICT -> "Signature or package version conflict"
                    PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "Package incompatible with device"
                    PackageInstaller.STATUS_FAILURE_INVALID -> "Invalid package format or digest"
                    PackageInstaller.STATUS_FAILURE_STORAGE -> "Insufficient storage space"
                    else -> "Installation failed (status $status): $message"
                }
                tracker.updateStatus(operationId, "FAILED", reason)
            }
        }
    }

    companion object {
        const val ACTION_INSTALL_STATUS = "dev.wind.agent.ACTION_INSTALL_STATUS"
        const val EXTRA_OPERATION_ID = "dev.wind.agent.EXTRA_OPERATION_ID"
    }
}
