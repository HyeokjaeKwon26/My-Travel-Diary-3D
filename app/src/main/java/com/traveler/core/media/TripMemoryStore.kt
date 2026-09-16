package com.traveler.core.media

import com.traveler.core.model.*
import com.traveler.feature.map.renderer.TravelMapRenderModel
import com.traveler.feature.map.story.TravelStoryTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

@Serializable
data class TripMemorySnapshot(
    val version: Int = 1,
    val inputKey: String,
    val photos: Map<String, SavedPhotoFeatures>,
    val selections: Map<String, List<PhotoStoryMoment>>
)

@Serializable
data class SavedPhotoFeatures(val sourceKey: String, val features: PhotoVisualFeatures?)

/** Durable journey data, deliberately outside cacheDir. A normal open never queries MediaStore. */
class TripMemoryStore(
    private val directory: File,
    private val analyze: suspend (List<MediaItem>, Boolean, (Int, Int) -> Unit) -> List<MediaItem>
) {
    companion object { private val lock = Mutex() }
    private val json = Json { ignoreUnknownKeys = true }
    private fun hash(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray())
        .joinToString("") { "%02x".format(it) }
    private fun file(id: String) = File(directory, hash(id) + ".json")
    private fun sourceKey(p: MediaItem) = hash("${p.contentUriString}|${p.timestampEpochMs}|${p.mimeType}")

    suspend fun prepare(trip: Trip, refresh: Boolean = false, progress: (Int, Int) -> Unit = { _, _ -> }): Trip =
        withContext(Dispatchers.IO) { lock.withLock {
            val originals = trip.memoryPhotos().sortedBy { it.id }
            // Saved metadata detects additions, removal, overrides, and changed route/visit membership.
            // Image edits in the phone gallery are intentionally picked up only by explicit refresh.
            val inputModel = trip.memoryRenderModel()
            val input = buildList {
                add(trip.startDateIso); add(trip.endDateIso)
                inputModel.visits.sortedBy { it.id }.forEach { v ->
                    add(listOf(v.id,v.startTimestampEpochMs,v.endTimestampEpochMs,v.location.latitude,v.location.longitude,v.placeName,v.isUserOverride).toString())
                }
                inputModel.segments.sortedBy { it.id }.forEach { m ->
                    add(listOf(m.id,m.startTimestampEpochMs,m.endTimestampEpochMs,m.startPoint.latitude,m.startPoint.longitude,
                        m.endPoint.latitude,m.endPoint.longitude,m.distanceMeters,m.effectiveMode).toString())
                }
                originals.forEach { p -> add(json.encodeToString(p.copy(visualFeatures=null,captureEvidence=MediaCaptureEvidence.UNKNOWN,
                    location=p.location?.let { com.traveler.core.common.geo.GeoPoint(it.latitude,it.longitude) }))) }
            }
            val key = hash(json.encodeToString(input))
            val target = file(trip.id)
            val saved = runCatching { json.decodeFromString<TripMemorySnapshot>(target.readText()) }.getOrNull()
                ?.takeIf { it.version == 1 }
            if (!refresh && saved?.inputKey == key) {
                return@withLock trip.withMemoryPhotos(originals.associate { p ->
                    p.id to p.copy(visualFeatures = saved.photos[p.id]?.features)
                }).copy(memorySnapshot = saved)
            }
            val needed = originals.filter { p -> refresh ||
                (saved?.photos?.get(p.id)?.sourceKey != sourceKey(p) && p.visualFeatures == null) }
            val updated = if (needed.isEmpty()) emptyMap() else analyze(needed, refresh, progress).associateBy { it.id }
            val features = originals.associate { p -> p.id to SavedPhotoFeatures(sourceKey(p),
                if (p.id in updated) updated[p.id]?.visualFeatures
                else if (saved?.photos?.get(p.id)?.sourceKey == sourceKey(p)) saved.photos[p.id]?.features
                else p.visualFeatures) }
            val prepared = trip.withMemoryPhotos(originals.associate { p -> p.id to p.copy(visualFeatures = features[p.id]?.features) }).copy(memorySnapshot = null)
            val model = prepared.memoryRenderModel()
            val selections = StoryDurationProfile.entries.associate { profile ->
                profile.name to TravelStoryTimeline.selectPhotoMoments(model, profile)
            }
            val snapshot = TripMemorySnapshot(inputKey = key, photos = features, selections = selections)
            currentCoroutineContext().ensureActive()
            directory.mkdirs()
            val temp = File.createTempFile("memories-", ".tmp", directory)
            try {
                temp.writeText(json.encodeToString(snapshot))
                currentCoroutineContext().ensureActive()
                Files.move(temp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            } finally { temp.delete() }
            prepared.copy(memorySnapshot = snapshot)
        } }

    suspend fun delete(tripId: String) = withContext(Dispatchers.IO) { lock.withLock { file(tripId).delete() } }
}

fun Trip.memoryPhotos(): List<MediaItem> = days.flatMap { day -> day.items.flatMap { item -> when (item) {
    is TripDayItem.VisitItem -> item.photos
    is TripDayItem.MovementItem -> item.photos
    is TripDayItem.ContextualPhotosItem -> item.photos
    is TripDayItem.UnassignedPhotosItem -> item.photos
} } }.distinctBy { it.id }

private fun Trip.withMemoryPhotos(photos: Map<String, MediaItem>) = copy(days = days.map { day ->
    day.copy(items = day.items.map { item ->
        fun mapped(items: List<MediaItem>) = items.map { photos[it.id] ?: it }
            .sortedWith(compareBy<MediaItem> { it.timestampEpochMs ?: Long.MAX_VALUE }.thenBy { it.id })
        when (item) {
            is TripDayItem.VisitItem -> item.copy(photos = mapped(item.photos))
            is TripDayItem.MovementItem -> item.copy(photos = mapped(item.photos))
            is TripDayItem.ContextualPhotosItem -> item.copy(photos = mapped(item.photos))
            is TripDayItem.UnassignedPhotosItem -> item.copy(photos = mapped(item.photos))
        }
    })
})

fun Trip.memoryRenderModel(): TravelMapRenderModel {
    val items = days.flatMap { it.items }
    return TravelMapRenderModel(
        items.mapNotNull { when(it) { is TripDayItem.VisitItem -> it.visit; is TripDayItem.ContextualPhotosItem -> it.parentVisit; else -> null } }.distinctBy { it.id },
        items.mapNotNull { when(it) { is TripDayItem.MovementItem -> it.segment; is TripDayItem.ContextualPhotosItem -> it.parentSegment; else -> null } }.distinctBy { it.id },
        memoryPhotos(), photoSelections = memorySnapshot?.selections
    )
}
