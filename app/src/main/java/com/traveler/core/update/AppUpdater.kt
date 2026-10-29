package com.traveler.core.update

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.FileProvider
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URI
import java.security.MessageDigest
import java.util.zip.ZipFile

/** Only public release metadata is fetched. APK download and installation require a user action. */
class AppUpdater(private val context: Context,
                 private val openConnection: (URI) -> HttpURLConnection = { it.toURL().openConnection() as HttpURLConnection }) {
    private val appId = context.packageName
    private val repository = UpdatePolicy.repository(appId)
    private val dir get() = File(context.cacheDir, "app_updates").apply { mkdirs() }
    private val apk get() = File(dir, "pending.apk")
    private val metadata get() = File(dir, "pending.json")
    val installedVersion: Long get() = version(context.packageManager.getPackageInfo(appId, 0))

    data class Check(val available: UpdateRelease?, val hasMetadata: Boolean, val needsNewerAndroid: Boolean)

    suspend fun check(includePrereleases: Boolean): Check = withContext(Dispatchers.IO) {
        val releases = UpdatePolicy.json.parseToJsonElement(text("https://api.github.com/repos/$repository/releases?per_page=30", 2 * 1024 * 1024)).jsonArray
        val candidates = mutableListOf<UpdateRelease>()
        // RC builds follow public RC releases too; stable builds only follow stable releases.
        for (element in releases) {
            currentCoroutineContext().ensureActive()
            val release = element.jsonObject
            if (release["draft"]?.jsonPrimitive?.booleanOrNull == true ||
                (!includePrereleases && release["prerelease"]?.jsonPrimitive?.booleanOrNull == true)) continue
            val asset = release["assets"]?.jsonArray?.firstOrNull {
                it.jsonObject["name"]?.jsonPrimitive?.content == UpdatePolicy.MANIFEST_ASSET
            }?.jsonObject ?: continue
            val url = asset["browser_download_url"]?.jsonPrimitive?.content ?: continue
            require(UpdatePolicy.assetUrl(url, repository)) { "업데이트 정보 주소가 올바르지 않습니다." }
            val candidate = UpdatePolicy.json.decodeFromString<UpdateRelease>(text(url, 64 * 1024))
            UpdatePolicy.validate(candidate, appId)
            candidates += candidate
        }
        val newer = candidates.filter { it.versionCode > installedVersion }
        Check(newer.filter { it.minSdk <= Build.VERSION.SDK_INT }.maxByOrNull { it.versionCode },
            candidates.isNotEmpty(), newer.any { it.minSdk > Build.VERSION.SDK_INT })
    }

    private suspend fun connection(url: String): HttpURLConnection {
        var next = url
        repeat(6) {
            currentCoroutineContext().ensureActive()
            val uri = URI(next)
            require(uri.scheme == "https" && uri.userInfo == null && uri.port == -1 && uri.host in setOf(
                "api.github.com", "github.com", "release-assets.githubusercontent.com", "objects.githubusercontent.com")) {
                "허용되지 않은 다운로드 주소입니다."
            }
            val connection = openConnection(uri)
            connection.instanceFollowRedirects = false
            connection.connectTimeout = 15_000
            connection.readTimeout = 15_000
            connection.setRequestProperty("User-Agent", "MyTravelDiary-Android-Updater")
            connection.setRequestProperty("Accept", if (uri.host == "api.github.com") "application/vnd.github+json" else "application/octet-stream")
            try {
                val status = connection.responseCode
                if (status in listOf(301, 302, 303, 307, 308)) {
                    next = uri.resolve(connection.getHeaderField("Location") ?: error("다운로드 주소가 없습니다.")).toString()
                    connection.disconnect()
                } else {
                    check(status == 200) { if (status == 403 || status == 429) "업데이트 서버의 요청 한도에 도달했습니다. 나중에 다시 확인하세요." else "업데이트 서버에 연결하지 못했습니다. (HTTP $status)" }
                    return connection
                }
            } catch (e: Exception) { connection.disconnect(); throw e }
        }
        error("다운로드 주소 이동이 너무 많습니다.")
    }

    private suspend fun text(url: String, max: Int): String {
        val connection = connection(url)
        try {
            return connection.inputStream.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    require(output.size() + n <= max) { "업데이트 정보가 너무 큽니다." }
                    output.write(buffer, 0, n)
                }
                output.toString("UTF-8")
            }
        } finally { connection.disconnect() }
    }

    suspend fun download(release: UpdateRelease, progress: (Long, Long) -> Unit): File = withContext(Dispatchers.IO) {
        UpdatePolicy.validate(release, appId)
        val part = File(dir, "download.part")
        metadata.delete(); apk.delete(); part.delete()
        val connection = connection(release.apkUrl)
        try {
            check(dir.usableSpace > release.apkBytes + 32 * 1024 * 1024) { "업데이트를 받을 저장 공간이 부족합니다." }
            val hash = MessageDigest.getInstance("SHA-256")
            var bytes = 0L
            connection.inputStream.use { input -> part.outputStream().use { output ->
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val n = input.read(buffer)
                    if (n < 0) break
                    bytes += n
                    require(bytes <= release.apkBytes) { "업데이트 파일 크기가 일치하지 않습니다." }
                    check(dir.usableSpace > 32 * 1024 * 1024) { "저장 공간이 부족합니다." }
                    output.write(buffer, 0, n); hash.update(buffer, 0, n)
                    progress(bytes, release.apkBytes)
                }
            } }
            require(bytes == release.apkBytes && hex(hash.digest()).equals(release.sha256, true)) { "업데이트 파일이 손상되었습니다. 다시 다운로드하세요." }
            verifyApk(part, release)
            currentCoroutineContext().ensureActive()
            check(part.renameTo(apk)) { "업데이트 파일을 저장하지 못했습니다." }
            metadata.writeText(UpdatePolicy.json.encodeToString(release))
            apk
        } finally { connection.disconnect(); part.delete() }
    }

    suspend fun recover(): UpdateRelease? = withContext(Dispatchers.IO) {
        if (!metadata.isFile || !apk.isFile) return@withContext null
        try {
            require(metadata.length() <= 64 * 1024)
            val release = UpdatePolicy.json.decodeFromString<UpdateRelease>(metadata.readText())
            verifyReady(release)
            release
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { metadata.delete(); apk.delete(); null }
    }

    suspend fun verifyReady(release: UpdateRelease): File = withContext(Dispatchers.IO) {
        UpdatePolicy.validate(release, appId)
        require(apk.length() == release.apkBytes) { "다운로드 파일이 없습니다. 다시 다운로드하세요." }
        val hash = MessageDigest.getInstance("SHA-256")
        apk.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                currentCoroutineContext().ensureActive()
                val n = input.read(buffer); if (n < 0) break
                hash.update(buffer, 0, n)
            }
        }
        require(hex(hash.digest()).equals(release.sha256, true)) { "다운로드 파일이 손상되었습니다. 다시 다운로드하세요." }
        verifyApk(apk, release)
        apk
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, release: UpdateRelease) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = context.packageManager
        val current = pm.getPackageInfo(appId, flags)
        val candidate = pm.getPackageArchiveInfo(file.path, flags) ?: error("설치할 수 없는 APK입니다.")
        fun signers(info: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28)
            info.signingInfo?.apkContentsSigners else info.signatures).orEmpty()
            .map { hex(MessageDigest.getInstance("SHA-256").digest(it.toByteArray())) }.toSet()
        UpdatePolicy.verifyIdentity(release, candidate.packageName, version(candidate), version(current), signers(current), signers(candidate))
        require(candidate.versionName == release.versionName && candidate.applicationInfo!!.minSdkVersion == release.minSdk && release.minSdk <= Build.VERSION.SDK_INT) { "업데이트의 Android 버전 정보가 일치하지 않습니다." }
        ZipFile(file).use { zip ->
            val abis = zip.entries().asSequence().filter { it.name.startsWith("lib/") && it.name.endsWith(".so") }
                .map { it.name.split('/')[1] }.toSet()
            require(abis.isEmpty() || Build.SUPPORTED_ABIS.any { it in abis }) { "이 휴대폰에서 사용할 수 없는 APK입니다." }
        }
    }

    fun installerIntent(file: File): Intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(FileProvider.getUriForFile(context, "$appId.fileprovider", file), "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

    companion object {
        @Suppress("DEPRECATION")
        private fun version(info: PackageInfo) = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        private fun hex(bytes: ByteArray) = bytes.joinToString("") { "%02x".format(it) }
    }
}
