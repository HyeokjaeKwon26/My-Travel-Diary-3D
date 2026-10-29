package com.traveler.core.update

import android.os.Build
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.security.MessageDigest

/** Optional emulator smoke fixture. The actual Home UI handles permission and installer launch. */
class UpdateInstallerAndroidTest {
    @Test fun prepareVerifiedCandidateForInstallerSmoke() = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("installerSmoke") == "true")
        assumeTrue(Build.FINGERPRINT.contains("generic") || Build.MODEL.contains("sdk"))
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fixture = File(context.getExternalFilesDir(null), "updater-fixtures/valid.apk")
        check(fixture.isFile) { "Prepare updater emulator fixtures first" }
        val release = UpdateRelease(applicationId = context.packageName,
            versionCode = AppUpdater(context).installedVersion + 1, versionName = "update-fixture", minSdk = 26,
            apkUrl = "https://github.com/${UpdatePolicy.repository(context.packageName)}/releases/download/test/app.apk",
            apkBytes = fixture.length(), sha256 = MessageDigest.getInstance("SHA-256").digest(fixture.readBytes())
                .joinToString("") { "%02x".format(it) }, notes = "Emulator installation verification only")
        val updater = AppUpdater(context) { uri -> object : HttpURLConnection(uri.toURL()) {
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun connect() {}
            override fun getResponseCode() = 200
            override fun getInputStream(): java.io.InputStream = fixture.inputStream()
        } }
        assertTrue(updater.download(release) { _, _ -> }.isFile)
        File(context.filesDir, "updater-data-probe.txt").writeText("preserve-existing-data")
    }
}
