package com.traveler.feature.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.traveler.MainActivity
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.*
import com.traveler.feature.map.renderer.*
import com.traveler.feature.map.story.TravelStoryTimeline
import com.traveler.feature.video.TravelVideoRenderer
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.time.Instant

class PlaybackOverlayAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val start = Instant.parse("2026-08-20T12:00:00Z").toEpochMilli()
    private val visit = Visit("overlay-visit", "Niagara Falls", location = GeoPoint(43.08,-79.07),
        startTimestampEpochMs = start, endTimestampEpochMs = start + 3_600_000, timezoneId = "America/New_York")

    private fun photo(w: Int, h: Int): MediaItem {
        val file = File(context.cacheDir, "overlay-$w-$h.png")
        val bitmap = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap); canvas.drawColor(Color.GREEN)
        val p = Paint().apply { color=Color.RED }
        canvas.drawRect(0f,0f,w.toFloat(),h*.15f,p)
        p.color=Color.BLUE;canvas.drawRect(0f,h*.85f,w.toFloat(),h.toFloat(),p)
        p.color=Color.YELLOW;canvas.drawRect(0f,h*.15f,w*.15f,h*.85f,p)
        p.color=Color.MAGENTA;canvas.drawRect(w*.85f,h*.15f,w.toFloat(),h*.85f,p)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
        return MediaItem("photo-$w-$h", android.net.Uri.fromFile(file).toString(), file.name,"image/png",start+1_800_000,
            TimestampConfidence.EXIF_EXACT, "America/New_York", visit.location,
            LocationConfidenceLevel.GPS_EXACT,1f,matchedVisitId=visit.id,isRepresentative=true,assignedDayIso="2026-08-20")
    }

    @Test fun portraitPhotoFitsBelowPersistentDateAndCompactHeaderAtLargeFont() {
        val photo = photo(300,400)
        val timeline = TravelStoryTimeline.build(TravelMapRenderModel(listOf(visit),emptyList(),listOf(photo)))
        val state = timeline.evaluate(.5f).copy(activePhoto=photo,currentVisit=visit,episodeIndex=0,storyTimeMs=start,
            totalTripDistanceMeters=2_609_800.0,currentTraveledDistanceMeters=1_589_300.0)
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            val original = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(original.density,1.5f)) {
                MaterialTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Box(Modifier.width(360.dp).height(320.dp)) { PlaybackOverlays(state,timeline,"2026-08-16") }
                } }
            }
        } }
        compose.onNodeWithText("2026.08.20").assertIsDisplayed()
        compose.onNodeWithText("Day 5").assertIsDisplayed()
        compose.waitUntil(10_000) {
            val bounds=compose.onNodeWithTag("playback-photo").fetchSemanticsNode().boundsInRoot
            kotlin.math.abs(bounds.width/bounds.height-.75f)<.02f
        }
        val info=compose.onNodeWithTag("playback-info").fetchSemanticsNode().boundsInRoot
        val date=compose.onNodeWithTag("playback-date").fetchSemanticsNode().boundsInRoot
        val image=compose.onNodeWithTag("playback-photo").fetchSemanticsNode().boundsInRoot
        assertTrue("Date overlaps info",info.right <= date.left)
        assertTrue("Photo overlaps header",image.top > maxOf(info.bottom,date.bottom))
        val density=context.resources.displayMetrics.density
        // Layout/Coil success can precede the actual display frame; verify the pixels too.
        compose.waitUntil(10_000) {
            saveScreenshot("rc7-large-font-overlay.png")
            val shot=android.graphics.BitmapFactory.decodeFile(File(context.getExternalFilesDir(null),"rc7-large-font-overlay.png").path)
            val colors=mutableSetOf<Int>()
            for(y in 0 until shot.height step 3) for(x in 0 until shot.width step 3) colors.add(shot.getPixel(x,y))
            shot.recycle()
            listOf(Color.RED,Color.BLUE,Color.YELLOW,Color.MAGENTA).all { it in colors }
        }
        compose.waitForIdle()
        Thread.sleep(150) // Let the display commit the aspect-driven card resize too.
        saveScreenshot("rc7-large-font-overlay.png")
        assertTrue("Header became tall again: ${info.height/density} dp",info.height/density < 80)
    }

    @Test fun dateConfirmationStaysAboveSystemNavigationAndRemainsClickable() {
        compose.onNodeWithText("New Travel Story",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Select dates",substring=true).performScrollTo().performClick()
        compose.onNodeWithText("Use dates").assertIsDisplayed().assertIsEnabled()
        val button=compose.onNodeWithText("Use dates").fetchSemanticsNode().boundsInWindow
        val insets=compose.activity.run { ViewCompat.getRootWindowInsets(window.decorView) }
        val bottom=insets?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        saveScreenshot("rc7-date-picker.png")
        assertTrue("Confirmation under system navigation: $button, inset=$bottom",button.bottom < device.displayHeight-bottom)
        saveScreenshot("rc7-date-picker.png")
        compose.onNodeWithText("Use dates").performClick()
        compose.onNodeWithText("Select travel dates").assertDoesNotExist()
    }

    @Test fun seekingToStartKeepsDateUntilPlaybackIsClosed() {
        val prefs=context.getSharedPreferences("scene_preferences",0)
        val previous=prefs.getBoolean("internetMaps",true)
        prefs.edit().putBoolean("internetMaps",false).commit()
        try {
            val trip=com.traveler.feature.map.threed.CanyonDemo.trip()
            val segments=trip.days.flatMap { it.items }.filterIsInstance<TripDayItem.MovementItem>().map { it.segment }
            compose.activityRule.scenario.onActivity { activity -> activity.setContent {
                MaterialTheme { Box(Modifier.fillMaxSize().safeDrawingPadding()) {
                    TravelMapView(emptyList(),segments,initialPlaybackProgress=.4f,tripStartDateIso=trip.startDateIso,
                        modifier=Modifier.fillMaxWidth().height(320.dp))
                } }
            } }
            compose.onNodeWithText("2026.07.01").assertIsDisplayed()
            compose.onNodeWithText("Day 1").assertIsDisplayed()
            compose.onNode(SemanticsMatcher.keyIsDefined(androidx.compose.ui.semantics.SemanticsActions.SetProgress))
                .performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(0f) }
            compose.onNodeWithContentDescription("Play").assertIsDisplayed()
            compose.onNodeWithText("2026.07.01").assertIsDisplayed()
            compose.onNodeWithText("Day 1").assertIsDisplayed()
            compose.onNodeWithText("3D • Terrain").assertDoesNotExist()
            compose.onNodeWithContentDescription("Exit Playback").performClick()
            compose.onNodeWithTag("playback-date").assertDoesNotExist()
        } finally { prefs.edit().putBoolean("internetMaps",previous).commit() }
    }

    @Test fun videoPhotoKeepsAllFourEdgesInPortraitAndLandscape() {
        for ((pw,ph) in listOf(300 to 400, 800 to 200)) {
            val photo=photo(pw,ph)
            val model=TravelMapRenderModel(listOf(visit),emptyList(),listOf(photo))
            val timeline=TravelStoryTimeline.build(model)
            assertTrue("Fixture photo must be selected",timeline.photoMoments.isNotEmpty())
            val moment=timeline.photoMoments.first()
            for ((w,h) in listOf(720 to 1280,1280 to 720)) {
                val renderer=TravelVideoRenderer(context,context.assets.open("basemap_world.json"),model,timeline,tripStartDateIso="2026-08-16")
                val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
                try {
                    renderer.renderFrame(Canvas(bitmap),w,h,(moment.displayStartStorySeconds+moment.displayEndStorySeconds)/2,renderMap=false)
                    // Every border of the source photo survives; centre cropping would remove at least two.
                    for (color in listOf(Color.RED,Color.BLUE,Color.YELLOW,Color.MAGENTA)) {
                        var count=0
                        for(y in 0 until h step 2) for(x in w/2 until w step 2) if(bitmap.getPixel(x,y)==color) count++
                        assertTrue("Missing photo border $color in ${w}x$h for ${pw}x$ph",count>20)
                    }
                    File(context.getExternalFilesDir(null),"rc7-video-overlay-${w}x$h-${pw}x$ph.png").outputStream().use {
                        bitmap.compress(Bitmap.CompressFormat.PNG,100,it)
                    }
                } finally { bitmap.recycle();renderer.release() }
            }
        }
    }

    private fun saveScreenshot(name:String) {
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(context.getExternalFilesDir(null),name))
    }
}
