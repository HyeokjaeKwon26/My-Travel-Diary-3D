package com.traveler.core.tasks

import android.app.*
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.Uri
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.traveler.MainActivity
import com.traveler.core.database.TravelerDatabase
import com.traveler.core.media.*
import com.traveler.core.terrain.TripArchive
import com.traveler.core.model.*
import com.traveler.data.repository.TripRepositoryImpl
import com.traveler.feature.video.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.io.File

class TravelTaskService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var runner: Job? = null
    private var operation: Job? = null
    @Volatile private var activeId: String? = null
    private var lastProgress = 0L
    private var latestStartId = 0
    private var wakeLock: android.os.PowerManager.WakeLock? = null
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onCreate() {
        super.onCreate(); TravelTasks.initialize(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("travel_work", "여행 작업", NotificationManager.IMPORTANCE_LOW))
    }
    private fun notification(message: String, progress: Float = 0f, ongoing: Boolean = true): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).putExtra("showTasks", true), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        return NotificationCompat.Builder(this, "travel_work").setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentTitle("My Travel Diary").setContentText(message).setContentIntent(open).setOngoing(ongoing)
            .setOnlyAlertOnce(true).apply {
                if (ongoing) setProgress(100, (progress * 100).toInt(), progress <= 0f)
                activeId?.takeIf { ongoing }?.let { id -> addAction(android.R.drawable.ic_delete, "취소",
                    PendingIntent.getService(this@TravelTaskService, id.hashCode(), Intent(this@TravelTaskService, TravelTaskService::class.java)
                        .setAction("CANCEL").putExtra("id", id), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)) }
            }.build()
    }
    private fun foreground(video: Boolean, text: String) {
        val type = if (video && Build.VERSION.SDK_INT >= 35) ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROCESSING else ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
        if (Build.VERSION.SDK_INT >= 29) startForeground(7101, notification(text), type) else startForeground(7101, notification(text))
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        latestStartId = startId
        if (intent?.action == "CANCEL") { if (activeId == intent.getStringExtra("id")) operation?.cancel(); if (runner?.isActive != true) stopSelfResult(startId); return START_NOT_STICKY }
        if (runner?.isActive == true) return START_NOT_STICKY
        foreground(false, "여행 작업 준비 중")
        wakeLock = getSystemService(android.os.PowerManager::class.java).newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK, "$packageName:travel-work").apply { acquire(6 * 60 * 60 * 1000L) }
        if (runner?.isActive != true) runner = scope.launch {
            while (true) {
                val task = TravelTasks.state.value.lastOrNull { it.status == "QUEUED" } ?: break
                activeId = task.id
                operation = launch {
                    try {
                        foreground(task.kind == "VIDEO", task.label)
                        TravelTasks.update(this@TravelTaskService, task.id) { it.copy(status = "RUNNING", message = task.label) }
                        withContext(Dispatchers.IO) { execute(task) }
                        TravelTasks.update(this@TravelTaskService, task.id) { it.copy(status = "DONE", progress = 1f, message = "완료했습니다.") }
                    } catch (e: CancellationException) {
                        TravelTasks.update(this@TravelTaskService, task.id) { it.copy(status = "CANCELLED", message = "중단했습니다. 완료된 항목은 유지됩니다. 영상은 재시작하면 처음부터 제작합니다.") }
                    } catch (e: Exception) {
                        TravelTasks.update(this@TravelTaskService, task.id) { it.copy(status = "FAILED", message = e.message ?: "작업에 실패했습니다.") }
                    }
                }
                operation?.join(); operation = null
            }
            activeId = null
            stopForeground(STOP_FOREGROUND_REMOVE)
            val last = TravelTasks.state.value.firstOrNull()
            getSystemService(NotificationManager::class.java).notify(7102, notification(last?.let { "${it.label}: ${it.message}" } ?: "작업 완료", ongoing = false))
            wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null
            stopSelfResult(latestStartId)
        }
        return START_NOT_STICKY
    }
    private fun progress(id: String, value: Float, message: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastProgress < 500 && value < 1f) return
        lastProgress = now
        TravelTasks.update(this, id) { it.copy(progress = value.coerceIn(0f, 1f), message = message) }
        getSystemService(NotificationManager::class.java).notify(7101, notification(message, value))
    }
    private fun done(task: TravelTask, key: String, output: String) {
        TravelTasks.update(this, task.id) { it.copy(completed = it.completed + key, outputs = it.outputs + output) }
    }
    private suspend fun execute(task: TravelTask) {
        val repo = TripRepositoryImpl(TravelerDatabase.getDatabase(this))
        when (task.kind) {
            "KEEP", "BACKUP", "VERIFY" -> task.trips.forEachIndexed { index, id ->
                if (id !in task.completed) {
                    val trip = repo.getTripById(id) ?: error("여행을 찾을 수 없습니다.")
                    val report: (Int, Int) -> Unit = { n, total -> progress(task.id, (index + n.toFloat() / total.coerceAtLeast(1)) / task.trips.size, "${trip.title} · $n / $total") }
                    val output = when (task.kind) {
                        "KEEP" -> { TripMediaFiles.keep(this, trip, report); "trip:$id" }
                        "VERIFY" -> { val result = MediaMigration.inspect(this, trip, report); check(result.missing.isEmpty()) { "${trip.title}: 읽을 수 없는 파일 ${result.missing.size}개" }; "trip:$id" }
                        else -> BackupCatalog.exportToFolder(this, trip, Uri.parse(task.destination), report).toString()
                    }
                    done(task, id, output)
                }
            }
            "RESTORE" -> task.uris.forEachIndexed { index, value ->
                if (value !in task.completed) {
                    progress(task.id, index.toFloat() / task.uris.size, "백업 확인 · ${index + 1} / ${task.uris.size}")
                    val preview = BackupCatalog.preview(this, Uri.parse(value), repo.getAllTrips().first())
                    val expected = task.options[value]
                    check(expected == null || expected == preview.digest) { "선택 후 백업 파일이 바뀌었습니다. 다시 선택하세요." }
                    val recorded = BackupCatalog.recordedImport(this, preview.digest)?.takeIf { repo.getTripById(it) != null }
                    val id = if (recorded != null && task.options["allowDuplicate"] != "true") recorded else TripArchive.restore(this, Uri.parse(value), java.util.UUID.nameUUIDFromBytes("${task.operationId}:$value".toByteArray()).toString()) {
                        progress(task.id, index.toFloat() / task.uris.size, "${preview.title} · 미디어 $it 개 복원")
                    }
                    withContext(NonCancellable) { BackupCatalog.recordImport(this@TravelTaskService, preview.digest, id); done(task, value, "trip:$id") }
                    getSharedPreferences("migration", 0).edit().putString("pendingTrip", id).apply()
                }
            }
            "ADD_MEDIA" -> {
                MediaLibraryEditor.add(this, task.trips.single(), task.uris.map(Uri::parse), task.options["day"], task.options["visit"]) { n, count ->
                    progress(task.id, n.toFloat() / count.coerceAtLeast(1), "사진 추가 · $n / $count")
                }
                done(task, task.trips.single(), "trip:${task.trips.single()}")
            }
            "VIDEO" -> {
                val trip = repo.getTripById(task.trips.single()) ?: error("여행을 찾을 수 없습니다.")
                val prepared = tripMemoryStore(this, trip.id).prepare(trip)
                val resolved = TripMediaFiles.resolve(this, prepared)
                val items = resolved.days.flatMap { it.items }
                val model = com.traveler.feature.map.renderer.TravelMapRenderModel(
                    items.mapNotNull { when (it) { is TripDayItem.VisitItem -> it.visit; is TripDayItem.ContextualPhotosItem -> it.parentVisit; else -> null } }.distinctBy { it.id },
                    items.mapNotNull { when (it) { is TripDayItem.MovementItem -> it.segment; is TripDayItem.ContextualPhotosItem -> it.parentSegment; else -> null } }.distinctBy { it.id },
                    resolved.allMedia(), photoSelections = resolved.memorySnapshot?.selections)
                val file = TravelVideoExporter.exportVideo(this, resolved, model,
                    StoryDurationProfile.valueOf(task.options.getValue("profile")), task.options["music"] != "false",
                    VideoResolution.valueOf(task.options.getValue("resolution")), task.options["privacy"] != "false",
                    onProgress = { progress(task.id, it, "영상 제작 · ${(it * 100).toInt()}%") }) ?: error("영상을 만들지 못했습니다.")
                val destination = File(File(filesDir, "finished_videos").apply { mkdirs() }, "${task.id}.mp4")
                check(file.renameTo(destination)) { "완성 영상을 저장하지 못했습니다." }
                done(task, trip.id, destination.absolutePath)
            }
            else -> error("지원하지 않는 작업입니다.")
        }
    }
    override fun onTimeout(startId: Int, fgsType: Int) { operation?.cancel(); stopSelf() }
    override fun onDestroy() { scope.cancel(); wakeLock?.let { if (it.isHeld) it.release() }; wakeLock = null; super.onDestroy() }
}
