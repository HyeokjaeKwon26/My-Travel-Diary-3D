package com.traveler.feature.video

import android.content.Context
import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import com.traveler.core.model.MediaItem
import com.traveler.feature.map.renderer.RegionalBasemapCache
import com.traveler.feature.map.renderer.SafeContentInsets
import com.traveler.feature.map.renderer.TravelMapRenderModel
import com.traveler.feature.map.renderer.TravelMapRenderer
import com.traveler.feature.map.story.StoryEndCard
import com.traveler.feature.map.story.StoryTitleCard
import com.traveler.feature.map.story.TravelStoryTimeline
import java.io.InputStream

/**
 * Shared GPU scene with a transparent Canvas photo/title overlay.
 *
 * Renders pure, cinematic 1080x1920 (9:16 vertical) frames directly to a native [Canvas]
 * driven by the deterministic [TravelStoryTimeline].
 *
 * Invariants:
 * 1. Clean Video: Zero Android UI chrome (no status bar, nav bar, buttons, or dialogs).
 * 2. Privacy Protection: Generalizes private home/street addresses when requested.
 * 3. Deterministic Frame Evaluation: Rendering at timestamp T is pure and reproducible.
 */
class TravelVideoRenderer(
    private val context: Context,
    basemapStream: InputStream,
    private val renderModel: TravelMapRenderModel,
    private val timeline: TravelStoryTimeline,
    private val generalizeHomeAddress: Boolean = true,
    private val sceneGeometry: com.traveler.feature.map.threed.SceneGeometry =
        com.traveler.feature.map.threed.SceneGeometry(renderModel, timeline, emptyList())
) {
    private val mapRenderer = TravelMapRenderer(basemapStream).apply {
        RegionalBasemapCache.preparedRegionalBasemap?.let { setPreparedRegionalBasemap(it) }
    }
    private val photoBitmapCache = object : android.util.LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.allocationByteCount
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (oldValue !== newValue) oldValue.recycle()
        }
    }
    private var glRenderer: com.traveler.feature.map.threed.TravelGlRenderer? = null
    private val calmCamera = true
    private val mapScale=context.getSharedPreferences("scene_preferences",0).getFloat("mapScale",1f).toDouble()

    fun renderGlFrame(surface: CodecInputSurface, bitmap: Bitmap, canvas: Canvas, width: Int, height: Int,
                      storySeconds: Float, ptsNs: Long) {
        surface.makeCurrent()
        val gl = glRenderer ?: com.traveler.feature.map.threed.TravelGlRenderer(context, sceneGeometry)
            .also { it.initialize(); glRenderer = it }
        gl.render(width, height, timeline.evaluateAtStoryTime(storySeconds), calmCamera,mapScale)
        canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
        renderFrame(canvas, width, height, storySeconds, renderMap = false)
        surface.drawFrame(bitmap, ptsNs, clear = false)
    }

    fun loadRegionalBasemap(stream: InputStream) {
        mapRenderer.loadRegionalBasemap(stream)
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        textAlign = Paint.Align.CENTER
    }

    private val subtitlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFE2E8F0.toInt()
        typeface = Typeface.DEFAULT
        textAlign = Paint.Align.CENTER
    }

    private val cardBackgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(220, 15, 23, 42) // Slate 900 with alpha
    }

    private val cardBorderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(120, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = 3f
    }

    /**
     * Renders a single deterministic offscreen frame at [storyTimeSeconds].
     */
    fun renderFrame(
        canvas: Canvas,
        width: Int,
        height: Int,
        storyTimeSeconds: Float,
        renderMap: Boolean = true
    ) {
        val totalSec = maxOf(0.001f, timeline.totalStoryDurationSeconds)
        val progress = (storyTimeSeconds / totalSec).coerceIn(0f, 1f)
        val playbackState = timeline.evaluateAtStoryTime(storyTimeSeconds, progress)

        // 1. Render base map, routes, and traveler marker with vertical safe insets
        val insets = SafeContentInsets(
            left = width * 0.05f,
            top = height * 0.08f,
            right = width * 0.05f,
            bottom = height * 0.12f
        )

        if (renderMap) mapRenderer.render(
            canvas = canvas,
            width = width,
            height = height,
            renderModel = renderModel,
            playbackState = playbackState,
            insets = insets
        )

        if (!renderMap && sceneGeometry.uncertain(playbackState)) {
            subtitlePaint.textSize = width * .026f
            canvas.drawRoundRect(width*.12f, height*.46f, width*.88f, height*.51f, 16f, 16f, cardBackgroundPaint)
            canvas.drawText("Elevation uncertain • vehicle hidden", width*.5f, height*.492f, subtitlePaint)
        }

        // 2. Active Photo Moment Card Overlay (Top-Right / Side)
        playbackState.activePhoto?.let { photo ->
            renderPhotoOverlay(canvas, width, height, photo, playbackState.currentVisit?.placeName)
        }

        // 3. Title Card (First 2.0s)
        val titleCard = timeline.titleCard
        if (titleCard != null && storyTimeSeconds <= titleCard.durationSeconds) {
            val alpha = if (storyTimeSeconds > titleCard.durationSeconds - 0.5f) {
                ((titleCard.durationSeconds - storyTimeSeconds) / 0.5f).coerceIn(0f, 1f)
            } else 1.0f
            renderTitleCardOverlay(canvas, width, height, titleCard, alpha)
        }

        // 4. End Card (Last 2.5s)
        val endCard = timeline.endCard
        if (endCard != null && storyTimeSeconds >= totalSec - endCard.durationSeconds) {
            val elapsedEnd = storyTimeSeconds - (totalSec - endCard.durationSeconds)
            val alpha = (elapsedEnd / 0.5f).coerceIn(0f, 1f)
            renderEndCardOverlay(canvas, width, height, endCard, alpha)
        }
        val credit=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE;textSize=width*.020f;typeface=Typeface.DEFAULT }
        val label="© OpenStreetMap contributors · Natural Earth"
        val textWidth=credit.measureText(label)
        canvas.drawRect(width*.025f,height-width*.063f,width*.045f+textWidth,height-width*.017f,cardBackgroundPaint)
        canvas.drawText(label,width*.035f,height-width*.031f,credit)

    }

    private fun renderPhotoOverlay(
        canvas: Canvas,
        width: Int,
        height: Int,
        photo: MediaItem,
        placeName: String?
    ) {
        val cardWidth = width * 0.38f
        val cardHeight = height * 0.22f
        val cardLeft = width - cardWidth - (width * 0.04f)
        val cardTop = height * 0.08f
        val rect = RectF(cardLeft, cardTop, cardLeft + cardWidth, cardTop + cardHeight)

        // Card shadow & background
        canvas.drawRoundRect(rect, 20f, 20f, cardBackgroundPaint)
        canvas.drawRoundRect(rect, 20f, 20f, cardBorderPaint)

        // Load & draw cached bitmap
        val bitmap = getOrDecodePhotoBitmap(photo, (cardWidth * 1.5f).toInt(), (cardHeight * 1.2f).toInt())
        if (bitmap != null) {
            val imgPadding = 8f
            val imgRect = RectF(
                rect.left + imgPadding,
                rect.top + imgPadding,
                rect.right - imgPadding,
                rect.bottom - (cardHeight * 0.22f)
            )

            val path = Path().apply {
                addRoundRect(imgRect, 14f, 14f, Path.Direction.CW)
            }
            canvas.save()
            canvas.clipPath(path)

            // Center-crop source calculation to prevent photo distortion
            val targetAspect = imgRect.width() / maxOf(1f, imgRect.height())
            val bmpAspect = bitmap.width.toFloat() / maxOf(1f, bitmap.height.toFloat())
            val src = when {
                bmpAspect > targetAspect -> {
                    val cropW = (bitmap.height * targetAspect).toInt()
                    val cropLeft = (bitmap.width - cropW) / 2
                    Rect(cropLeft, 0, cropLeft + cropW, bitmap.height)
                }
                bmpAspect < targetAspect -> {
                    val cropH = (bitmap.width / targetAspect).toInt()
                    val cropTop = (bitmap.height - cropH) / 2
                    Rect(0, cropTop, bitmap.width, cropTop + cropH)
                }
                else -> Rect(0, 0, bitmap.width, bitmap.height)
            }

            val photoPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            canvas.drawBitmap(bitmap, src, imgRect, photoPaint)
            canvas.restore()
        }

        // Label (Place name or contextual date, NOT raw filename)
        val label = placeName ?: photo.assignedDayIso ?: "Photo Moment"
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = cardHeight * 0.10f
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            textAlign = Paint.Align.CENTER
        }
        val textY = rect.bottom - (cardHeight * 0.07f)
        canvas.drawText(label, rect.centerX(), textY, textPaint)
    }

    private fun renderTitleCardOverlay(
        canvas: Canvas,
        width: Int,
        height: Int,
        card: StoryTitleCard,
        alphaFraction: Float
    ) {
        val cardWidth = width * 0.84f
        val cardHeight = height * 0.24f
        val rect = RectF(
            (width - cardWidth) / 2f,
            height * 0.35f,
            (width + cardWidth) / 2f,
            height * 0.35f + cardHeight
        )

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((230 * alphaFraction).toInt(), 15, 23, 42)
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((180 * alphaFraction).toInt(), 96, 165, 250)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        canvas.drawRoundRect(rect, 32f, 32f, bgPaint)
        canvas.drawRoundRect(rect, 32f, 32f, border)

        titlePaint.apply {
            textSize = cardHeight * 0.22f
            color = Color.argb((255 * alphaFraction).toInt(), 255, 255, 255)
        }
        subtitlePaint.apply {
            textSize = cardHeight * 0.14f
            color = Color.argb((220 * alphaFraction).toInt(), 226, 232, 240)
        }

        canvas.drawText(card.title, rect.centerX(), rect.top + cardHeight * 0.36f, titlePaint)
        canvas.drawText(card.dateRangeStr, rect.centerX(), rect.top + cardHeight * 0.62f, subtitlePaint)
        canvas.drawText(card.subtitle, rect.centerX(), rect.top + cardHeight * 0.82f, subtitlePaint)
    }

    private fun renderEndCardOverlay(
        canvas: Canvas,
        width: Int,
        height: Int,
        card: StoryEndCard,
        alphaFraction: Float
    ) {
        val cardWidth = width * 0.84f
        val cardHeight = height * 0.28f
        val rect = RectF(
            (width - cardWidth) / 2f,
            height * 0.32f,
            (width + cardWidth) / 2f,
            height * 0.32f + cardHeight
        )

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((235 * alphaFraction).toInt(), 15, 23, 42)
        }
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb((180 * alphaFraction).toInt(), 251, 191, 36)
            style = Paint.Style.STROKE
            strokeWidth = 4f
        }

        canvas.drawRoundRect(rect, 32f, 32f, bgPaint)
        canvas.drawRoundRect(rect, 32f, 32f, border)

        titlePaint.apply {
            textSize = cardHeight * 0.18f
            color = Color.argb((255 * alphaFraction).toInt(), 255, 255, 255)
        }
        subtitlePaint.apply {
            textSize = cardHeight * 0.13f
            color = Color.argb((230 * alphaFraction).toInt(), 241, 245, 249)
        }

        canvas.drawText(card.title, rect.centerX(), rect.top + cardHeight * 0.28f, titlePaint)
        canvas.drawText("${card.totalDaysStr} · ${card.totalDistanceStr}", rect.centerX(), rect.top + cardHeight * 0.52f, subtitlePaint)
        canvas.drawText("${card.memoriesCountStr} · My Travel Diary 3D", rect.centerX(), rect.top + cardHeight * 0.74f, subtitlePaint)
    }

    private fun getOrDecodePhotoBitmap(photo: MediaItem, reqWidth: Int, reqHeight: Int): Bitmap? {
        photoBitmapCache.get(photo.id)?.let { return it }
        return try {
            val uri = android.net.Uri.parse(photo.contentUriString)

            // 1. Inspect EXIF orientation metadata
            var rotationDegrees = 0
            var isFlipped = false
            try {
                context.contentResolver.openInputStream(uri)?.use { stream ->
                    val exif = ExifInterface(stream)
                    rotationDegrees = exif.rotationDegrees
                    isFlipped = exif.isFlipped
                }
            } catch (_: Exception) {}

            // 2. Determine sample size based on effective (possibly rotated) dimensions
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            }
            val isSwapped = (rotationDegrees == 90 || rotationDegrees == 270)
            val effectiveWidth = if (isSwapped) options.outHeight else options.outWidth
            val effectiveHeight = if (isSwapped) options.outWidth else options.outHeight
            options.inSampleSize = calculateInSampleSize(effectiveWidth, effectiveHeight, reqWidth, reqHeight)
            options.inJustDecodeBounds = false

            // 3. Decode sampled bitmap
            val rawBmp = context.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, options)
            } ?: return null

            // 4. Rotate/flip to correct upright orientation if needed
            val finalBmp = if (rotationDegrees != 0 || isFlipped) {
                val matrix = Matrix()
                if (isFlipped) {
                    matrix.postScale(-1f, 1f)
                }
                if (rotationDegrees != 0) {
                    matrix.postRotate(rotationDegrees.toFloat())
                }
                val rotated = Bitmap.createBitmap(rawBmp, 0, 0, rawBmp.width, rawBmp.height, matrix, true)
                if (rotated != rawBmp) {
                    rawBmp.recycle()
                }
                rotated
            } else {
                rawBmp
            }

            photoBitmapCache.put(photo.id, finalBmp)
            finalBmp
        } catch (_: Exception) {
            null
        }
    }

    private fun calculateInSampleSize(width: Int, height: Int, reqWidth: Int, reqHeight: Int): Int {
        var inSampleSize = 1
        if (height > reqHeight || width > reqWidth) {
            val halfHeight = height / 2
            val halfWidth = width / 2
            while (halfHeight / inSampleSize >= reqHeight && halfWidth / inSampleSize >= reqWidth) {
                inSampleSize *= 2
            }
        }
        return inSampleSize
    }

    fun release() {
        photoBitmapCache.evictAll()
        glRenderer?.release()
        glRenderer = null
    }
}
