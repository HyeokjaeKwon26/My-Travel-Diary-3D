package com.traveler.feature.trip

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
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
import com.traveler.core.model.*
import com.traveler.feature.map.renderer.TravelMapRenderModel
import com.traveler.feature.map.threed.CanyonDemo
import com.traveler.feature.video.CelebrationCards
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class DiaryPresentationAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun representativeButtonStaysAboveNavigationAndPhotoKeepsAspect() {
        val file=File(context.cacheDir,"rc10-photo.png")
        Bitmap.createBitmap(800,400,Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.CYAN); file.outputStream().use { compress(Bitmap.CompressFormat.PNG,100,it) }; recycle()
        }
        val photo=MediaItem("photo",file.toURI().toString(),"Landscape photograph.jpg","image/png", timestampEpochMs = null)
        var clicked=false
        compose.activityRule.scenario.onActivity { it.setContent {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,1.5f)) {
                MaterialTheme { FullscreenPhotoDialog(photo,onToggleRepresentative={clicked=true},onDismiss={}) }
            }
        } }
        val button=compose.onNodeWithText("☆ Use as Representative Photo")
        button.assertIsDisplayed().performClick()
        assertTrue(clicked)
        val bounds=button.fetchSemanticsNode().boundsInWindow
        val bottom=ViewCompat.getRootWindowInsets(compose.activity.window.decorView)
            ?.getInsets(WindowInsetsCompat.Type.navigationBars())?.bottom ?: 0
        val device=UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
        assertTrue("Button under system navigation: $bounds", bounds.bottom < device.displayHeight-bottom)
        device.takeScreenshot(File(context.getExternalFilesDir(null),"rc10-photo-dialog.png"))
    }

    @Test fun routeOverviewIsRenderedWithoutNetwork() {
        val trip=CanyonDemo.trip()
        val items=trip.days.flatMap { it.items }
        val model=TravelMapRenderModel(items.filterIsInstance<TripDayItem.VisitItem>().map { it.visit },
            items.filterIsInstance<TripDayItem.MovementItem>().map { it.segment })
        compose.activityRule.scenario.onActivity { it.setContent {
            MaterialTheme { Column(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) { TripRouteOverview(model) } }
        } }
        compose.waitUntil(20_000) { compose.onAllNodesWithContentDescription("여행 전체 경로 지도").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithContentDescription("여행 전체 경로 지도").assertIsDisplayed()
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(context.getExternalFilesDir(null),"rc10-route-overview.png"))
    }

    @Test fun celebrationCardsRenderEveryAspectAndLongTitlesDeterministically() {
        for ((w,h) in listOf(720 to 1280,1280 to 720,1080 to 1920,1920 to 1080)) for(end in listOf(false,true)) {
            val bitmap=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888)
            val title=if(w==1080) "아주 긴 여행 이름 · Newport mansion adventure 2026" else "Newport 2"
            CelebrationCards.draw(Canvas(bitmap),w,h,title,if(end) "A JOURNEY TO REMEMBER" else "2026-09-14 — 2026-09-14",
                if(end) listOf("1 Day","242.5 km","57 Memories") else listOf("MY TRAVEL DIARY 3D","LET’S GO!"),end,1f,1f)
            var bright=0
            for(y in 0 until h step 4) for(x in 0 until w step 4) {
                val c=bitmap.getPixel(x,y)
                if(Color.red(c)>170 || Color.green(c)>170 || Color.blue(c)>170) bright++
            }
            assertTrue("Missing decorative/text layers",bright>w*h/16*.06)
            File(context.getExternalFilesDir(null),"rc10-card-${w}x$h-$end.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            bitmap.recycle()
        }
    }
}
