package com.traveler.feature.home

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
import com.traveler.core.database.TravelerDatabase
import com.traveler.core.media.*
import com.traveler.core.tasks.*
import com.traveler.data.repository.TripRepositoryImpl
import kotlinx.coroutines.*
import java.text.DateFormat
import java.util.Date

@Composable fun LibraryManagerDialog(onOpenTrip: (String) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val repo = remember { TripRepositoryImpl(TravelerDatabase.getDatabase(context)) }
    val all by remember { repo.getAllTrips() }.collectAsState(emptyList())
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(setOf<String>()) }
    var previews by remember { mutableStateOf<List<BackupPreview>>(emptyList()) }
    var restoreSelected by remember { mutableStateOf(setOf<String>()) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf("") }
    var trash by remember { mutableStateOf(TripTrash.ids(context)) }
    var deleting by remember { mutableStateOf<String?>(null) }
    val tasks by TravelTasks.state.collectAsState()
    fun submit(task: TravelTask) { runCatching { TravelTasks.enqueue(context, task); message = "작업을 시작했습니다. 첫 화면의 작업에서 확인할 수 있습니다." }.onFailure { message = it.message ?: "시작하지 못했습니다." } }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null && selected.isNotEmpty()) { BackupCatalog.retain(context, uri, true); submit(TravelTask(kind = "BACKUP", label = "여러 여행 백업", trips = selected.toList(), destination = uri.toString())) }
    }
    val files = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) scope.launch {
            busy = true; message = "백업 내용을 확인하고 있습니다. 큰 파일은 시간이 걸릴 수 있습니다."; previews = emptyList()
            try {
                require(uris.size <= 50) { "한 번에 최대 50개를 선택하세요." }
                previews = uris.map { uri -> BackupCatalog.retain(context, uri); BackupCatalog.preview(context, uri, all) }
                restoreSelected = previews.filterNot { it.similar || BackupCatalog.recordedImport(context, it.digest) != null }.map { it.uri }.toSet()
                message = "복원할 여행을 확인하세요. JSON은 사진 원본을 포함하지 않습니다. 중복 표시된 항목을 선택하면 새 여행으로 복원합니다."
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { message = e.message ?: "백업을 읽지 못했습니다." }
            finally { busy = false }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, title = { Text("백업·복원·휴지통") },
        text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("사진 포함 백업은 선택한 폴더에 여행별 ZIP으로 저장합니다. 완료된 여행부터 백업 날짜가 기록됩니다.")
            Row { TextButton(onClick = { selected = all.filterNot { it.id in trash }.map { it.id }.toSet() }) { Text("전체 선택") }; TextButton(onClick = { selected = emptySet() }) { Text("해제") } }
            all.filterNot { it.id in trash }.forEach { trip ->
                Row {
                    Checkbox(checked = trip.id in selected, onCheckedChange = { selected = if (it) selected + trip.id else selected - trip.id })
                    Column(Modifier.weight(1f)) {
                        Text(trip.title); Text("${trip.startDateIso} ~ ${trip.endDateIso} · 미디어 ${trip.totalMediaCount}개", style = MaterialTheme.typography.bodySmall)
                        val last = remember(tasks, trip.id) { BackupCatalog.lastBackup(context, trip.id) }
                        Text(if (last == 0L) "아직 사진 포함 백업 없음" else "최근 백업: ${DateFormat.getDateTimeInstance().format(Date(last))}", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            Button(enabled = selected.isNotEmpty() && !busy, onClick = { folder.launch(null) }) { Text("${selected.size}개 여행 사진 포함 백업") }
            OutlinedButton(enabled = selected.isNotEmpty() && !busy, onClick = { submit(TravelTask(kind = "KEEP", label = "선택 여행 사진 보관", trips = selected.toList())) }) { Text("선택한 여행 사진 모두 보관") }
            OutlinedButton(enabled = selected.isNotEmpty() && !busy, onClick = { submit(TravelTask(kind = "VERIFY", label = "사진 연결 검사", trips = selected.toList())) }) { Text("사진 연결 검사") }
            HorizontalDivider()
            Button(enabled = !busy, onClick = { files.launch(arrayOf("application/json", "application/zip", "application/x-zip-compressed", "application/octet-stream")) }) { Text("복원할 백업 선택") }
            previews.forEach { preview -> Row {
                Checkbox(checked = preview.uri in restoreSelected, onCheckedChange = { restoreSelected = if (it) restoreSelected + preview.uri else restoreSelected - preview.uri })
                Column(Modifier.weight(1f)) {
                    Text(preview.title); Text("${preview.start} ~ ${preview.end}\n미디어 ${preview.photos}개 · ${if (preview.containsMedia) "사진 포함" else "기록만 포함"}")
                    if (preview.similar || BackupCatalog.recordedImport(context, preview.digest) != null) Text("같거나 비슷한 여행이 있습니다.", color = MaterialTheme.colorScheme.error)
                }
            } }
            if (previews.isNotEmpty()) Button(enabled = restoreSelected.isNotEmpty() && !busy, onClick = {
                val chosen = previews.filter { it.uri in restoreSelected }
                submit(TravelTask(kind = "RESTORE", label = "여행 복원", uris = chosen.map { it.uri }, options = chosen.associate { it.uri to it.digest } + ("allowDuplicate" to "true")))
                previews = emptyList(); restoreSelected = emptySet()
            }) { Text("선택한 ${restoreSelected.size}개 여행 복원") }
            HorizontalDivider(); Text("휴지통 · ${trash.size}개")
            Text("휴지통에서는 기록과 앱 보관 사진을 유지합니다. 영구 삭제하면 앱 보관본도 삭제됩니다.", style = MaterialTheme.typography.bodySmall)
            all.filter { it.id in trash }.forEach { trip -> Column {
                Text(trip.title)
                Row { TextButton(onClick = { TripTrash.restore(context, trip.id); trash = TripTrash.ids(context) }) { Text("되살리기") }
                    TextButton(enabled = tasks.none { it.active && trip.id in it.trips }, onClick = { deleting = trip.id }) { Text("영구 삭제") } }
            } }
            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (message.isNotBlank()) Text(message)
        } }, confirmButton = { TextButton(onClick = onDismiss) { Text("닫기") } })
    deleting?.let { id -> AlertDialog(onDismissRequest = { deleting = null }, title = { Text("영구 삭제할까요?") }, text = { Text("이 여행과 앱에 보관한 사진을 복구할 수 없습니다. 갤러리 원본과 외부 백업은 유지됩니다.") },
        confirmButton = { TextButton(onClick = { deleting = null; scope.launch {
            try { repo.deleteTrip(id); TripMediaFiles.delete(context, id); tripMemoryStore(context).delete(id); TripTrash.restore(context, id); trash = TripTrash.ids(context) }
            catch (e: Exception) { message = e.message ?: "삭제하지 못했습니다." }
        } }) { Text("영구 삭제") } }, dismissButton = { TextButton(onClick = { deleting = null }) { Text("취소") } }) }
}
