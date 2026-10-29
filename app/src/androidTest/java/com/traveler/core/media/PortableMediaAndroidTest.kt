package com.traveler.core.media

import coil.imageLoader
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.traveler.core.database.TravelerDatabase
import com.traveler.core.terrain.TripArchive
import com.traveler.core.model.*
import com.traveler.data.repository.TripRepositoryImpl
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@RunWith(AndroidJUnit4::class)
class PortableMediaAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = TripRepositoryImpl(TravelerDatabase.getDatabase(context))
    private fun photo(dir: File, id: String): MediaItem {
        val file = File(dir, "$id.jpg")
        val bitmap = Bitmap.createBitmap(48, 96, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }; bitmap.recycle()
        return MediaItem(id, TripMediaFiles.uri(context, file), file.name, "image/jpeg", null, isRepresentative = true)
    }
    private fun trip(id: String, a: MediaItem, b: MediaItem) = Trip(id, "Portable test", "2026-01-01", "2026-01-01", 0.0,
        days = listOf(TripDay(1, "2026-01-01", items = listOf(TripDayItem.UnassignedPhotosItem(listOf(a))),
            unassignedPhotos = listOf(a))), uncertainDateMedia = listOf(b), totalMediaCount = 2)

    @Test fun legacyJsonBackupsStillRestoreAsNewTrips() = runBlocking {
        val original = Trip(UUID.randomUUID().toString(), "Legacy", "2026-01-01", "2026-01-01", 0.0)
        val file = File(context.filesDir, "${original.id}.json")
        var restored: String? = null
        try {
            val uri = Uri.parse(TripMediaFiles.uri(context, file))
            TripArchive.export(context, original, uri)
            restored = TripArchive.restore(context, uri)
            assertNotEquals(original.id, restored)
            assertEquals("Legacy", repository.getTripById(restored!!)!!.title)
        } finally { restored?.let { repository.deleteTrip(it) }; file.delete() }
    }

    @Test fun keptPhotosSurviveOriginalDeletionAndTripCleanupLeavesOtherTripsUntouched() = runBlocking {
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "test-$id").apply { mkdir() }
        val source = trip(id, photo(dir, "a"), photo(dir, "b"))
        try {
            assertEquals(2, TripMediaFiles.keep(context, source).saved)
            assertEquals(2, TripMediaFiles.keep(context, source).saved) // Idempotent retry.
            dir.deleteRecursively()
            val resolved = TripMediaFiles.resolve(context, source)
            assertEquals(source.allMedia().map { it.id }, resolved.allMedia().map { it.id })
            resolved.allMedia().forEach {
                val bitmap = MediaBitmapDecoder.decode(context, it, 40, 40)
                assertNotNull(bitmap); assertTrue(bitmap!!.height > bitmap.width); bitmap.recycle()
            }
            TripMediaFiles.delete(context, "unrelated")
            assertEquals(2, TripMediaFiles.status(context, source).saved)
        } finally { TripMediaFiles.delete(context, id); dir.deleteRecursively() }
    }

    @Test fun keepingCopiesPreservesSavedStoryChoicesWithoutReanalysis() = runBlocking {
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "test-$id").apply { mkdir() }
        val memoryDir = File(dir, "memories")
        val time = 1767225600000L
        val visit = Visit("v", location = com.traveler.core.common.geo.GeoPoint(42.0, -71.0),
            startTimestampEpochMs = time, endTimestampEpochMs = time + 600000, confidence = 1f, timezoneId = "UTC")
        val photo = photo(dir, "a").copy(timestampEpochMs = time + 300000,
            timestampConfidence = TimestampConfidence.EXIF_EXACT, matchedVisitId = "v")
        val trip = Trip(id, "Memories", "2026-01-01", "2026-01-01", 0.0,
            days = listOf(TripDay(1, "2026-01-01", items = listOf(TripDayItem.VisitItem(visit, listOf(photo))))))
        try {
            val first = TripMemoryStore(memoryDir) { items, _, _ -> items }.prepare(trip)
            TripMediaFiles.keep(context, trip)
            File(dir, "a.jpg").delete()
            val reopened = TripMemoryStore(memoryDir) { _, _, _ -> error("Must reuse saved analysis") }.prepare(trip)
            val resolved = TripMediaFiles.resolve(context, reopened)
            assertEquals(first.memorySnapshot!!.selections.mapValues { it.value.map { m -> m.mediaId } },
                resolved.memorySnapshot!!.selections.mapValues { it.value.map { m -> m.mediaId } })
            assertTrue(resolved.memorySnapshot!!.selections.values.flatten().isNotEmpty())
            resolved.memorySnapshot!!.selections.values.flatten().forEach { moment ->
                val bitmap = MediaBitmapDecoder.decode(context, moment.photo, 40, 40)
                assertNotNull(bitmap); bitmap?.recycle()
            }
        } finally { dir.deleteRecursively(); TripMediaFiles.delete(context, id) }
    }

    @Test fun portableBackupRestoresAllMediaAfterOriginalAndKeptCopiesAreDeleted() = runBlocking {
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "test-$id").apply { mkdir() }
        val source = trip(id, photo(dir, "a"), photo(dir, "undated"))
        val backup = File(context.filesDir, "$id.travel.zip")
        val restoredIds = mutableListOf<String>()
        try {
            TripMediaFiles.keep(context, source)
            dir.deleteRecursively()
            PortableTripArchive.export(context, source, Uri.parse(TripMediaFiles.uri(context, backup)))
            TripMediaFiles.delete(context, id)
            // Exercise the public auto-detecting Restore path, twice without replacing either trip.
            repeat(2) {
                val restoredId = TripArchive.restore(context, Uri.parse(TripMediaFiles.uri(context, backup)))
                restoredIds += restoredId
                val restored = repository.getTripById(restoredId)!!
                assertEquals(2, restored.allMedia().size)
                assertEquals(2, TripMediaFiles.status(context, restored).saved)
                restored.allMedia().forEach { media ->
                    assertTrue(media.isRepresentative)
                    assertTrue(media.id.startsWith(restoredId))
                    val bitmap = MediaBitmapDecoder.decode(context, media, 40, 40)
                    assertNotNull(bitmap); bitmap?.recycle()
                }
            }
            assertNotEquals(restoredIds[0], restoredIds[1])
        } finally {
            restoredIds.forEach { repository.deleteTrip(it); TripMediaFiles.delete(context, it) }
            dir.deleteRecursively(); backup.delete(); TripMediaFiles.delete(context, id)
        }
    }

    @Test fun missingSourceFailsBeforeOverwritingDestinationAndLeavesNoPartialCopy() = runBlocking {
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "test-$id").apply { mkdir() }
        val a = photo(dir, "a")
        val b = photo(dir, "b"); File(dir, "b.jpg").delete()
        val source = trip(id, a, b)
        val backup = File(context.filesDir, "$id.zip").apply { writeText("existing") }
        try {
            try { TripMediaFiles.keep(context, source); fail("Missing source must fail") } catch (_: java.io.FileNotFoundException) { }
            assertEquals(1, TripMediaFiles.status(context, source).saved)
            assertFalse(TripMediaFiles.directory(context, id).listFiles()!!.any { it.name.endsWith(".pending") })
            try { PortableTripArchive.export(context, source, Uri.parse(TripMediaFiles.uri(context, backup))); fail("Must fail") }
            catch (_: java.io.FileNotFoundException) { }
            assertEquals("existing", backup.readText())
        } finally { dir.deleteRecursively(); backup.delete(); TripMediaFiles.delete(context, id) }
    }

    @Test fun cancellationKeepsCompletedFilesAndAllowsRetry() = runBlocking {
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "test-$id").apply { mkdir() }
        val source = trip(id, photo(dir, "a"), photo(dir, "b"))
        try {
            try { TripMediaFiles.keep(context, source) { done, _ -> if (done == 1) throw CancellationException() }; fail() }
            catch (_: CancellationException) { }
            assertEquals(1, TripMediaFiles.status(context, source).saved)
            assertEquals(2, TripMediaFiles.keep(context, source).saved)
        } finally { dir.deleteRecursively(); TripMediaFiles.delete(context, id) }
    }

    @Test fun tamperedAndTraversalBackupsAreRejectedWithoutLeavingFiles() = runBlocking {
        val id = UUID.randomUUID().toString()
        val dir = File(context.filesDir, "test-$id").apply { mkdir() }
        val source = trip(id, photo(dir, "a"), photo(dir, "b"))
        val good = File(dir, "good.zip")
        val bad = File(dir, "bad.zip")
        val before = TripMediaFiles.root(context).list()!!.toSet()
        try {
            PortableTripArchive.export(context, source, Uri.parse(TripMediaFiles.uri(context, good)))
            ZipInputStream(good.inputStream()).use { input -> ZipOutputStream(bad.outputStream()).use { output ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val bytes = input.readBytes()
                    if (entry.name == "media/0.bin") bytes[0] = (bytes[0].toInt() xor 1).toByte()
                    output.putNextEntry(ZipEntry(entry.name)); output.write(bytes); output.closeEntry()
                }
            } }
            try { TripArchive.restore(context, Uri.parse(TripMediaFiles.uri(context, bad))); fail("Hash mismatch") }
            catch (_: IllegalArgumentException) { }
            assertEquals(before, TripMediaFiles.root(context).list()!!.toSet())
            ZipOutputStream(bad.outputStream()).use { it.putNextEntry(ZipEntry("../escape")); it.write(1); it.closeEntry() }
            try { TripArchive.restore(context, Uri.parse(TripMediaFiles.uri(context, bad))); fail("Traversal") }
            catch (_: IllegalArgumentException) { }
            catch (_: java.util.zip.ZipException) { } // Android 14+ may reject traversal before our validator.
            assertEquals(before, TripMediaFiles.root(context).list()!!.toSet())
        } finally { dir.deleteRecursively() }
    }

    @Test fun videoFramesAreVisibleBoundedAndRespectPortraitRotation() = runBlocking {
        val dir = File(context.filesDir, "test-${UUID.randomUUID()}").apply { mkdir() }
        try {
            for (name in listOf("red-landscape.mp4", "red-portrait.mp4")) {
                val file = File(dir, name)
                InstrumentationRegistry.getInstrumentation().context.assets.open("media/$name").use { input -> file.outputStream().use { input.copyTo(it) } }
                val media = MediaItem(name, TripMediaFiles.uri(context, file), name, "video/mp4", null)
                val frame = MediaBitmapDecoder.decode(context, media, 64, 64)
                assertNotNull(frame)
                assertTrue(frame!!.width <= 64 && frame.height <= 64)
                assertTrue(Color.red(frame.getPixel(frame.width / 2, frame.height / 2)) > 200)
                assertEquals(name.contains("portrait"), frame.height > frame.width)
                frame.recycle()
                // Coil's application loader must also support videos in diary/live playback.
                val result = context.imageLoader.execute(coil.request.ImageRequest.Builder(context)
                    .data(media.contentUriString).size(64, 64).build())
                assertTrue(result is coil.request.SuccessResult)

                val time = 1767225600000L
                val visit = Visit("v", location = com.traveler.core.common.geo.GeoPoint(42.0, -71.0),
                    startTimestampEpochMs = time, endTimestampEpochMs = time + 600000, confidence = 1f, timezoneId = "UTC")
                val storyMedia = media.copy(timestampEpochMs = time + 300000, timestampConfidence = TimestampConfidence.EXIF_EXACT,
                    matchedVisitId = "v", isRepresentative = true)
                val model = com.traveler.feature.map.renderer.TravelMapRenderModel(listOf(visit), emptyList(), listOf(storyMedia))
                val timeline = com.traveler.feature.map.story.TravelStoryTimeline.build(model)
                val moment = timeline.photoMoments.first()
                val renderer = com.traveler.feature.video.TravelVideoRenderer(context, context.assets.open("basemap_world.json"), model, timeline)
                val output = Bitmap.createBitmap(360, 640, Bitmap.Config.ARGB_8888)
                try {
                    renderer.renderFrame(android.graphics.Canvas(output), 360, 640,
                        (moment.displayStartStorySeconds + moment.displayEndStorySeconds) / 2, renderMap = false)
                    var red = 0
                    for (y in 65 until 300) for (x in 180 until 360) {
                        val pixel = output.getPixel(x, y)
                        if (Color.red(pixel) > 200 && Color.green(pixel) < 40 && Color.blue(pixel) < 40) red++
                    }
                    assertTrue("Video still must be painted in the exported frame", red > 100)
                } finally { output.recycle(); renderer.release() }
            }
        } finally { dir.deleteRecursively() }
    }
}
