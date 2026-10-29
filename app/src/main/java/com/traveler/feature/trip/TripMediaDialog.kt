package com.traveler.feature.trip

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.traveler.core.media.*
import com.traveler.core.model.Trip
import kotlinx.coroutines.*

@Composable
fun TripMediaDialog(trip: Trip, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var status by remember { mutableStateOf<TripMediaFiles.Status?>(null) }
    var progress by remember { mutableStateOf("") }
    var message by remember { mutableStateOf("") }
    var job by remember { mutableStateOf<Job?>(null) }
    var busy by remember { mutableStateOf(false) }
    var audit by remember { mutableStateOf<MediaMigration.Audit?>(null) }
    var page by remember { mutableStateOf(0) }
    var editor by remember { mutableStateOf(false) }
    if (editor) MediaEditorDialog(trip) { editor = false; onDismiss() }
    var selectedMedia by remember { mutableStateOf<com.traveler.core.model.MediaItem?>(null) }
    var selectedUri by remember { mutableStateOf<android.net.Uri?>(null) }
    suspend fun refresh() {
        val result = withContext(Dispatchers.IO) {
            MediaMigration.inspect(context, trip) to TripMediaFiles.status(context, trip)
        }
        // Publish the two related results together, including with an unconfined test dispatcher.
        withContext(Dispatchers.Main.immediate) {
            audit = result.first
            status = result.second
        }
    }
    LaunchedEffect(trip.id) {
        job = currentCoroutineContext()[Job]
        busy = true
        progress = "사진 연결을 확인하고 있습니다."
        try { refresh() }
        catch (e: CancellationException) { throw e }
        catch (e: Exception) { message = e.message ?: "사진 연결 상태를 확인하지 못했습니다." }
        finally { withContext(NonCancellable + Dispatchers.Main.immediate) { busy = false; progress = "" } }
    }
    DisposableEffect(Unit) { onDispose { job?.cancel() } }

    fun runTask(action: suspend () -> String) {
        busy = true
        message = ""
        progress = "준비 중…"
        job = scope.launch {
            try { message = action() }
            catch (e: CancellationException) { message = "취소했습니다. 완료된 보관 파일은 유지됩니다."; throw e }
            catch (e: Exception) { message = e.message ?: "완료하지 못했습니다. 권한과 저장 공간을 확인하세요." }
            finally {
                withContext(NonCancellable + Dispatchers.Main.immediate) {
                    try { refresh() }
                    catch (e: Exception) { message = e.message ?: "사진 연결 상태를 확인하지 못했습니다." }
                    finally { busy = false; progress = "" }
                }
            }
        }
    }
    val permissions = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        runTask { "사진 접근 권한을 확인했습니다. 아래 연결 상태를 확인하세요." }
    }
    val reconnect = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        selectedUri = uri
        if (uri == null) selectedMedia = null
    }
    val photoBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/zip")) { uri ->
        if (uri != null) runTask {
            PortableTripArchive.export(context, trip, uri) { done, total -> progress = "백업 준비 · $done / $total" }
            "사진·동영상을 포함한 백업을 저장했습니다."
        }
    }
    val jsonBackup = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) runTask {
            com.traveler.core.terrain.TripArchive.export(context, trip, uri)
            "여행 기록을 저장했습니다. 이 JSON 백업에는 사진·동영상 파일이 없습니다."
        }
    }
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("사진 보관·백업") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedButton(enabled = !busy, onClick = { editor = true }) { Text("사진 추가·날짜·장소·동영상 편집") }
                Text("이전 확인: 기존 앱과 갤러리 원본은 그대로 유지하세요. 기록 복원과 사진 파일 보관은 별도 단계입니다.")
                audit?.let { result ->
                    Text("사진 연결: ${result.readable} / ${result.total}개 읽기 가능 · 확인 필요 ${result.missing.size}개")
                    if (result.missing.isEmpty()) Text("연결 확인을 마쳤습니다. 사진 내용을 직접 확인한 뒤 아래에서 앱 보관과 ZIP 백업을 완료하세요.")
                    else {
                        Text("권한을 먼저 허용하세요. 그래도 남는 항목은 원본 파일을 직접 선택해야 합니다. 삭제된 사진을 JSON에서 복구할 수는 없습니다.")
                        OutlinedButton(enabled = !busy, onClick = {
                            val requested = when {
                                android.os.Build.VERSION.SDK_INT >= 34 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO, android.Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
                                android.os.Build.VERSION.SDK_INT >= 33 -> arrayOf(android.Manifest.permission.READ_MEDIA_IMAGES, android.Manifest.permission.READ_MEDIA_VIDEO)
                                else -> arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE)
                            }
                            permissions.launch(requested)
                        }) { Text("사진·동영상 접근 허용") }
                        val current = page.coerceAtMost((result.missing.size - 1) / 5)
                        result.missing.drop(current * 5).take(5).forEach { missing ->
                            Text("${missing.media.fileName}\n${missing.reason}")
                            TextButton(enabled = !busy, onClick = {
                                selectedMedia = missing.media
                                reconnect.launch(arrayOf(missing.media.mimeType))
                            }) { Text("이 파일 직접 연결") }
                        }
                        Row {
                            TextButton(enabled = current > 0 && !busy, onClick = { page = current - 1 }) { Text("이전") }
                            Text("${current + 1} / ${(result.missing.size + 4) / 5}", Modifier.padding(top = 12.dp))
                            TextButton(enabled = (current + 1) * 5 < result.missing.size && !busy, onClick = { page = current + 1 }) { Text("다음") }
                        }
                    }
                }
                TextButton(enabled = !busy, onClick = { runTask { "사진 연결 상태를 다시 확인했습니다." } }) { Text("사진 연결 다시 확인") }
                HorizontalDivider()
                Text("여행에 연결된 사진·동영상을 앱에 복사하면 갤러리 원본을 삭제해도 볼 수 있습니다. 복사하지 않은 파일은 원본과 접근 권한이 필요합니다.")
                status?.let { Text("앱에 보관: ${it.saved} / ${it.total}개 · ${android.text.format.Formatter.formatFileSize(context, it.bytes)}") }
                Text("보관할 때 추가 저장 공간이 필요합니다. 여행이나 앱을 삭제하면 보관본도 삭제됩니다. 다른 기기로 옮기려면 사진 포함 백업을 별도로 저장하세요.")
                OutlinedButton(enabled = !busy && audit?.missing?.isEmpty() == true && status?.let { it.saved < it.total } == true,
                    onClick = { runTask {
                        val result = TripMediaFiles.keep(context, trip) { done, total -> progress = "보관 중 · $done / $total" }
                        "${result.saved}개를 앱에 보관했습니다."
                    } }, modifier = Modifier.fillMaxWidth()) { Text("사진·동영상 모두 앱에 보관") }
                TextButton(enabled = !busy && audit?.missing?.isEmpty() == true, onClick = {
                    runCatching { com.traveler.core.tasks.TravelTasks.enqueue(context, com.traveler.core.tasks.TravelTask(kind = "KEEP", label = "사진 백그라운드 보관", trips = listOf(trip.id))); message = "작업 목록에서 진행 상황을 확인하세요." }.onFailure { message = it.message ?: "작업을 시작하지 못했습니다." }
                }) { Text("다른 화면에서도 계속 보관") }
                HorizontalDivider()
                Text("사진 포함 백업은 2D·3D 앱의 첫 화면 Restore에서 복원할 수 있습니다. 위치 기록과 원본 미디어가 포함되므로 안전한 곳에 보관하세요.")
                Text("파일당 최대 512 MB, 미디어 합계 2 GB. 백업 준비에는 비슷한 크기의 임시 공간이 필요합니다. 읽을 수 없는 파일이 있으면 완료로 처리하지 않습니다.")
                Button(enabled = !busy && audit?.missing?.isEmpty() == true, onClick = { photoBackup.launch("journey-${trip.startDateIso}.travel.zip") },
                    modifier = Modifier.fillMaxWidth()) { Text("사진·동영상 포함 백업") }
                TextButton(enabled = !busy, onClick = { jsonBackup.launch("journey-${trip.startDateIso}.travel.json") },
                    modifier = Modifier.fillMaxWidth()) { Text("여행 기록만 백업 (JSON)") }
                if (busy) { LinearProgressIndicator(Modifier.fillMaxWidth()); Text(progress) }
                if (message.isNotBlank()) Text(message)
            }
        },
        confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("닫기") } },
        dismissButton = { if (busy) TextButton(onClick = { job?.cancel() }) { Text("취소") } }
    )
    val pendingMedia = selectedMedia
    val pendingUri = selectedUri
    if (pendingMedia != null && pendingUri != null) AlertDialog(
        onDismissRequest = { selectedUri = null; selectedMedia = null }, title = { Text("이 원본을 연결할까요?") },
        text = { Column(Modifier.verticalScroll(rememberScrollState())) {
            Text("기록 속 파일: ${pendingMedia.fileName}")
            Text("선택한 파일: ${MediaMigration.displayName(context, pendingUri) ?: "선택한 원본"}")
            coil.compose.AsyncImage(model = pendingUri, contentDescription = "연결할 원본 미리보기", modifier = Modifier.fillMaxWidth().height(160.dp))
            Text("같은 사진·동영상인지 확인하세요. 이 여행의 기록은 유지하고 선택한 파일을 새 앱 안에 복사합니다.")
        } }, confirmButton = { TextButton(onClick = {
            selectedUri = null; selectedMedia = null
            runTask { MediaMigration.connect(context, trip, pendingMedia, pendingUri); "원본을 연결하고 새 앱에 보관했습니다." }
        }) { Text("연결하고 보관") } }, dismissButton = { TextButton(onClick = { selectedUri = null; selectedMedia = null }) { Text("취소") } })
}
