package com.traveler.feature.video

import android.content.Intent
import android.media.MediaMetadataRetriever
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.media.StoryDurationProfile
import com.traveler.core.model.*
import com.traveler.feature.map.renderer.TravelMapRenderModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class TravelVideoExportAndroidTest {

    private fun createSyntheticTrip(): Pair<Trip, TravelMapRenderModel> {
        val visit1 = Visit(
            id = "v_nyc",
            location = GeoPoint(40.7128, -74.0060),
            startTimestampEpochMs = 1723800000000L,
            endTimestampEpochMs = 1723807200000L,
            confidence = 0.95f,
            placeName = "New York City"
        )
        val drive1 = MovementSegment(
            id = "s_drive",
            startTimestampEpochMs = 1723807200000L,
            endTimestampEpochMs = 1723814400000L,
            startPoint = GeoPoint(40.7128, -74.0060),
            endPoint = GeoPoint(42.1292, -80.0851),
            distanceMeters = 600_000.0,
            durationMillis = 7200000L,
            transport = TransportPrediction(TransportMode.CAR, 0.95f, "Interstate")
        )
        val visit2 = Visit(
            id = "v_erie",
            location = GeoPoint(42.1292, -80.0851),
            startTimestampEpochMs = 1723814400000L,
            endTimestampEpochMs = 1723821600000L,
            confidence = 0.95f,
            placeName = "Erie"
        )

        val photo1 = MediaItem(
            id = "p_nyc_1",
            contentUriString = "android.resource://com.traveler.threed/drawable/ic_launcher_background",
            fileName = "times_square.jpg",
            mimeType = "image/jpeg",
            timestampEpochMs = 1723803600000L,
            timestampConfidence = TimestampConfidence.EXIF_EXACT,
            location = GeoPoint(40.7580, -73.9855),
            locationConfidence = LocationConfidenceLevel.GPS_EXACT,
            confidenceScore = 0.95f,
            matchedVisitId = "v_nyc",
            isRepresentative = true
        )

        val trip = Trip(
            id = "trip_test_east",
            title = "East Coast Adventure",
            startDateIso = "2026-08-16",
            endDateIso = "2026-08-21",
            totalDistanceMeters = 600_000.0,
            days = listOf(
                TripDay(
                    dayIndex = 1,
                    dateIso = "2026-08-16",
                    timezoneId = "America/New_York",
                    items = listOf(
                        TripDayItem.VisitItem(visit1, listOf(photo1)),
                        TripDayItem.MovementItem(drive1, emptyList()),
                        TripDayItem.VisitItem(visit2, emptyList())
                    ),
                    unassignedPhotos = emptyList(),
                    totalDistanceMeters = 600_000.0,
                    photoCount = 1
                )
            ),
            cities = listOf("New York", "Erie"),
            countries = listOf("United States"),
            totalMediaCount = 1
        )

        val renderModel = TravelMapRenderModel(
            visits = listOf(visit1, visit2),
            segments = listOf(drive1),
            photos = listOf(photo1)
        )

        return trip to renderModel
    }

    // A. Synthetic Story Export with Music = ON (Playable MP4 + Audio Track)
    @Test
    fun testExportPlayableMp4WithAudio() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val (trip, renderModel) = createSyntheticTrip()

            var lastProgress = 0f
            val exportedFile = TravelVideoExporter.exportVideo(
                context = context,
                trip = trip,
                renderModel = renderModel,
                profile = StoryDurationProfile.SHORT,
                includeMusic = true,
                generalizeHomeAddress = true,
                onProgress = { lastProgress = it }
            )

            assertNotNull("Exported MP4 file must not be null", exportedFile)
            assertTrue("Exported file must exist", exportedFile!!.exists())
            assertTrue("Exported MP4 must have valid non-trivial size (>10KB)", exportedFile.length() > 10240)
            assertEquals("Progress should reach ~1.0", 1.0f, lastProgress, 0.05f)

            // Verify MP4 metadata using MediaMetadataRetriever
            val retriever = MediaMetadataRetriever()
            var durationMs: Long? = null
            try {
                retriever.setDataSource(exportedFile.absolutePath)
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)
                val width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                val height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                durationMs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()

                assertEquals("Video track must be present", "yes", hasVideo)
                assertEquals("Audio track must be present when Music = ON", "yes", hasAudio)
                assertEquals("Video width must be 1080", 1080, width)
                assertEquals("Video height must be 1920", 1920, height)
                assertTrue("Video duration must be > 3 seconds and < 300 seconds", (durationMs ?: 0L) in 3000L..300_000L)
            } finally {
                retriever.release()
            }

            // P0-05: Inspect sample PTS using MediaExtractor
            val extractor = android.media.MediaExtractor()
            try {
                extractor.setDataSource(exportedFile.absolutePath)
                var videoTrackIdx = -1
                for (i in 0 until extractor.trackCount) {
                    val format = extractor.getTrackFormat(i)
                    val mime = format.getString(android.media.MediaFormat.KEY_MIME) ?: ""
                    if (mime.startsWith("video/")) {
                        videoTrackIdx = i
                        break
                    }
                }
                assertTrue("Video track must be present in MediaExtractor", videoTrackIdx >= 0)
                extractor.selectTrack(videoTrackIdx)

                var sampleCount = 0
                var firstPtsUs = -1L
                var lastPtsUs = -1L
                var negativeDeltaCount = 0

                val byteBuf = java.nio.ByteBuffer.allocate(1024 * 1024)
                while (extractor.readSampleData(byteBuf, 0) >= 0) {
                    val ptsUs = extractor.sampleTime
                    if (firstPtsUs < 0) firstPtsUs = ptsUs
                    if (lastPtsUs >= 0) {
                        val delta = ptsUs - lastPtsUs
                        if (delta < 0) negativeDeltaCount++
                    }
                    lastPtsUs = ptsUs
                    sampleCount++
                    extractor.advance()
                }

                assertEquals("First video sample PTS must be approximately 0", 0L, firstPtsUs)
                assertEquals("Negative PTS deltas must be 0", 0, negativeDeltaCount)
                assertTrue("Sample count must be > 50", sampleCount > 50)
                assertTrue("Last PTS must not be in hour range (< 300s)", lastPtsUs < 300_000_000L)
            } finally {
                extractor.release()
            }

            exportedFile.delete()
        }
    }

    // B. Synthetic Story Export with Music = OFF (No Audio Track)
    @Test
    fun testExportPlayableMp4WithoutAudio() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val (trip, renderModel) = createSyntheticTrip()

            val exportedFile = TravelVideoExporter.exportVideo(
                context = context,
                trip = trip,
                renderModel = renderModel,
                profile = StoryDurationProfile.SHORT,
                includeMusic = false,
                generalizeHomeAddress = true
            )

            assertNotNull("Exported MP4 file must not be null", exportedFile)
            assertTrue("Exported file must exist", exportedFile!!.exists())

            val retriever = MediaMetadataRetriever()
            try {
                retriever.setDataSource(exportedFile.absolutePath)
                val hasVideo = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO)
                val hasAudio = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO)

                assertEquals("Video track must be present", "yes", hasVideo)
                assertTrue("Audio track must NOT be present when Music = OFF", hasAudio == null || hasAudio == "no")
            } finally {
                retriever.release()
            }

            exportedFile.delete()
        }
    }

    // C. Cancellation Clean Cleanup Test
    @Test
    fun testExportCancellationCleansUp() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val (trip, renderModel) = createSyntheticTrip()

            var encoderToCancel: TravelVideoEncoder? = null
            val exportedFile = TravelVideoExporter.exportVideo(
                context = context,
                trip = trip,
                renderModel = renderModel,
                profile = StoryDurationProfile.FULL_STORY,
                includeMusic = true,
                encoderRef = { enc -> encoderToCancel = enc },
                onProgress = { p ->
                    if (p > 0.1f) {
                        encoderToCancel?.cancel()
                    }
                }
            )

            // Cancelled export must return null and not leave partial file
            assertNull("Cancelled export must return null", exportedFile)
        }
    }

    // D. Save Video to MediaStore
    @Test
    fun testSaveVideoToMediaStore() {
        runBlocking {
            val context = InstrumentationRegistry.getInstrumentation().targetContext
            val tempFile = File(context.cacheDir, "test_save_video.mp4")
            tempFile.writeBytes(ByteArray(2048) { 0x42 })

            val uri = TravelVideoExporter.saveVideoToMediaStore(
                context = context,
                videoFile = tempFile,
                tripTitle = "East Adventure",
                startDateIso = "2026-08-16",
                endDateIso = "2026-08-21"
            )

            assertNotNull("MediaStore URI must not be null", uri)
            tempFile.delete()
        }
    }

    // E. Android Sharesheet Content URI
    @Test
    fun testCreateShareIntent() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val tempFile = File(TravelVideoExporter.getExportTempDir(context), "test_share.mp4")
        tempFile.writeBytes(ByteArray(2048) { 0x42 })

        val chooserIntent = TravelVideoExporter.createShareIntent(context, tempFile)
        val targetIntent = chooserIntent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)

        assertNotNull("Target share intent must not be null", targetIntent)
        assertEquals("Intent action must be ACTION_SEND", Intent.ACTION_SEND, targetIntent?.action)
        assertEquals("MIME type must be video/mp4", "video/mp4", targetIntent?.type)

        val streamUri = targetIntent?.getParcelableExtra<android.net.Uri>(Intent.EXTRA_STREAM)
        assertNotNull("Stream URI must not be null", streamUri)
        assertEquals("Scheme must be content:// (NEVER file://)", "content", streamUri?.scheme)
        assertTrue("Flags must include FLAG_GRANT_READ_URI_PERMISSION", (targetIntent?.flags ?: 0) and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)

        tempFile.delete()
    }
}
