package com.traveler.feature.map

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.traveler.MainActivity
import com.traveler.core.model.TripDayItem
import com.traveler.feature.map.threed.CanyonDemo
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File

/** Manual emulator acceptance: run with -e offlinePlayback true and network disabled. */
@RunWith(AndroidJUnit4::class)
class LiveMapPlaybackAndroidTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun missingStreetDetailDoesNotStopPlaybackAndSwitchingTo2DKeepsPosition() {
        Assume.assumeTrue(InstrumentationRegistry.getArguments().getString("offlinePlayback")=="true")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val prefs=context.getSharedPreferences("scene_preferences",0)
        val prior=prefs.getBoolean("internetMaps",true)
        prefs.edit().putBoolean("internetMaps",true).commit()
        val segment=CanyonDemo.trip().days.flatMap { it.items }.filterIsInstance<TripDayItem.MovementItem>().first().segment
        val segments=(0 until 16).map { i ->
            val points=if(i%2==0) segment.simplifiedPoints else segment.simplifiedPoints.reversed()
            segment.copy(id="offline-loop-$i",startTimestampEpochMs=segment.startTimestampEpochMs+i*3_600_000L,
                endTimestampEpochMs=segment.startTimestampEpochMs+(i+1)*3_600_000L,
                startPoint=points.first(),endPoint=points.last(),simplifiedPoints=points)
        }
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.mainClock.autoAdvance=false
        try {
            compose.activityRule.scenario.onActivity { it.setContent {
                MaterialTheme { Column {
                    TravelMapView(emptyList(),segments,initialPlaybackProgress=.3f,modifier=Modifier.fillMaxWidth().height(280.dp))
                    androidx.compose.material3.Text("Diary remains below the map",Modifier.fillMaxSize())
                } }
            } }
            compose.mainClock.advanceTimeBy(1000)
            Thread.sleep(2000)
            fun progress():Float=compose.onAllNodes(SemanticsMatcher.keyIsDefined(SemanticsProperties.ProgressBarRangeInfo))
                .fetchSemanticsNodes().first { it.config[SemanticsProperties.ProgressBarRangeInfo].range.endInclusive==1f }
                .config[SemanticsProperties.ProgressBarRangeInfo].current
            val values=mutableListOf(progress())
            compose.onNodeWithContentDescription("Play").performClick()
            repeat(5) {
                Thread.sleep(600);compose.mainClock.advanceTimeBy(650)
                values.add(progress())
            }
            assertTrue("Offline map stopped story: $values",values.zipWithNext().all { (a,b)-> b>a })
            File(context.getExternalFilesDir(null),"rc6-offline-playback.txt").writeText("progress=$values")
            compose.onNodeWithContentDescription("Pause").performClick()
            val paused=progress()
            compose.onNodeWithText("3D • Terrain").performClick()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithText("Switch to 2D map").performScrollTo().performClick()
            compose.onNodeWithText("Done").performClick()
            compose.mainClock.advanceTimeBy(500);Thread.sleep(1500)
            assertEquals(paused,progress(),.001f)
            compose.onNodeWithText("2D • Options").assertIsDisplayed()
            compose.onNodeWithText("Diary remains below the map").assertIsDisplayed()
            device.takeScreenshot(File("/sdcard/Download/rc6-2d-boundary.png"))
            compose.onNodeWithText("2D • Options").performClick()
            compose.mainClock.advanceTimeBy(400)
            compose.onNodeWithText("Switch to 3D map").performScrollTo().performClick()
            compose.onNodeWithText("Done").performClick()
            compose.mainClock.advanceTimeBy(500);Thread.sleep(1500)
            assertEquals(paused,progress(),.001f)
            device.takeScreenshot(File("/sdcard/Download/rc6-returned-3d.png"))
            File(context.getExternalFilesDir(null),"rc6-offline-playback.txt").writeText("progress=$values paused=$paused afterSwitch=${progress()}")
        } finally { prefs.edit().putBoolean("internetMaps",prior).commit();compose.mainClock.autoAdvance=true }
    }
}
