package com.traveler.feature.ui

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.traveler.MainActivity
import com.traveler.core.media.StoryDurationProfile
import com.traveler.core.media.PhotoVisualAnalyzer
import com.traveler.core.model.*
import com.traveler.feature.map.threed.CanyonDemo
import com.traveler.feature.map.renderer.TravelMapRenderModel
import com.traveler.feature.video.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class AdaptiveJourneyAndroidTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val device get()=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private var internet=true
    @Before fun offline() {
        val prefs=context.getSharedPreferences("scene_preferences",0)
        internet=prefs.getBoolean("internetMaps",true)
        prefs.edit().putBoolean("internetMaps",false).commit()
    }
    @After fun restore() {
        device.unfreezeRotation()
        context.getSharedPreferences("scene_preferences",0).edit().putBoolean("internetMaps",internet).commit()
    }
    @Test fun datesArePickedFromCalendar() {
        compose.onNodeWithText("New Travel Story",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Select dates",substring=true).performScrollTo().performClick()
        compose.onNodeWithText("Select travel dates").assertIsDisplayed()
        device.takeScreenshot(File("/sdcard/Download/rc5-calendar.png"))
        compose.onNodeWithText("Use dates").performClick()
        compose.onNodeWithText("Select dates",substring=true).assertExists()
    }
    @Test fun landscapeExportAndLocalPhotoAnalysis() = runBlocking {
        val original=CanyonDemo.trip()
        val segment=original.days.flatMap { it.items }.filterIsInstance<TripDayItem.MovementItem>().first().segment
        val trip=original.copy(days=listOf(original.days.first().copy(items=listOf(TripDayItem.MovementItem(segment)))))
        val model=TravelMapRenderModel(emptyList(),listOf(segment))
        val output=TravelVideoExporter.exportVideo(context,trip,model,StoryDurationProfile.SHORT,
            includeMusic=true,resolution=VideoResolution.LANDSCAPE_HD)!!
        val retriever=MediaMetadataRetriever()
        try {
            retriever.setDataSource(output.path)
            assertEquals("1280",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH))
            assertEquals("720",retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT))
            val duration=retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)!!.toLong()
            val image=retriever.getFrameAtTime(duration*500,MediaMetadataRetriever.OPTION_CLOSEST)!!
            File("/sdcard/Download/rc5-landscape-frame.png").outputStream().use { image.compress(Bitmap.CompressFormat.PNG,100,it) }
            image.recycle()
            output.copyTo(File("/sdcard/Download/rc5-landscape.mp4"),overwrite=true)
        } finally { retriever.release() }
        val raster=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
        val drawable=androidx.core.content.ContextCompat.getDrawable(context,com.traveler.R.drawable.demo_niagara)!!
        drawable.setBounds(0,0,256,256);drawable.draw(android.graphics.Canvas(raster))
        val fixture=File(context.cacheDir,"photo-analysis-fixture.png")
        fixture.outputStream().use { raster.compress(Bitmap.CompressFormat.PNG,100,it) };raster.recycle()
        val photo=MediaItem("fixture",android.net.Uri.fromFile(fixture).toString(),"niagara.png","image/png",0)
        val analyzer=PhotoVisualAnalyzer(context)
        val first=analyzer.analyze(listOf(photo)).single()
        assertNotNull(first.visualFeatures)
        assertTrue("Bundled image model ran successfully",first.visualFeatures!!.labelsComputed)
        assertEquals(first.visualFeatures,analyzer.analyze(listOf(photo)).single().visualFeatures)

        val intent=android.content.Intent(context,VideoPlayerActivity::class.java).putExtra("videoPath",output.path)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        val scenario=androidx.test.core.app.ActivityScenario.launch<VideoPlayerActivity>(intent)
        try {
            fun findVideo(v:android.view.View):android.widget.VideoView? {
                if(v is android.widget.VideoView) return v
                if(v is android.view.ViewGroup) for(i in 0 until v.childCount) findVideo(v.getChildAt(i))?.let { return it }
                return null
            }
            var player:android.widget.VideoView?=null
            val deadline=System.currentTimeMillis()+15_000
            var ready=false
            while(!ready && System.currentTimeMillis()<deadline) {
                scenario.onActivity { player=findVideo(it.window.decorView);ready=(player?.duration ?: 0)>0 }
                if(!ready) Thread.sleep(100)
            }
            assertTrue("In-app video prepared",ready)
            scenario.onActivity { player!!.pause();player!!.seekTo(1500) }
            Thread.sleep(300)
            device.setOrientationLeft()
            Thread.sleep(800)
            scenario.onActivity {
                assertSame("Rotation retains player instance",player,findVideo(it.window.decorView))
                assertFalse(player!!.isPlaying)
                assertTrue(kotlin.math.abs(player!!.currentPosition-1500)<500)
                assertTrue("Letterbox keeps video aspect",kotlin.math.abs(player!!.width.toDouble()/player!!.height-1280.0/720)<.05)
            }
            device.takeScreenshot(File("/sdcard/Download/rc5-player-landscape.png"))
        } finally { scenario.close() }
        // Keep the produced file in exports so the same in-app player path can be smoke-tested.
        File(context.getExternalFilesDir(null),"rc5-player-path.txt").writeText(output.path)
        Unit
    }
}
