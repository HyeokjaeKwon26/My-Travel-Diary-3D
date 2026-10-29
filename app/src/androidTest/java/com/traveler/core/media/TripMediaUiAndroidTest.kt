package com.traveler.core.media

import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.traveler.core.model.*
import com.traveler.MainActivity
import com.traveler.feature.trip.TripMediaDialog
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertEquals
import java.io.File
import java.util.UUID

class TripMediaUiAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun storageActionAndBackupChoicesRemainReachable() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val id = UUID.randomUUID().toString()
        val file = File(context.filesDir, "$id.jpg")
        val bitmap = Bitmap.createBitmap(30, 50, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }; bitmap.recycle()
        val media = MediaItem("photo", TripMediaFiles.uri(context, file), file.name, "image/jpeg", null)
        val trip = Trip(id, "Test", "2026-01-01", "2026-01-01", 0.0, uncertainDateMedia = listOf(media), totalMediaCount = 1)
        try {
            assertEquals(TripMediaFiles.Status(0, 1, 0), runBlocking { TripMediaFiles.status(context, trip) })
            assertEquals(1, runBlocking { MediaMigration.inspect(context, trip).readable })
            compose.activityRule.scenario.onActivity { activity ->
                activity.setContent { MaterialTheme { TripMediaDialog(trip) {} } }
            }
            compose.waitForIdle()
            // Scroll the action into view before waiting for the asynchronous audit to enable it.
            compose.onNodeWithText("사진·동영상 모두 앱에 보관").performScrollTo()
            compose.waitUntil(15000) { compose.mainClock.advanceTimeByFrame(); compose.onAllNodes(hasText("사진·동영상 모두 앱에 보관") and isEnabled()).fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("사진·동영상 모두 앱에 보관").performClick()
            compose.waitUntil(10000) { compose.mainClock.advanceTimeByFrame(); compose.onAllNodesWithText("1개를 앱에 보관했습니다.").fetchSemanticsNodes().isNotEmpty() }
            compose.onNodeWithText("사진·동영상 포함 백업").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("여행 기록만 백업 (JSON)").performScrollTo().assertIsDisplayed().assertIsEnabled()
            compose.onNodeWithText("닫기").assertIsDisplayed().assertIsEnabled()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(context.getExternalFilesDir(null), "portable-media-dialog.png"))
        } catch (failure: Throwable) {
            val evidence = context.getExternalFilesDir(null)!!
            File(evidence, "media-ui-failure.txt").writeText(compose.onAllNodes(isRoot(), useUnmergedTree = true).printToString())
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(evidence, "media-ui-failure.png"))
            throw failure
        } finally { file.delete(); runBlocking { TripMediaFiles.delete(context, id) } }
    }
}
