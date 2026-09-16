package com.traveler.data.repository

import androidx.room.withTransaction
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.database.TravelerDatabase
import com.traveler.core.database.entity.*
import com.traveler.core.model.*
import com.traveler.core.timeline.MovementTimelineCanonicalizer
import com.traveler.domain.repository.OrphanCleanupSummary
import com.traveler.domain.repository.TripRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

class TripRepositoryImpl(
    private val database: TravelerDatabase
) : TripRepository {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    override fun getAllTrips(): Flow<List<Trip>> {
        return database.tripDao().getTripCardRowsFlow().map { rows ->
            rows.groupBy { it.trip.id }.values.map { tripRows ->
                val entity = tripRows.first().trip
                Trip(
                    id = entity.id,
                    title = entity.title,
                    startDateIso = entity.startDateIso,
                    endDateIso = entity.endDateIso,
                    totalDistanceMeters = entity.totalDistanceMeters,
                    cities = parseJsonStringList(entity.citiesJson),
                    countries = parseJsonStringList(entity.countriesJson),
                    days = emptyList(), // Days loaded on detail screen
                    totalMediaCount = entity.totalMediaCount,
                    createdAtEpochMs = entity.createdAtEpochMs,
                    visitSummary = TripVisitSummary.from(tripRows.mapNotNull { row ->
                        row.summaryVisitId?.let { id -> TripVisitLabel(
                            id, row.summaryPlaceName,
                            location = if (row.summaryLatitude != null && row.summaryLongitude != null &&
                                row.summaryLatitude in -90.0..90.0 && row.summaryLongitude in -180.0..180.0)
                                GeoPoint(row.summaryLatitude, row.summaryLongitude) else null,
                            isUserOverride = row.summaryUserOverride,
                            startEpochMs = row.summaryStart,
                            durationMs = ((row.summaryEnd ?: 0).toDouble() - (row.summaryStart ?: 0).toDouble())
                                .coerceIn(0.0, 86_400_000.0).toLong(),
                            timezoneId = row.summaryTimezone, photoCount = row.summaryPhotoCount
                        ) }
                    })
                )
            }
        }.flowOn(Dispatchers.Default)
    }

    override suspend fun getTripById(tripId: String): Trip? = withContext(Dispatchers.IO) {
        val tripEntity = database.tripDao().getTripById(tripId) ?: return@withContext null
        val visitEntities = database.visitDao().getVisitsForTrip(tripId)
        val segmentEntities = database.movementSegmentDao().getSegmentsForTrip(tripId)
        val mediaEntities = database.tripMediaDao().getMediaForTrip(tripId)

        // Load durable user overrides with composite key (targetType, targetSourceId) - P1-08
        val durableOverrides = database.userOverrideDao().getAllOverrides()
            .associateBy { Pair(it.targetType, it.targetSourceId) }

        // Map domain objects (using persisted timezone identifiers & durable overrides)
        val visits = visitEntities.map { v ->
            val durableNameOverride = durableOverrides[Pair("VISIT_NAME", v.sourceId)]?.overrideValue
            val effectivePlaceName = durableNameOverride ?: v.placeName
            val isOverridden = durableNameOverride != null || v.isUserOverride
            Visit(
                id = v.sourceId,
                placeName = effectivePlaceName,
                placeAddress = v.placeAddress,
                placeId = v.placeId,
                location = GeoPoint(v.latitude, v.longitude, v.altitudeMeters),
                startTimestampEpochMs = v.startTimestampEpochMs,
                endTimestampEpochMs = v.endTimestampEpochMs,
                confidence = v.confidence,
                timezoneId = v.timezoneId,
                isUserOverride = isOverridden
            )
        }

        val segments = segmentEntities.map { s ->
            val simplified = try {
                if (s.polylineJson.isNotBlank()) json.decodeFromString<List<GeoPoint>>(s.polylineJson) else emptyList()
            } catch (_: Exception) {
                emptyList()
            }

            val predictedMode = try { TransportMode.valueOf(s.predictedTransportMode) } catch (_: Exception) { TransportMode.UNKNOWN }
            val geomProvenance = try { GeometryProvenance.valueOf(s.geometryProvenance) } catch (_: Exception) { GeometryProvenance.UNKNOWN }

            val durableOverride = durableOverrides[Pair("SEGMENT_TRANSPORT", s.sourceId)]?.overrideValue
            val overrideStr = durableOverride ?: s.userOverrideTransportMode
            val overrideMode = overrideStr?.let {
                try { TransportMode.valueOf(it) } catch (_: Exception) { null }
            }

            MovementSegment(
                id = s.sourceId,
                startTimestampEpochMs = s.startTimestampEpochMs,
                endTimestampEpochMs = s.endTimestampEpochMs,
                startPoint = GeoPoint(s.startLat, s.startLng, s.startAltitudeMeters),
                endPoint = GeoPoint(s.endLat, s.endLng, s.endAltitudeMeters),
                rawPoints = if (s.rawPointsJson.isBlank()) emptyList() else
                    json.decodeFromString<List<LocationPoint>>(s.rawPointsJson),
                simplifiedPoints = simplified,
                distanceMeters = s.distanceMeters,
                durationMillis = s.durationMillis,
                transport = TransportPrediction(
                    mode = predictedMode,
                    confidence = s.predictedConfidence,
                    reason = s.predictedReason,
                    isUserOverride = overrideMode != null
                ),
                startTimezoneId = s.startTimezoneId,
                endTimezoneId = s.endTimezoneId,
                userOverrideMode = overrideMode,
                isUserOverride = overrideMode != null,
                geometryProvenance = geomProvenance
            )
        }

        val mediaItems = mediaEntities.map { m ->
            MediaItem(
                id = m.mediaKey,
                contentUriString = m.contentUriString,
                fileName = m.fileName,
                mimeType = m.mimeType,
                timestampEpochMs = m.timestampEpochMs,
                timestampConfidence = try { TimestampConfidence.valueOf(m.timestampConfidence) } catch (_: Exception) { TimestampConfidence.UNKNOWN },
                captureTimezoneId = m.captureTimezoneId,
                location = if (m.latitude != null && m.longitude != null) GeoPoint(m.latitude, m.longitude) else null,
                locationConfidence = try { LocationConfidenceLevel.valueOf(m.locationConfidence) } catch (_: Exception) { LocationConfidenceLevel.UNKNOWN },
                confidenceScore = m.confidenceScore,
                matchedVisitId = m.matchedVisitId,
                matchedSegmentId = m.matchedSegmentId,
                isRepresentative = m.isRepresentative,
                isUserLocationOverride = m.isUserLocationOverride,
                assignedDayIso = m.assignedDayIso,
                dayAssignmentConfidence = try { DayAssignmentConfidence.valueOf(m.dayAssignmentConfidence) } catch (_: Exception) { DayAssignmentConfidence.UNKNOWN },
                dayAssignmentProvenance = m.dayAssignmentProvenance
            )
        }

        // P0-01: Separate confident day-assigned photos from uncertain date photos
        val confidentMediaItems = mediaItems.filter { it.assignedDayIso != null }
        val uncertainDateMediaList = mediaItems.filter { it.assignedDayIso == null }

        // Reconstruct TripDays using persisted timezones without blocking on TimeShape
        val days = reconstructDays(visits, segments, confidentMediaItems, tripEntity.startDateIso, tripEntity.endDateIso)
        val visitPhotoCounts = mediaItems.mapNotNull { it.matchedVisitId }.groupingBy { it }.eachCount()

        Trip(
            id = tripEntity.id,
            title = tripEntity.title,
            startDateIso = tripEntity.startDateIso,
            endDateIso = tripEntity.endDateIso,
            totalDistanceMeters = tripEntity.totalDistanceMeters,
            cities = parseJsonStringList(tripEntity.citiesJson),
            countries = parseJsonStringList(tripEntity.countriesJson),
            days = days,
            uncertainDateMedia = uncertainDateMediaList,
            totalMediaCount = tripEntity.totalMediaCount,
            createdAtEpochMs = tripEntity.createdAtEpochMs,
            visitSummary = TripVisitSummary.from(visits.sortedWith(
                compareBy<Visit> { it.startTimestampEpochMs }.thenBy { it.id }
            ).map { TripVisitLabel(it.id, it.placeName, it.location, it.isUserOverride,
                it.startTimestampEpochMs, it.durationMillis, it.timezoneId, visitPhotoCounts[it.id] ?: 0) })
        )
    }

    override suspend fun saveTrip(trip: Trip) = withContext(Dispatchers.IO) {
        val tripEntity = TripEntity(
            id = trip.id,
            title = trip.title,
            startDateIso = trip.startDateIso,
            endDateIso = trip.endDateIso,
            totalDistanceMeters = trip.totalDistanceMeters,
            citiesJson = json.encodeToString(trip.cities),
            countriesJson = json.encodeToString(trip.countries),
            totalMediaCount = trip.totalMediaCount,
            createdAtEpochMs = trip.createdAtEpochMs
        )

        val visitEntities = mutableMapOf<String, VisitEntity>()
        val segmentEntities = mutableMapOf<String, MovementSegmentEntity>()
        val mediaEntities = mutableMapOf<String, TripMediaEntity>()

        val durableOverrides = database.userOverrideDao().getAllOverrides()
            .associateBy { Pair(it.targetType, it.targetSourceId) }

        for (day in trip.days) {
            for (item in day.items) {
                when (item) {
                    is TripDayItem.VisitItem -> {
                        val visit = item.visit
                        visitEntities[visit.id] = VisitEntity(
                            tripId = trip.id,
                            sourceId = visit.id,
                            placeName = durableOverrides[Pair("VISIT_NAME", visit.id)]?.overrideValue ?: visit.placeName,
                            placeAddress = visit.placeAddress,
                            placeId = visit.placeId,
                            latitude = visit.location.latitude,
                            longitude = visit.location.longitude,
                            startTimestampEpochMs = visit.startTimestampEpochMs,
                            endTimestampEpochMs = visit.endTimestampEpochMs,
                            confidence = visit.confidence,
                            isUserOverride = visit.isUserOverride,
                            timezoneId = visit.timezoneId,
                            altitudeMeters = visit.location.altitudeMeters
                        )
                        for (photo in item.photos) {
                            val photoWithVisit = photo.copy(
                                matchedVisitId = photo.matchedVisitId ?: visit.id,
                                assignedDayIso = photo.assignedDayIso ?: day.dateIso
                            )
                            mediaEntities[photo.id] = mapMediaItemToEntity(trip.id, photoWithVisit)
                        }
                    }
                    is TripDayItem.MovementItem -> {
                        val seg = item.segment
                        segmentEntities[seg.id] = MovementSegmentEntity(
                            tripId = trip.id,
                            sourceId = seg.id,
                            startTimestampEpochMs = seg.startTimestampEpochMs,
                            endTimestampEpochMs = seg.endTimestampEpochMs,
                            startLat = seg.startPoint.latitude,
                            startLng = seg.startPoint.longitude,
                            endLat = seg.endPoint.latitude,
                            endLng = seg.endPoint.longitude,
                            distanceMeters = seg.distanceMeters,
                            durationMillis = seg.durationMillis,
                            predictedTransportMode = seg.transport.mode.name,
                            predictedConfidence = seg.transport.confidence,
                            predictedReason = seg.transport.reason,
                            userOverrideTransportMode = durableOverrides[Pair("SEGMENT_TRANSPORT", seg.id)]?.overrideValue ?: seg.userOverrideMode?.name,
                            polylineJson = if (seg.simplifiedPoints.isNotEmpty()) json.encodeToString(seg.simplifiedPoints) else "",
                            isUserOverride = seg.isUserOverride,
                            startTimezoneId = seg.startTimezoneId,
                            endTimezoneId = seg.endTimezoneId,
                            geometryProvenance = seg.geometryProvenance.name,
                            startAltitudeMeters = seg.startPoint.altitudeMeters,
                            endAltitudeMeters = seg.endPoint.altitudeMeters,
                            rawPointsJson = if (seg.rawPoints.isEmpty()) "" else json.encodeToString(seg.rawPoints)
                        )
                        for (photo in item.photos) {
                            val photoWithSeg = photo.copy(
                                matchedSegmentId = photo.matchedSegmentId ?: seg.id,
                                assignedDayIso = photo.assignedDayIso ?: day.dateIso
                            )
                            mediaEntities[photo.id] = mapMediaItemToEntity(trip.id, photoWithSeg)
                        }
                    }
                    is TripDayItem.ContextualPhotosItem -> {
                        for (photo in item.photos) {
                            val photoWithContext = photo.copy(
                                matchedVisitId = photo.matchedVisitId ?: item.parentVisit?.id,
                                matchedSegmentId = photo.matchedSegmentId ?: item.parentSegment?.id,
                                assignedDayIso = photo.assignedDayIso ?: day.dateIso
                            )
                            mediaEntities[photo.id] = mapMediaItemToEntity(trip.id, photoWithContext)
                        }
                    }
                    is TripDayItem.UnassignedPhotosItem -> {
                        for (photo in item.photos) {
                            val photoWithDay = photo.copy(
                                assignedDayIso = photo.assignedDayIso ?: day.dateIso
                            )
                            mediaEntities[photo.id] = mapMediaItemToEntity(trip.id, photoWithDay)
                        }
                    }
                }
            }
        }

        // P0-01: Persist uncertain date photos
        for (photo in trip.uncertainDateMedia) {
            mediaEntities[photo.id] = mapMediaItemToEntity(trip.id, photo)
        }

        database.withTransaction {
            database.visitDao().deleteVisitsForTrip(trip.id)
            database.movementSegmentDao().deleteSegmentsForTrip(trip.id)
            database.tripMediaDao().deleteMediaForTrip(trip.id)

            database.tripDao().insertTrip(tripEntity)
            database.visitDao().insertVisits(visitEntities.values.toList())
            database.movementSegmentDao().insertSegments(segmentEntities.values.toList())
            database.tripMediaDao().insertMediaItems(mediaEntities.values.toList())
        }
    }

    override suspend fun deleteTrip(tripId: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            database.tripMediaDao().deleteMediaForTrip(tripId)
            database.movementSegmentDao().deleteSegmentsForTrip(tripId)
            database.visitDao().deleteVisitsForTrip(tripId)
            database.tripDao().deleteTrip(tripId)
        }
    }

    override suspend fun cleanOrphanTripData(): OrphanCleanupSummary = withContext(Dispatchers.IO) {
        database.withTransaction {
            val orphanMedia = database.tripMediaDao().deleteOrphanMedia()
            val orphanSegments = database.movementSegmentDao().deleteOrphanSegments()
            val orphanVisits = database.visitDao().deleteOrphanVisits()
            OrphanCleanupSummary(
                deletedVisits = orphanVisits,
                deletedSegments = orphanSegments,
                deletedMedia = orphanMedia
            )
        }
    }

    override suspend fun updateVisitName(tripId: String, visitSourceId: String, name: String) = withContext(Dispatchers.IO) {
        database.withTransaction {
            database.visitDao().updateVisitName(tripId, visitSourceId, name)
            database.userOverrideDao().insertOverride(
                UserOverrideEntity(
                    id = "VISIT_NAME:$visitSourceId",
                    targetType = "VISIT_NAME",
                    targetSourceId = visitSourceId,
                    overrideValue = name
                )
            )
        }
    }

    override suspend fun updateTransportMode(tripId: String, segmentSourceId: String, mode: TransportMode) = withContext(Dispatchers.IO) {
        database.withTransaction {
            database.movementSegmentDao().updateTransportMode(tripId, segmentSourceId, mode.name)
            database.userOverrideDao().insertOverride(
                UserOverrideEntity(
                    id = "SEGMENT_TRANSPORT:$segmentSourceId",
                    targetType = "SEGMENT_TRANSPORT",
                    targetSourceId = segmentSourceId,
                    overrideValue = mode.name
                )
            )
        }
    }

    override suspend fun updateMediaVisit(tripId: String, mediaKey: String, visitSourceId: String?) = withContext(Dispatchers.IO) {
        database.tripMediaDao().updateMediaVisit(tripId, mediaKey, visitSourceId)
    }

    override suspend fun setRepresentativeMedia(tripId: String, mediaKey: String, isRepresentative: Boolean) = withContext(Dispatchers.IO) {
        database.withTransaction {
            database.tripMediaDao().updateRepresentative(tripId, mediaKey, isRepresentative)
            database.userOverrideDao().insertOverride(
                UserOverrideEntity(
                    id = "MEDIA_REPRESENTATIVE:$mediaKey",
                    targetType = "MEDIA_REPRESENTATIVE",
                    targetSourceId = mediaKey,
                    overrideValue = isRepresentative.toString()
                )
            )
        }
    }

    override suspend fun getDurableUserOverrides(): List<UserOverrideEntity> = withContext(Dispatchers.IO) {
        database.userOverrideDao().getAllOverrides()
    }

    private fun parseJsonStringList(raw: String): List<String> {
        if (raw.isBlank()) return emptyList()
        return try {
            json.decodeFromString<List<String>>(raw)
        } catch (_: Exception) {
            raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }
        }
    }

    private fun mapMediaItemToEntity(tripId: String, m: MediaItem): TripMediaEntity {
        // Enforce exclusive semantic parent invariant (P1-07): matchedVisitId XOR matchedSegmentId
        val effectiveSegmentId = if (m.matchedVisitId != null) null else m.matchedSegmentId
        return TripMediaEntity(
            tripId = tripId,
            mediaKey = m.id,
            contentUriString = m.contentUriString,
            fileName = m.fileName,
            mimeType = m.mimeType,
            timestampEpochMs = m.timestampEpochMs,
            timestampConfidence = m.timestampConfidence.name,
            captureTimezoneId = m.captureTimezoneId,
            latitude = m.location?.latitude,
            longitude = m.location?.longitude,
            locationConfidence = m.locationConfidence.name,
            confidenceScore = m.confidenceScore,
            matchedVisitId = m.matchedVisitId,
            matchedSegmentId = effectiveSegmentId,
            isRepresentative = m.isRepresentative,
            isUserLocationOverride = m.isUserLocationOverride,
            assignedDayIso = m.assignedDayIso,
            dayAssignmentConfidence = m.dayAssignmentConfidence.name,
            dayAssignmentProvenance = m.dayAssignmentProvenance
        )
    }

    companion object {
        fun reconstructDays(
            visits: List<Visit>,
            segments: List<MovementSegment>,
            mediaItems: List<MediaItem>,
            tripStartDateIso: String,
            tripEndDateIso: String
        ): List<TripDay> {
            val startDate = try { java.time.LocalDate.parse(tripStartDateIso) } catch (_: Exception) { null }
            val endDate = try { java.time.LocalDate.parse(tripEndDateIso) } catch (_: Exception) { startDate }

            // P1-11: Consume canonical segments directly, repairing only if legacy overlap violations exist
            val canonicalSegments = if (com.traveler.core.timeline.CanonicalTimelineValidator.countOverlapViolations(segments) == 0) {
                segments
            } else {
                MovementTimelineCanonicalizer.canonicalize(segments).canonicalSegments
            }
            val canonicalVisits = if (com.traveler.core.timeline.CanonicalVisitTimelineValidator.countOverlapViolations(visits) == 0) {
                visits
            } else {
                com.traveler.core.timeline.CanonicalVisitTimelineValidator.requireNonOverlapping(visits)
            }
            val visitMap = canonicalVisits.associateBy { it.id }
            val segmentMap = canonicalSegments.associateBy { it.id }

            // Enforce exclusive semantic parent invariant defensively on all media items (P1-07)
            val sanitizedMediaItems = mediaItems.map { m ->
                if (m.matchedVisitId != null && m.matchedSegmentId != null) {
                    m.copy(matchedSegmentId = null)
                } else {
                    m
                }
            }

            // Confident media strictly grouped by assignedDayIso within requested date window (P0-01 & P1-11)
            val confidentMedia = sanitizedMediaItems.filter { m ->
                m.assignedDayIso != null &&
                        (startDate == null || m.assignedDayIso!! >= tripStartDateIso) &&
                        (endDate == null || m.assignedDayIso!! <= tripEndDateIso)
            }
            val mediaByDay = confidentMedia.groupBy { it.assignedDayIso!! }

            // 1. Anchor Visits to local calendar date
            val visitsByDate = mutableListOf<Pair<String, Visit>>()
            for (v in visits) {
                val zone = v.timezoneId?.let { try { ZoneId.of(it) } catch (_: Exception) { null } }
                val startLocal = if (zone != null) Instant.ofEpochMilli(v.startTimestampEpochMs).atZone(zone).toLocalDate() else (startDate ?: java.time.LocalDate.now())
                val endLocal = if (zone != null) Instant.ofEpochMilli(v.endTimestampEpochMs).atZone(zone).toLocalDate() else (endDate ?: startLocal)
                val clampedDate = if (startDate != null && endDate != null) {
                    val overlaps = !(endLocal < startDate || startLocal > endDate)
                    if (overlaps) maxOf(startDate, minOf(endDate, startLocal)).toString() else startLocal.toString()
                } else {
                    startLocal.toString()
                }
                visitsByDate.add(clampedDate to v)
            }
            val visitsGroupedByDate = visitsByDate.groupBy({ it.first }, { it.second })

            // 2. Anchor Movements to local calendar date
            val segmentsByDate = mutableListOf<Pair<String, MovementSegment>>()
            for (s in canonicalSegments) {
                val startZone = s.startTimezoneId?.let { try { ZoneId.of(it) } catch (_: Exception) { null } }
                val endZone = s.endTimezoneId?.let { try { ZoneId.of(it) } catch (_: Exception) { null } } ?: startZone
                val startLocal = if (startZone != null) Instant.ofEpochMilli(s.startTimestampEpochMs).atZone(startZone).toLocalDate() else (startDate ?: java.time.LocalDate.now())
                val endLocal = if (endZone != null) Instant.ofEpochMilli(s.endTimestampEpochMs).atZone(endZone).toLocalDate() else (endDate ?: startLocal)
                val minLocal = minOf(startLocal, endLocal)
                val maxLocal = maxOf(startLocal, endLocal)
                val clampedDate = if (startDate != null && endDate != null) {
                    val overlaps = !(maxLocal < startDate || minLocal > endDate)
                    if (overlaps) maxOf(startDate, minOf(endDate, minLocal)).toString() else minLocal.toString()
                } else {
                    minLocal.toString()
                }
                segmentsByDate.add(clampedDate to s)
            }
            val segmentsGroupedByDate = segmentsByDate.groupBy({ it.first }, { it.second })

            // Generate full inclusive date range (P1-05 & P1-11)
            val sequenceDates = if (startDate != null && endDate != null) {
                generateSequence(startDate) { it.plusDays(1) }
                    .takeWhile { !it.isAfter(endDate) }
                    .map { it.toString() }
                    .toList()
            } else emptyList()

            val allDates = if (sequenceDates.isNotEmpty()) {
                sequenceDates
            } else {
                (visitsGroupedByDate.keys + segmentsGroupedByDate.keys + mediaByDay.keys).distinct().sorted()
            }
            if (allDates.isEmpty()) return emptyList()

            var dayIdx = 1
            return allDates.map { dateStr ->
                val dayVisits = visitsGroupedByDate[dateStr] ?: emptyList()
                val daySegments = segmentsGroupedByDate[dateStr] ?: emptyList()
                val dayPhotos = mediaByDay[dateStr] ?: emptyList()

                val emittedMediaIds = mutableSetOf<String>()
                val sameDayVisitIds = dayVisits.map { it.id }.toSet()
                val sameDaySegmentIds = daySegments.map { it.id }.toSet()

                // 1. Visit items on this day
                val visitItems = dayVisits.map { v ->
                    val vPhotos = dayPhotos.filter { it.matchedVisitId == v.id && emittedMediaIds.add(it.id) }
                    TripDayItem.VisitItem(v, vPhotos)
                }

                // 2. Movement items on this day
                val movementItems = daySegments.map { s ->
                    val sPhotos = dayPhotos.filter { it.matchedSegmentId == s.id && emittedMediaIds.add(it.id) }
                    TripDayItem.MovementItem(s, sPhotos)
                }

                // 3. Contextual Visit items (photos matched to visits anchored on different days)
                val otherVisitPhotos = dayPhotos.filter { it.matchedVisitId != null && it.matchedVisitId !in sameDayVisitIds && !emittedMediaIds.contains(it.id) }
                val contextualVisitItems = otherVisitPhotos.groupBy { it.matchedVisitId!! }.map { (visitId, photos) ->
                    val filteredPhotos = photos.filter { emittedMediaIds.add(it.id) }
                    val v = visitMap[visitId]
                    val label = if (v != null) "${v.placeName ?: "Place"} — continued stay" else "Continued stay"
                    TripDayItem.ContextualPhotosItem(
                        parentVisit = v,
                        parentSegment = null,
                        contextLabel = label,
                        photos = filteredPhotos
                    )
                }

                // 4. Contextual Movement items (photos matched to movements anchored on different days)
                val otherSegmentPhotos = dayPhotos.filter { it.matchedSegmentId != null && it.matchedSegmentId !in sameDaySegmentIds && !emittedMediaIds.contains(it.id) }
                val contextualSegmentItems = otherSegmentPhotos.groupBy { it.matchedSegmentId!! }.map { (segId, photos) ->
                    val filteredPhotos = photos.filter { emittedMediaIds.add(it.id) }
                    val s = segmentMap[segId]
                    val modeName = s?.transport?.mode?.displayName?.lowercase() ?: "transit"
                    val emoji = s?.transport?.mode?.emoji ?: "🚗"
                    val label = "During $modeName $emoji"
                    TripDayItem.ContextualPhotosItem(
                        parentVisit = null,
                        parentSegment = s,
                        contextLabel = label,
                        photos = filteredPhotos
                    )
                }

                // 5. Unassigned photos on this day
                val unassignedPhotos = dayPhotos.filter { !emittedMediaIds.contains(it.id) }
                val unassignedItems = if (unassignedPhotos.isNotEmpty()) {
                    unassignedPhotos.forEach { emittedMediaIds.add(it.id) }
                    listOf(TripDayItem.UnassignedPhotosItem(unassignedPhotos))
                } else {
                    emptyList()
                }

                val allDayItems = (visitItems + movementItems + contextualVisitItems + contextualSegmentItems + unassignedItems)
                    .sortedBy { it.timestampEpochMs }

                val dayZoneId = allDayItems.firstNotNullOfOrNull {
                    when (it) {
                        is TripDayItem.VisitItem -> it.visit.timezoneId
                        is TripDayItem.MovementItem -> it.segment.startTimezoneId
                        is TripDayItem.ContextualPhotosItem -> it.parentVisit?.timezoneId ?: it.parentSegment?.startTimezoneId
                        else -> null
                    }
                } ?: visits.firstOrNull()?.timezoneId ?: segments.firstOrNull()?.startTimezoneId
                val dayZone = dayZoneId?.let { try { ZoneId.of(it) } catch (_: Exception) { null } }

                val dayDistance = movementItems.sumOf { it.segment.distanceMeters }

                TripDay(
                    dayIndex = dayIdx++,
                    dateIso = dateStr,
                    timezoneId = dayZone?.id,
                    items = allDayItems,
                    unassignedPhotos = unassignedPhotos,
                    totalDistanceMeters = dayDistance,
                    photoCount = dayPhotos.size
                )
            }
        }
    }
}
