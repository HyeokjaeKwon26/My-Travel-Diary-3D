package com.traveler.core.media

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import com.traveler.core.model.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest

/** Copies are optional and trip-owned; the original database references remain untouched. */
object TripMediaFiles {
    internal val lock = Mutex()
    const val MAX_FILE_BYTES = 512L * 1024 * 1024
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024
    const val MAX_MEDIA = 20_000
    private const val RESERVE_BYTES = 32L * 1024 * 1024
    fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    fun root(context: Context) = File(context.filesDir, "trip_media").apply { mkdirs() }
    fun directory(context: Context, tripId: String) = File(root(context), hash(tripId))
    fun name(media: MediaItem): String {
        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(media.mimeType)
            ?.takeIf { it.matches(Regex("[a-zA-Z0-9]{1,10}")) } ?: "bin"
        return hash(media.id) + "." + extension
    }
    fun uri(context: Context, file: File): String = FileProvider.getUriForFile(context,
        context.packageName + ".fileprovider", file).toString()

    fun resolveMedia(context: Context, tripId: String, media: MediaItem): MediaItem {
        val file = File(directory(context, tripId), name(media))
        if (file.isFile && file.length() > 0) return media.copy(contentUriString = uri(context, file))
        // Legacy demo pictures are bundled assets, not user gallery files or private app storage.
        val original = Uri.parse(media.contentUriString)
        if (original.scheme == "android.resource" && original.authority in setOf("com.traveler", "com.traveler.threed") &&
            original.path in setOf("/drawable/demo_boston", "/drawable/demo_flight", "/drawable/demo_niagara", "/drawable/demo_unassigned")) {
            return media.copy(contentUriString = original.buildUpon().authority(context.packageName).build().toString())
        }
        return media
    }

    fun resolve(context: Context, trip: Trip): Trip = trip.mapMedia { resolveMedia(context, trip.id, it) }.copy(
        memorySnapshot = trip.memorySnapshot?.let { snapshot -> snapshot.copy(selections = snapshot.selections.mapValues { (_, moments) ->
            moments.map { it.copy(photo = resolveMedia(context, trip.id, it.photo)) }
        }) }
    )

    data class Status(val saved: Int, val total: Int, val bytes: Long)
    /** Bundled sample illustrations are drawable XML, so export rendered image bytes, not binary XML disguised as JPEG. */
    internal fun openSource(context: Context, media: MediaItem): InputStream? {
        val uri = Uri.parse(media.contentUriString)
        if (uri.scheme != "android.resource" || media.isVideo) return context.contentResolver.openInputStream(uri)
        val bitmap = MediaBitmapDecoder.decode(context, media, 2048, 2048) ?: error("Cannot render ${media.fileName}")
        return try {
            val bytes = java.io.ByteArrayOutputStream()
            val format = if (media.mimeType == "image/png") android.graphics.Bitmap.CompressFormat.PNG else android.graphics.Bitmap.CompressFormat.JPEG
            check(bitmap.compress(format, 95, bytes)) { "Cannot save ${media.fileName}" }
            java.io.ByteArrayInputStream(bytes.toByteArray())
        } finally { bitmap.recycle() }
    }
    suspend fun status(context: Context, trip: Trip) = withContext(Dispatchers.IO) {
        val files = trip.allMedia().map { File(directory(context, trip.id), name(it)) }.filter { it.isFile && it.length() > 0 }
        Status(files.size, trip.allMedia().size, files.sumOf { it.length() })
    }

    /** Each file is committed atomically. Cancellation keeps completed copies and removes the partial one. */
    suspend fun keep(context: Context, trip: Trip, progress: (Int, Int) -> Unit = { _, _ -> }): Status =
        withContext(Dispatchers.IO) { lock.withLock {
            val media = resolve(context, trip).allMedia()
            require(media.size <= MAX_MEDIA) { "Too many media files in this journey" }
            val dir = directory(context, trip.id).apply { mkdirs() }
            var total = dir.listFiles().orEmpty().filter { !it.name.endsWith(".pending") }.sumOf { it.length() }
            media.forEachIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                val target = File(dir, name(item))
                if (!target.isFile || target.length() == 0L) {
                    MediaMigration.requireIdentity(context, item)
                    val pending = File(dir, name(item) + ".pending")
                    try {
                        val size = (openSource(context, item)
                            ?: error("Cannot read ${item.fileName}. Restore photo access and try again.")).use { input ->
                            pending.outputStream().use { output -> copy(input, output, minOf(MAX_FILE_BYTES, MAX_TOTAL_BYTES - total), dir) }
                        }
                        require(size > 0) { "Empty media file: ${item.fileName}" }
                        check(pending.renameTo(target)) { "Cannot save ${item.fileName}" }
                        total += size
                    } finally { pending.delete() }
                }
                progress(index + 1, media.size)
            }
            status(context, trip)
        } }

    internal suspend fun copy(input: InputStream, output: OutputStream, limit: Long, disk: File? = null): Long {
        val buffer = ByteArray(64 * 1024)
        var count = 0L
        while (true) {
            currentCoroutineContext().ensureActive()
            val n = input.read(buffer)
            if (n < 0) break
            count += n
            require(count <= limit) { "Media limit exceeded (512 MB per file, 2 GB per backup)" }
            if (disk != null) check(disk.usableSpace > RESERVE_BYTES + n) { "Not enough free storage" }
            output.write(buffer, 0, n)
        }
        return count
    }

    suspend fun delete(context: Context, tripId: String) = withContext(Dispatchers.IO) {
        lock.withLock { directory(context, tripId).deleteRecursively() }
    }
}

/** Includes undated and unassigned media, not only the photos chosen for playback. */
fun Trip.allMedia(): List<MediaItem> = (days.flatMap { day -> day.unassignedPhotos + day.items.flatMap { item ->
    when (item) {
        is TripDayItem.VisitItem -> item.photos
        is TripDayItem.MovementItem -> item.photos
        is TripDayItem.ContextualPhotosItem -> item.photos
        is TripDayItem.UnassignedPhotosItem -> item.photos
    }
} } + uncertainDateMedia).distinctBy { it.id }

fun Trip.mapMedia(transform: (MediaItem) -> MediaItem): Trip = copy(
    days = days.map { day -> day.copy(unassignedPhotos = day.unassignedPhotos.map(transform), items = day.items.map { item ->
        when (item) {
            is TripDayItem.VisitItem -> item.copy(photos = item.photos.map(transform))
            is TripDayItem.MovementItem -> item.copy(photos = item.photos.map(transform))
            is TripDayItem.ContextualPhotosItem -> item.copy(photos = item.photos.map(transform))
            is TripDayItem.UnassignedPhotosItem -> item.copy(photos = item.photos.map(transform))
        }
    }) }, uncertainDateMedia = uncertainDateMedia.map(transform), memorySnapshot = null
)
