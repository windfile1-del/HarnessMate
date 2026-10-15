package dev.wind.agent.screen

import android.app.Notification
import android.app.Activity
import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.util.Base64
import android.util.DisplayMetrics
import android.view.WindowManager
import android.widget.Toast
import dev.wind.agent.CancellationToken
import dev.wind.agent.ChatStore
import dev.wind.agent.Fa
import dev.wind.agent.MainActivity
import dev.wind.agent.Message
import dev.wind.agent.Prefs
import dev.wind.agent.R
import dev.wind.agent.Theme
import java.io.ByteArrayOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Owns one authorized projection, independently of activities and agent runs. */
class ScreenShareService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private lateinit var thread: HandlerThread
    private lateinit var frames: Handler
    // All projection/reader/request fields below belong to the frames thread.
    private var projection: MediaProjection? = null
    private var display: VirtualDisplay? = null
    private var reader: ImageReader? = null
    private var projectionCallback: MediaProjection.Callback? = null
    private var width = 0
    private var height = 0
    private var density = 0
    private var currentRequest: CaptureRequest? = null
    @Volatile private var window: FloatingChatWindow? = null
    private val visibleActivities = HashSet<Activity>()
    private val activityVisibility = object : Application.ActivityLifecycleCallbacks {
        override fun onActivityStarted(activity: Activity) {
            visibleActivities.add(activity)
            ScreenShareSession.appActivitiesVisible = visibleActivities.isNotEmpty()
            window?.refreshStatus()
        }
        override fun onActivityStopped(activity: Activity) {
            visibleActivities.remove(activity)
            ScreenShareSession.appActivitiesVisible = visibleActivities.isNotEmpty()
            window?.refreshStatus()
        }
        override fun onActivityCreated(activity: Activity, state: Bundle?) { }
        override fun onActivityResumed(activity: Activity) { }
        override fun onActivityPaused(activity: Activity) { }
        override fun onActivitySaveInstanceState(activity: Activity, state: Bundle) { }
        override fun onActivityDestroyed(activity: Activity) { visibleActivities.remove(activity) }
    }
    @Volatile private var destroyed = false
    @Volatile private var intentionallyStopped = false
    @Volatile private var capturing = false
    val hasWindow: Boolean get() = window != null

    private class CaptureRequest(val expected: Long, val phoneObservation: Boolean = false) {
        val done = AtomicBoolean(false)
        val latch = CountDownLatch(1)
        @Volatile var result = ScreenShareSession.CaptureResult(error = "Capture did not finish.")
    }

    override fun onCreate() {
        super.onCreate()
        Fa.apply(this)
        Theme.init(this)
        Theme.applyFromPrefs(this, Prefs(this))
        thread = HandlerThread("vega-screen-on-send").also { it.start() }
        frames = Handler(thread.looper)
        ScreenShareSession.service = this
        application.registerActivityLifecycleCallbacks(activityVisibility)
        if (ScreenShareSession.status.isEmpty()) ScreenShareSession.status = ScreenStrings.inactive
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent == null) { stopSelf(); return START_NOT_STICKY }
        if (intent.action == ACTION_STOP_ALL) {
            intentionallyStopped = true
            closeWindow()
            frames.post { releaseProjection(false); main.post { stopSelf() } }
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_STOP_SHARE) {
            intentionallyStopped = true
            frames.post { releaseProjection(false); main.post { updateForegroundOrStop() } }
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_CLOSE_WINDOW) {
            if (!ScreenShareSession.isSharing) intentionallyStopped = true
            closeWindow()
            updateForegroundOrStop()
            return START_NOT_STICKY
        }
        val startShare = intent.action == ACTION_START_SHARE
        if (startShare) intentionallyStopped = false
        try {
            promote(startShare || ScreenShareSession.isSharing)
        } catch (error: Exception) {
            ScreenShareSession.status = error.message ?: ScreenStrings.interrupted
            Toast.makeText(this, ScreenShareSession.status, Toast.LENGTH_LONG).show()
            stopSelf()
            return START_NOT_STICKY
        }
        if (intent.action == ACTION_WINDOW || intent.getBooleanExtra(EXTRA_WINDOW, false)) {
            showWindow(intent.getStringExtra(EXTRA_CHAT))
        }
        if (startShare && !ScreenShareSession.isSharing) {
            @Suppress("DEPRECATION")
            val data = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION)
            val result = intent.getIntExtra(EXTRA_RESULT, 0)
            if (data == null || result != android.app.Activity.RESULT_OK) {
                ScreenShareSession.status = ScreenStrings.grantDenied
                updateForegroundOrStop()
            } else {
                frames.post { startProjection(result, data) }
            }
        }
        return START_NOT_STICKY
    }

    private fun startProjection(result: Int, data: Intent) {
        if (destroyed || projection != null) return
        var obtained: MediaProjection? = null
        try {
            val manager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
            val active = manager.getMediaProjection(result, data)
                ?: throw IllegalStateException("Projection was not granted")
            obtained = active
            val callback = object : MediaProjection.Callback() {
                override fun onStop() {
                    if (projection !== active) return
                    intentionallyStopped = false
                    releaseProjection(true)
                    main.post { updateForegroundOrStop() }
                }
            }
            active.registerCallback(callback, frames)
            projection = active
            projectionCallback = callback
            val size = captureSize()
            width = size[0]; height = size[1]; density = size[2]
            // Null surface while idle: no frame listener, screenshots, encoding or upload.
            // Reuse this ONE virtual display for the entire authorized session (Android 14+).
            display = active.createVirtualDisplay("VegaScreenOnSend", width, height, density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, null, null, frames)
                ?: throw IllegalStateException("Could not create screen display")
            ScreenShareSession.sessionId = SystemClock.elapsedRealtimeNanos()
            ScreenShareSession.status = ScreenStrings.sharing
            main.post { if (!destroyed) updateForegroundOrStop() }
        } catch (error: Exception) {
            releaseProjection(true)
            try { obtained?.stop() } catch (_: Exception) { }
            main.post {
                Toast.makeText(this, error.message ?: ScreenStrings.interrupted, Toast.LENGTH_LONG).show()
                updateForegroundOrStop()
            }
        }
    }

    private fun captureSize(): IntArray {
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        (getSystemService(Context.WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(metrics)
        val scale = minOf(1.0, 1600.0 / maxOf(metrics.widthPixels, metrics.heightPixels).coerceAtLeast(1))
        return intArrayOf((metrics.widthPixels * scale).toInt().coerceAtLeast(1),
            (metrics.heightPixels * scale).toInt().coerceAtLeast(1), metrics.densityDpi)
    }

    internal fun captureBlocking(expected: Long, token: CancellationToken, phoneObservation: Boolean = false,
        timeoutMs: Long = 4500L): ScreenShareSession.CaptureResult {
        check(Looper.myLooper() != Looper.getMainLooper()) { "Screen capture cannot block the UI" }
        val request = CaptureRequest(expected, phoneObservation)
        main.post {
            if (request.done.get()) return@post
            if (destroyed || capturing || expected != ScreenShareSession.sessionId ||
                !request.phoneObservation && !ScreenShareSession.canCaptureForMessage(expected)) {
                request.result = ScreenShareSession.CaptureResult(error = "Screen sharing is unavailable.")
                request.done.set(true); request.latch.countDown()
            } else {
                capturing = true
                window?.hideForCapture(hideInputKeyboard = !request.phoneObservation)
                // Give SurfaceFlinger and the keyboard time to remove the overlay before enabling output.
                main.postDelayed({
                    if (!request.done.get()) frames.post { startCapture(request) }
                }, 200L)
            }
        }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceIn(1L, 4500L)
        try {
            while (!request.latch.await(75L, TimeUnit.MILLISECONDS)) {
                if (token.isCancelled || destroyed || SystemClock.elapsedRealtime() >= deadline) {
                    frames.post { complete(request, ScreenShareSession.CaptureResult(error =
                        if (token.isCancelled) "Capture cancelled." else "Timed out waiting for a new screen frame.")) }
                    return ScreenShareSession.CaptureResult(error = "Screen capture was cancelled or timed out.")
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            frames.post { complete(request, ScreenShareSession.CaptureResult(error = "Capture interrupted.")) }
            return ScreenShareSession.CaptureResult(error = "Capture interrupted.")
        }
        return request.result
    }

    private fun startCapture(request: CaptureRequest) {
        if (destroyed || request.done.get() || projection == null || display == null ||
            request.expected != ScreenShareSession.sessionId ||
            !request.phoneObservation && !ScreenShareSession.canCaptureForMessage(request.expected)) {
            complete(request, ScreenShareSession.CaptureResult(error = "Screen sharing ended."))
            return
        }
        try {
            currentRequest = request
            // Rebuild ONLY the reader on rotation; never create another virtual display/token.
            val size = captureSize()
            width = size[0]; height = size[1]; density = size[2]
            display?.resize(width, height, density)
            reader?.close()
            val source = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)
            reader = source
            source.setOnImageAvailableListener({ available ->
                var image: Image? = null
                try {
                    image = available.acquireLatestImage()
                    if (image != null && !request.done.get()) {
                        if (request.expected != ScreenShareSession.sessionId) {
                            complete(request, ScreenShareSession.CaptureResult(error = "Screen sharing ended."))
                        } else {
                            val bytes = encode(image)
                            val timestamp = System.currentTimeMillis()
                            val attachment = Message.Attachment("screen-$timestamp.jpg", null,
                                "image/jpeg", bytes.size.toLong(), "image")
                            attachment.dataUri = "data:image/jpeg;base64," + Base64.encodeToString(bytes, Base64.NO_WRAP)
                            complete(request, ScreenShareSession.CaptureResult(attachment, timestamp,
                                width = image.width, height = image.height))
                        }
                    }
                } catch (error: Throwable) {
                    complete(request, ScreenShareSession.CaptureResult(error = error.message ?: "Cannot read screen."))
                } finally {
                    image?.close()
                }
            }, frames)
            display?.surface = source.surface
            frames.postDelayed({ complete(request,
                ScreenShareSession.CaptureResult(error = "No new screen frame arrived.")) }, 2500L)
        } catch (error: Exception) {
            complete(request, ScreenShareSession.CaptureResult(error = error.message ?: "Cannot start capture."))
        }
    }

    private fun encode(image: Image): ByteArray {
        val plane = image.planes[0]
        val paddedWidth = image.width + (plane.rowStride - plane.pixelStride * image.width) / plane.pixelStride
        val padded = Bitmap.createBitmap(paddedWidth, image.height, Bitmap.Config.ARGB_8888)
        var cropped: Bitmap? = null
        try {
            padded.copyPixelsFromBuffer(plane.buffer)
            cropped = Bitmap.createBitmap(padded, 0, 0, image.width, image.height)
            val output = ByteArrayOutputStream()
            if (!cropped.compress(Bitmap.CompressFormat.JPEG, 85, output)) {
                throw IllegalStateException("Screen JPEG encoding failed")
            }
            return output.toByteArray()
        } finally {
            if (cropped !== padded) cropped?.recycle()
            padded.recycle()
        }
    }

    private fun complete(request: CaptureRequest, result: ScreenShareSession.CaptureResult) {
        if (!request.done.compareAndSet(false, true)) return
        if (currentRequest === request) {
            try { display?.surface = null } catch (_: Exception) { }
            reader?.setOnImageAvailableListener(null, null)
            reader?.close(); reader = null
            currentRequest = null
        }
        request.result = result
        main.post {
            capturing = false
            window?.restoreAfterCapture()
            if (result.image == null && !destroyed) {
                Toast.makeText(this, ScreenStrings.captureFailed, Toast.LENGTH_SHORT).show()
            }
            request.latch.countDown()
        }
    }

    private fun releaseProjection(interrupted: Boolean) {
        if (ScreenShareSession.service === this || ScreenShareSession.service == null) {
            ScreenShareSession.sessionId = 0L
            ScreenShareSession.status = if (interrupted) ScreenStrings.interrupted else ScreenStrings.inactive
        }
        currentRequest?.let { complete(it, ScreenShareSession.CaptureResult(error = "Screen sharing ended.")) }
        try { display?.release() } catch (_: Exception) { }
        display = null
        try { reader?.close() } catch (_: Exception) { }
        reader = null
        val active = projection
        projection = null
        projectionCallback?.let { try { active?.unregisterCallback(it) } catch (_: Exception) { } }
        projectionCallback = null
        try { active?.stop() } catch (_: Exception) { }
    }

    private fun showWindow(chatId: String?) {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, ScreenStrings.overlayPermission, Toast.LENGTH_LONG).show()
            updateForegroundOrStop()
            return
        }
        val existing = ScreenShareSession.chat
        if (existing == null || (chatId != null && existing.id != chatId)) {
            ScreenShareSession.chat = dev.wind.agent.AgentBus.liveChat?.takeIf {
                it.id == chatId || dev.wind.agent.AgentBus.isBusy()
            } ?: ChatStore(this).load(chatId ?: Prefs(this).lastChatId()) ?: ChatStore(this).create()
        }
        try {
            if (window == null) window = FloatingChatWindow(this) { closeWindow(); updateForegroundOrStop() }
            window?.show()
        } catch (error: Exception) {
            closeWindow()
            Toast.makeText(this, error.message ?: ScreenStrings.closeWindow, Toast.LENGTH_LONG).show()
            updateForegroundOrStop()
        }
    }

    private fun closeWindow() { window?.destroy(); window = null }

    /** Main-thread only. Avoid a coordinate action being intercepted by our pet/answer panels. */
    internal fun setPhoneActionHidden(hidden: Boolean) {
        if (hidden) window?.hideForPhoneAction() else window?.restoreAfterPhoneAction()
    }

    internal fun setMainVisible(visible: Boolean) {
        ScreenShareSession.mainVisible = visible
        window?.setMainVisible(visible)
    }

    private fun updateForegroundOrStop() {
        if (destroyed) return
        if (!ScreenShareSession.isSharing && window == null) stopSelf()
        else {
            try { promote(ScreenShareSession.isSharing) } catch (_: Exception) { stopSelf() }
            window?.refreshStatus()
        }
    }

    private fun promote(sharing: Boolean) {
        val notification = notification()
        if (Build.VERSION.SDK_INT >= 34) {
            val type = ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE or
                if (sharing) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else 0
            startForeground(NOTIFICATION_ID, notification, type)
        } else if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIFICATION_ID, notification, if (sharing)
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION else ServiceInfo.FOREGROUND_SERVICE_TYPE_NONE)
        } else startForeground(NOTIFICATION_ID, notification)
    }

    private fun notification(): Notification {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) {
            manager.createNotificationChannel(NotificationChannel(CHANNEL, ScreenStrings.title,
                NotificationManager.IMPORTANCE_LOW))
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val open = PendingIntent.getActivity(this, 70, Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP), flags)
        val stop = PendingIntent.getService(this, 71,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP_ALL), flags)
        val stopShare = PendingIntent.getService(this, 72,
            Intent(this, ScreenShareService::class.java).setAction(ACTION_STOP_SHARE), flags)
        val builder = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL)
            else @Suppress("DEPRECATION") Notification.Builder(this)
        builder.setSmallIcon(R.mipmap.ic_launcher).setContentTitle(ScreenStrings.title)
            .setContentText(ScreenShareSession.status).setContentIntent(open).setOngoing(true)
            .addAction(Notification.Action.Builder(null, ScreenStrings.stopShare, stopShare).build())
            .addAction(Notification.Action.Builder(null, ScreenStrings.stopAll, stop).build())
        return builder.build()
    }

    override fun onDestroy() {
        destroyed = true
        application.unregisterActivityLifecycleCallbacks(activityVisibility)
        visibleActivities.clear()
        closeWindow()
        if (ScreenShareSession.service === this) {
            ScreenShareSession.service = null
            ScreenShareSession.appActivitiesVisible = false
            ScreenShareSession.sessionId = 0L
            ScreenShareSession.status = if (intentionallyStopped) ScreenStrings.inactive else ScreenStrings.interrupted
        }
        frames.post { releaseProjection(!intentionallyStopped); thread.quitSafely() }
        main.removeCallbacksAndMessages(null)
        @Suppress("DEPRECATION")
        stopForeground(true)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_WINDOW = "dev.wind.agent.screen.WINDOW"
        const val ACTION_START_SHARE = "dev.wind.agent.screen.START"
        const val ACTION_STOP_SHARE = "dev.wind.agent.screen.STOP"
        const val ACTION_CLOSE_WINDOW = "dev.wind.agent.screen.CLOSE_WINDOW"
        const val ACTION_STOP_ALL = "dev.wind.agent.screen.STOP_ALL"
        const val EXTRA_CHAT = "screenChat"
        const val EXTRA_PROJECTION = "projectionData"
        const val EXTRA_RESULT = "projectionResult"
        const val EXTRA_WINDOW = "showWindow"
        private const val NOTIFICATION_ID = 4032
        private const val CHANNEL = "vega_screen_on_send"

        internal fun launch(context: Context, intent: Intent) {
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }
    }
}
