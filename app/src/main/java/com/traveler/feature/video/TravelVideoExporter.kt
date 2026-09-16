package com.traveler.feature.video

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.traveler.core.model.Trip
import com.traveler.feature.map.renderer.TravelMapRenderModel
import com.traveler.core.media.StoryDurationProfile
import com.traveler.feature.map.story.StoryEndCard
import com.traveler.feature.map.story.StoryTitleCard
import com.traveler.feature.map.story.TravelStoryTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDate

/**
 * High-level Video Export Coordinator (P1).
 *
 * Coordinates timeline compilation, offscreen frame rendering, native MP4 encoding,
 * saving to Android MediaStore, and sharing via the standard Android Sharesheet.
 *
 * Invariants:
 * 1. 100% Offline: Operates entirely locally in Airplane mode with ZERO network access.
 * 2. Privacy-Safe: Generalizes private home/street address labels in exported videos.
 * 3. Atomic Export: Partial or failed exports are immediately deleted.
 */
object TravelVideoExporter {

    private const val EXPORT_DIR_NAME = "exports"

    fun getExportTempDir(context: Context): File {
        val dir = File(context.cacheDir, EXPORT_DIR_NAME)
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    /**
     * Builds the deterministic [TravelStoryTimeline] for video export with title and end cards.
     */
    fun buildExportTimeline(
        trip: Trip,
        renderModel: TravelMapRenderModel,
        profile: StoryDurationProfile
    ): TravelStoryTimeline {
        val totalDays = trip.days.size
        val totalDistanceKm = String.format(java.util.Locale.US, "%.1f km", trip.totalDistanceMeters / 1000.0)
        val memoriesCount = "${trip.totalMediaCount} Memories"

        val titleCard = StoryTitleCard(
            title = trip.title,
            dateRangeStr = "${trip.startDateIso} — ${trip.endDateIso}",
            subtitle = "My Travel Diary 3D",
            durationSeconds = 2.0f
        )

        val endCard = StoryEndCard(
            title = trip.title,
            totalDaysStr = "$totalDays Days",
            totalDistanceStr = totalDistanceKm,
            memoriesCountStr = memoriesCount,
            durationSeconds = 2.5f
        )

        return TravelStoryTimeline.build(
            renderModel = renderModel,
            profile = profile,
            titleCard = titleCard,
            endCard = endCard
        )
    }

    /**
     * Executes video export to a temporary MP4 file in the app cache.
     */
    suspend fun exportVideo(
        context: Context,
        trip: Trip,
        renderModel: TravelMapRenderModel,
        profile: StoryDurationProfile,
        includeMusic: Boolean = true,
        resolution: VideoResolution = VideoResolution.FULL_HD,
        generalizeHomeAddress: Boolean = true,
        encoderRef: ((TravelVideoEncoder) -> Unit)? = null,
        onProgress: ((Float) -> Unit)? = null
    ): File? = withContext(Dispatchers.IO) {
        val safeModel = if (generalizeHomeAddress) com.traveler.feature.video.ExportPrivacy.generalize(renderModel) else renderModel
        val safeTrip = if (generalizeHomeAddress) trip.copy(title = com.traveler.feature.video.ExportPrivacy.label(trip.title)) else trip
        val timeline = buildExportTimeline(safeTrip, safeModel, profile)
        val terrain = com.traveler.core.terrain.JourneyTerrain.load(context,renderModel)
        val frozenMaps=ExportMapSnapshot.create(context) { onProgress?.invoke(it*.03f) }
        try {
        val basemapStream = context.assets.open("basemap_world.json")
        val renderer = TravelVideoRenderer(
            context = context,
            basemapStream = basemapStream,
            renderModel = safeModel,
            timeline = timeline,
            generalizeHomeAddress = generalizeHomeAddress,
            sceneGeometry = com.traveler.feature.map.threed.SceneGeometry(safeModel, timeline, terrain),
            streetCacheDirectory = frozenMaps
        )
        val tempDir = getExportTempDir(context)
        val sanitizedTitle = trip.title.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val tempFile = File(tempDir, "temp_export_${sanitizedTitle}_${System.currentTimeMillis()}.mp4")

        val encoder = TravelVideoEncoder(
            context = context,
            timeline = timeline,
            renderer = renderer
        )
        encoderRef?.invoke(encoder)

        val actualResolution=resolution.supportedOrFallback()
        val success = encoder.encodeToMp4(
            outputFile = tempFile,
            width = actualResolution.width,
            height = actualResolution.height,
            fps = 30,
            includeMusic = includeMusic,
            onProgress = { onProgress?.invoke(.03f+it*.96f) }
        )

        if (success && tempFile.exists() && tempFile.length() > 1024) {
            // P0-07 Hard Sanity Gate: Validate MP4 metadata duration
            val retriever = android.media.MediaMetadataRetriever()
            val actualDurationSeconds = try {
                retriever.setDataSource(tempFile.absolutePath)
                val durStr = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)
                val durMs = durStr?.toLongOrNull() ?: 0L
                durMs / 1000.0f
            } catch (e: Exception) {
                if (tempFile.exists()) tempFile.delete()
                throw IllegalStateException("Video export validation failed: unreadable MP4 metadata", e)
            } finally {
                retriever.release()
            }

            val intendedDurationSeconds = timeline.totalStoryDurationSeconds
            val maxTolerance = maxOf(1.5f, intendedDurationSeconds * 0.08f)
            if (kotlin.math.abs(actualDurationSeconds - intendedDurationSeconds) > maxTolerance || actualDurationSeconds > 3600.0f) {
                if (tempFile.exists()) tempFile.delete()
                throw IllegalStateException("Video export timing validation failed: intended $intendedDurationSeconds s, but container reported $actualDurationSeconds s")
            }

            onProgress?.invoke(1f)
            tempFile
        } else {
            if (tempFile.exists()) tempFile.delete()
            null
        }
        } finally { ExportMapSnapshot.clear(frozenMaps) }
    }

    /**
     * Saves the exported MP4 video to Android MediaStore Movies folder (P1).
     */
    suspend fun saveVideoToMediaStore(
        context: Context,
        videoFile: File,
        tripTitle: String,
        startDateIso: String,
        endDateIso: String
    ): Uri? = withContext(Dispatchers.IO) {
        val sanitizedTitle = tripTitle.replace(Regex("[^a-zA-Z0-9_-]"), "_")
        val displayName = "MyTravelDiary_${sanitizedTitle}_${startDateIso}_${endDateIso}.mp4"

        val resolver = context.contentResolver
        val contentValues = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/My Travel Diary 3D")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
        }

        val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }

        var uri: Uri? = null
        try {
            uri = resolver.insert(collection, contentValues) ?: return@withContext null
            (resolver.openOutputStream(uri) ?: error("Cannot open gallery output")).use { out ->
                FileInputStream(videoFile).use { input ->
                    input.copyTo(out)
                }
                out.flush()
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                contentValues.clear()
                contentValues.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(uri, contentValues, null, null)
            }

            uri
        } catch (e: Exception) {
            uri?.let { runCatching { resolver.delete(it, null, null) } }
            if (e is kotlinx.coroutines.CancellationException) throw e
            null
        }
    }

    /**
     * Creates an Android Sharesheet Intent sharing the video via content:// URI (P1).
     */
    fun createShareIntent(context: Context, videoFile: File): Intent {
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            videoFile
        )

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "video/mp4"
            putExtra(Intent.EXTRA_STREAM, contentUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

        return Intent.createChooser(shareIntent, "Share Travel Video")
    }

    /**
     * Cleans temporary export files older than 24 hours without touching MediaStore.
     */
    fun cleanOldTempVideos(context: Context) {
        try {
            val dir = getExportTempDir(context)
            val now = System.currentTimeMillis()
            dir.listFiles()?.forEach { file ->
                if (now - file.lastModified() > 86_400_000L) {
                    file.delete()
                }
            }
        } catch (_: Exception) {}
    }
}
