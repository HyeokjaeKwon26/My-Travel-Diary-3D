package com.traveler.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import com.traveler.core.model.MediaItem

/** A bounded, upright still image for both gallery photos and video memories. */
object MediaBitmapDecoder {
    fun decode(context: Context, media: MediaItem, width: Int, height: Int): Bitmap? = try {
        val uri = Uri.parse(media.contentUriString)
        if (media.isVideo) video(context, uri, width, height) else photo(context, uri, width, height)
    } catch (_: Exception) { null }

    private fun video(context: Context, uri: Uri, width: Int, height: Int): Bitmap? {
        val retriever = MediaMetadataRetriever()
        try {
            retriever.setDataSource(context, uri)
            // The platform applies video rotation metadata. Do not rotate this bitmap again.
            val frame = if (Build.VERSION.SDK_INT >= 27) {
                retriever.getScaledFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    width.coerceAtLeast(1), height.coerceAtLeast(1))
            } else retriever.getFrameAtTime(0, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
            return frame?.let { fit(it, width, height) }
        } finally { retriever.release() }
    }

    private fun photo(context: Context, uri: Uri, width: Int, height: Int): Bitmap? {
        if (uri.scheme == "android.resource" && uri.authority == context.packageName) {
            val id = when (uri.lastPathSegment) {
                "demo_boston" -> com.traveler.R.drawable.demo_boston
                "demo_flight" -> com.traveler.R.drawable.demo_flight
                "demo_niagara" -> com.traveler.R.drawable.demo_niagara
                "demo_unassigned" -> com.traveler.R.drawable.demo_unassigned
                else -> uri.lastPathSegment?.toIntOrNull() ?: 0
            }
            if (id != 0) {
                val drawable = context.resources.getDrawable(id, context.theme)
                val w = drawable.intrinsicWidth.coerceAtLeast(1)
                val h = drawable.intrinsicHeight.coerceAtLeast(1)
                val scale = minOf(1.0, width.coerceAtLeast(1).toDouble() / w, height.coerceAtLeast(1).toDouble() / h)
                return Bitmap.createBitmap((w * scale).toInt().coerceAtLeast(1), (h * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888).also {
                    drawable.setBounds(0, 0, it.width, it.height)
                    drawable.draw(android.graphics.Canvas(it))
                }
            }
        }
        val resolver = context.contentResolver
        val exif = runCatching { resolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth <= 0 || options.outHeight <= 0) return null
        val rotated = exif?.rotationDegrees in listOf(90, 270)
        val w = if (rotated) options.outHeight else options.outWidth
        val h = if (rotated) options.outWidth else options.outHeight
        options.inSampleSize = 1
        while (w / (options.inSampleSize * 2) >= width.coerceAtLeast(1) ||
            h / (options.inSampleSize * 2) >= height.coerceAtLeast(1)) options.inSampleSize *= 2
        options.inJustDecodeBounds = false
        var bitmap = resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return null
        if (exif != null && (exif.rotationDegrees != 0 || exif.isFlipped)) {
            val matrix = Matrix().apply {
                if (exif.isFlipped) postScale(-1f, 1f)
                postRotate(exif.rotationDegrees.toFloat())
            }
            val upright = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
            if (upright !== bitmap) bitmap.recycle()
            bitmap = upright
        }
        return fit(bitmap, width, height)
    }

    private fun fit(bitmap: Bitmap, width: Int, height: Int): Bitmap {
        val scale = minOf(1.0, width.coerceAtLeast(1).toDouble() / bitmap.width,
            height.coerceAtLeast(1).toDouble() / bitmap.height)
        if (scale >= 1.0) return bitmap
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * scale).toInt().coerceAtLeast(1),
            (bitmap.height * scale).toInt().coerceAtLeast(1), true).also { if (it !== bitmap) bitmap.recycle() }
    }
}
