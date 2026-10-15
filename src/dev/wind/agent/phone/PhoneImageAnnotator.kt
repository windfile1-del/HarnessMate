package dev.wind.agent.phone

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Matrix
import android.util.Base64
import dev.wind.agent.Message
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream

/** Image-only transforms; region coordinates always refer to the full 0..1000 screen. */
internal object PhoneImageAnnotator {
    fun validate(args: JSONObject, mode: PhoneAutomation.Mode) {
        require(mode != PhoneAutomation.Mode.UI || !args.optBoolean("marks") && !args.optBoolean("ocr")) { "PHONE_IMAGE_OPTIONS_REQUIRE_VISUAL" }
        require(mode != PhoneAutomation.Mode.UI || !args.optBoolean("grid") && !args.has("region")) { "PHONE_IMAGE_OPTIONS_REQUIRE_VISUAL" }
        args.optJSONArray("region")?.let { r ->
            require(r.length() == 4 && (0..3).all { r.getDouble(it).isFinite() && r.getDouble(it) in 0.0..1000.0 } &&
                r.getDouble(0) < r.getDouble(2) && r.getDouble(1) < r.getDouble(3)) { "PHONE_REGION_INVALID" }
        }
        require(!args.has("region") || args.optJSONArray("region") != null) { "PHONE_REGION_INVALID" }
    }
    fun annotate(image: Message.Attachment, observation: PhoneControlService.Observation, args: JSONObject): Message.Attachment {
        validate(args, observation.mode)
        if (!args.optBoolean("marks") && !args.optBoolean("grid") && !args.has("region")) return image
        val uri = image.dataUri ?: error("PHONE_IMAGE_DATA_MISSING")
        val bytes = Base64.decode(uri.substringAfter(','), Base64.DEFAULT)
        val source = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: error("PHONE_IMAGE_DECODE_FAILED")
        var output: Bitmap? = null
        try {
            val region = args.optJSONArray("region") ?: JSONArray(listOf(0, 0, 1000, 1000))
            val left = region.getDouble(0).toFloat(); val top = region.getDouble(1).toFloat()
            val right = region.getDouble(2).toFloat(); val bottom = region.getDouble(3).toFloat()
            val crop = RectF(left / 1000 * source.width, top / 1000 * source.height, right / 1000 * source.width, bottom / 1000 * source.height)
            val scale = if (args.has("region")) 1024f / maxOf(crop.width(), crop.height()) else 1f
            val width = (crop.width() * scale).toInt().coerceIn(1, 2048)
            val height = (crop.height() * scale).toInt().coerceIn(1, 2048)
            val rendered = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888); output = rendered
            val transform = Matrix().apply { setRectToRect(crop, RectF(0f, 0f, width.toFloat(), height.toFloat()), Matrix.ScaleToFit.FILL) }
            val canvas = Canvas(rendered); canvas.drawBitmap(source, transform, Paint(Paint.FILTER_BITMAP_FLAG))
            fun x(normalized: Float) = (normalized - left) / (right - left) * width
            fun y(normalized: Float) = (normalized - top) / (bottom - top) * height
            val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.CYAN; strokeWidth = 2f; style = Paint.Style.STROKE }
            val letters = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 22f }
            val background = Paint().apply { color = Color.argb(205, 0, 0, 0) }
            fun label(value: String, px: Float, py: Float) {
                val lx = px.coerceIn(0f, (width - letters.measureText(value) - 8).coerceAtLeast(0f))
                val ly = py.coerceIn(24f.coerceAtMost(height.toFloat()), height.toFloat())
                canvas.drawRect(lx, ly - 23, lx + letters.measureText(value) + 7, ly + 4, background)
                canvas.drawText(value, lx + 3, ly, letters)
            }
            if (args.optBoolean("grid")) {
                for (step in 0..1000 step 100) {
                    if (step.toFloat() in left..right) { val px = x(step.toFloat()); canvas.drawLine(px, 0f, px, height.toFloat(), stroke); label(step.toString(), px + 2, 23f) }
                    if (step.toFloat() in top..bottom) { val py = y(step.toFloat()); canvas.drawLine(0f, py, width.toFloat(), py, stroke); label(step.toString(), 2f, py + 22) }
                }
            }
            if (args.optBoolean("marks")) observation.nodes.entries.filter {
                it.value.facts.let { f -> f.actionable && (f.clickable || f.editable || f.scrollable) }
            }.take(60).forEach { (id, node) ->
                val f = node.facts
                val box = RectF(x(f.left * 1000f / observation.width), y(f.top * 1000f / observation.height),
                    x(f.right * 1000f / observation.width), y(f.bottom * 1000f / observation.height))
                if (box.right >= 0 && box.bottom >= 0 && box.left < width && box.top < height) {
                    canvas.drawRect(box, stroke); label(id.removePrefix("n"), box.left, box.top + 23)
                }
            }
            if (args.optBoolean("marks")) observation.visualTargets.values.take(60).forEach { target ->
                val box = RectF(x(target.left.toFloat()), y(target.top.toFloat()), x(target.right.toFloat()), y(target.bottom.toFloat()))
                if (box.right >= 0 && box.bottom >= 0 && box.left < width && box.top < height) {
                    canvas.drawRect(box, stroke); label(target.id, box.left, box.top + 23)
                }
            }
            val encoded = ByteArrayOutputStream().use { stream ->
                check(rendered.compress(Bitmap.CompressFormat.JPEG, 88, stream)) { "PHONE_IMAGE_ENCODE_FAILED" }; stream.toByteArray()
            }
            observation.json.put("image_width", width).put("image_height", height).put("image_region", region)
                .put("image_coordinate_space", "full_screen_normalized_0_1000").put("marks", args.optBoolean("marks")).put("grid", args.optBoolean("grid"))
            return Message.Attachment("phone-view.jpg", null, "image/jpeg", encoded.size.toLong(), "image").also {
                it.dataUri = "data:image/jpeg;base64," + Base64.encodeToString(encoded, Base64.NO_WRAP)
            }
        } finally { output?.recycle(); source.recycle() }
    }
}
