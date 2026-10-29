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
import com.traveler.core.tasks.*
import com.traveler.core.media.TripMediaFiles
import android.content.Intent
import android.net.Uri
import java.io.File

@Composable fun TaskCenterButton(onOpenTrip: (String) -> Unit) {
    val context = LocalContext.current
    remember { TravelTasks.initialize(context); true }
    val tasks by TravelTasks.state.collectAsState()
    var open by remember { mutableStateOf((context as? android.app.Activity)?.intent?.getBooleanExtra("showTasks", false) == true) }
    var error by remember { mutableStateOf("") }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    TextButton(onClick = { open = true; if (android.os.Build.VERSION.SDK_INT >= 33) notifications.launch(android.Manifest.permission.POST_NOTIFICATIONS) }) { Text("작업${tasks.count { it.active }.takeIf { it > 0 }?.let { " $it" } ?: ""}") }
    if (open) AlertDialog(onDismissRequest = { open = false }, title = { Text("진행 중인 작업·완료 기록") },
        text = { Column(Modifier.heightIn(max = 560.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            if (tasks.isEmpty()) Text("진행한 작업이 없습니다.")
            tasks.forEach { task ->
                Text(task.label, style = MaterialTheme.typography.titleSmall); Text(task.message)
                if (task.active) { LinearProgressIndicator(progress = { task.progress }, modifier = Modifier.fillMaxWidth()); TextButton(onClick = { TravelTasks.cancel(context, task) }) { Text("작업 중단") } }
                if (task.kind != "NOTICE" && task.status in setOf("FAILED", "INTERRUPTED", "CANCELLED")) TextButton(onClick = { runCatching { TravelTasks.retry(context, task) }.onFailure { error = it.message ?: "재시작하지 못했습니다." } }) { Text(if (task.kind == "VIDEO") "처음부터 다시 제작" else "완료된 항목 유지하고 다시 시작") }
                task.outputs.distinct().forEach { output -> TextButton(onClick = {
                    runCatching {
                        if (output.startsWith("trip:")) { open = false; onOpenTrip(output.removePrefix("trip:")) }
                        else {
                            val video = output.startsWith("/") || output.startsWith(context.filesDir.absolutePath)
                            val uri = if (video) { val file = File(output); check(file.exists()) { "파일이 없습니다." }; Uri.parse(TripMediaFiles.uri(context, file)) } else Uri.parse(output)
                            if (video) context.startActivity(Intent(context, com.traveler.feature.video.VideoPlayerActivity::class.java).putExtra("videoPath", output))
                            else context.startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/zip").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
                        }
                    }.onFailure { error = it.message ?: "열 수 있는 앱이 없습니다." }
                }) { Text(if (output.startsWith("trip:")) "여행 열기" else if (task.kind == "VIDEO") "완성 영상 보기" else "백업 파일 열기") } }
                HorizontalDivider()
            }
            if (error.isNotBlank()) Text(error)
        } }, confirmButton = { TextButton(onClick = { open = false }) { Text("닫기") } })
}
