package com.traveler.core.tasks

import android.content.Context
import android.content.Intent
import android.util.AtomicFile
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.util.UUID

@Serializable
data class TravelTask(val id: String = UUID.randomUUID().toString(), val kind: String, val label: String,
    val trips: List<String> = emptyList(), val uris: List<String> = emptyList(), val destination: String = "",
    val options: Map<String, String> = emptyMap(), val status: String = "QUEUED", val progress: Float = 0f,
    val message: String = "대기 중", val completed: List<String> = emptyList(), val outputs: List<String> = emptyList(),
    val created: Long = System.currentTimeMillis(), val operationId: String = id) {
    val active get() = status in setOf("QUEUED", "RUNNING")
}

/** Durable user-started work. Process death requires an explicit retry, never a silent duplicate import. */
object TravelTasks {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val mutable = MutableStateFlow<List<TravelTask>>(emptyList())
    val state = mutable.asStateFlow()
    private var initialized = false
    private fun file(c: Context) = AtomicFile(File(c.filesDir, "travel-tasks.json"))
    @Synchronized fun initialize(c: Context) {
        if (initialized) return
        val f = file(c)
        val loaded = try { if (f.baseFile.exists()) json.decodeFromString<List<TravelTask>>(f.readFully().toString(Charsets.UTF_8)) else emptyList() }
        catch (e: Exception) {
            // Preserve unreadable history for diagnosis; never infer completion of interrupted work.
            if (f.baseFile.isFile) f.baseFile.copyTo(File(c.filesDir, "travel-tasks-damaged-${System.currentTimeMillis()}.json"))
            listOf(TravelTask(kind = "NOTICE", label = "작업 기록 복구", status = "FAILED", message = "작업 기록을 읽지 못했습니다. 여행 데이터는 유지됩니다. 백업·복원 결과를 확인한 후 새 작업을 시작하세요."))
        }
        mutable.value = loaded.map { if (it.active) it.copy(status = "INTERRUPTED", message = "앱 종료로 중단됐습니다. 다시 시작할 수 있습니다.") else it }
        initialized = true
        save(c, mutable.value)
    }
    private fun save(c: Context, value: List<TravelTask>) {
        val f = file(c); val stream = f.startWrite()
        try { stream.write(json.encodeToString(value).toByteArray()); f.finishWrite(stream) }
        catch (e: Exception) { f.failWrite(stream); throw e }
    }
    @Synchronized fun update(c: Context, id: String, transform: (TravelTask) -> TravelTask) {
        val next = mutable.value.map { if (it.id == id) transform(it) else it }
        save(c, next); mutable.value = next
    }
    @Synchronized fun enqueue(c: Context, task: TravelTask): String {
        initialize(c)
        require(mutable.value.none { it.active && it.operationId == task.operationId }) { "이미 재시작 중인 작업입니다." }
        require(mutable.value.count { it.active } < 10) { "진행 중인 작업을 마친 뒤 다시 시도하세요." }
        val next = (listOf(task) + mutable.value).let { list -> list.filter { it.active } + list.filterNot { it.active }.take(30) }.sortedByDescending { it.created }
        save(c, next); mutable.value = next
        try { ContextCompat.startForegroundService(c, Intent(c, TravelTaskService::class.java)) }
        catch (e: Exception) { update(c, task.id) { it.copy(status = "FAILED", message = "작업을 시작하지 못했습니다. 앱을 연 상태에서 다시 시도하세요.") }; throw e }
        return task.id
    }
    fun retry(c: Context, task: TravelTask) = enqueue(c, task.copy(id = UUID.randomUUID().toString(), status = "QUEUED", progress = 0f,
        message = "재시작 대기 중", created = System.currentTimeMillis()))
    fun cancel(c: Context, task: TravelTask) {
        if (task.status == "QUEUED") update(c, task.id) { it.copy(status = "CANCELLED", message = "취소했습니다.") }
        else c.startService(Intent(c, TravelTaskService::class.java).setAction("CANCEL").putExtra("id", task.id))
    }
}
