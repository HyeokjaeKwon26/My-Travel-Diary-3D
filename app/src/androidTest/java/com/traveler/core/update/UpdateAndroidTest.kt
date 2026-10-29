package com.traveler.core.update

import android.content.Intent
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest

/** Run tools/prepare_update_test_fixtures.py against the test emulator first. */
class UpdateAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val dir get() = File(context.cacheDir, "app_updates")
    private fun fixture(name: String) = File(context.getExternalFilesDir(null), "updater-fixtures/$name.apk").also {
        check(it.isFile) { "Prepare updater emulator fixtures first" }
    }
    private fun release(file: File) = UpdateRelease(applicationId = context.packageName,
        versionCode = AppUpdater(context).installedVersion + 1, versionName = "update-fixture", minSdk = 26,
        apkUrl = "https://github.com/${UpdatePolicy.repository(context.packageName)}/releases/download/test/app.apk",
        apkBytes = file.length(), sha256 = MessageDigest.getInstance("SHA-256").digest(file.readBytes()).joinToString("") { "%02x".format(it) })
    private fun updater(file: File, release: UpdateRelease = release(file), redirect: String? = null): AppUpdater = AppUpdater(context) { uri ->
        object : HttpURLConnection(uri.toURL()) {
            override fun disconnect() {}
            override fun usingProxy() = false
            override fun connect() {}
            override fun getResponseCode() = if (redirect == null) 200 else 302
            override fun getHeaderField(name: String?) = if (name == "Location") redirect else null
            override fun getInputStream(): java.io.InputStream = when {
                uri.host == "api.github.com" -> """[{"draft":false,"prerelease":true,"assets":[{"name":"update-next.json","browser_download_url":"https://github.com/${UpdatePolicy.repository(context.packageName)}/releases/download/test/update-next.json"}]}]""".byteInputStream()
                uri.path.endsWith("update-next.json") -> UpdatePolicy.json.encodeToString(release).byteInputStream()
                else -> file.inputStream()
            }
        }
    }
    @Test fun checksRcChannelAndAndroidCompatibility() = runBlocking {
        val file = fixture("valid"); val updater = updater(file)
        assertEquals(release(file), updater.check(true).available)
        assertFalse(updater.check(false).hasMetadata)
        val incompatible = updater(file, release(file).copy(minSdk = 100)).check(true)
        assertNull(incompatible.available); assertTrue(incompatible.needsNewerAndroid)
    }
    @Test fun verifiedDownloadSurvivesRecreationAndGrantsInstallerReadAccess() = runBlocking {
        val file = fixture("valid"); val release = release(file); val updater = updater(file)
        try {
            val downloaded = updater.download(release) { _, _ -> }
            assertEquals(release, AppUpdater(context).recover())
            val intent = updater.installerIntent(downloaded)
            assertEquals(Intent.ACTION_VIEW, intent.action)
            assertEquals("application/vnd.android.package-archive", intent.type)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
            context.contentResolver.openInputStream(intent.data!!)!!.use { assertTrue(it.read() >= 0) }
            downloaded.appendBytes(byteArrayOf(1))
            assertNull(AppUpdater(context).recover())
            assertFalse(downloaded.exists())
        } finally { dir.deleteRecursively() }
    }
    @Test fun rejectsWrongSignerWrongAppAndForgedVersion() = runBlocking {
        for (name in listOf("wrong-signer", "wrong-package", "valid")) {
            val file = fixture(name)
            val release = release(file).let { if (name == "valid") it.copy(versionCode = it.versionCode + 1) else it }
            try { updater(file).download(release) { _, _ -> }; fail("Invalid APK accepted: $name") }
            catch (_: IllegalArgumentException) { }
            assertFalse(File(dir, "pending.apk").exists())
            assertFalse(File(dir, "download.part").exists())
        }
    }
    @Test fun rejectsBadHashAndUnsafeRedirect() = runBlocking {
        val file = fixture("valid")
        try { updater(file).download(release(file).copy(sha256 = "0".repeat(64))) { _, _ -> }; fail("Bad hash accepted") }
        catch (_: IllegalArgumentException) { }
        try { updater(file, redirect = "http://github.com/unsafe").download(release(file)) { _, _ -> }; fail("HTTP redirect accepted") }
        catch (_: IllegalArgumentException) { }
        assertFalse(File(dir, "pending.apk").exists())
        assertFalse(File(dir, "download.part").exists())
    }
    @Test fun cancellationRemovesPartialAndAllowsRetry() = runBlocking {
        val file = fixture("valid"); val updater = updater(file)
        try {
            val task = launch { updater.download(release(file)) { _, _ -> cancel() } }
            task.join()
            assertTrue(task.isCancelled)
            assertFalse(File(dir, "download.part").exists())
            assertFalse(File(dir, "pending.apk").exists())
            assertTrue(updater.download(release(file)) { _, _ -> }.isFile)
        } finally { dir.deleteRecursively() }
    }
}
