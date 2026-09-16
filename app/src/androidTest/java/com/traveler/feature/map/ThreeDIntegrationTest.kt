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
    private var previousInternetMaps=true
    @Before fun disableLiveMapRequests() {
        val prefs=context.getSharedPreferences("scene_preferences",0)
        previousInternetMaps=prefs.getBoolean("internetMaps",true)
        prefs.edit().putBoolean("internetMaps",false).commit()
    }
    @After fun restoreMapPreference() {
        context.getSharedPreferences("scene_preferences",0).edit().putBoolean("internetMaps",previousInternetMaps).commit()
    }
    private fun model(trip:Trip):TravelMapRenderModel {
        val items=trip.days.flatMap { it.items }
        return TravelMapRenderModel(items.filterIsInstance<TripDayItem.VisitItem>().map { it.visit },
            items.filterIsInstance<TripDayItem.MovementItem>().map { it.segment })
    }

    @Test fun demoCanBeOpenedThroughNormalAppAndTerrainOptions() {
        compose.onNodeWithText("New Travel Story",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Try Grand Canyon 3D • illustrative route").performScrollTo().performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithContentDescription("Map settings").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("Map settings").performClick()
        compose.onNodeWithText("3D map & terrain").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithContentDescription("Start Playback").assertExists()
        val screenshot=File(context.getExternalFilesDir(null),"canyon-3d-app.png")
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.waitUntil(30_000) {
            device.takeScreenshot(screenshot)
            val bitmap=android.graphics.BitmapFactory.decodeFile(screenshot.path)
            val colors=buildSet {
                for(y in bitmap.height/6 until bitmap.height/3 step 20)
                    for(x in bitmap.width/4 until bitmap.width*3/4 step 20) add(bitmap.getPixel(x,y))
            }
            bitmap.recycle()
            colors.size>12
        }
        assertFalse("A system/app non-response dialog obscures the screenshot",
            device.hasObject(androidx.test.uiautomator.By.textContains("isn't responding")))
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
            Travel3DSurface(scene,timeline.evaluate(.45f),true,Modifier.fillMaxSize(),internetMaps=false) { errors.add(it) }
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
            var toyPixels=0
            val colors=if(bitmap==null) emptySet() else buildSet {
                for(y in bitmap.height/4 until bitmap.height*3/4 step 30)
                    for(x in bitmap.width/4 until bitmap.width*3/4 step 30) {
                        val pixel=bitmap.getPixel(x,y);add(pixel)
                        if(android.graphics.Color.red(pixel)>200 && android.graphics.Color.blue(pixel)<80) toyPixels++
                    }
                bitmap.recycle()
            }
            // A close rural view has fewer map colors than the old regional view.
            // Require both terrain variation and the rendered red/yellow vehicle.
            (colors.size>6 && toyPixels>3) || errors.isNotEmpty()
        }
        assertTrue(errors.joinToString(),errors.isEmpty())
        assertFalse("A system/app non-response dialog obscures the screenshot",
            device.hasObject(androidx.test.uiautomator.By.textContains("isn't responding")))
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
