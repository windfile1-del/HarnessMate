package dev.wind.agent.screen

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.media.projection.MediaProjectionConfig
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import dev.wind.agent.Fa
import dev.wind.agent.Prefs

/** Visible, explicit grant flow; never opened automatically by sending a message. */
class ScreenSharePermissionActivity : Activity() {
    private var chatId: String? = null
    private var requested = false
    private var returnToOverlay = false
    private var dialog: AlertDialog? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        Fa.apply(this)
        chatId = intent.getStringExtra(ScreenShareService.EXTRA_CHAT) ?: Prefs(this).lastChatId()
        returnToOverlay = intent.getBooleanExtra("returnToOverlay", false)
        requested = state?.getBoolean("requested") ?: false
        if (!requested) showControls()
    }

    private fun showControls() {
        val actions = arrayOf(
            if (ScreenShareSession.hasWindow) ScreenStrings.closeWindow else ScreenStrings.showWindow,
            if (ScreenShareSession.isSharing) ScreenStrings.stopShare else ScreenStrings.startShare,
            ScreenStrings.appearance,
            ScreenStrings.phoneTools
        )
        dialog = AlertDialog.Builder(this).setTitle(ScreenStrings.title)
            .setItems(actions) { _, which ->
                if (which == 0) {
                    if (ScreenShareSession.hasWindow) command(ScreenShareService.ACTION_CLOSE_WINDOW)
                    else openWindow()
                } else if (which == 1) {
                    if (ScreenShareSession.isSharing) command(ScreenShareService.ACTION_STOP_SHARE)
                    else explainSharing()
                } else if (which == 2) showCharacters()
                else {
                    dev.wind.agent.phone.PhoneControlsActivity.open(this, returnToOverlay)
                    returnToOverlay = false // the child now owns returning to the desktop pet
                    finish()
                }
            }.setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.create()
        dialog?.show()
    }

    private fun showCharacters() {
        val characters = PetAppearance.Character.values()
        var selected = characters.indexOf(PetAppearance.selected(this))
        dialog = AlertDialog.Builder(this).setTitle(ScreenStrings.appearance)
            .setSingleChoiceItems(characters.map { it.label }.toTypedArray(), selected) { _, which -> selected = which }
            .setPositiveButton(android.R.string.ok) { _, _ ->
                PetAppearance.choose(this, characters[selected]); finish()
            }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.create()
        dialog?.show()
    }

    private fun explainSharing() {
        dialog = AlertDialog.Builder(this).setTitle(ScreenStrings.startShare)
            .setMessage(ScreenStrings.hint)
            .setPositiveButton(android.R.string.ok) { _, _ -> requestProjection() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> finish() }
            .setOnCancelListener { finish() }.create()
        dialog?.show()
    }

    private fun openWindow() {
        if (Settings.canDrawOverlays(this)) {
            command(ScreenShareService.ACTION_WINDOW)
        } else {
            requested = true
            Toast.makeText(this, ScreenStrings.overlayPermission, Toast.LENGTH_LONG).show()
            try {
                startActivityForResult(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:$packageName")), REQUEST_OVERLAY)
            } catch (error: Exception) {
                requested = false
                Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                finish()
            }
        }
    }

    private fun requestProjection() {
        if (ScreenShareSession.isSharing) { finish(); return }
        requested = true
        val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        val request = if (Build.VERSION.SDK_INT >= 34) {
            // The question is about the CURRENT phone page, across app switches.
            manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay())
        } else manager.createScreenCaptureIntent()
        try { startActivityForResult(request, REQUEST_PROJECTION) }
        catch (error: Exception) {
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        requested = false
        if (requestCode == REQUEST_OVERLAY) {
            if (Settings.canDrawOverlays(this)) command(ScreenShareService.ACTION_WINDOW)
            else finish()
        } else if (requestCode == REQUEST_PROJECTION) {
            if (resultCode == RESULT_OK && data != null && !ScreenShareSession.isSharing) {
                try {
                    ScreenShareService.launch(this, Intent(this, ScreenShareService::class.java)
                        .setAction(ScreenShareService.ACTION_START_SHARE)
                        .putExtra(ScreenShareService.EXTRA_RESULT, resultCode)
                        .putExtra(ScreenShareService.EXTRA_PROJECTION, data)
                        .putExtra(ScreenShareService.EXTRA_CHAT, chatId))
                } catch (error: Exception) {
                    Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
                }
            } else if (!ScreenShareSession.isSharing) {
                Toast.makeText(this, ScreenStrings.grantDenied, Toast.LENGTH_SHORT).show()
            }
            finish()
        }
    }

    private fun command(action: String) {
        try {
            val command = Intent(this, ScreenShareService::class.java).setAction(action)
                .putExtra(ScreenShareService.EXTRA_CHAT, chatId)
            if (action == ScreenShareService.ACTION_WINDOW) {
                ScreenShareService.launch(this, command)
                // Make opening the floating window actually leave the full app UI.
                moveTaskToBack(true)
            }
            else if (ScreenShareSession.service != null) startService(command)
        } catch (error: Exception) {
            Toast.makeText(this, error.message, Toast.LENGTH_LONG).show()
        }
        finish()
    }

    override fun onStart() {
        super.onStart()
        ScreenShareSession.controlsVisible = true
        ScreenShareSession.service?.setMainVisible(ScreenShareSession.mainVisible)
    }

    override fun onDestroy() {
        dialog?.dismiss()
        ScreenShareSession.controlsVisible = false
        ScreenShareSession.service?.setMainVisible(ScreenShareSession.mainVisible)
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("requested", requested)
        super.onSaveInstanceState(outState)
    }

    override fun finish() {
        if (returnToOverlay) moveTaskToBack(true)
        super.finish()
    }

    companion object {
        private const val REQUEST_OVERLAY = 81
        private const val REQUEST_PROJECTION = 82
        fun open(context: Context, chatId: String?) {
            val intent = Intent(context, ScreenSharePermissionActivity::class.java)
                .putExtra(ScreenShareService.EXTRA_CHAT, chatId)
                .putExtra("returnToOverlay", context !is Activity)
            if (context !is Activity) intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}
