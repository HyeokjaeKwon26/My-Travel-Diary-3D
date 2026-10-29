package com.traveler.feature.update

import android.app.Application
import androidx.activity.compose.setContent
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.traveler.MainActivity
import com.traveler.core.update.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.net.HttpURLConnection
import java.util.concurrent.atomic.AtomicInteger

class StartupUpdateAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun automaticChecksShowNewVersionAgainOnNextStartAndRespectOptOut() {
        val app = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application
        val calls = AtomicInteger()
        val release = UpdateRelease(applicationId = app.packageName, versionCode = AppUpdater(app).installedVersion + 1, versionName = "2.1.1", minSdk = 26,
            apkUrl = "https://github.com/${UpdatePolicy.repository(app.packageName)}/releases/download/v2.1.1/app.apk",
            apkBytes = 100, sha256 = "a".repeat(64), notes = "실행 시 새 버전 알림 확인")
        val updater = AppUpdater(app) { uri ->
            object : HttpURLConnection(uri.toURL()) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy() = false
                override fun getResponseCode() = 200
                override fun getInputStream() = if (uri.host == "api.github.com") {
                    calls.incrementAndGet()
                    """[{"draft":false,"prerelease":false,"assets":[{"name":"update-next.json","browser_download_url":"https://github.com/${UpdatePolicy.repository(app.packageName)}/releases/download/v2.1.1/update-next.json"}]}]""".byteInputStream()
                } else UpdatePolicy.json.encodeToString(release).byteInputStream()
            }
        }
        val model = UpdateViewModel(app, updater)
        try {
            compose.activityRule.scenario.onActivity { it.setContent { MaterialTheme { UpdateNotifications(model) } } }
            compose.runOnIdle { model.autoDownloadWifi(false); model.automatic(true); model.check(automatic = true) }
            compose.waitUntil(15000) { model.state.value.visible && !model.state.value.busy }
            compose.onNodeWithText("새 버전 2.1.1", substring = true).assertExists()
            assertEquals(1, calls.get())
            compose.runOnIdle { model.check(automatic = true) } // Existing alert must not be replaced.
            assertEquals(1, calls.get())
            compose.runOnIdle { model.close(); model.check(automatic = true) }
            compose.waitUntil(15000) { calls.get() == 2 && model.state.value.visible && !model.state.value.busy }
            compose.runOnIdle { model.close(); model.automatic(false); model.check(automatic = true) }
            assertFalse(model.state.value.visible); assertEquals(2, calls.get())
        } finally { compose.runOnIdle { model.cancel(); model.automatic(true); model.close() } }
    }
}
