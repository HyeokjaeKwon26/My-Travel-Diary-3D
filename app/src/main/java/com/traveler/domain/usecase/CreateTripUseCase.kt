package com.traveler.domain.usecase

import com.traveler.core.classifier.TransportClassifier
import com.traveler.core.common.time.GeoTimezoneEngine
import com.traveler.core.common.time.TimezoneResolution
import com.traveler.core.common.time.TimezoneResolver
import com.traveler.core.media.MediaRepository
import com.traveler.core.media.PhotoLocationMatcher
import com.traveler.core.media.PhotoTimestampResolver
import com.traveler.core.media.RawMediaCandidate
import com.traveler.core.model.*
import com.traveler.core.timeline.CanonicalTimelineValidator
import com.traveler.core.timeline.CanonicalVisitTimelineValidator
import com.traveler.core.timeline.DateRangeFilter
import com.traveler.core.timeline.LocationHistorySource
import com.traveler.core.timeline.MovementTimelineCanonicalizer
import com.traveler.core.timeline.SpatialDiscontinuityType
import com.traveler.core.timeline.TimelineParseStatus
import com.traveler.data.repository.TripRepositoryImpl
import com.traveler.domain.repository.TripRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

class CreateTripUseCase(
    private val locationHistorySource: LocationHistorySource,
    private val mediaRepository: MediaRepository,
    private val transportClassifier: TransportClassifier,
    private val tripRepository: TripRepository,
    private val timezoneResolver: TimezoneResolver = GeoTimezoneEngine
) {

    suspend fun execute(
        timelineStream: InputStream,
        startDate: LocalDate,
        endDate: LocalDate,
        customTitle: String? = null,
        onProgress: ((String) -> Unit)? = null
    ): Trip = withContext(Dispatchers.Default) {
        try {
            onProgress?.invoke("Reconstructing travel timeline…")

            // P1-03: Use global timezone-safe ingestion window [UTC+14 .. UTC-12]
            val filter = DateRangeFilter.forLocalDateRange(startDate, endDate)

            // 1. Parse Timeline JSON with wide date range filter (P1-02: interval overlap semantics)
            val parseResult = locationHistorySource.parse(timelineStream, filter)

            // Explicit parse error check - fail early on fatal malformed or unsupported inputs
            if (parseResult.status == TimelineParseStatus.FATAL_UNSUPPORTED_FORMAT) {
                throw IllegalArgumentException(parseResult.errorMessage ?: "Unsupported JSON schema: no recognized Google Timeline records found")
            }
            if (parseResult.status == TimelineParseStatus.FATAL_MALFORMED_INPUT) {
                throw IllegalArgumentException(parseResult.errorMessage ?: "Malformed or corrupted JSON file")
            }

            // Load durable user overrides with composite key (targetType, targetSourceId) - P1-08
            val durableOverrides = try {
                tripRepository.getDurableUserOverrides().associateBy { Pair(it.targetType, it.targetSourceId) }
            } catch (_: Exception) {
                emptyMap()
            }

            // 2. Classify/Refine transport modes for all movement segments & apply durable user overrides
            val classifiedSegments = parseResult.movementSegments.map { segment ->
                val durableOverride = durableOverrides[Pair("SEGMENT_TRANSPORT", segment.id)]?.overrideValue
                val overrideMode = durableOverride?.let {
                    try { TransportMode.valueOf(it) } catch (_: Exception) { null }
                }

                val classified = if (overrideMode != null) {
                    segment.copy(
                        transport = TransportPrediction(overrideMode, 1.0f, "User Manual Correction", isUserOverride = true),
                        userOverrideMode = overrideMode,
                        isUserOverride = true
                    )
                } else {
                    val prediction = transportClassifier.classify(segment)
                    segment.copy(transport = prediction)
                }

                // P1-06: If effective mode is AIRPLANE and geometry was endpoint-only, provenance is ESTIMATED_GEODESIC
                if (classified.transport.mode == TransportMode.AIRPLANE &&
                    classified.geometryProvenance == GeometryProvenance.ENDPOINT_INTERPOLATED) {
                    classified.copy(geometryProvenance = GeometryProvenance.ESTIMATED_GEODESIC)
                } else {
                    classified
                }
            }

            // P0-01 ~ P0-08 & P1-11: Canonicalize movement timeline ONCE to eliminate overlapping replay routes
            val canonicalTimeline = MovementTimelineCanonicalizer.canonicalize(classifiedSegments)

            // P0-01 & P0-04: Hard Validation Before Persistence: assert strictly 0 temporal overlaps
            if (canonicalTimeline.diagnostics.finalOverlapViolationsCount > 0) {
                throw IllegalStateException("Timeline canonicalization invariant violated: ${canonicalTimeline.diagnostics.finalOverlapViolationsCount} overlap violations remaining.")
            }

            // P0-01, P0-03, P0-04: Only fail if there are unrepaired artificial jumps introduced by canonicalizer
            val unrepairableJumps = canonicalTimeline.diagnostics.spatialDiscontinuities.filter {
                it.type == SpatialDiscontinuityType.CANONICALIZER_INTRODUCED_JUMP
            }
            if (unrepairableJumps.isNotEmpty()) {
                throw IllegalStateException("Timeline canonicalization invariant violated: ${unrepairableJumps.size} unrepairable artificial jumps introduced by canonicalizer.")
            }

            val canonicalSegments = CanonicalTimelineValidator.requireNonOverlapping(
                canonicalTimeline.canonicalSegments.map { s ->
                    val durableOverride = durableOverrides[Pair("SEGMENT_TRANSPORT", s.id)]?.overrideValue
                    val overrideMode = durableOverride?.let {
                        try { TransportMode.valueOf(it) } catch (_: Exception) { null }
                    }
                    if (overrideMode != null) {
                        s.copy(
                            transport = TransportPrediction(overrideMode, 1.0f, "User Manual Correction", isUserOverride = true),
                            userOverrideMode = overrideMode,
                            isUserOverride = true
                        )
                    } else {
                        s
                    }
                }
            )

            // 3. Precompute timezones asynchronously in background for visits & movement endpoints & apply durable VISIT_NAME
            onProgress?.invoke("Preparing offline timezone data…")
            var engineInitFailureReason: String? = null
            val rawVisitsWithTimezones = parseResult.visits.map { v ->
                val durableNameOverride = durableOverrides[Pair("VISIT_NAME", v.id)]?.overrideValue
                val effectivePlaceName = durableNameOverride ?: v.placeName
                val isOverridden = durableNameOverride != null || v.isUserOverride
                val res = if (v.timezoneId != null) null else timezoneResolver.resolve(v.location)
                if (res is TimezoneResolution.EngineInitializationFailure) {
                    engineInitFailureReason = res.reason
                }
                val zone = v.timezoneId ?: res?.zoneIdOrNull?.id
                v.copy(placeName = effectivePlaceName, isUserOverride = isOverridden, timezoneId = zone)
            }
            val visitsWithTimezones = CanonicalVisitTimelineValidator.requireNonOverlapping(rawVisitsWithTimezones)

            val segmentsWithTimezones = canonicalSegments.map { s ->
                val startRes = if (s.startTimezoneId != null) null else timezoneResolver.resolve(s.startPoint)
                val endRes = if (s.endTimezoneId != null) null else timezoneResolver.resolve(s.endPoint)
                if (startRes is TimezoneResolution.EngineInitializationFailure) engineInitFailureReason = startRes.reason
                if (endRes is TimezoneResolution.EngineInitializationFailure) engineInitFailureReason = endRes.reason
                val startZone = s.startTimezoneId ?: startRes?.zoneIdOrNull?.id
                val endZone = s.endTimezoneId ?: endRes?.zoneIdOrNull?.id
                s.copy(startTimezoneId = startZone, endTimezoneId = endZone)
            }

            // P0-03: Surface explicit failure if offline timezone resolver fails globally
            if (engineInitFailureReason != null) {
                throw IllegalStateException("Offline timezone polygon engine failed to initialize: $engineInitFailureReason")
            }

            // Include movement start and end points in candidate trip timezones
            val candidateTripZones = (visitsWithTimezones.mapNotNull { it.timezoneId } +
                    segmentsWithTimezones.mapNotNull { it.startTimezoneId } +
                    segmentsWithTimezones.mapNotNull { it.endTimezoneId })
                .mapNotNull { try { ZoneId.of(it) } catch (_: Exception) { null } }
                .distinct()

            // 4. Query MediaStore for raw media candidates with diagnostics (P0-01)
            val scanResult = mediaRepository.queryMediaWithDiagnostics(filter.startEpochMs, filter.endEpochMs)
            val rawCandidates = scanResult.candidates
            val initDiag = scanResult.diagnostics

            // 5. Precompute photo direct GPS timezones asynchronously
            val enrichedCandidates = rawCandidates.map { candidate ->
                val gpsZone = candidate.directGps?.let { timezoneResolver.resolve(it).zoneIdOrNull?.id }
                candidate.copy(directGpsZoneId = gpsZone)
            }

            // 6. Contextually resolve timestamps using pure PhotoTimestampResolver
            onProgress?.invoke("Matching photos…")
            var exifCount = 0
            var fallbackCount = 0
            var unresolvedCount = 0
            var gpsExactCount = 0

            val mediaItems = enrichedCandidates.map { candidate ->
                if (candidate.directGps != null) gpsExactCount++
                val resolvedTime = PhotoTimestampResolver.resolve(
                    candidate = candidate,
                    candidateTripTimezones = candidateTripZones,
                    visits = visitsWithTimezones,
                    segments = segmentsWithTimezones,
                    tripIntervalStartMs = filter.startEpochMs,
                    tripIntervalEndMs = filter.endEpochMs
                )

                when (resolvedTime.confidence) {
                    TimestampConfidence.EXIF_EXACT, TimestampConfidence.EXIF_LOCAL -> exifCount++
                    TimestampConfidence.MEDIASTORE, TimestampConfidence.FILENAME_INFERRED -> fallbackCount++
                    TimestampConfidence.UNKNOWN -> unresolvedCount++
                    else -> {}
                }

                val location = candidate.directGps
                val locationConfidence = if (location != null) LocationConfidenceLevel.GPS_EXACT else LocationConfidenceLevel.UNKNOWN
                val confScore = if (location != null) 0.98f else 0.0f

                MediaItem(
                    id = candidate.id,
                    contentUriString = candidate.contentUriString,
                    fileName = candidate.fileName,
                    mimeType = candidate.mimeType,
                    timestampEpochMs = resolvedTime.timestampEpochMs,
                    timestampConfidence = resolvedTime.confidence,
                    captureTimezoneId = resolvedTime.resolvedZoneId,
                    location = location,
                    locationConfidence = locationConfidence,
                    confidenceScore = confScore,
                    captureEvidence = candidate.effectiveEvidence,
                    isRepresentative = durableOverrides[Pair("MEDIA_REPRESENTATIVE", candidate.id)]
                        ?.overrideValue?.toBooleanStrictOrNull() ?: false
                )
            }

            // 7. Match photos with places & movement segments (O(M log N) binary interval search)
            val matchedMediaItems = PhotoLocationMatcher.matchPhotos(
                photos = mediaItems,
                visits = visitsWithTimezones,
                segments = segmentsWithTimezones,
                rawPoints = parseResult.rawLocationPoints
            )

            // P0-01: Gate automatic Trip media inclusion on capture evidence (exclude downloads/memes/screenshots/weak date-only)
            val eligibleMediaItems = matchedMediaItems.filter { item ->
                val isMatchedWithGps = item.locationConfidence == LocationConfidenceLevel.GPS_EXACT && (item.matchedVisitId != null || item.matchedSegmentId != null)
                item.captureEvidence.isEligibleForTrip || isMatchedWithGps
            }

            // 8. Explicit Day Assignment without fabricating captureTimezoneId (P0-01A, P0-01B, P0-02)
            val visitMap = visitsWithTimezones.associateBy { it.id }
            val segmentMap = segmentsWithTimezones.associateBy { it.id }

            val dayAssignedMedia = eligibleMediaItems.map { photo ->
                val v = photo.matchedVisitId?.let { visitMap[it] }
                val s = photo.matchedSegmentId?.let { segmentMap[it] }

                when {
                    // 1. Photo's own resolved capture timezone takes highest precedence (P0-01)
                    photo.captureTimezoneId != null && photo.timestampEpochMs != null -> {
                        val zone = try { ZoneId.of(photo.captureTimezoneId) } catch (_: Exception) { null }
                        val dayIso = if (zone != null) {
                            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone).format(Instant.ofEpochMilli(photo.timestampEpochMs))
                        } else {
                            null
                        }
                        photo.copy(
                            assignedDayIso = dayIso,
                            dayAssignmentConfidence = if (dayIso != null) DayAssignmentConfidence.EXACT else DayAssignmentConfidence.UNKNOWN,
                            dayAssignmentProvenance = "Explicit capture timezone (${photo.captureTimezoneId})"
                        )
                    }

                    // 2. Matched Visit contextual timezone (P0-01)
                    v != null -> {
                        val zone = v.timezoneId?.let { try { ZoneId.of(it) } catch (_: Exception) { null } }
                        val ts = photo.timestampEpochMs ?: v.startTimestampEpochMs
                        val dayIso = if (zone != null) {
                            DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone).format(Instant.ofEpochMilli(ts))
                        } else {
                            null
                        }
                        photo.copy(
                            assignedDayIso = dayIso,
                            dayAssignmentConfidence = if (dayIso != null) DayAssignmentConfidence.CONTEXTUAL else DayAssignmentConfidence.UNKNOWN,
                            dayAssignmentProvenance = "Matched to visit ${v.placeName ?: v.id}"
                        )
                    }

                    // 3. Matched Movement contextual timezone (P0-01, P0-01A)
                    s != null -> {
                        val startZone = s.startTimezoneId?.let { try { ZoneId.of(it) } catch (_: Exception) { null } }
                        val endZone = s.endTimezoneId?.let { try { ZoneId.of(it) } catch (_: Exception) { null } }
                        val ts = photo.timestampEpochMs ?: s.startTimestampEpochMs

                        when {
                            startZone != null && endZone != null && startZone.id == endZone.id -> {
                                val dayIso = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(startZone).format(Instant.ofEpochMilli(ts))
                                photo.copy(
                                    assignedDayIso = dayIso,
                                    dayAssignmentConfidence = DayAssignmentConfidence.CONTEXTUAL,
                                    dayAssignmentProvenance = "Matched to movement segment ${s.id}"
                                )
                            }
                            startZone != null && endZone != null && startZone.id != endZone.id -> {
                                val startDateIso = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(startZone).format(Instant.ofEpochMilli(ts))
                                val endDateIso = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(endZone).format(Instant.ofEpochMilli(ts))
                                if (startDateIso == endDateIso) {
                                    photo.copy(
                                        assignedDayIso = startDateIso,
                                        dayAssignmentConfidence = DayAssignmentConfidence.CONSENSUS,
                                        dayAssignmentProvenance = "Endpoint timezone consensus (${startZone.id}, ${endZone.id}) on movement segment ${s.id}"
                                    )
                                } else {
                                    // Conflicting endpoint dates - evaluate location context if available
                                    val resolvedZone = if (photo.location != null) {
                                        timezoneResolver.resolveSync(photo.location).zoneIdOrNull
                                    } else null

                                    if (resolvedZone != null) {
                                        val resolvedDate = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(resolvedZone).format(Instant.ofEpochMilli(ts))
                                        photo.copy(
                                            assignedDayIso = resolvedDate,
                                            dayAssignmentConfidence = DayAssignmentConfidence.CONTEXTUAL,
                                            dayAssignmentProvenance = "Resolved location timezone (${resolvedZone.id}) during movement segment ${s.id}"
                                        )
                                    } else {
                                        photo.copy(
                                            assignedDayIso = null,
                                            dayAssignmentConfidence = DayAssignmentConfidence.AMBIGUOUS,
                                            dayAssignmentProvenance = "Conflicting movement endpoint dates ($startDateIso vs $endDateIso) on segment ${s.id}"
                                        )
                                    }
                                }
                            }
                            startZone != null -> {
                                val dayIso = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(startZone).format(Instant.ofEpochMilli(ts))
                                photo.copy(
                                    assignedDayIso = dayIso,
                                    dayAssignmentConfidence = DayAssignmentConfidence.CONTEXTUAL,
                                    dayAssignmentProvenance = "Matched to departure timezone (${startZone.id}) on movement segment ${s.id}"
                                )
                            }
                            endZone != null -> {
                                val dayIso = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(endZone).format(Instant.ofEpochMilli(ts))
                                photo.copy(
                                    assignedDayIso = dayIso,
                                    dayAssignmentConfidence = DayAssignmentConfidence.CONTEXTUAL,
                                    dayAssignmentProvenance = "Matched to arrival timezone (${endZone.id}) on movement segment ${s.id}"
                                )
                            }
                            else -> {
                                photo.copy(
                                    assignedDayIso = null,
                                    dayAssignmentConfidence = DayAssignmentConfidence.UNKNOWN,
                                    dayAssignmentProvenance = "Matched to movement segment ${s.id} with no timezone data"
                                )
                            }
                        }
                    }

                    // 4. Candidate Trip Timezones Consensus (P0-01, P0-02)
                    photo.timestampEpochMs != null -> {
                        if (candidateTripZones.isNotEmpty()) {
                            val dateSet = candidateTripZones.map { zone ->
                                DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(zone).format(Instant.ofEpochMilli(photo.timestampEpochMs))
                            }.distinct()

                            if (dateSet.size == 1) {
                                photo.copy(
                                    assignedDayIso = dateSet.first(),
                                    dayAssignmentConfidence = DayAssignmentConfidence.CONSENSUS,
                                    dayAssignmentProvenance = "Consensus across candidate timezones (${candidateTripZones.joinToString { it.id }})"
                                )
                            } else {
                                // Conflicting dates across trip timezones -> AMBIGUOUS, assignedDayIso = null
                                photo.copy(
                                    assignedDayIso = null,
                                    dayAssignmentConfidence = DayAssignmentConfidence.AMBIGUOUS,
                                    dayAssignmentProvenance = "Conflicting candidate dates across trip timezones (${dateSet.joinToString()})"
                                )
                            }
                        } else {
                            // Zero candidate timezones -> UNKNOWN, not fake UTC CONSENSUS
                            photo.copy(
                                assignedDayIso = null,
                                dayAssignmentConfidence = DayAssignmentConfidence.UNKNOWN,
                                dayAssignmentProvenance = "Absolute timestamp known but zero candidate trip timezones"
                            )
                        }
                    }

                    // 5. Unknown / unanchored media
                    else -> {
                        photo.copy(
                            assignedDayIso = null,
                            dayAssignmentConfidence = DayAssignmentConfidence.UNKNOWN,
                            dayAssignmentProvenance = "Unknown capture time and no spatial anchor"
                        )
                    }
                }
            }

            // P1-11: Filter media strictly to requested [startDate..endDate] (or uncertain date)
            val startIsoStr = startDate.toString()
            val endIsoStr = endDate.toString()
            val filteredAssignedMedia = dayAssignedMedia.filter { m ->
                m.assignedDayIso == null || (m.assignedDayIso in startIsoStr..endIsoStr)
            }

            // P0-01 & P1-05: Delegate day decomposition to reconstructDays for 100% parity with reload
            val tripDays = TripRepositoryImpl.reconstructDays(
                visits = visitsWithTimezones,
                segments = segmentsWithTimezones,
                mediaItems = filteredAssignedMedia,
                tripStartDateIso = startIsoStr,
                tripEndDateIso = endIsoStr
            )
            val uncertainDateMediaList = filteredAssignedMedia.filter { it.assignedDayIso == null }

            val totalDist = tripDays.sumOf { it.totalDistanceMeters }
            val totalPhotos = filteredAssignedMedia.size

            // P0-01: Compute full 20-metric diagnostics
            val matchedVisitCount = eligibleMediaItems.count { it.matchedVisitId != null }
            val matchedMovementCount = eligibleMediaItems.count { it.matchedSegmentId != null }
            val dateConfidentUnassignedCount = filteredAssignedMedia.count { it.matchedVisitId == null && it.matchedSegmentId == null && it.assignedDayIso != null }
            val ambiguousDateCount = dayAssignedMedia.count { it.dayAssignmentConfidence == DayAssignmentConfidence.AMBIGUOUS }
            val finalTripCount = filteredAssignedMedia.size

            val strongOrLikelyCount = rawCandidates.count { it.captureEvidence.isEligibleForTrip }
            val downloadsExcludedCount = rawCandidates.count { it.captureEvidence == MediaCaptureEvidence.DOWNLOADED_OR_EXTERNAL && it.directGps == null }
            val screenshotsExcludedCount = rawCandidates.count { it.captureEvidence == MediaCaptureEvidence.SCREENSHOT && it.directGps == null }
            val weakDateOnlyExcludedCount = rawCandidates.count { it.captureEvidence == MediaCaptureEvidence.WEAK_DATE_ONLY && it.directGps == null }

            val fullDiagnostics = initDiag.copy(
                rowsPassingBroadIngestionWindow = rawCandidates.size,
                rowsParsedExifTimestamp = exifCount,
                rowsParsedFallbackTimestamp = fallbackCount,
                rowsExcludedUnresolvedTimestamp = unresolvedCount,
                rowsWithGpsExact = gpsExactCount,
                rowsMatchedVisit = matchedVisitCount,
                rowsMatchedMovement = matchedMovementCount,
                rowsDateConfidentUnassigned = dateConfidentUnassignedCount,
                rowsAmbiguousDate = ambiguousDateCount,
                strongOrLikelyCaptures = strongOrLikelyCount,
                downloadsExcluded = downloadsExcludedCount,
                screenshotsExcluded = screenshotsExcludedCount,
                weakDateOnlyExcluded = weakDateOnlyExcludedCount,
                finalTripMediaCount = finalTripCount
            )

            val detectedCities = visitsWithTimezones.mapNotNull { it.placeName }.distinct().take(5)
            val title = customTitle?.takeIf { it.isNotBlank() }
                ?: if (detectedCities.isNotEmpty()) "Trip to ${detectedCities.joinToString(", ")}" else "Trip $startDate ~ $endDate"

            val trip = Trip(
                id = UUID.randomUUID().toString(),
                title = title,
                startDateIso = startIsoStr,
                endDateIso = endIsoStr,
                days = tripDays,
                uncertainDateMedia = uncertainDateMediaList,
                totalDistanceMeters = totalDist,
                cities = detectedCities,
                countries = emptyList(),
                totalMediaCount = totalPhotos,
                mediaDiagnostics = fullDiagnostics,
                movementDiagnostics = canonicalTimeline.diagnostics,
                createdAtEpochMs = System.currentTimeMillis()
            )

            // Save trip to Room database
            onProgress?.invoke("Saving travel story…")
            tripRepository.saveTrip(trip)

            trip
        } finally {
            // P0-02 & P1-09: Always release timezone engine resources on success, failure, or cancellation
            timezoneResolver.release()
        }
    }
}
