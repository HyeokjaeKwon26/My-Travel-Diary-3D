package com.traveler.core.media

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.traveler.core.model.MediaItem
import com.traveler.core.model.Trip
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import java.io.File

/** Never guesses a replacement from a reused MediaStore ID or a similar filename. */
object MediaMigration {
    data class Missing(val media: MediaItem, val reason: String)
    data class Audit(val total: Int, val readable: Int, val missing: List<Missing>)

    fun displayName(context: Context, uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        }
    }.getOrNull()

    fun requireIdentity(context: Context, media: MediaItem) {
        val uri = Uri.parse(media.contentUriString)
        require(uri.authority != "media" || displayName(context, uri) == media.fileName) {
            "${media.fileName}: 갤러리 파일 이름이 백업과 다릅니다. 사진 연결 확인에서 원본을 직접 선택하세요."
        }
    }

    suspend fun inspect(context: Context, trip: Trip, progress: (Int, Int) -> Unit = { _, _ -> }): Audit = withContext(Dispatchers.IO) {
        val media = TripMediaFiles.resolve(context, trip).allMedia()
        val missing = mutableListOf<Missing>()
        media.forEachIndexed { index, item ->
            currentCoroutineContext().ensureActive()
            val uri = Uri.parse(item.contentUriString)
            val reason = try {
                val readable = context.contentResolver.openInputStream(uri)?.use { it.read() >= 0 } == true
                when {
                    !readable -> "원본 파일을 읽을 수 없습니다."
                    uri.authority == "media" && displayName(context, uri) != item.fileName -> "갤러리 파일 이름이 백업과 다릅니다. 원본을 직접 확인하세요."
                    else -> null
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { "원본 또는 사진 접근 권한이 없습니다." }
            if (reason != null) missing += Missing(item, reason)
            progress(index + 1, media.size)
        }
        Audit(media.size, media.size - missing.size, missing)
    }

    /** User explicitly selected and confirmed this exact file; preserve all trip metadata and IDs. */
    suspend fun connect(context: Context, trip: Trip, media: MediaItem, selected: Uri) = withContext(Dispatchers.IO) {
        TripMediaFiles.lock.withLock {
            require(trip.allMedia().any { it.id == media.id })
            val actual = context.contentResolver.getType(selected)
            require(actual == media.mimeType || (actual == "image/jpg" && media.mimeType == "image/jpeg")) {
                "원본과 같은 형식의 사진·동영상을 선택하세요. (원본: ${media.mimeType})"
            }
            val directory = TripMediaFiles.directory(context, trip.id).apply { mkdirs() }
            val target = File(directory, TripMediaFiles.name(media))
            check(!target.exists() || target.length() == 0L) { "이미 보관한 원본이 있습니다. 덮어쓰지 않았습니다." }
            val pending = File(directory, target.name + ".pending")
            val total = directory.listFiles().orEmpty().filter { !it.name.endsWith(".pending") }.sumOf { it.length() }
            try {
                val count = (context.contentResolver.openInputStream(selected) ?: error("선택한 파일을 읽을 수 없습니다.")).use { input ->
                    pending.outputStream().use { output -> TripMediaFiles.copy(input, output,
                        minOf(TripMediaFiles.MAX_FILE_BYTES, TripMediaFiles.MAX_TOTAL_BYTES - total), directory) }
                }
                require(count > 0) { "빈 파일은 연결할 수 없습니다." }
                currentCoroutineContext().ensureActive()
                check(pending.renameTo(target)) { "사진을 보관하지 못했습니다." }
            } finally { pending.delete() }
        }
    }
}
