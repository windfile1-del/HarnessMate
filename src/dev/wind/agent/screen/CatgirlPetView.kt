package dev.wind.agent.screen

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.os.SystemClock
import android.view.View
import org.json.JSONObject
import kotlin.math.sin

/** Registered task and companion atlases; four poses per state, transparent outside the character. */
internal class CatgirlPetView(context: Context) : View(context) {
    private data class Frame(val bounds: Rect, val anchorX: Float, val baselineY: Float)
    private data class Atlas(val image: Bitmap, val frames: List<Frame>, val unit: Float)
    private fun loadAtlas(character: PetAppearance.Character, imageName: String, metadataName: String): Atlas? {
        var image: Bitmap? = null
        return try {
            val bitmap = context.assets.open("desktop-pet/${character.assetDirectory}/$imageName").use { BitmapFactory.decodeStream(it) }
                ?: return null
            image = bitmap
            val metadata = context.assets.open("desktop-pet/${character.assetDirectory}/$metadataName").bufferedReader().use { JSONObject(it.readText()) }
            require(metadata.getInt("width") == bitmap.width && metadata.getInt("height") == bitmap.height)
            val entries = metadata.getJSONArray("frames")
            require(entries.length() == 32)
            val frames = List(32) { index ->
                val entry = entries.getJSONObject(index)
                require(entry.getInt("row") == index / 4 && entry.getInt("column") == index % 4)
                val bounds = Rect(entry.getInt("left"), entry.getInt("top"), entry.getInt("right"), entry.getInt("bottom"))
                require(bounds.width() > 0 && bounds.height() > 0 && bounds.left >= 0 && bounds.top >= 0 &&
                    bounds.right <= bitmap.width && bounds.bottom <= bitmap.height)
                Frame(bounds, entry.getDouble("anchorX").toFloat(), entry.getDouble("baselineY").toFloat())
            }
            Atlas(bitmap, frames, frames.maxOf { it.baselineY - it.bounds.top }.coerceAtLeast(1f))
        } catch (_: Exception) {
            image?.recycle(); null
        }
    }
    private var taskAtlas: Atlas? = null
    private var companionAtlas: Atlas? = null
    private var atlases = emptyList<Atlas>()
    private var attemptedAppearance: PetAppearance.Character? = null
    // Shared normalized scale, including across image resolutions. Never scale each pose by its tail width.
    private var horizontalExtent = 1f
    private var belowBaseline = 0f
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
    private val source = Rect()
    private val destination = RectF()
    private var state = PetActivity.State.IDLE
    private var startedAt = SystemClock.uptimeMillis()
    private var interactionUntil = 0L
    private var released = false
    private val tick = object : Runnable {
        override fun run() {
            if (released || !isAttachedToWindow || !isShown) return
            invalidate()
            postDelayed(this, 80L)
        }
    }

    init {
        val selected = PetAppearance.selected(context)
        if (!setAppearance(selected)) {
            setAppearance(PetAppearance.Character.CATGIRL)
            attemptedAppearance = selected
        }
    }

    /** Called on the view thread. Commit both atlases together, then release the old bitmaps. */
    fun setAppearance(character: PetAppearance.Character): Boolean {
        if (released) return false
        attemptedAppearance = character
        val task = loadAtlas(character, "sprites.png", "anchors.json")
        val companion = loadAtlas(character, "companion-sprites.png", "companion-anchors.json")
        if (task == null || companion == null) {
            task?.image?.recycle(); companion?.image?.recycle()
            return false
        }
        val previous = atlases
        taskAtlas = task; companionAtlas = companion; atlases = listOf(task, companion)
        horizontalExtent = atlases.flatMap { atlas -> atlas.frames.map {
            maxOf(it.anchorX - it.bounds.left, it.bounds.right - it.anchorX) / atlas.unit
        } }.maxOrNull() ?: 1f
        belowBaseline = atlases.flatMap { atlas -> atlas.frames.map {
            (it.bounds.bottom - it.baselineY) / atlas.unit
        } }.maxOrNull() ?: 0f
        startedAt = SystemClock.uptimeMillis(); interactionUntil = 0L
        previous.forEach { it.image.recycle() }
        invalidate()
        return true
    }

    fun syncAppearance() {
        val selected = PetAppearance.selected(context)
        if (selected != attemptedAppearance && !setAppearance(selected)) {
            android.widget.Toast.makeText(context, ScreenStrings.appearanceFailed, android.widget.Toast.LENGTH_SHORT).show()
        }
    }

    fun setState(next: PetActivity.State, label: String) {
        contentDescription = "$label · ${ScreenStrings.petHint}"
        if (next != state) { state = next; startedAt = SystemClock.uptimeMillis(); invalidate() }
    }

    fun interact() { interactionUntil = SystemClock.uptimeMillis() + 1800L; invalidate() }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); removeCallbacks(tick); post(tick) }
    override fun onDetachedFromWindow() { removeCallbacks(tick); super.onDetachedFromWindow() }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        // Called even during View construction; only schedule once attached.
        if (!isAttachedToWindow) return
        removeCallbacks(tick)
        if (isShown && !released) post(tick)
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (!isAttachedToWindow) return
        removeCallbacks(tick)
        if (visibility == VISIBLE && isShown && !released) post(tick)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val now = SystemClock.uptimeMillis()
        val reacting = now < interactionUntil
        val requestedRow = if (reacting) PetActivity.State.HAPPY.row else state.row
        val atlas = (if (requestedRow >= 8) companionAtlas else taskAtlas) ?: taskAtlas
        if (atlas == null || atlas.image.isRecycled) {
            // Graceful asset-load failure, not a substitute for the bundled generated artwork.
            paint.color = Color.WHITE; paint.textSize = height * 0.5f
            paint.setShadowLayer(3f, 0f, 1f, Color.BLACK)
            canvas.drawText("V", width * 0.3f, height * 0.7f, paint)
            paint.clearShadowLayer()
            return
        }
        val row = if (requestedRow >= 8 && atlas === companionAtlas) requestedRow - 8
            else if (requestedRow >= 8) 0 else requestedRow
        val step = if (reacting) 230L else when (state) {
            PetActivity.State.READING, PetActivity.State.STUDYING -> 480L
            PetActivity.State.WATCHING, PetActivity.State.BROWSING -> 360L
            PetActivity.State.GAMING -> 190L
            PetActivity.State.IDLE -> 340L
            else -> 230L
        }
        val frame = ((now - startedAt) / step % 4L).toInt()
        // The generated atlas is NOT a uniform grid: some tails and heads cross nominal cell edges.
        val pose = atlas.frames[row * 4 + frame]
        source.set(pose.bounds)
        val unitScale = minOf(width * 0.94f / (horizontalExtent * 2f), height * 0.90f / (1f + belowBaseline))
        val scale = unitScale / atlas.unit
        val bounce = if (reacting) sin((now % 550L) / 550.0 * Math.PI * 2).toFloat() * height * 0.035f
            else 0f
        val baseline = height * 0.95f - belowBaseline * unitScale + bounce
        destination.set(width / 2f + (source.left - pose.anchorX) * scale,
            baseline + (source.top - pose.baselineY) * scale,
            width / 2f + (source.right - pose.anchorX) * scale,
            baseline + (source.bottom - pose.baselineY) * scale)
        canvas.drawBitmap(atlas.image, source, destination, paint)
    }

    fun release() { released = true; removeCallbacks(tick); atlases.forEach { it.image.recycle() } }
}
