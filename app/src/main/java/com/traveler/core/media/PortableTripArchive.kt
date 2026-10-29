package com.traveler.core.media

import android.content.Context
import android.net.Uri
import com.traveler.core.database.TravelerDatabase
import com.traveler.core.model.*
import com.traveler.data.repository.TripRepositoryImpl
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.*
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
internal data class PackedMedia(val id: String, val path: String, val bytes: Long, val sha256: String)
@Serializable
internal data class PortableManifest(val format: String = "MyTravelDiaryMedia", val version: Int = 2,
    val trip: Trip, val media: List<PackedMedia>)

/** Shared format for 2D and 3D. No network access, gallery permissions or source-device URIs on restore. */
object PortableTripArchive {
    private const val MAX_MANIFEST = 32L * 1024 * 1024
    private val json = Json { encodeDefaults = true }
    private fun digest() = MessageDigest.getInstance("SHA-256")
    private fun MessageDigest.hex() = digest().joinToString("") { "%02x".format(it) }
    internal fun safeEntry(path: String) = path.matches(Regex("media/[0-9]{1,5}\\.bin"))

    suspend fun export(context: Context, trip: Trip, destination: Uri, progress: (Int, Int) -> Unit = { _, _ -> }) =
        withContext(Dispatchers.IO) { TripMediaFiles.lock.withLock {
            val source = TripMediaFiles.resolve(context, trip)
            val media = source.allMedia()
            require(media.size <= TripMediaFiles.MAX_MEDIA) { "Too many media files" }
            val packed = mutableListOf<PackedMedia>()
            val temp = File.createTempFile("journey-", ".travel.zip", context.cacheDir)
            try {
                var total = 0L
                ZipOutputStream(BufferedOutputStream(temp.outputStream())).use { zip ->
                    zip.setLevel(0) // Photos and videos are already compressed.
                    media.forEachIndexed { index, item ->
                        MediaMigration.requireIdentity(context, item)
                        require(item.mimeType.startsWith("image/") || item.isVideo) { "Unsupported media: ${item.fileName}" }
                        val path = "media/$index.bin"
                        val hash = digest()
                        zip.putNextEntry(ZipEntry(path))
                        val size = (TripMediaFiles.openSource(context, item)
                            ?: error("Cannot read ${item.fileName}. Keep the original or restore photo access.")).use { stream ->
                            DigestInputStream(stream, hash).use { input ->
                                TripMediaFiles.copy(input, zip, minOf(TripMediaFiles.MAX_FILE_BYTES,
                                    TripMediaFiles.MAX_TOTAL_BYTES - total), context.cacheDir)
                            }
                        }
                        require(size > 0) { "Cannot back up an empty media file: ${item.fileName}" }
                        zip.closeEntry()
                        total += size
                        packed += PackedMedia(item.id, path, size, hash.hex())
                        progress(index + 1, media.size)
                    }
                    val paths = packed.associateBy { it.id }
                    val portable = source.mapMedia { it.copy(contentUriString = "content://travel-backup/${paths.getValue(it.id).path}") }
                    val bytes = json.encodeToString(PortableManifest(trip = portable, media = packed)).toByteArray()
                    require(bytes.size <= MAX_MANIFEST) { "Journey metadata exceeds 32 MB" }
                    zip.putNextEntry(ZipEntry("manifest.json")); zip.write(bytes); zip.closeEntry()
                }
                currentCoroutineContext().ensureActive()
                // Do not touch the destination until all source media have been read successfully.
                (context.contentResolver.openOutputStream(destination, "wt") ?: error("Cannot write backup")).use { output ->
                    temp.inputStream().use { TripMediaFiles.copy(it, output, TripMediaFiles.MAX_TOTAL_BYTES + MAX_MANIFEST + 16 * 1024 * 1024) }
                }
            } finally { temp.delete() }
        } }

    suspend fun restore(context: Context, source: Uri, validate: (Trip) -> Unit,
                        restoreId: String = UUID.randomUUID().toString(), progress: (Int) -> Unit = {}): String = withContext(Dispatchers.IO) {
        TripMediaFiles.lock.withLock {
            val newId = restoreId
            require(runCatching { UUID.fromString(newId).toString() == newId }.getOrDefault(false))
            if (TripRepositoryImpl(TravelerDatabase.getDatabase(context)).getTripById(newId) != null) return@withLock newId
            val root = TripMediaFiles.root(context)
            val stage = File(root, "restore-$newId").apply { if (exists()) deleteRecursively(); check(mkdir()) }
            val target = TripMediaFiles.directory(context, newId)
            // Only an uncommitted staging destination for this reserved UUID may be removed.
            if (target.exists()) target.deleteRecursively()
            var committed = false
            try {
                var manifest: PortableManifest? = null
                var total = 0L
                val actual = linkedMapOf<String, Pair<Long, String>>()
                (context.contentResolver.openInputStream(source) ?: error("Cannot open backup")).use { stream ->
                    ZipInputStream(BufferedInputStream(stream)).use { zip ->
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val entry = zip.nextEntry ?: break
                            require(!entry.isDirectory) { "Unexpected backup directory" }
                            if (entry.name == "manifest.json") {
                                require(manifest == null) { "Duplicate backup manifest" }
                                val bytes = ByteArrayOutputStream()
                                TripMediaFiles.copy(zip, bytes, MAX_MANIFEST)
                                manifest = json.decodeFromString<PortableManifest>(bytes.toString("UTF-8"))
                            } else {
                                require(safeEntry(entry.name) && entry.name !in actual && actual.size < TripMediaFiles.MAX_MEDIA) {
                                    "Invalid or duplicate backup entry"
                                }
                                val hash = digest()
                                val file = File(stage, entry.name.substringAfter('/'))
                                val count = file.outputStream().use { output ->
                                    // Do not close the wrapper: the ZIP stream must remain open for subsequent entries.
                                    TripMediaFiles.copy(DigestInputStream(zip, hash), output,
                                        minOf(TripMediaFiles.MAX_FILE_BYTES, TripMediaFiles.MAX_TOTAL_BYTES - total), root)
                                }
                                total += count
                                actual[entry.name] = count to hash.hex()
                                progress(actual.size)
                            }
                            zip.closeEntry() // Validates each entry's CRC.
                        }
                    }
                }
                val doc = manifest ?: error("Missing backup manifest")
                require(doc.format == "MyTravelDiaryMedia" && doc.version == 2) { "Unsupported photo backup" }
                validate(doc.trip)
                val photos = doc.trip.allMedia()
                require(doc.media.size == photos.size && doc.media.map { it.id }.toSet() == photos.map { it.id }.toSet() &&
                    doc.media.map { it.path }.toSet().size == doc.media.size && actual.keys == doc.media.map { it.path }.toSet()) {
                    "Backup media list is incomplete or duplicated"
                }
                doc.media.forEach { item ->
                    require(item.bytes > 0 && actual[item.path] == (item.bytes to item.sha256)) { "Backup media is damaged" }
                }
                // Namespace imported IDs, so unrelated local gallery IDs/manual overrides cannot collide.
                val restored = remap(doc.trip, newId)
                val byOldId = doc.media.associateBy { it.id }
                val renamed = File(stage, "ready").apply { check(mkdir()) }
                photos.forEach { old ->
                    require(old.mimeType.startsWith("image/") || old.isVideo) { "Unsupported backup media" }
                    val media = old.copy(id = "$newId:${old.id}")
                    check(File(stage, byOldId.getValue(old.id).path.substringAfter('/'))
                        .renameTo(File(renamed, TripMediaFiles.name(media)))) { "Cannot prepare restored media" }
                }
                currentCoroutineContext().ensureActive()
                check(!target.exists() && renamed.renameTo(target)) { "Cannot restore media files" }
                val local = restored.mapMedia { it.copy(contentUriString = TripMediaFiles.uri(context,
                    File(target, TripMediaFiles.name(it)))) }
                // Once database commit starts, cancellation must not delete files referenced by committed rows.
                withContext(NonCancellable) {
                    TripRepositoryImpl(TravelerDatabase.getDatabase(context)).saveTrip(local)
                    committed = true
                }
                newId
            } finally {
                stage.deleteRecursively()
                if (!committed) target.deleteRecursively()
            }
        }
    }

    internal fun remap(trip: Trip, id: String): Trip {
        fun visit(v: Visit) = v.copy(id = "$id:${v.id}")
        fun segment(s: MovementSegment) = s.copy(id = "$id:${s.id}")
        val media = trip.mapMedia { it.copy(id = "$id:${it.id}",
            matchedVisitId = it.matchedVisitId?.let { key -> "$id:$key" },
            matchedSegmentId = it.matchedSegmentId?.let { key -> "$id:$key" }) }
        return media.copy(id = id, days = media.days.map { day -> day.copy(items = day.items.map { item ->
            when (item) {
                is TripDayItem.VisitItem -> item.copy(visit = visit(item.visit))
                is TripDayItem.MovementItem -> item.copy(segment = segment(item.segment))
                is TripDayItem.ContextualPhotosItem -> item.copy(parentVisit = item.parentVisit?.let(::visit), parentSegment = item.parentSegment?.let(::segment))
                is TripDayItem.UnassignedPhotosItem -> item
            }
        }) })
    }
}
