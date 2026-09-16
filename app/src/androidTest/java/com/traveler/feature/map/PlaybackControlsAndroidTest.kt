package com.traveler.feature.map

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.traveler.MainActivity
import com.traveler.feature.map.threed.CanyonDemo
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlaybackControlsAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun hidesOnIdleRevealsWithoutPausingAndKeepsPausedControls() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val prefs = context.getSharedPreferences("scene_preferences", 0)
        val original = prefs.getBoolean("internetMaps", true)
        prefs.edit().putBoolean("internetMaps", false).commit()
        val trip = CanyonDemo.trip()
        val items = trip.days.flatMap { it.items }
        val base = items.filterIsInstance<com.traveler.core.model.TripDayItem.VisitItem>().first().visit
        // A long story avoids wall-clock playback ending on slow emulator hosts.
        val visits = List(30) { i -> base.copy(id = "controls-$i",
            startTimestampEpochMs = base.startTimestampEpochMs + i * 86_400_000L,
            endTimestampEpochMs = base.endTimestampEpochMs + i * 86_400_000L) }
        val segments = items.filterIsInstance<com.traveler.core.model.TripDayItem.MovementItem>().map { it.segment }
        compose.mainClock.autoAdvance = false
        try {
            compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme {
                TravelMapView(visits, segments, modifier = Modifier.fillMaxSize(),
                    initialIsPlaying = true, fullscreen = true, onToggleFullscreen = {})
            } } }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(3_500)
            compose.onNodeWithTag("playback-controls").assertDoesNotExist()
            compose.onNodeWithTag("playback-surface").performTouchInput { click(center) }
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
            compose.onNodeWithTag("playback-seek").performTouchInput { down(center) }
            compose.mainClock.advanceTimeBy(4_500)
            compose.onNodeWithTag("playback-controls").assertIsDisplayed()
            compose.onNodeWithTag("playback-seek").performTouchInput { up() }
            compose.mainClock.advanceTimeBy(200)
            compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
            compose.onNodeWithContentDescription("Pause").performClick()
            compose.mainClock.advanceTimeBy(5_000)
            compose.onNodeWithContentDescription("Play").assertIsDisplayed()
            compose.onNodeWithTag("playback-seek").performSemanticsAction(SemanticsActions.SetProgress) { it(.4f) }
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithContentDescription("Play").assertIsDisplayed()
            compose.onNodeWithContentDescription("Play").performClick()
            compose.mainClock.advanceTimeBy(100)
            // Seeking while playing resumes; seeking while paused above stayed paused.
            compose.onNodeWithTag("playback-seek").performSemanticsAction(SemanticsActions.SetProgress) { it(.6f) }
            compose.mainClock.advanceTimeBy(100)
            compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(2_000)
            compose.onNodeWithContentDescription("Playback speed").performClick()
            compose.mainClock.advanceTimeBy(2_000)
            compose.onNodeWithContentDescription("Pause").assertIsDisplayed()
            compose.mainClock.advanceTimeBy(1_500)
            compose.onNodeWithTag("playback-controls").assertDoesNotExist()
            compose.onNodeWithTag("playback-surface").performClick() // accessibility action
            compose.mainClock.advanceTimeBy(300)
            compose.onNodeWithTag("playback-seek").performSemanticsAction(SemanticsActions.SetProgress) { it(1f) }
            compose.mainClock.advanceTimeBy(5_000)
            compose.onNodeWithContentDescription("Play").assertIsDisplayed()
            compose.onNodeWithContentDescription("Exit Playback").performClick()
            compose.mainClock.advanceTimeBy(500)
            compose.onNodeWithContentDescription("Start Playback").assertIsDisplayed()
        } finally {
            compose.mainClock.autoAdvance = true
            prefs.edit().putBoolean("internetMaps", original).commit()
        }
    }

    @Test fun compactControlsFitSmallPhoneAndWideScreenWithAccessibleTargets() {
        fun show(width: Int, fontScale: Float) {
            compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme {
                val density = LocalDensity.current.density
                CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                    Box(Modifier.fillMaxSize()) {
                        PlaybackControls(.4f, "3:18 / 8:16", true, 1f, true, true, true,
                            {}, {}, {}, {}, {}, {}, {}, {}, {}, Modifier.width(width.dp))
                    }
                }
            } } }
        }
        show(320, 1.5f)
        val bar = compose.onNodeWithTag("playback-controls").fetchSemanticsNode().boundsInRoot
        for (label in listOf("Pause", "Restart", "Playback speed", "Mute music", "Exit fullscreen", "Exit Playback")) {
            val node = compose.onNodeWithContentDescription(label).assertIsDisplayed().fetchSemanticsNode()
            assertTrue("$label is outside controls", node.boundsInRoot.left >= bar.left && node.boundsInRoot.right <= bar.right)
            assertTrue("$label target too small", node.boundsInRoot.height >= 48 * compose.activity.resources.displayMetrics.density - 1)
        }
        val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        compose.activityRule.scenario.onActivity { it.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE }
        compose.waitUntil(15_000) { device.displayWidth > device.displayHeight }
        show(700, 1f)
        val clock = compose.onNodeWithTag("playback-time").fetchSemanticsNode().boundsInRoot
        val pause = compose.onNodeWithContentDescription("Pause").fetchSemanticsNode().boundsInRoot
        assertTrue("Wide controls should use one row", clock.center.y >= pause.top && clock.center.y <= pause.bottom)
    }
}
