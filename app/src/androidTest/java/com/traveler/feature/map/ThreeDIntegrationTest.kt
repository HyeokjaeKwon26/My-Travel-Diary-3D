package com.traveler.feature.map

import android.graphics.Bitmap
import androidx.activity.compose.setContent
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.traveler.MainActivity
import com.traveler.core.database.TravelerDatabase
import com.traveler.core.model.*
import com.traveler.core.terrain.TerrainRepository
import com.traveler.data.repository.TripRepositoryImpl
import com.traveler.feature.map.renderer.TravelMapRenderModel
import com.traveler.feature.map.story.TravelStoryTimeline
import com.traveler.feature.map.threed.*
import com.traveler.core.media.StoryDurationProfile
import com.traveler.feature.video.TravelVideoExporter
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier

@RunWith(AndroidJUnit4::class)
class ThreeDIntegrationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private fun model(trip:Trip):TravelMapRenderModel {
        val items=trip.days.flatMap { it.items }
        return TravelMapRenderModel(items.filterIsInstance<TripDayItem.VisitItem>().map { it.visit },
            items.filterIsInstance<TripDayItem.MovementItem>().map { it.segment })
    }

    @Test fun demoCanBeOpenedThroughNormalAppAndTerrainOptions() {
        compose.onNodeWithText("New Travel Story",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Try Grand Canyon 3D • illustrative route").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithText("3D • Terrain").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("3D • Terrain").performClick()
        compose.onNodeWithText("3D map & terrain").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Start Playback").assertExists()
        val screenshot=File(context.getExternalFilesDir(null),"canyon-3d-app.png")
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        device.takeScreenshot(screenshot)
        device.executeShellCommand("cp ${screenshot.path} /sdcard/Download/canyon-3d-app.png")
    }

    @Test fun canyon3DFrameAndVideo() = runBlocking {
        val trip=CanyonDemo.trip();val model=model(trip)
        val packs=TerrainRepository.load(context)
        assertTrue(packs.first().heights.filterNotNull().let { it.max()-it.min() }>1000)
        val timeline=TravelStoryTimeline.build(model,StoryDurationProfile.SHORT)
        val scene=SceneGeometry(model,timeline,packs)
        val errors=java.util.concurrent.CopyOnWriteArrayList<String>()
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            Travel3DSurface(scene,timeline.evaluate(.45f),false,Modifier.fillMaxSize()) { errors.add(it) }
        }
        }
        compose.waitForIdle()
        // GLSurfaceView is asynchronous to Compose. Wait for an actual non-uniform
        // GPU frame rather than treating Compose idle as proof of rendering.
        val screenshot=File(context.getExternalFilesDir(null),"canyon-3d.png")
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.waitUntil(30_000) {
            device.takeScreenshot(screenshot)
            val bitmap=android.graphics.BitmapFactory.decodeFile(screenshot.path)
            val colors=if(bitmap==null) emptySet() else buildSet {
                for(y in bitmap.height/4 until bitmap.height*3/4 step 30)
                    for(x in bitmap.width/4 until bitmap.width*3/4 step 30) add(bitmap.getPixel(x,y))
                bitmap.recycle()
            }
            colors.size>30 || errors.isNotEmpty()
        }
        assertTrue(errors.joinToString(),errors.isEmpty())
        val file=TravelVideoExporter.exportVideo(context,trip,model,StoryDurationProfile.SHORT,includeMusic=false)
        assertNotNull(file)
        val retriever=android.media.MediaMetadataRetriever()
        try {
            retriever.setDataSource(file!!.path)
            val duration=retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            assertTrue(duration>3000)
            val frame=retriever.getFrameAtTime(duration*450,android.media.MediaMetadataRetriever.OPTION_CLOSEST)
            assertNotNull(frame)
            File(context.getExternalFilesDir(null),"canyon-3d-video-frame.png").outputStream().use { frame!!.compress(Bitmap.CompressFormat.PNG,100,it) }
            frame!!.recycle()
            file.copyTo(File(context.getExternalFilesDir(null),"canyon-3d-demo.mp4"),overwrite=true)
            // Preserve synthetic verification artifacts after Gradle uninstalls the test app.
            for(name in listOf("canyon-3d.png","canyon-3d-video-frame.png","canyon-3d-demo.mp4")) {
                device.executeShellCommand("cp ${context.getExternalFilesDir(null)}/$name /sdcard/Download/$name")
            }
        } finally { retriever.release();file?.delete() }
        Unit
    }

    @Test fun savedJourneyPreservesAltitudeAndTimedPoints() = runBlocking {
        val db=Room.inMemoryDatabaseBuilder(context,TravelerDatabase::class.java).build()
        try {
            val original=CanyonDemo.trip()
            val trip=original.copy(days=original.days.map { day -> day.copy(items=day.items.map { item ->
                if(item is TripDayItem.MovementItem) {
                    val s=item.segment
                    val points=s.simplifiedPoints.mapIndexed { i,p -> p.copy(altitudeMeters=1800.0+i*10) }
                    item.copy(segment=s.copy(startPoint=points.first(),endPoint=points.last(),simplifiedPoints=points,
                        rawPoints=points.mapIndexed { i,p -> LocationPoint("p$i",s.startTimestampEpochMs+i*60_000,p) }))
                } else item
            }) })
            val repo=TripRepositoryImpl(db)
            repo.saveTrip(trip)
            val restored=repo.getTripById(trip.id)!!
            val a=trip.days.flatMap { it.items }.filterIsInstance<TripDayItem.MovementItem>().first().segment
            val b=restored.days.flatMap { it.items }.filterIsInstance<TripDayItem.MovementItem>().first().segment
            assertEquals(a.startPoint.altitudeMeters,b.startPoint.altitudeMeters)
            assertEquals(a.endPoint.altitudeMeters,b.endPoint.altitudeMeters)
            assertEquals(a.rawPoints,b.rawPoints)
        } finally { db.close() }
    }
}
