package com.traveler.core.media

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
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.security.MessageDigest
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LibraryEnhancementsAndroidTest {
    private val c = InstrumentationRegistry.getInstrumentation().targetContext
    private val repo = TripRepositoryImpl(TravelerDatabase.getDatabase(c))
    @Test fun previewDoesNotImportAndReservedRestoreIdMakesRetriesIdempotent() = runBlocking {
        val source = Trip(UUID.randomUUID().toString(), "Preview and retry", "2026-01-01", "2026-01-01", 0.0)
        val file = File(c.filesDir, "${source.id}.zip")
        val uri = Uri.parse(TripMediaFiles.uri(c, file))
        val reserved = UUID.randomUUID().toString()
        try {
            PortableTripArchive.export(c, source, uri)
            val count = repo.getAllTrips().first().size
            val preview = BackupCatalog.preview(c, uri, listOf(source))
            assertTrue(preview.containsMedia); assertTrue(preview.similar)
            assertEquals(MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) }, preview.digest)
            assertEquals(count, repo.getAllTrips().first().size)
            assertEquals(reserved, TripArchive.restore(c, uri, reserved))
            assertEquals(reserved, TripArchive.restore(c, uri, reserved))
            assertEquals(count + 1, repo.getAllTrips().first().size)
            BackupCatalog.recordImport(c, preview.digest, reserved)
            assertEquals(reserved, BackupCatalog.recordedImport(c, preview.digest))
        } finally { repo.deleteTrip(reserved); TripMediaFiles.delete(c, reserved); file.delete() }
    }
    @Test fun addedMediaIsCopiedOnceAndPlacementAndTrashPreserveTheFile() = runBlocking {
        val id = UUID.randomUUID().toString()
        val source = File(c.filesDir, "$id.jpg")
        val bitmap = Bitmap.createBitmap(32,32,Bitmap.Config.ARGB_8888).apply { eraseColor(Color.GREEN) }
        source.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG,90,it) }; bitmap.recycle()
        val uri = Uri.parse(TripMediaFiles.uri(c, source))
        try {
            repo.saveTrip(Trip(id,"Added photo","2026-01-01","2026-01-02",0.0))
            repeat(2) { MediaLibraryEditor.add(c,id,listOf(uri),"2026-01-01",null) { _,_ -> } }
            val media = repo.getTripById(id)!!.allMedia().single()
            assertEquals("2026-01-01",media.assignedDayIso); assertNull(media.timestampEpochMs)
            source.delete()
            c.contentResolver.openInputStream(Uri.parse(media.contentUriString))!!.use { assertTrue(it.read() >= 0) }
            MediaLibraryEditor.place(c,id,media.id,"2026-01-02",null)
            assertEquals("2026-01-02",repo.getTripById(id)!!.allMedia().single().assignedDayIso)
            TripTrash.move(c,id); assertTrue(id in TripTrash.ids(c)); assertNotNull(repo.getTripById(id))
            TripTrash.restore(c,id); assertFalse(id in TripTrash.ids(c))
            assertEquals(1,TripMediaFiles.status(c,repo.getTripById(id)!!).saved)
        } finally { TripTrash.restore(c,id); repo.deleteTrip(id); TripMediaFiles.delete(c,id); source.delete() }
    }
    @Test fun clipSettingsSurviveDatabaseAndPortableBackupRoundTrip() = runBlocking {
        val id = UUID.randomUUID().toString()
        val source = File(c.filesDir,"$id.mp4"); val archive = File(c.filesDir,"$id.zip")
        InstrumentationRegistry.getInstrumentation().context.assets.open("media/clip-colors-tone.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        var restored: String? = null
        try {
            val media = MediaItem("clip",TripMediaFiles.uri(c,source),source.name,"video/mp4",null)
            repo.saveTrip(Trip(id,"Clip backup","2026-01-01","2026-01-01",0.0,uncertainDateMedia=listOf(media),totalMediaCount=1))
            val clip = VideoClip(startMs=4000,durationMs=3000,originalVolume=.75f,musicVolume=.2f)
            MediaLibraryEditor.setClip(c,id,media.id,clip)
            val loaded = repo.getTripById(id)!!
            assertEquals(clip,loaded.allMedia().single().videoClip)
            PortableTripArchive.export(c,loaded,Uri.parse(TripMediaFiles.uri(c,archive)))
            restored = TripArchive.restore(c,Uri.parse(TripMediaFiles.uri(c,archive)))
            source.delete()
            val imported = repo.getTripById(restored!!)!!.allMedia().single()
            assertEquals(clip,imported.videoClip); assertNotEquals(media.id,imported.id)
            c.contentResolver.openInputStream(Uri.parse(imported.contentUriString))!!.use { assertTrue(it.read() >= 0) }
        } finally { restored?.let { repo.deleteTrip(it); TripMediaFiles.delete(c,it) }; repo.deleteTrip(id); TripMediaFiles.delete(c,id); source.delete(); archive.delete() }
    }
}
