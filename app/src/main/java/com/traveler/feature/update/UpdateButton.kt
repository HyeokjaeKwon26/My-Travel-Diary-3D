package com.traveler.feature.update

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.traveler.BuildConfig

@Composable
fun UpdateButton(model: UpdateViewModel = viewModel()) {
    IconButton(onClick = { model.check() }) { Icon(Icons.Default.SystemUpdate, contentDescription = "업데이트 확인") }
}

@Composable
fun UpdateNotifications(model: UpdateViewModel) {
    val state by model.state.collectAsState()
    val context = LocalContext.current
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (context.packageManager.canRequestPackageInstalls()) model.install(context::startActivity)
        else model.permissionDenied()
    }
    if (state.visible) UpdateDialog(state, onCheck = { model.check() }, onDownload = model::download,
        onInstall = {
            if (context.packageManager.canRequestPackageInstalls()) model.install(context::startActivity)
            else try {
                permission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}")))
            } catch (_: Exception) { model.permissionDenied() }
        }, onCancel = model::cancel, onClose = model::close, onAutomatic = model::automatic, onAutoDownloadWifi = model::autoDownloadWifi)
}

@Composable
internal fun UpdateDialog(state: UpdateUiState, onCheck: () -> Unit, onDownload: () -> Unit,
                          onInstall: () -> Unit, onCancel: () -> Unit, onClose: () -> Unit,
                          onAutomatic: (Boolean) -> Unit, onAutoDownloadWifi: (Boolean) -> Unit = {}) {
    AlertDialog(onDismissRequest = { if (!state.busy) onClose() }, title = { Text("앱 업데이트") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("현재 버전 ${BuildConfig.VERSION_NAME}")
            state.release?.let { release ->
                Text("새 버전 ${release.versionName} · ${release.apkBytes / (1024 * 1024)} MB")
                if (release.notes.isNotBlank()) Text(release.notes)
            }
            Text(state.message)
            if (state.busy) {
                if (state.downloading) {
                    LinearProgressIndicator(progress = { state.progress }, modifier = Modifier.fillMaxWidth())
                    Text("${(state.progress * 100).toInt()}% · 다운로드 후 파일과 서명을 확인합니다.")
                } else LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            if (state.release != null && !state.busy) Button(onClick = if (state.ready) onInstall else onDownload,
                modifier = Modifier.fillMaxWidth()) { Text(if (state.ready) "설치" else "업데이트 다운로드") }
            TextButton(onClick = onCheck, enabled = !state.busy) { Text("다시 확인") }
            Row {
                Checkbox(checked = state.automatic, onCheckedChange = onAutomatic)
                Text("앱 실행 시 업데이트 확인", Modifier.padding(top = 12.dp))
            }
            Row {
                Checkbox(checked = state.autoDownloadWifi, onCheckedChange = onAutoDownloadWifi, enabled = !state.busy)
                Text("실행 시 Wi-Fi에서 새 버전 자동 받기", Modifier.padding(top = 12.dp))
            }
            Text("자동 받기는 앱 실행 시 확인이 켜져 있을 때, 데이터 요금이 없는 Wi-Fi에서 동작합니다. 연결이 바뀌거나 앱이 종료되면 중단될 수 있으며 다시 받을 수 있습니다.")
            Text("공식 GitHub 배포 정보를 확인합니다. 여행 기록과 사진은 전송하지 않습니다. 설치는 Android 확인 화면에서 승인해야 합니다.")
        } }, confirmButton = { TextButton(enabled = !state.busy, onClick = onClose) { Text("나중에") } },
        dismissButton = { if (state.busy) TextButton(onClick = onCancel) { Text("취소") } })
}
