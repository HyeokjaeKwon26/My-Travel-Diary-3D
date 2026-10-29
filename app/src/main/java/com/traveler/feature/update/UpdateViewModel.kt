package com.traveler.feature.update

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.traveler.BuildConfig
import com.traveler.core.update.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class UpdateUiState(
    val visible: Boolean = false,
    val busy: Boolean = false,
    val downloading: Boolean = false,
    val ready: Boolean = false,
    val progress: Float = 0f,
    val release: UpdateRelease? = null,
    val message: String = "",
    val automatic: Boolean = true,
    val autoDownloadWifi: Boolean = false
)

class UpdateViewModel @JvmOverloads constructor(application: Application, private val updater: AppUpdater = AppUpdater(application)) : AndroidViewModel(application) {
    private val prefs = application.getSharedPreferences("app_updates", 0)
    private val mutable = MutableStateFlow(UpdateUiState(automatic = prefs.getBoolean("automatic", true), autoDownloadWifi = prefs.getBoolean("autoDownloadWifi", false)))
    val state = mutable.asStateFlow()
    private var work: Job? = null

    fun automatic(enabled: Boolean) {
        prefs.edit().putBoolean("automatic", enabled).apply()
        mutable.update { it.copy(automatic = enabled) }
    }

    fun autoDownloadWifi(enabled: Boolean) {
        prefs.edit().putBoolean("autoDownloadWifi", enabled).apply()
        mutable.update { it.copy(autoDownloadWifi = enabled) }
    }
    private fun onWifi(): Boolean {
        val manager = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)
        val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
        return capabilities.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) && !manager.isActiveNetworkMetered
    }

    fun check(automatic: Boolean = false) {
        if (mutable.value.busy) { if (!automatic) mutable.update { it.copy(visible = true) }; return }
        if (automatic && !UpdatePolicy.shouldCheckOnStart(mutable.value.automatic, mutable.value.busy, mutable.value.visible)) return
        mutable.update { it.copy(visible = !automatic || it.visible, busy = true, message = "새 버전을 확인하고 있습니다.") }
        work = viewModelScope.launch {
            var prefetch = false
            try {
                val pending = updater.recover()
                if (pending != null) {
                    mutable.update { it.copy(visible = true, release = pending, ready = true, message = "받아 둔 업데이트가 있습니다. 설치 버튼을 눌러 계속하세요.") }
                } else {
                    val result = updater.check(BuildConfig.VERSION_NAME.contains('-'))
                    prefetch = automatic && result.available != null && mutable.value.autoDownloadWifi && onWifi()
                    mutable.update { it.copy(visible = it.visible || result.available != null, release = result.available, ready = false,
                        message = when {
                            result.available != null -> "새 버전을 사용할 수 있습니다. 다운로드는 버튼을 눌렀을 때 시작합니다."
                            result.needsNewerAndroid -> "새 버전은 더 높은 Android 버전이 필요합니다."
                            !result.hasMetadata -> "아직 앱 내 업데이트용 배포 정보가 게시되지 않았습니다."
                            else -> "현재 설치된 버전보다 새로운 배포가 없습니다."
                        }) }
                }
            } catch (e: CancellationException) { mutable.update { it.copy(message = "확인을 취소했습니다. 다시 시도할 수 있습니다.") }; throw e }
            catch (e: Exception) { mutable.update { it.copy(message = e.message ?: "네트워크 연결을 확인한 뒤 다시 시도하세요.") } }
            finally { mutable.update { it.copy(busy = false) } }
            if (prefetch && mutable.value.autoDownloadWifi) beginDownload(wifiOnly = true)
        }
    }

    fun download() = beginDownload(wifiOnly = false)
    private fun beginDownload(wifiOnly: Boolean) {
        val release = mutable.value.release ?: return
        if (mutable.value.busy) return
        mutable.update { it.copy(busy = true, downloading = true, ready = false, progress = 0f, message = if (wifiOnly) "Wi-Fi로 업데이트를 미리 받고 있습니다. 완료 후 설치를 눌러 주세요." else "다운로드 중… 모바일 데이터가 사용될 수 있습니다.") }
        work = viewModelScope.launch {
            try {
                val downloadClient = if (wifiOnly) {
                    check(onWifi()) { "Wi-Fi 연결을 확인하세요." }
                    val manager = getApplication<Application>().getSystemService(android.net.ConnectivityManager::class.java)
                    val network = manager.activeNetwork ?: error("Wi-Fi 연결이 끊겼습니다.")
                    // Pin every redirect/socket to this Wi-Fi network; never fall back to cellular.
                    AppUpdater(getApplication()) { uri -> network.openConnection(uri.toURL()) as java.net.HttpURLConnection }
                } else updater
                downloadClient.download(release) { done, total ->
                    check(!wifiOnly || (mutable.value.autoDownloadWifi && onWifi())) { "Wi-Fi 자동 받기가 중단됐습니다. Wi-Fi 연결과 설정을 확인한 뒤 다시 다운로드하세요." }
                    mutable.update { it.copy(progress = done.toFloat() / total) }
                }
                mutable.update { it.copy(ready = true, message = "파일과 서명을 확인했습니다. 설치를 눌러 Android 확인 화면으로 이동하세요.") }
            } catch (e: CancellationException) {
                mutable.update { it.copy(message = "다운로드를 취소했습니다. 다시 시도할 수 있습니다.") }; throw e
            } catch (e: Exception) { mutable.update { it.copy(message = e.message ?: "다운로드하지 못했습니다. 다시 시도하세요.") } }
            finally { mutable.update { it.copy(busy = false, downloading = false) } }
        }
    }

    fun install(open: (Intent) -> Unit) {
        val release = mutable.value.release ?: return
        if (mutable.value.busy || !mutable.value.ready) return
        mutable.update { it.copy(busy = true, message = "설치 파일을 다시 확인하고 있습니다.") }
        work = viewModelScope.launch {
            try {
                val file = updater.verifyReady(release)
                open(updater.installerIntent(file))
                mutable.update { it.copy(message = "Android 설치 화면에서 업데이트를 확인하세요. 설치를 취소했다면 다시 설치를 누를 수 있습니다.") }
            } catch (e: CancellationException) { mutable.update { it.copy(message = "확인을 취소했습니다. 다시 시도할 수 있습니다.") }; throw e }
            catch (e: Exception) { mutable.update { it.copy(ready = false, message = e.message ?: "설치 화면을 열 수 없습니다. 다시 다운로드하세요.") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun permissionDenied() { mutable.update { it.copy(message = "업데이트 설치에는 이 앱의 ‘알 수 없는 앱 설치’ 허용이 필요합니다. 기존 앱은 그대로 유지됩니다.") } }
    fun cancel() { work?.cancel() }
    fun close() { if (!mutable.value.busy) mutable.update { it.copy(visible = false) } }
}
