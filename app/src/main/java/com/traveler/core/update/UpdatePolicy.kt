package com.traveler.core.update

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

@Serializable
data class UpdateRelease(
    val schemaVersion: Int = 1,
    val applicationId: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apkUrl: String,
    val apkBytes: Long,
    val sha256: String,
    val notes: String = ""
)

object UpdatePolicy {
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    const val MANIFEST_ASSET = "update-next.json"
    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    fun repository(applicationId: String) = when (applicationId) {
        "com.traveler.next" -> "HyeokjaeKwon26/My-Travel-Diary"
        "com.traveler.threed.next" -> "HyeokjaeKwon26/My-Travel-Diary-3D"
        else -> error("지원하지 않는 앱입니다.")
    }
    fun assetUrl(url: String, repository: String): Boolean = runCatching {
        val uri = URI(url)
        uri.scheme == "https" && uri.host == "github.com" && uri.port == -1 &&
            uri.userInfo == null && uri.fragment == null && uri.query == null &&
            uri.rawPath.startsWith("/$repository/releases/download/") &&
            !uri.rawPath.contains("..") && !uri.rawPath.contains("%2f", true) &&
            !uri.rawPath.contains("%5c", true)
    }.getOrDefault(false)

    fun validate(release: UpdateRelease, applicationId: String) {
        require(release.schemaVersion == 1 && release.applicationId == applicationId) { "다른 앱의 업데이트 정보입니다." }
        require(release.versionCode > 0 && release.versionName.isNotBlank() && release.versionName.length <= 100)
        require(release.minSdk in 26..100 && release.apkBytes in 1..MAX_APK_BYTES) { "지원하지 않는 업데이트 파일입니다." }
        require(release.sha256.matches(Regex("[0-9a-fA-F]{64}"))) { "업데이트 체크섬이 없습니다." }
        require(assetUrl(release.apkUrl, repository(applicationId))) { "공식 배포 주소가 아닙니다." }
        require(release.notes.length <= 20_000)
    }

    fun shouldCheckOnStart(enabled: Boolean, busy: Boolean, visible: Boolean) = enabled && !busy && !visible

    fun verifyIdentity(expected: UpdateRelease, packageName: String, versionCode: Long,
                       installedVersion: Long, currentSigners: Set<String>, candidateSigners: Set<String>) {
        require(packageName == expected.applicationId) { "2D·3D 앱 종류가 일치하지 않습니다." }
        require(versionCode == expected.versionCode && versionCode > installedVersion) { "설치된 앱보다 새로운 버전이 아닙니다." }
        require(currentSigners.isNotEmpty() && currentSigners == candidateSigners) {
            "기존 앱과 서명 키가 달라 업데이트할 수 없습니다. 기존 앱을 삭제하지 말고 배포자에게 문의하세요."
        }
    }
}
