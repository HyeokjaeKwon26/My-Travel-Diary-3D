package com.traveler.feature.trip

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.traveler.MainActivity
import com.traveler.core.database.TravelerDatabase
import com.traveler.core.media.tripMemoryStore
import com.traveler.data.repository.TripRepositoryImpl
import com.traveler.feature.map.threed.CanyonDemo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class FullscreenPlaybackAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun fullscreenRotationAndBackPreservePositionAndClock() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val repository=TripRepositoryImpl(TravelerDatabase.getDatabase(context))
        val trip=CanyonDemo.trip().copy(id="rc11-fullscreen-test",title="Fullscreen journey")
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        val prefs=context.getSharedPreferences("scene_preferences",0)
        val priorMaps=prefs.getBoolean("internetMaps",true)
        prefs.edit().putBoolean("internetMaps",false).commit()
        runBlocking { repository.saveTrip(trip) }
        device.setOrientationNatural()
        compose.mainClock.autoAdvance=false
        try {
            compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme {
                TravelDiaryScreen(trip.id,onNavigateBack={error("Back should leave fullscreen only")})
            } } }
            compose.waitUntil(30_000) {
                compose.mainClock.advanceTimeBy(32)
                compose.onAllNodesWithContentDescription("Start Playback").fetchSemanticsNodes().isNotEmpty()
            }
            compose.onNodeWithContentDescription("Start Playback").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithContentDescription("Pause").performClick()
            compose.mainClock.advanceTimeBy(32)
            compose.onNodeWithTag("playback-seek").performSemanticsAction(SemanticsActions.SetProgress) { it(.42f) }
            compose.mainClock.advanceTimeBy(32)
            fun progress()=compose.onNodeWithTag("playback-seek").fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].current
            fun clock()=compose.onNodeWithTag("playback-time").fetchSemanticsNode().config[SemanticsProperties.Text].joinToString { it.text }
            val before=progress();val label=clock()
            assertTrue(label.contains(" / "))
            val compactBounds=compose.onNodeWithTag("playback-seek").fetchSemanticsNode().boundsInWindow
            compose.onNodeWithContentDescription("Fullscreen").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithContentDescription("Exit fullscreen").assertIsDisplayed()
            assertEquals(before,progress(),.00001f)
            assertEquals(label,clock())
            val expanded=compose.onNodeWithTag("playback-seek").fetchSemanticsNode().boundsInWindow
            assertTrue("Map did not expand",expanded.bottom>compactBounds.bottom+100)
            Thread.sleep(1000)
            device.takeScreenshot(File(context.getExternalFilesDir(null),"rc11-fullscreen-portrait.png"))
            device.setOrientationLeft()
            compose.waitUntil(15_000) { device.displayWidth>device.displayHeight }
            compose.mainClock.advanceTimeBy(500)
            assertEquals(before,progress(),.00001f)
            assertEquals(label,clock())
            compose.onNodeWithContentDescription("Play").assertIsDisplayed().performClick()
            Thread.sleep(600);compose.mainClock.advanceTimeBy(64)
            assertTrue(progress()>before)
            compose.onNodeWithContentDescription("Pause").performClick()
            compose.mainClock.advanceTimeBy(32)
            Thread.sleep(1000)
            device.takeScreenshot(File(context.getExternalFilesDir(null),"rc11-fullscreen-landscape.png"))
            val paused=progress()
            compose.activityRule.scenario.onActivity { it.onBackPressedDispatcher.onBackPressed() }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithContentDescription("Fullscreen").assertIsDisplayed()
            assertEquals(paused,progress(),.00001f)
            compose.onNodeWithContentDescription("Play").assertIsDisplayed()
        } finally {
            device.setOrientationNatural();device.unfreezeRotation()
            prefs.edit().putBoolean("internetMaps",priorMaps).commit()
            compose.mainClock.autoAdvance=true
            runBlocking { repository.deleteTrip(trip.id);tripMemoryStore(context).delete(trip.id) }
        }
    }
}
