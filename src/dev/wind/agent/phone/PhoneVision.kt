package dev.wind.agent.phone

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Base64
import com.googlecode.tesseract.android.TessBaseAPI
import dev.wind.agent.CancellationToken
import dev.wind.agent.Message
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/** Offline OCR. Only one native job; timeout/cancellation never recycles a bitmap still used by JNI. */
internal object PhoneVision {
    data class Result(val status: String, val targets: List<PhoneVisual.Target> = emptyList(), val error: String? = null, val truncated: Boolean = false) {
        fun json() = JSONObject().put("status", status).put("engine", "tesseract4android-4.9.0")
            .put("languages", "chi_sim+eng").put("coordinate_space", "full_screen_normalized_0_1000")
            .put("error", error ?: JSONObject.NULL).put("truncated", truncated).put("targets", JSONArray(targets.map { targetJson(it) }))
    }
    private val gate = Semaphore(1)
    private val worker = Executors.newSingleThreadExecutor { task -> Thread(task, "phone-local-ocr").apply { isDaemon = true } }
    private val hashes = mapOf(
        "chi_sim" to "a5fcb6f0db1e1d6d8522f39db4e848f05984669172e584e8d76b6b3141e1f730",
        "eng" to "7d4322bd2a7749724879683fc3912cb542f19906c83bcc1a52132556427170b2")
    fun targetJson(target: PhoneVisual.Target) = JSONObject().put("visual_id", target.id).put("text", target.text)
        .put("bounds", JSONArray(listOf(target.left, target.top, target.right, target.bottom)))
        .put("confidence", target.confidence.toDouble()).put("kind", "ocr_text").put("clickability", "unknown")
    private fun sha(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input -> val b = ByteArray(65536); while (true) {
            val n = input.read(b); if (n < 0) break; digest.update(b, 0, n)
        } }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
    private fun models(context: Context, token: CancellationToken): File {
        val root = File(context.filesDir, "phone-ocr"); val data = File(root, "tessdata")
        check(data.isDirectory || data.mkdirs()) { "PHONE_OCR_MODEL_DIRECTORY" }
        for ((lang, hash) in hashes) {
            token.throwIfCancelled()
            val target = File(data, "$lang.traineddata")
            if (target.isFile && sha(target) == hash) continue
            val temp = File(data, "$lang.part")
            try {
                context.assets.open("phone-ocr/tessdata/$lang.traineddata").use { input ->
                    FileOutputStream(temp).use { output -> val b = ByteArray(65536); while (true) {
                        token.throwIfCancelled(); val n = input.read(b); if (n < 0) break; output.write(b, 0, n)
                    }; output.fd.sync() }
                }
                check(sha(temp) == hash) { "PHONE_OCR_MODEL_DIGEST" }
                check((!target.exists() || target.delete()) && temp.renameTo(target)) { "PHONE_OCR_MODEL_INSTALL" }
            } finally { if (temp.exists()) temp.delete() }
        }
        return root
    }
    fun recognize(context: Context, image: Message.Attachment, token: CancellationToken, timeoutMs: Long = 3000): Result {
        token.throwIfCancelled()
        if (!gate.tryAcquire()) return Result("BUSY", error = "Previous cancelled native job is still finishing")
        val lock = Any(); var api: TessBaseAPI? = null; var recognizing = false
        val jobToken = CancellationToken()
        val registration = token.onCancel { jobToken.cancel() }
        val uri = image.dataUri
        val future = try { worker.submit<Result> {
            var source: Bitmap? = null; var scaled: Bitmap? = null
            try {
                jobToken.throwIfCancelled()
                val root = models(context.applicationContext, jobToken)
                val bytes = Base64.decode(uri?.substringAfter(',') ?: error("PHONE_OCR_IMAGE_MISSING"), Base64.DEFAULT)
                source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("PHONE_OCR_IMAGE_DECODE")
                val bitmap = requireNotNull(source)
                val scale = minOf(1f, 1400f / maxOf(bitmap.width, bitmap.height))
                val input = if (scale < 1) Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
                    (bitmap.height * scale).toInt().coerceAtLeast(1), true).also { scaled = it } else bitmap
                jobToken.throwIfCancelled()
                val tess = TessBaseAPI { _ ->
                    if (jobToken.isCancelled) synchronized(lock) { runCatching { api?.stop() } }
                }
                synchronized(lock) { api = tess }
                check(tess.init(root.absolutePath, "chi_sim+eng", TessBaseAPI.OEM_LSTM_ONLY)) { "PHONE_OCR_INIT_FAILED" }
                tess.setPageSegMode(TessBaseAPI.PageSegMode.PSM_SPARSE_TEXT)
                tess.setImage(input)
                jobToken.throwIfCancelled()
                synchronized(lock) { jobToken.throwIfCancelled(); recognizing = true }
                tess.getHOCRText(0) // This recognition entry point supports stop(), unlike an unmonitored text read.
                synchronized(lock) { recognizing = false }
                jobToken.throwIfCancelled()
                val targets = ArrayList<PhoneVisual.Target>()
                var truncated = false; var characters = 0
                val iterator = tess.resultIterator
                if (iterator != null) try {
                    iterator.begin()
                    do {
                        jobToken.throwIfCancelled()
                        val level = TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE
                        PhoneVisual.normalized("v${targets.size}", iterator.getUTF8Text(level).orEmpty(),
                            iterator.getBoundingBox(level), input.width, input.height, iterator.confidence(level))?.let {
                            targets.add(it); characters += it.text.length
                        }
                        if (targets.size >= 100 || characters >= 12000) { truncated = true; break }
                    } while (iterator.next(TessBaseAPI.PageIteratorLevel.RIL_TEXTLINE))
                } finally { iterator.delete() }
                Result("OK", targets, truncated = truncated)
            } catch (error: Throwable) {
                Result(if (jobToken.isCancelled) "CANCELLED" else "ERROR", error = error.message ?: error.javaClass.simpleName)
            } finally {
                synchronized(lock) { recognizing = false; runCatching { api?.recycle() }; api = null }
                runCatching { scaled?.recycle() }; runCatching { source?.recycle() }; gate.release(); registration.close()
            }
        } } catch (error: Exception) { gate.release(); registration.close(); throw error }
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceIn(1, 5000)
        while (!future.isDone && !token.isCancelled && SystemClock.elapsedRealtime() < deadline) token.sleep(25)
        if (!future.isDone || token.isCancelled) {
            jobToken.cancel()
            synchronized(lock) { if (recognizing) runCatching { api?.stop() } }
            token.throwIfCancelled()
            return Result("TIMEOUT", error = "Native job cancelled; no partial targets published")
        }
        token.throwIfCancelled()
        return future.get(1, TimeUnit.SECONDS)
    }
}
