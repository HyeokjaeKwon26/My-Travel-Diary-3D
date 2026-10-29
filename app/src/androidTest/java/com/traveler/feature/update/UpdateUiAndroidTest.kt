package com.traveler.feature.update

import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.traveler.MainActivity
import com.traveler.core.update.UpdateRelease
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class UpdateUiAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    @Test fun downloadAndInstallRequireSeparateActionsAndRemainReachable() {
        var downloaded = false; var installed = false
        val state = mutableStateOf(UpdateUiState(visible = true, release = UpdateRelease(applicationId = "com.traveler",
            versionCode = 100, versionName = "test", minSdk = 26, apkUrl = "", apkBytes = 90_000_000,
            sha256 = "", notes = "새 기능과 수정 사항\n".repeat(12)), message = "새 버전을 사용할 수 있습니다."))
        compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme {
            UpdateDialog(state.value, {}, { downloaded = true; state.value = state.value.copy(ready = true) },
                { installed = true }, {}, {}, { state.value = state.value.copy(automatic = it) })
        } } }
        assertFalse(downloaded); assertFalse(installed)
        compose.onNodeWithText("업데이트 다운로드").performScrollTo().performClick()
        assertTrue(downloaded); assertFalse(installed)
        compose.onNodeWithText("설치").performScrollTo().performClick()
        assertTrue(installed)
        compose.onNodeWithText("나중에").assertIsDisplayed().assertIsEnabled()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(File(context.getExternalFilesDir(null), "updater-dialog.png"))
    }
}
