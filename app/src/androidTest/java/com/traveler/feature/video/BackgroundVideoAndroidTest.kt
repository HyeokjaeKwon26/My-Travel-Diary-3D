package com.traveler.feature.video

import android.media.MediaMetadataRetriever
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.traveler.MainActivity
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.database.TravelerDatabase
import com.traveler.core.media.*
import com.traveler.core.model.*
import com.traveler.core.tasks.*
import com.traveler.data.repository.TripRepositoryImpl
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class BackgroundVideoAndroidTest {
    @Test fun foregroundVideoFinishesAfterActivityClosesAndContainsClipAudioWithoutMusic() = runBlocking {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        c.getSharedPreferences("app_updates",0).edit().putBoolean("automatic",false).commit()
        val repo = TripRepositoryImpl(TravelerDatabase.getDatabase(c))
        val id=UUID.randomUUID().toString()
        val source=File(c.filesDir,"$id.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("media/clip-colors-tone.mp4").use { input -> source.outputStream().use { input.copyTo(it) } }
        var output:File?=null
        try {
            val time=1767225600000L
            val visit=Visit("$id-visit",location=GeoPoint(42.0,-71.0),startTimestampEpochMs=time,endTimestampEpochMs=time+600000,confidence=1f,timezoneId="UTC")
            val media=MediaItem("$id-clip",TripMediaFiles.uri(c,source),source.name,"video/mp4",time+300000,
                timestampConfidence=TimestampConfidence.EXIF_EXACT,matchedVisitId=visit.id,isRepresentative=true,
                videoClip=VideoClip(startMs=4000,durationMs=3000,originalVolume=1f,musicVolume=0f))
            repo.saveTrip(Trip(id,"Background clip","2026-01-01","2026-01-01",0.0,
                days=listOf(TripDay(1,"2026-01-01",timezoneId="UTC",items=listOf(TripDayItem.VisitItem(visit,listOf(media))))),totalMediaCount=1))
            var taskId=""
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { taskId=TravelTasks.enqueue(c,TravelTask(kind="VIDEO",label="Background video test",trips=listOf(id),
                    options=mapOf("profile" to "SHORT","resolution" to "HD","music" to "false","privacy" to "true"))) }
                withTimeout(30000) { TravelTasks.state.first { rows -> rows.any { it.id==taskId && it.status!="QUEUED" } } }
            }
            val done=withTimeout(240000) { TravelTasks.state.first { rows -> rows.any { it.id==taskId && !it.active } }.first { it.id==taskId } }
            assertEquals(done.message,"DONE",done.status)
            output=File(done.outputs.single()); assertTrue(output!!.isFile)
            assertEquals(File(c.filesDir,"finished_videos").canonicalFile,output!!.canonicalFile.parentFile)
            val r=MediaMetadataRetriever()
            try { r.setDataSource(output!!.absolutePath)
                assertEquals("yes",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO))
                assertEquals("yes",r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO))
                assertTrue(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()>3000)
            } finally { r.release() }
            assertTrue(File(c.filesDir,"travel-tasks.json").readText().contains(taskId))
        } finally { output?.delete(); source.delete(); repo.deleteTrip(id); TripMediaFiles.delete(c,id) }
    }
}
