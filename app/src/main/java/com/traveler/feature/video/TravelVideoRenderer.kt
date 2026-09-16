package com.traveler.feature.video

import android.content.Context
import android.graphics.*
import androidx.exifinterface.media.ExifInterface
import com.traveler.core.model.MediaItem
import com.traveler.feature.map.renderer.PlaybackOverlayContent
import com.traveler.feature.map.renderer.TravelPlaybackState
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
        com.traveler.feature.map.threed.SceneGeometry(renderModel, timeline, emptyList()),
    private val streetCacheDirectory: java.io.File? = null,
    private val tripStartDateIso: String? = null
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
    private val mapScale=1.0

    fun renderGlFrame(surface: CodecInputSurface, bitmap: Bitmap, canvas: Canvas, width: Int, height: Int,
                      storySeconds: Float, ptsNs: Long) {
        surface.makeCurrent()
        val gl = glRenderer ?: com.traveler.feature.map.threed.TravelGlRenderer(context, sceneGeometry,
            com.traveler.feature.map.threed.StreetMapSession(context,cacheDirectory=streetCacheDirectory ?: java.io.File(context.cacheDir,"street_maps_v1")))
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
            canvas.drawText("Height estimated", width*.5f, height*.492f, subtitlePaint)
        }

        if (!playbackState.isTitleCardActive && !playbackState.isEndCardActive) renderPlaybackHeader(canvas, width, height, playbackState)

        // 2. Active Photo Moment Card Overlay (Top-Right / Side)
        playbackState.activePhoto?.let { photo ->
            renderPhotoOverlay(canvas, width, height, photo)
        }

        // 3. Title Card (First 2.0s)
        val titleCard = timeline.titleCard
        if (titleCard != null && storyTimeSeconds <= titleCard.durationSeconds) {
            val alpha = if (storyTimeSeconds > titleCard.durationSeconds - 0.5f) {
                ((titleCard.durationSeconds - storyTimeSeconds) / 0.5f).coerceIn(0f, 1f)
            } else 1.0f
            CelebrationCards.draw(canvas, width, height, titleCard.title, titleCard.dateRangeStr,
                listOf("MY TRAVEL DIARY 3D", "LET’S GO!"), false, storyTimeSeconds, alpha)
        }

        // 4. End Card (Last 2.5s)
        val endCard = timeline.endCard
        if (endCard != null && storyTimeSeconds >= totalSec - endCard.durationSeconds) {
            val elapsedEnd = storyTimeSeconds - (totalSec - endCard.durationSeconds)
            val alpha = (elapsedEnd / 0.5f).coerceIn(0f, 1f)
            CelebrationCards.draw(canvas, width, height, endCard.title, "A JOURNEY TO REMEMBER",
                listOf(endCard.totalDaysStr, endCard.totalDistanceStr, endCard.memoriesCountStr), true, elapsedEnd, alpha)
        }
        val credit=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE;textSize=width*.020f;typeface=Typeface.DEFAULT }
        val label="© OpenStreetMap contributors · Natural Earth"
        val textWidth=credit.measureText(label)
        canvas.drawRect(width*.025f,height-width*.063f,width*.045f+textWidth,height-width*.017f,cardBackgroundPaint)
        canvas.drawText(label,width*.035f,height-width*.031f,credit)

    }

    private fun fitText(paint: Paint, text: String, width: Float) {
        val measured = paint.measureText(text)
        if (measured > width) paint.textSize *= width / measured
    }

    private fun drawFittedText(canvas: Canvas, text: String, x: Float, y: Float, width: Float, paint: Paint) {
        val originalSize = paint.textSize
        fitText(paint, text, width)
        canvas.drawText(text, x, y, paint)
        paint.textSize = originalSize
    }

    private fun renderPlaybackHeader(canvas: Canvas, width: Int, height: Int, state: TravelPlaybackState) {
        val unit = minOf(width, height) / 360f
        val margin = 8 * unit
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; typeface = Typeface.DEFAULT_BOLD; textSize = 11 * unit }
        val date = PlaybackOverlayContent.date(state, timeline)
        val dateWidth = text.measureText(date) + 16 * unit
        val dateLeft = width - margin - dateWidth
        val cardWidth = minOf(200 * unit, dateLeft - 2 * margin)
        canvas.drawRoundRect(margin, margin, margin + cardWidth, margin + 48 * unit, 8 * unit, 8 * unit, cardBackgroundPaint)
        val mode = "${state.currentTransportMode.emoji} ${PlaybackOverlayContent.mode(state)}"
        fitText(text, mode, cardWidth - 16 * unit)
        canvas.drawText(mode, margin + 8 * unit, margin + 16 * unit, text)
        text.textSize = 10 * unit; text.color = 0xFF72D6F6.toInt()
        val distance = PlaybackOverlayContent.distance(state)
        fitText(text, distance, cardWidth - 16 * unit)
        canvas.drawText(distance, margin + 8 * unit, margin + 32 * unit, text)
        val fraction = (state.currentTraveledDistanceMeters / maxOf(1.0, state.totalTripDistanceMeters)).toFloat().coerceIn(0f, 1f)
        text.color = 0xFF536172.toInt()
        canvas.drawRect(margin + 8 * unit, margin + 40 * unit, margin + cardWidth - 8 * unit, margin + 42 * unit, text)
        text.color = 0xFF45CDB5.toInt()
        canvas.drawRect(margin + 8 * unit, margin + 40 * unit, margin + 8 * unit + (cardWidth - 16 * unit) * fraction, margin + 42 * unit, text)
        canvas.drawRoundRect(dateLeft, margin, width - margin, margin + 40 * unit, 8 * unit, 8 * unit, cardBackgroundPaint)
        text.color = Color.WHITE; text.textSize = 11 * unit
        canvas.drawText(date, dateLeft + 8 * unit, margin + 16 * unit, text)
        text.color = 0xFFCBD5E1.toInt(); text.textSize = 10 * unit
        canvas.drawText(PlaybackOverlayContent.day(state, timeline, tripStartDateIso), dateLeft + 8 * unit, margin + 32 * unit, text)
    }

    private fun renderPhotoOverlay(
        canvas: Canvas, width: Int, height: Int, photo: MediaItem
    ) {
        val unit = minOf(width, height) / 360f
        val maxWidth = minOf(width * .38f, 180 * unit)
        val maxHeight = minOf(height * .30f, 180 * unit)
        val bitmap = getOrDecodePhotoBitmap(photo, maxWidth.toInt(), maxHeight.toInt()) ?: return
        val fitted = PlaybackOverlayContent.fitPhoto(bitmap.width.toFloat(), bitmap.height.toFloat(), maxWidth, maxHeight)
        val padding = 4 * unit
        val cardWidth = fitted.width + padding * 2
        val cardHeight = fitted.height + padding * 2
        val left = width - cardWidth - 8 * unit
        val top = 64 * unit
        val rect = RectF(left, top, left + cardWidth, top + cardHeight)
        canvas.drawRoundRect(rect, 8 * unit, 8 * unit, cardBackgroundPaint)
        val imgRect = RectF(left + padding, top + padding, left + padding + fitted.width, top + padding + fitted.height)
        // Full upright bitmap, with an aspect-matched destination. No clipping of photo corners.
        canvas.drawBitmap(bitmap, null, imgRect, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG))

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
