package com.traveler.core.media

import android.content.Context
import android.net.Uri
import com.traveler.core.database.TravelerDatabase
import com.traveler.core.database.entity.UserOverrideEntity
import com.traveler.core.model.*
import com.traveler.data.repository.TripRepositoryImpl
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.time.LocalDate

object MediaLibraryEditor {
    private val edits = Mutex()
    suspend fun add(c: Context, tripId: String, sources: List<Uri>, day: String?, visitId: String?, progress: (Int, Int) -> Unit) = withContext(Dispatchers.IO) { edits.withLock {
        require(sources.size in 1..500) { "한 번에 최대 500개를 추가할 수 있습니다." }
        val repo = TripRepositoryImpl(TravelerDatabase.getDatabase(c))
        var trip = repo.getTripById(tripId) ?: error("여행을 찾을 수 없습니다.")
        day?.takeIf { it.isNotBlank() }?.let { require(LocalDate.parse(it) in LocalDate.parse(trip.startDateIso)..LocalDate.parse(trip.endDateIso)) { "여행 기간 안의 날짜를 선택하세요." } }
        val visit = trip.days.flatMap { it.items }.filterIsInstance<TripDayItem.VisitItem>().map { it.visit }.firstOrNull { it.id == visitId }
        require(visitId.isNullOrBlank() || visit != null)
        sources.distinct().forEachIndexed { index, uri ->
            currentCoroutineContext().ensureActive()
            val id = "$tripId:added:${TripMediaFiles.hash(uri.toString())}"
            if (trip.allMedia().none { it.id == id }) {
                val mime = c.contentResolver.getType(uri) ?: error("파일 형식을 확인할 수 없습니다.")
                require(mime.startsWith("image/") || mime.startsWith("video/"))
                val item = MediaItem(id, uri.toString(), MediaMigration.displayName(c, uri) ?: "added-media", mime, null,
                    location = visit?.location, matchedVisitId = visit?.id, isUserLocationOverride = visit != null,
                    assignedDayIso = day?.takeIf { it.isNotBlank() }, dayAssignmentConfidence = if (day.isNullOrBlank()) DayAssignmentConfidence.UNKNOWN else DayAssignmentConfidence.EXACT,
                    dayAssignmentProvenance = if (day.isNullOrBlank()) null else "USER_SELECTED")
                val next = trip.copy(uncertainDateMedia = trip.uncertainDateMedia + item, totalMediaCount = trip.allMedia().size + 1)
                val target = File(TripMediaFiles.directory(c, tripId), TripMediaFiles.name(item))
                // The stable id lets a retry reuse a copy made just before process death.
                if (!target.isFile || target.length() == 0L) MediaMigration.connect(c, next, item, uri)
                val local = next.mapMedia { if (it.id == id) it.copy(contentUriString = TripMediaFiles.uri(c, target)) else it }
                withContext(NonCancellable) { repo.saveTrip(local); tripMemoryStore(c, tripId).delete(tripId) }
                trip = local
            }
            progress(index + 1, sources.size)
        }
    } }
    suspend fun setClip(c: Context, tripId: String, mediaId: String, clip: VideoClip) = withContext(Dispatchers.IO) { edits.withLock {
        clip.validate()
        val database = TravelerDatabase.getDatabase(c)
        val trip = TripRepositoryImpl(database).getTripById(tripId) ?: error("여행을 찾을 수 없습니다.")
        require(trip.allMedia().any { it.id == mediaId && it.isVideo })
        database.userOverrideDao().insertOverride(UserOverrideEntity(id = "VIDEO_CLIP:$tripId:$mediaId", targetType = "VIDEO_CLIP",
            targetSourceId = "$tripId:$mediaId", overrideValue = Json.encodeToString(clip)))
        if (clip.enabled) TripRepositoryImpl(database).setRepresentativeMedia(tripId, mediaId, true)
        tripMemoryStore(c, tripId).delete(tripId)
    } }
    suspend fun place(c: Context, tripId: String, mediaId: String, day: String?, visitId: String?) = withContext(Dispatchers.IO) { edits.withLock {
        val repo = TripRepositoryImpl(TravelerDatabase.getDatabase(c))
        val trip = repo.getTripById(tripId) ?: error("여행을 찾을 수 없습니다.")
        day?.let { require(LocalDate.parse(it) in LocalDate.parse(trip.startDateIso)..LocalDate.parse(trip.endDateIso)) }
        val visit = trip.days.flatMap { it.items }.filterIsInstance<TripDayItem.VisitItem>().map { it.visit }.firstOrNull { it.id == visitId }
        require(visitId == null || visit != null)
        val photo = trip.allMedia().first { it.id == mediaId }.copy(assignedDayIso = day, matchedVisitId = visitId, matchedSegmentId = null,
            location = visit?.location, isUserLocationOverride = true, dayAssignmentProvenance = "USER_SELECTED",
            dayAssignmentConfidence = if (day == null) DayAssignmentConfidence.UNKNOWN else DayAssignmentConfidence.EXACT)
        val without = trip.copy(days = trip.days.map { d -> d.copy(unassignedPhotos = d.unassignedPhotos.filterNot { it.id == mediaId }, items = d.items.map { item ->
            when (item) {
                is TripDayItem.VisitItem -> item.copy(photos = item.photos.filterNot { it.id == mediaId })
                is TripDayItem.MovementItem -> item.copy(photos = item.photos.filterNot { it.id == mediaId })
                is TripDayItem.ContextualPhotosItem -> item.copy(photos = item.photos.filterNot { it.id == mediaId })
                is TripDayItem.UnassignedPhotosItem -> item.copy(photos = item.photos.filterNot { it.id == mediaId })
            }
        }) }, uncertainDateMedia = trip.uncertainDateMedia.filterNot { it.id == mediaId } + photo)
        repo.saveTrip(without); tripMemoryStore(c, tripId).delete(tripId)
    } }
}
