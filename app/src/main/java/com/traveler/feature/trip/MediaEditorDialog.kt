package com.traveler.feature.trip

import android.net.Uri
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
import com.traveler.core.model.*
import com.traveler.core.tasks.*
import kotlinx.coroutines.*

@Composable fun MediaEditorDialog(trip: Trip, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<List<Uri>>(emptyList()) }
    var day by remember { mutableStateOf(trip.startDateIso) }
    var visit by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var editing by remember { mutableStateOf<MediaItem?>(null) }
    var clipEditing by remember { mutableStateOf<MediaItem?>(null) }
    var busy by remember { mutableStateOf(false) }
    var page by remember { mutableStateOf(0) }
    var revision by remember { mutableStateOf(0) }
    val media by produceState(initialValue = trip.allMedia(), trip, revision) {
        if (revision > 0) value = withContext(Dispatchers.IO) {
            val saved = com.traveler.data.repository.TripRepositoryImpl(com.traveler.core.database.TravelerDatabase.getDatabase(context)).getTripById(trip.id) ?: trip
            TripMediaFiles.resolve(context, saved).allMedia()
        }
    }
    val visits = remember(trip) { trip.days.flatMap { it.items }.filterIsInstance<TripDayItem.VisitItem>().map { it.visit }.distinctBy { it.id } }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        pending = uris; uris.forEach { BackupCatalog.retain(context, it) }; editing = null
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("사진·동영상 추가와 편집") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("추가한 파일은 앱에 복사합니다. 선택한 날짜·장소는 여행 안에서의 배치이며 원본 촬영 정보는 바꾸지 않습니다.")
            Button(enabled = !busy, onClick = { picker.launch(arrayOf("image/*", "video/*")) }) { Text("사진·동영상 추가") }
            if (pending.isNotEmpty() || editing != null) {
                Text(editing?.fileName ?: "${pending.size}개 선택됨")
                OutlinedTextField(value = day, onValueChange = { day = it }, label = { Text("날짜 YYYY-MM-DD · 비우면 미확정") })
                var expanded by remember { mutableStateOf(false) }
                Box {
                    TextButton(onClick = { expanded = true }) { Text(visits.firstOrNull { it.id == visit }?.placeName ?: "장소 미지정 ▾") }
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(text = { Text("장소 미지정") }, onClick = { visit = null; expanded = false })
                        visits.forEach { v -> DropdownMenuItem(text = { Text(v.placeName ?: "이름 없는 방문지") }, onClick = { visit = v.id; expanded = false }) }
                    }
                }
                Button(enabled = !busy, onClick = {
                    val date = day.trim().takeIf { it.isNotBlank() }
                    if (editing == null) runCatching {
                        TravelTasks.enqueue(context, TravelTask(kind = "ADD_MEDIA", label = "여행에 사진 추가", trips = listOf(trip.id), uris = pending.map { it.toString() },
                            options = mapOf("day" to (date ?: ""), "visit" to (visit ?: ""))))
                        pending = emptyList(); error = "작업 목록에서 추가 결과를 확인하세요."
                    }.onFailure { error = it.message ?: "추가하지 못했습니다." }
                    else scope.launch {
                        busy = true
                        try { MediaLibraryEditor.place(context, trip.id, editing!!.id, date, visit); revision++; editing = null; error = "저장했습니다. 닫으면 여행에 반영됩니다." }
                        catch (e: Exception) { error = e.message ?: "날짜와 장소를 확인하세요." }
                        finally { busy = false }
                    }
                }) { Text(if (editing == null) "선택한 파일 추가" else "날짜·장소 저장") }
            }
            HorizontalDivider()
            media.drop(page * 20).take(20).forEach { photo ->
                Text(photo.fileName, maxLines = 2)
                Text(if (Uri.parse(photo.contentUriString).authority == "${context.packageName}.fileprovider") "앱 보관본" else "원본 연결 · 보관 화면에서 상태 확인", style = MaterialTheme.typography.bodySmall)
                Row {
                    TextButton(enabled = !busy, onClick = { editing = photo; pending = emptyList(); day = photo.assignedDayIso ?: ""; visit = photo.matchedVisitId }) { Text("날짜·장소") }
                    if (photo.isVideo) TextButton(enabled = !busy, onClick = { clipEditing = photo }) { Text("동영상 구간·소리") }
                }
            }
            Row { TextButton(enabled = page > 0, onClick = { page-- }) { Text("이전") }; Text("${page + 1} / ${((media.size + 19) / 20).coerceAtLeast(1)}"); TextButton(enabled = (page + 1) * 20 < media.size, onClick = { page++ }) { Text("다음") } }
            if (error.isNotBlank()) Text(error)
        }
    }, confirmButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("닫기") } })
    clipEditing?.let { ClipEditorDialog(trip.id, it) { clipEditing = null; revision++ } }
}

@Composable fun ClipEditorDialog(tripId: String, media: MediaItem, onDismiss: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clip by remember { mutableStateOf(media.videoClip ?: VideoClip()) }
    var duration by remember { mutableStateOf(0L) }
    var message by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(media.id) {
        try { duration = withContext(Dispatchers.IO) {
            val r = android.media.MediaMetadataRetriever()
            try { r.setDataSource(context, Uri.parse(media.contentUriString)); r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L } finally { r.release() }
        }; clip = clip.copy(startMs = clip.startMs.coerceAtMost((duration - 1).coerceAtLeast(0)), durationMs = clip.durationMs.coerceAtMost(duration.coerceAtLeast(1))) }
        catch (e: Exception) { message = e.message ?: "동영상을 읽지 못했습니다." }
    }
    AlertDialog(onDismissRequest = { if (!busy) onDismiss() }, title = { Text("여행 영상에 넣을 동영상 구간") }, text = {
        Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState())) {
            Text(media.fileName)
            Row { Checkbox(clip.enabled, { clip = clip.copy(enabled = it) }); Text("선택 구간 재생 · 대표 장면에 포함") }
            Text("시작: ${"%.1f".format(clip.startMs / 1000f)}초 / 전체 ${"%.1f".format(duration / 1000f)}초")
            Slider(value = clip.startMs.toFloat(), onValueChange = { clip = clip.copy(startMs = it.toLong(), durationMs = clip.durationMs.coerceIn(minOf(3000L, (duration - it.toLong()).coerceAtLeast(1)), minOf(10000L, (duration - it.toLong()).coerceAtLeast(1)))) }, valueRange = 0f..(duration - 1000).coerceAtLeast(1).toFloat())
            Text("길이: ${"%.1f".format(clip.durationMs / 1000f)}초 · 최대 10초")
            val maximum = minOf(10000f, (duration - clip.startMs).toFloat().coerceAtLeast(1f))
            val minimum = minOf(3000f, maximum)
            Slider(value = clip.durationMs.toFloat().coerceIn(minimum, maximum), onValueChange = { clip = clip.copy(durationMs = it.toLong().coerceAtLeast(1)) }, valueRange = minimum..maximum)
            Text("원본 소리 ${(clip.originalVolume * 100).toInt()}%"); Slider(clip.originalVolume, { clip = clip.copy(originalVolume = it) })
            Text("이 구간 배경음악 ${(clip.musicVolume * 100).toInt()}%"); Slider(clip.musicVolume, { clip = clip.copy(musicVolume = it) })
            if (duration > 0) ClipVideoPreview(media, clip, Modifier.fillMaxWidth().height(180.dp))
            Text("날짜·장소에 연결된 동영상은 해당 장면에서 재생합니다. 원본에 소리가 없으면 배경음악만 재생합니다.")
            if (message.isNotBlank()) Text(message)
        }
    }, confirmButton = { TextButton(enabled = duration > 0 && !busy, onClick = { scope.launch {
        busy = true
        try { MediaLibraryEditor.setClip(context, tripId, media.id, clip.copy(durationMs = minOf(clip.durationMs, duration - clip.startMs).coerceAtLeast(1))); onDismiss() }
        catch (e: Exception) { message = e.message ?: "저장하지 못했습니다." }
        finally { busy = false }
    } }) { Text("저장") } }, dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("취소") } })
}

@Composable fun ClipVideoPreview(media: MediaItem, clip: VideoClip, modifier: Modifier) {
    val context = LocalContext.current
    val view = remember(media.contentUriString) { android.widget.VideoView(context) }
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(view, lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event -> if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) view.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer); view.stopPlayback() }
    }
    LaunchedEffect(view, clip) {
        view.setOnPreparedListener { it.setVolume(clip.originalVolume, clip.originalVolume); view.seekTo(clip.startMs.toInt()); view.start() }
        view.setVideoURI(Uri.parse(media.contentUriString))
        while (true) { delay(150); if (view.isPlaying && view.currentPosition >= clip.startMs + clip.durationMs) { view.pause(); view.seekTo(clip.startMs.toInt()) } }
    }
    androidx.compose.ui.viewinterop.AndroidView(factory = { view }, modifier = modifier, update = { it.setOnClickListener { if (view.isPlaying) view.pause() else view.start() } })
}
