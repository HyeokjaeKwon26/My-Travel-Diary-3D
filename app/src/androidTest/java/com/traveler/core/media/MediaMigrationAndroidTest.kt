package com.traveler.core.media

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.platform.app.InstrumentationRegistry
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.database.TravelerDatabase
import com.traveler.core.terrain.TripArchive
import com.traveler.core.model.*
import com.traveler.data.repository.TripRepositoryImpl
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.UUID

class MediaMigrationAndroidTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val repository = TripRepositoryImpl(TravelerDatabase.getDatabase(context))
    private fun picture(file: File) {
        val bitmap = Bitmap.createBitmap(32, 64, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
    }
    private fun media(uri: String, name: String = "original.jpg") = MediaItem("old-photo", uri, name, "image/jpeg",
        1767225601000, location = GeoPoint(42.0, -71.0), matchedVisitId = "old-visit", isRepresentative = true,
        isUserLocationOverride = true, assignedDayIso = "2026-01-01")
    private fun trip(item: MediaItem) = Trip(UUID.randomUUID().toString(), "Edited old journey", "2026-01-01", "2026-01-01", 0.0,
        days = listOf(TripDay(1, "2026-01-01", items = listOf(TripDayItem.VisitItem(
            Visit("old-visit", "My edited place", location = GeoPoint(42.0, -71.0), startTimestampEpochMs = 1767225600000,
                endTimestampEpochMs = 1767226200000, timezoneId = "UTC", isUserOverride = true), listOf(item))))), totalMediaCount = 1)

    @Test fun legacyDemoPhotosResolveFromThisAppWithoutDependingOnOldInstallation() = runBlocking<Unit> {
        for (old in listOf("com.traveler", "com.traveler.threed")) {
            for (name in listOf("demo_boston", "demo_flight", "demo_niagara", "demo_unassigned")) {
                val source = trip(media("android.resource://$old/drawable/$name"))
                val photo = TripMediaFiles.resolve(context, source).allMedia().single()
                assertEquals(context.packageName, Uri.parse(photo.contentUriString).authority)
                try {
                    val bitmap = MediaBitmapDecoder.decode(context, photo, 64, 64)
                    assertNotNull(bitmap); bitmap?.recycle()
                    assertEquals(1, TripMediaFiles.keep(context, source).saved)
                    val saved = MediaBitmapDecoder.decode(context, TripMediaFiles.resolve(context, source).allMedia().single(), 64, 64)
                    assertNotNull(saved); saved?.recycle()
                } finally { TripMediaFiles.delete(context, source.id) }
            }
        }
    }

    @Test fun repeatedLegacyRestorePreservesEditsAndNeverStealsExistingChildRows() = runBlocking<Unit> {
        val file = File(context.filesDir, "migration-${UUID.randomUUID()}.json")
        val source = trip(media("content://missing.old.app/original.jpg"))
        val ids = mutableListOf(source.id)
        try {
            repository.saveTrip(source)
            TripArchive.export(context, source, Uri.parse(TripMediaFiles.uri(context, file)))
            repeat(2) { ids += TripArchive.restore(context, Uri.parse(TripMediaFiles.uri(context, file))) }
            assertEquals(3, ids.toSet().size)
            ids.forEach { id ->
                val loaded = repository.getTripById(id)!!
                val visit = (loaded.days.single().items.single() as TripDayItem.VisitItem).visit
                val photo = loaded.allMedia().single()
                assertEquals("My edited place", visit.placeName); assertTrue(visit.isUserOverride)
                assertEquals(visit.id, photo.matchedVisitId); assertTrue(photo.isUserLocationOverride)
                assertEquals(source.allMedia().single().location, photo.location)
                assertEquals("2026-01-01", photo.assignedDayIso)
                if (id != source.id) assertTrue(photo.id.startsWith("$id:"))
            }
        } finally { ids.forEach { repository.deleteTrip(it) }; file.delete() }
    }

    @Test fun missingOriginalCanBeExplicitlyReconnectedAndBackedUpWithoutChangingMetadata() = runBlocking<Unit> {
        val source = trip(media("content://missing.old.app/original.jpg"))
        val file = File(context.filesDir, "selected-${UUID.randomUUID()}.jpg").also(::picture)
        val zip = File(context.filesDir, "migration-${UUID.randomUUID()}.zip")
        var restored: String? = null
        try {
            assertEquals(1, MediaMigration.inspect(context, source).missing.size)
            MediaMigration.connect(context, source, source.allMedia().single(), Uri.parse(TripMediaFiles.uri(context, file)))
            file.delete()
            assertEquals(0, MediaMigration.inspect(context, source).missing.size)
            assertEquals(1, TripMediaFiles.status(context, source).saved)
            val resolved = TripMediaFiles.resolve(context, source).allMedia().single()
            assertEquals(source.allMedia().single(), resolved.copy(contentUriString = source.allMedia().single().contentUriString))
            PortableTripArchive.export(context, source, Uri.parse(TripMediaFiles.uri(context, zip)))
            TripMediaFiles.delete(context, source.id)
            restored = TripArchive.restore(context, Uri.parse(TripMediaFiles.uri(context, zip)))
            val loaded = repository.getTripById(restored!!)!!
            assertEquals(1, MediaMigration.inspect(context, loaded).readable)
            val bitmap = MediaBitmapDecoder.decode(context, loaded.allMedia().single(), 64, 64)
            assertNotNull(bitmap); bitmap?.recycle()
        } finally {
            restored?.let { repository.deleteTrip(it); TripMediaFiles.delete(context, it) }
            TripMediaFiles.delete(context, source.id); file.delete(); zip.delete()
        }
    }

    @Test fun reusedGalleryIdWithDifferentNameIsNotSilentlyCopied() = runBlocking<Unit> {
        val file = File(context.filesDir, "gallery-${UUID.randomUUID()}.jpg").also(::picture)
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, file.name); put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        })!!
        val source = trip(media(uri.toString(), "some-other-original.jpg"))
        try {
            context.contentResolver.openOutputStream(uri)!!.use { out -> file.inputStream().use { it.copyTo(out) } }
            assertEquals(1, MediaMigration.inspect(context, source).missing.size)
            try { TripMediaFiles.keep(context, source); fail("Reused gallery reference must be rejected") }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, TripMediaFiles.status(context, source).saved)
            val correct = source.copy(days = emptyList(), uncertainDateMedia = listOf(media(uri.toString(), file.name)))
            assertEquals(1, MediaMigration.inspect(context, correct).readable)
        } finally { context.contentResolver.delete(uri, null, null); TripMediaFiles.delete(context, source.id); file.delete() }
    }

    @Test fun explicitReconnectRejectsWrongTypeAndCannotOverwriteSavedOriginal() = runBlocking<Unit> {
        val source = trip(media("content://missing.old.app/original.jpg"))
        val file = File(context.filesDir, "selected-${UUID.randomUUID()}.jpg").also(::picture)
        val wrong = File(context.filesDir, "selected-${UUID.randomUUID()}.txt").apply { writeText("wrong") }
        try {
            try { MediaMigration.connect(context, source, source.allMedia().single(), Uri.parse(TripMediaFiles.uri(context, wrong))); fail() }
            catch (_: IllegalArgumentException) { }
            assertEquals(0, TripMediaFiles.status(context, source).saved)
            val selected = Uri.parse(TripMediaFiles.uri(context, file))
            MediaMigration.connect(context, source, source.allMedia().single(), selected)
            try { MediaMigration.connect(context, source, source.allMedia().single(), selected); fail() }
            catch (_: IllegalStateException) { }
            assertEquals(1, MediaMigration.inspect(context, source).readable)
        } finally { TripMediaFiles.delete(context, source.id); file.delete(); wrong.delete() }
    }
}
