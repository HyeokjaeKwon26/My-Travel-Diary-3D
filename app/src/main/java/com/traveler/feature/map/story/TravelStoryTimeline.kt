package com.traveler.feature.map.story

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.common.geo.GeodesicUtils
import com.traveler.core.common.geo.WebMercator
import com.traveler.core.media.*
import com.traveler.core.model.*
import com.traveler.core.timeline.CanonicalTimelineValidator
import com.traveler.core.timeline.CanonicalVisitTimelineValidator
import com.traveler.core.timeline.MovementTimelineCanonicalizer
import com.traveler.core.timeline.VisitCandidateCanonicalizer
import com.traveler.feature.map.renderer.*
import kotlin.math.abs

/**
 * Pure domain episode in the cinematic travel story (P1).
 */
sealed interface StoryEpisode {
    val durationStorySeconds: Float
    val startTimestampEpochMs: Long
    val endTimestampEpochMs: Long
    val stableId: String

    data class VisitEpisode(
        val visit: Visit,
        override val durationStorySeconds: Float,
        override val startTimestampEpochMs: Long = visit.startTimestampEpochMs,
        override val endTimestampEpochMs: Long = visit.endTimestampEpochMs,
        override val stableId: String = "ep_visit_${visit.id}"
    ) : StoryEpisode

    data class MovementEpisode(
        val segment: MovementSegment,
        val pathPoints: List<GeoPoint>,
        val cumulativeDistances: List<Double>,
        val totalDistanceMeters: Double,
        val headingTrack: PrecomputedHeadingTrack,
        override val durationStorySeconds: Float,
        override val startTimestampEpochMs: Long = segment.startTimestampEpochMs,
        override val endTimestampEpochMs: Long = segment.endTimestampEpochMs,
        override val stableId: String = "ep_seg_${segment.id}"
    ) : StoryEpisode
}

data class StoryTitleCard(
    val title: String,
    val dateRangeStr: String,
    val subtitle: String = "My Travel Diary 3D",
    val durationSeconds: Float = 2.0f
)

data class StoryEndCard(
    val title: String,
    val totalDaysStr: String,
    val totalDistanceStr: String,
    val memoriesCountStr: String,
    val durationSeconds: Float = 2.5f
)

data class TimelineStoryDiagnostics(
    val unresolvedCrossTypeOverlapCount: Int = 0,
    val backwardEpisodeChronologyCount: Int = 0,
    val overlapGeneratedBridgeCount: Int = 0
)

/**
 * Unified, deterministic, random-access TravelStoryTimeline (P1).
 *
 * Drives BOTH:
 * 1. Interactive Compose Canvas playback ([TravelMapView]).
 * 2. Deterministic offscreen video rendering ([TravelVideoRenderer] & [TravelVideoEncoder]).
 *
 * Invariants:
 * 1. Timeline is authoritative: Traveler position, heading, mode, and camera are computed
 *    EXCLUSIVELY from the canonical route and active episode.
 * 2. Photos are pure passive overlays: Displaying a photo NEVER modifies story time,
 *    marker coordinates, camera target, or node index.
 * 3. Strict Monotonicity: Progress in [0.0 .. 1.0] maps monotonically to story time (0 rewinds).
 */
class TravelStoryTimeline private constructor(
    val profile: StoryDurationProfile,
    val episodes: List<StoryEpisode>,
    val photoMoments: List<PhotoStoryMoment>,
    val titleCard: StoryTitleCard?,
    val endCard: StoryEndCard?,
    val totalStoryDurationSeconds: Float,
    private val episodeStartTimes: FloatArray,
    val diagnostics: TimelineStoryDiagnostics = TimelineStoryDiagnostics(),
    val totalTripDistanceMeters: Double = 0.0,
    private val episodeStartDistances: DoubleArray = DoubleArray(0)
) {
    /**
     * Evaluates playback state at progress in [0.0 .. 1.0] using O(log N) binary search.
     */
    fun evaluate(progress: Float): TravelPlaybackState {
        val clampedProgress = progress.coerceIn(0.0f, 1.0f)
        val currentStoryTime = clampedProgress * totalStoryDurationSeconds
        return evaluateAtStoryTime(currentStoryTime, clampedProgress)
    }

    /**
     * Deterministically evaluates playback state at an exact story time in seconds.
     */
    fun evaluateAtStoryTime(
        storyTimeSeconds: Float,
        progress: Float = (storyTimeSeconds / maxOf(0.001f, totalStoryDurationSeconds)).coerceIn(0f, 1f)
    ): TravelPlaybackState {
        if (episodes.isEmpty()) {
            val defaultPoint = GeoPoint(0.0, 0.0)
            return TravelPlaybackState(
                progress = progress,
                storyTimeMs = 0L,
                currentPosition = defaultPoint,
                currentTransportMode = TransportMode.UNKNOWN,
                currentHeadingDegrees = 0.0f,
                currentVisit = null,
                activePhoto = null,
                cameraCenter = defaultPoint,
                cameraSpanLat = 0.1,
                cameraSpanLng = 0.1,
                isTitleCardActive = false,
                isEndCardActive = false
            )
        }

        // Title Card Phase
        if (titleCard != null && storyTimeSeconds < titleCard.durationSeconds) {
            val firstPos = when (val first = episodes.first()) {
                is StoryEpisode.VisitEpisode -> first.visit.location
                is StoryEpisode.MovementEpisode -> first.segment.startPoint
            }
            return TravelPlaybackState(
                progress = progress,
                storyTimeMs = episodes.first().startTimestampEpochMs,
                currentPosition = firstPos,
                currentTransportMode = TransportMode.UNKNOWN,
                currentHeadingDegrees = 0f,
                currentVisit = null,
                activePhoto = null,
                cameraCenter = firstPos,
                cameraSpanLat = 0.15,
                cameraSpanLng = 0.15,
                isTitleCardActive = true,
                isEndCardActive = false,
                currentAltitudeMeters = firstPos.altitudeMeters,
                currentSpeedKmh = 0.0,
                currentTraveledDistanceMeters = 0.0,
                totalTripDistanceMeters = totalTripDistanceMeters
            )
        }

        // End Card Phase
        val episodePhaseEnd = (episodeStartTimes.lastOrNull() ?: 0f) + (episodes.lastOrNull()?.durationStorySeconds ?: 0f)
        if (endCard != null && storyTimeSeconds >= episodePhaseEnd) {
            val lastPos = when (val last = episodes.last()) {
                is StoryEpisode.VisitEpisode -> last.visit.location
                is StoryEpisode.MovementEpisode -> last.segment.endPoint
            }
            return TravelPlaybackState(
                progress = progress,
                storyTimeMs = episodes.last().endTimestampEpochMs,
                currentPosition = lastPos,
                currentTransportMode = TransportMode.UNKNOWN,
                currentHeadingDegrees = 0f,
                currentVisit = null,
                activePhoto = null,
                cameraCenter = lastPos,
                cameraSpanLat = 0.20,
                cameraSpanLng = 0.20,
                isTitleCardActive = false,
                isEndCardActive = true,
                currentAltitudeMeters = lastPos.altitudeMeters,
                currentSpeedKmh = 0.0,
                currentTraveledDistanceMeters = totalTripDistanceMeters,
                totalTripDistanceMeters = totalTripDistanceMeters
            )
        }

        // Episode Phase (Binary Search O(log N))
        var episodeIndex = episodeStartTimes.binarySearch(storyTimeSeconds)
        if (episodeIndex < 0) {
            episodeIndex = -(episodeIndex + 1) - 1
        }
        episodeIndex = episodeIndex.coerceIn(0, episodes.size - 1)

        val activeEpisode = episodes[episodeIndex]
        val epStart = episodeStartTimes[episodeIndex]
        val epProgress = if (activeEpisode.durationStorySeconds > 0f) {
            ((storyTimeSeconds - epStart) / activeEpisode.durationStorySeconds).coerceIn(0.0f, 1.0f)
        } else {
            1.0f
        }

        // Active photo overlay (P0-03 & P0-06: parentEpisodeIndex AND parentEpisodeStableId AND parentId must match active episode)
        val activeEpisodeParentId = when (activeEpisode) {
            is StoryEpisode.VisitEpisode -> activeEpisode.visit.id
            is StoryEpisode.MovementEpisode -> activeEpisode.segment.id
        }
        val activePhoto = photoMoments.firstOrNull { moment ->
            moment.parentEpisodeIndex == episodeIndex &&
            (moment.parentEpisodeStableId.isEmpty() || moment.parentEpisodeStableId == activeEpisode.stableId) &&
            moment.parentId == activeEpisodeParentId &&
            storyTimeSeconds >= moment.displayStartStorySeconds &&
            storyTimeSeconds < moment.displayEndStorySeconds
        }?.photo

        val tripStartEpochMs = episodes.firstOrNull()?.startTimestampEpochMs ?: 0L

        return when (activeEpisode) {
            is StoryEpisode.VisitEpisode -> {
                val loc = activeEpisode.visit.location
                val startTs = activeEpisode.startTimestampEpochMs
                val endTs = activeEpisode.endTimestampEpochMs
                val currentTs = startTs + ((endTs - startTs).toDouble() * epProgress).toLong()

                // P2-08: Day transition calculation
                val elapsedDays = if (tripStartEpochMs > 0L && currentTs >= tripStartEpochMs) {
                    ((currentTs - tripStartEpochMs) / 86_400_000L).toInt() + 1
                } else 1
                val msIntoDay = if (tripStartEpochMs > 0L) (currentTs - tripStartEpochMs) % 86_400_000L else 0L
                val isDayTransition = elapsedDays > 1 && msIntoDay < 4_000_000L
                val dayLabel = if (isDayTransition) "Day $elapsedDays" else null

                // P2-01 & P2-04: Arrival settling zoom from 0.080 to 0.060
                val settleT = (epProgress / 0.35f).coerceIn(0f, 1f)
                val visitSpan = 0.080 * (1f - settleT) + 0.060 * settleT

                val alt = activeEpisode.visit.location.altitudeMeters
                val distBefore = if (episodeIndex in episodeStartDistances.indices) episodeStartDistances[episodeIndex] else 0.0

                TravelPlaybackState(
                    progress = progress,
                    storyTimeMs = currentTs,
                    currentPosition = loc,
                    currentTransportMode = TransportMode.WALK,
                    currentHeadingDegrees = 0.0f,
                    currentSegment = null,
                    currentVisit = activeEpisode.visit,
                    activePhoto = activePhoto,
                    cameraCenter = loc,
                    cameraSpanLat = visitSpan,
                    cameraSpanLng = visitSpan,
                    isTitleCardActive = false,
                    isEndCardActive = false,
                    episodeIndex = episodeIndex,
                    currentDayIndex = elapsedDays,
                    dayTransitionLabel = dayLabel,
                    isDayTransitionActive = isDayTransition,
                    currentAltitudeMeters = alt,
                    currentSpeedKmh = 0.0,
                    currentTraveledDistanceMeters = minOf(totalTripDistanceMeters,distBefore),
                    totalTripDistanceMeters = totalTripDistanceMeters
                )
            }
            is StoryEpisode.MovementEpisode -> {
                val seg = activeEpisode.segment
                val path = activeEpisode.pathPoints
                val cumDist = activeEpisode.cumulativeDistances
                val totalDist = activeEpisode.totalDistanceMeters

                // P2-02: Smoothstep cinematic easing on route progress
                val easedProgress = epProgress * epProgress * (3f - 2f * epProgress)
                val targetDist = totalDist * easedProgress
                val currentPos = VehicleHeadingCalculator.interpolatePointAtDistance(path, cumDist, targetDist)
                val heading = activeEpisode.headingTrack.evaluate(targetDist)

                val startTs = activeEpisode.startTimestampEpochMs
                val endTs = activeEpisode.endTimestampEpochMs
                val currentTs = startTs + ((endTs - startTs).toDouble() * epProgress).toLong()

                // P2-08: Day transition calculation
                val elapsedDays = if (tripStartEpochMs > 0L && currentTs >= tripStartEpochMs) {
                    ((currentTs - tripStartEpochMs) / 86_400_000L).toInt() + 1
                } else 1
                val msIntoDay = if (tripStartEpochMs > 0L) (currentTs - tripStartEpochMs) % 86_400_000L else 0L
                val isDayTransition = elapsedDays > 1 && msIntoDay < 4_000_000L
                val dayLabel = if (isDayTransition) "Day $elapsedDays" else null

                // P2-01: Semantic movement-dependent camera framing
                val isFlight = seg.effectiveMode == TransportMode.AIRPLANE
                val isFerry = seg.effectiveMode == TransportMode.FERRY
                val isTrain = seg.effectiveMode == TransportMode.TRAIN
                val isWalk = seg.effectiveMode == TransportMode.WALK || seg.effectiveMode == TransportMode.BICYCLE

                val baseSpan = when {
                    isFlight -> 6.0
                    isFerry -> 0.18 // show coastline / water context
                    isTrain -> 0.28 // wide regional movement
                    isWalk -> 0.045 // closer local exploration
                    totalDist > 80_000.0 -> 0.22 // Long highway driving: wide
                    totalDist > 20_000.0 -> 0.14 // Medium road trip
                    else -> 0.085 // Slow city driving
                }

                // Arrival deceleration zoom toward destination in final 15%
                val finalCameraSpan = if (epProgress > 0.85f && !isFlight) {
                    val arrivalT = ((epProgress - 0.85f) / 0.15f).coerceIn(0f, 1f)
                    baseSpan * (1f - arrivalT) + 0.065 * arrivalT
                } else {
                    baseSpan
                }

                // P2-03: Look-Ahead Camera (blends traveler position + future route position)
                val lookAheadDelta = (totalDist * 0.18).coerceIn(200.0, 35_000.0)
                val lookAheadDist = minOf(totalDist, targetDist + lookAheadDelta)
                val lookAheadPos = VehicleHeadingCalculator.interpolatePointAtDistance(path, cumDist, lookAheadDist)

                // Settle look-ahead to 0 at destination approach for smooth arrival
                val arrivalFactor = if (epProgress > 0.82f) {
                    (1.0f - (epProgress - 0.82f) / 0.18f).coerceIn(0f, 1f)
                } else {
                    minOf(1.0f, epProgress / 0.12f)
                }
                val lookAheadWeight = (0.22f * arrivalFactor).coerceIn(0f, 0.30f)

                val camLat = currentPos.latitude * (1.0 - lookAheadWeight) + lookAheadPos.latitude * lookAheadWeight
                val camLng = currentPos.longitude * (1.0 - lookAheadWeight) + lookAheadPos.longitude * lookAheadWeight
                val cameraCenter = GeoPoint(camLat, camLng)

                val alt = currentPos.altitudeMeters

                // Real kinematic speed calculation (zero mockup / hardcoding)
                val rawAvgSpeedKmh = seg.averageSpeedKmh
                val baseSpeedKmh = if (rawAvgSpeedKmh in 0.5..1200.0) {
                    rawAvgSpeedKmh
                } else if (activeEpisode.durationStorySeconds > 0f) {
                    (totalDist / 1000.0) / (activeEpisode.durationStorySeconds / 3600.0)
                } else {
                    0.0
                }
                val accelEnvelope = (epProgress / 0.10f).coerceIn(0f, 1f)
                val decelEnvelope = ((1f - epProgress) / 0.10f).coerceIn(0f, 1f)
                val speedEnvelope = minOf(accelEnvelope, decelEnvelope)
                val smoothedEnvelope = (speedEnvelope * speedEnvelope * (3f - 2f * speedEnvelope)).toDouble()
                val currentSpeed = baseSpeedKmh * smoothedEnvelope

                val distBefore = if (episodeIndex in episodeStartDistances.indices) episodeStartDistances[episodeIndex] else 0.0
                val isSyntheticBridge = seg.id.startsWith("bridge_")
                val currentTraveledDist = if (isSyntheticBridge) {
                    distBefore
                } else {
                    minOf(totalTripDistanceMeters, distBefore + seg.distanceMeters * (targetDist / maxOf(1.0,totalDist)).coerceIn(0.0,1.0))
                }

                TravelPlaybackState(
                    progress = progress,
                    storyTimeMs = currentTs,
                    currentPosition = currentPos,
                    currentTransportMode = seg.effectiveMode,
                    currentHeadingDegrees = heading,
                    currentSegment = seg,
                    currentVisit = null,
                    activePhoto = activePhoto,
                    cameraCenter = cameraCenter,
                    cameraSpanLat = finalCameraSpan,
                    cameraSpanLng = finalCameraSpan,
                    isTitleCardActive = false,
                    isEndCardActive = false,
                    episodeIndex = episodeIndex,
                    currentDayIndex = elapsedDays,
                    dayTransitionLabel = dayLabel,
                    isDayTransitionActive = isDayTransition,
                    currentAltitudeMeters = alt,
                    currentSpeedKmh = currentSpeed,
                    currentTraveledDistanceMeters = currentTraveledDist,
                    totalTripDistanceMeters = totalTripDistanceMeters
                )
            }
        }
    }

    /**
     * Validates all photo teleport invariants (P0-01, P0-04, P0-06, P0-08).
     *
     * All counts in the returned [PhotoTeleportReport] must be 0 for a valid cinematic playback.
     */
    fun validateInvariants(): PhotoTeleportReport {
        var completedEpisodeReactivationCount = 0
        var outOfBoundsPhotoWindowCount = 0
        var photoParentMismatchCount = 0
        val details = mutableListOf<PhotoMomentDiagnostic>()

        for (moment in photoMoments) {
            // Find parent episode
            val parentEpIdx = if (moment.parentEpisodeIndex in episodes.indices) {
                moment.parentEpisodeIndex
            } else {
                episodes.indexOfFirst { ep ->
                    when (ep) {
                        is StoryEpisode.VisitEpisode -> ep.visit.id == moment.parentId
                        is StoryEpisode.MovementEpisode -> ep.segment.id == moment.parentId
                    }
                }
            }

            if (parentEpIdx < 0) {
                photoParentMismatchCount++
                details.add(
                    PhotoMomentDiagnostic(
                        mediaId = moment.mediaId,
                        parentId = moment.parentId,
                        parentType = moment.parentType,
                        parentEpisodeIndex = -1,
                        displayStartStorySeconds = moment.displayStartStorySeconds,
                        displayEndStorySeconds = moment.displayEndStorySeconds,
                        parentEpisodeStoryStart = -1f,
                        parentEpisodeStoryEnd = -1f,
                        isOutOfBounds = true,
                        isEpisodeMismatch = true
                    )
                )
                continue
            }

            val epStart = episodeStartTimes[parentEpIdx]
            val epEnd = epStart + episodes[parentEpIdx].durationStorySeconds

            // P0-01: Display window inside parent episode
            val isOutOfBounds = moment.displayStartStorySeconds < epStart - 0.02f ||
                    moment.displayEndStorySeconds > epEnd + 0.02f
            if (isOutOfBounds) {
                outOfBoundsPhotoWindowCount++
            }

            // P0-06: At photo midpoint, active episode must be the photo's parent
            val midpoint = (moment.displayStartStorySeconds + moment.displayEndStorySeconds) / 2f
            val midEpIdx = run {
                var idx = episodeStartTimes.toList().binarySearch { it.compareTo(midpoint) }
                if (idx < 0) idx = -(idx + 1) - 1
                idx.coerceIn(0, episodes.size - 1)
            }
            val activeEpParentId = when (val ep = episodes[midEpIdx]) {
                is StoryEpisode.VisitEpisode -> ep.visit.id
                is StoryEpisode.MovementEpisode -> ep.segment.id
            }
            val isEpisodeMismatch = activeEpParentId != moment.parentId
            if (isEpisodeMismatch) {
                completedEpisodeReactivationCount++
            }

            details.add(
                PhotoMomentDiagnostic(
                    mediaId = moment.mediaId,
                    parentId = moment.parentId,
                    parentType = moment.parentType,
                    parentEpisodeIndex = parentEpIdx,
                    displayStartStorySeconds = moment.displayStartStorySeconds,
                    displayEndStorySeconds = moment.displayEndStorySeconds,
                    parentEpisodeStoryStart = epStart,
                    parentEpisodeStoryEnd = epEnd,
                    isOutOfBounds = isOutOfBounds,
                    isEpisodeMismatch = isEpisodeMismatch
                )
            )
        }

        return PhotoTeleportReport(
            completedEpisodeReactivationCount = completedEpisodeReactivationCount,
            outOfBoundsPhotoWindowCount = outOfBoundsPhotoWindowCount,
            photoParentMismatchCount = photoParentMismatchCount,
            totalPhotoMoments = photoMoments.size,
            details = details
        )
    }

    companion object {
        /**
         * Builds a deterministic [TravelStoryTimeline] from render model data and duration profile.
         */
        fun build(
            renderModel: TravelMapRenderModel,
            profile: StoryDurationProfile = StoryDurationProfile.STANDARD,
            titleCard: StoryTitleCard? = null,
            endCard: StoryEndCard? = null
        ): TravelStoryTimeline {
            val canonicalSegments = CanonicalTimelineValidator.requireNonOverlapping(
                if (CanonicalTimelineValidator.countOverlapViolations(renderModel.segments) == 0) {
                    renderModel.segments
                } else {
                    MovementTimelineCanonicalizer.canonicalize(renderModel.segments).canonicalSegments
                }
            ).sortedBy { it.startTimestampEpochMs }

            val canonicalVisits = CanonicalVisitTimelineValidator.requireNonOverlapping(
                if (CanonicalVisitTimelineValidator.countOverlapViolations(renderModel.visits) == 0) {
                    renderModel.visits
                } else {
                    VisitCandidateCanonicalizer.deduplicate(renderModel.visits)
                }
            ).sortedBy { it.startTimestampEpochMs }

            // Cross-Type Temporal Overlap Resolution (Pass 21.8b):
            // Split any movement segments that overlap with intermediate visits so that
            // visits and movements interleave in strictly non-overlapping, true chronological order.
            val resolvedSegments = resolveCrossTypeTemporalOverlaps(canonicalSegments, canonicalVisits)

            // Precompute globally sorted representative photo moments (P0-03 ~ P0-05)
            val photoMoments = PhotoStoryEngine.buildGlobalPhotoStoryMoments(
                visits = canonicalVisits,
                segments = resolvedSegments,
                allPhotos = renderModel.photos,
                profile = profile
            )

            val photoMomentsByVisit = photoMoments.filter { it.parentType == "VISIT" }.groupBy { it.parentId }
            val photoMomentsBySegment = photoMoments.filter { it.parentType == "MOVEMENT" }.groupBy { it.parentId }

            // Select key visits and key segments according to profile (P1)
            val maxKeyVisits = when (profile) {
                StoryDurationProfile.SHORT -> 8
                StoryDurationProfile.STANDARD -> 25
                StoryDurationProfile.FULL_STORY -> 45
            }
            val maxKeySegments = when (profile) {
                StoryDurationProfile.SHORT -> 10
                StoryDurationProfile.STANDARD -> 30
                StoryDurationProfile.FULL_STORY -> 55
            }

            // P0: Photo parent visits/segments are NON-DROPPABLE.
            // Every selected representative MUST have its parent Episode in the final Story.
            val requiredVisitIds = photoMomentsByVisit.keys.toHashSet()
            val requiredSegmentIds = photoMomentsBySegment.keys.toHashSet()

            val keyVisitIds = HashSet<String>(requiredVisitIds) // Start with ALL photo parents
            canonicalVisits.firstOrNull()?.id?.let { keyVisitIds.add(it) }
            canonicalVisits.lastOrNull()?.id?.let { keyVisitIds.add(it) }
            // Fill remaining budget with longest-dwell visits
            canonicalVisits.sortedByDescending { it.endTimestampEpochMs - it.startTimestampEpochMs }.forEach {
                if (keyVisitIds.size < maxOf(maxKeyVisits, requiredVisitIds.size + 2)) keyVisitIds.add(it.id)
            }

            val keySegmentIds = HashSet<String>(requiredSegmentIds) // Start with ALL photo parents
            resolvedSegments.firstOrNull()?.id?.let { keySegmentIds.add(it) }
            resolvedSegments.lastOrNull()?.id?.let { keySegmentIds.add(it) }
            // If a split sub-segment's parent had photos or was required, keep the sub-segments
            for (seg in resolvedSegments) {
                val origId = seg.id.substringBefore("_part_")
                if (origId != seg.id && (origId in requiredSegmentIds || seg.id in requiredSegmentIds)) {
                    keySegmentIds.add(seg.id)
                }
            }
            resolvedSegments.sortedWith(
                compareByDescending<MovementSegment> { it.effectiveMode == TransportMode.AIRPLANE }
                    .thenByDescending { it.isUserOverride }
                    .thenByDescending { it.distanceMeters }
            ).forEach {
                if (keySegmentIds.size < maxOf(maxKeySegments, requiredSegmentIds.size + 2)) keySegmentIds.add(it.id)
            }

            val rawEpisodes = mutableListOf<StoryEpisode>()
            val chronologicalItems = ArrayList<Pair<Long, Any>>()
            for (v in canonicalVisits) chronologicalItems.add(v.startTimestampEpochMs to v)
            for (s in resolvedSegments) chronologicalItems.add(s.startTimestampEpochMs to s)
            chronologicalItems.sortBy { it.first }

            val durationMultiplier = when (profile) {
                StoryDurationProfile.SHORT -> 0.70f
                StoryDurationProfile.STANDARD -> 1.0f
                StoryDurationProfile.FULL_STORY -> 1.35f
            }

            for ((_, item) in chronologicalItems) {
                when (item) {
                    is Visit -> {
                        if (canonicalVisits.size <= 8 || item.id in keyVisitIds) {
                            val vMoments = photoMomentsByVisit[item.id] ?: emptyList()
                            val baseDuration = when {
                                vMoments.isNotEmpty() -> 3.0f + (vMoments.size - 1) * 1.5f
                                item.isUserOverride -> 2.5f
                                else -> 2.0f
                            }
                            val duration = maxOf(1.2f, baseDuration * durationMultiplier, vMoments.size*2.6f+.6f)
                            rawEpisodes.add(StoryEpisode.VisitEpisode(visit = item, durationStorySeconds = duration))
                        }
                    }
                    is MovementSegment -> {
                        if (resolvedSegments.size <= 10 || item.id in keySegmentIds) {
                            val movement = buildMovementEpisode(item, durationMultiplier)
                            val count = photoMoments.count { it.parentType == "MOVEMENT" && it.parentId == item.id }
                            rawEpisodes.add(movement.copy(durationStorySeconds=maxOf(movement.durationStorySeconds,count*2.6f+.6f)))
                        }
                    }
                }
            }

            // Connect all consecutive episodes:
            // CRITICAL INVARIANT: PREFER REAL RECORDED ROAD SEGMENTS OVER SYNTHETIC BRIDGES
            val connectedEpisodes = mutableListOf<StoryEpisode>()
            val addedSegmentIds = HashSet<String>()
            for (ep in rawEpisodes) {
                if (ep is StoryEpisode.MovementEpisode) addedSegmentIds.add(ep.segment.id)
            }

            var overlapGeneratedBridgeAttempts = 0

            for (i in rawEpisodes.indices) {
                val curr = rawEpisodes[i]
                connectedEpisodes.add(curr)
                if (i < rawEpisodes.size - 1) {
                    val next = rawEpisodes[i + 1]
                    val currEndPos = when (curr) {
                        is StoryEpisode.VisitEpisode -> curr.visit.location
                        is StoryEpisode.MovementEpisode -> curr.pathPoints.last()
                    }
                    val nextStartPos = when (next) {
                        is StoryEpisode.VisitEpisode -> next.visit.location
                        is StoryEpisode.MovementEpisode -> next.pathPoints.first()
                    }
                    val gapDist = GeodesicUtils.distanceMeters(currEndPos, nextStartPos)

                    // HARD INVARIANT (Pass 21.8b): Bridges/connectors are ONLY valid for legitimate forward temporal gaps.
                    // If curr.endTimestampEpochMs > next.startTimestampEpochMs, it is an overlap / backward chronology error,
                    // NOT a gap, and must NEVER generate a bridge or connector!
                    val isLegitimateTemporalGap = curr.endTimestampEpochMs <= next.startTimestampEpochMs

                    if (gapDist > 150.0) {
                        if (isLegitimateTemporalGap) {
                            // Check if real recorded movement segments exist strictly within the temporal gap (curr.end -> next.start)
                            val gapStart = curr.endTimestampEpochMs
                            val gapEnd = next.startTimestampEpochMs
                            val connectingRealSegments = if (gapEnd >= gapStart) {
                                resolvedSegments.filter { seg ->
                                    seg.id !in addedSegmentIds &&
                                    seg.startTimestampEpochMs >= gapStart - 60_000L &&
                                    seg.endTimestampEpochMs <= gapEnd + 60_000L
                                }.sortedBy { it.startTimestampEpochMs }
                            } else emptyList()

                            if (connectingRealSegments.isNotEmpty()) {
                                // Insert the actual real road segments that were omitted by budget!
                                for (realSeg in connectingRealSegments) {
                                    addedSegmentIds.add(realSeg.id)
                                    val realEp = buildMovementEpisode(realSeg, durationMultiplier)
                                    connectedEpisodes.add(realEp)
                                }
                            }
                            else if (gapEnd > gapStart) {
                                // Presentation-only connection; never persist it or count it as recorded distance.
                                val bridge = MovementSegment(
                                    id = "bridge_${curr.stableId}_${next.stableId}",
                                    startTimestampEpochMs = gapStart, endTimestampEpochMs = gapEnd,
                                    startPoint = currEndPos, endPoint = nextStartPos,
                                    distanceMeters = 0.0, durationMillis = gapEnd-gapStart,
                                    transport = TransportPrediction(TransportMode.UNKNOWN, 0f, "Display-only estimated connection"),
                                    geometryProvenance = GeometryProvenance.CONTINUITY_ESTIMATE
                                )
                                connectedEpisodes.add(buildMovementEpisode(bridge, durationMultiplier).copy(
                                    durationStorySeconds = (2.0 + kotlin.math.ln(1.0+gapDist/1000.0)*.5).toFloat().coerceIn(2f,5f)))
                            }
                        } else {
                            overlapGeneratedBridgeAttempts++
                        }
                    }
                }
            }

            // Preserve the authoritative geometry; no renderer-specific endpoint snapping.
            val alignedEpisodes = connectedEpisodes

            // Enforce strictly monotonic story timestamps across consecutive episodes (P0-07)
            var runningEndMs = Long.MIN_VALUE
            var backwardEpisodeChronologyCount = 0
            val monotonicEpisodes = alignedEpisodes.map { ep ->
                if (runningEndMs != Long.MIN_VALUE && ep.startTimestampEpochMs < runningEndMs - 1000L) {
                    backwardEpisodeChronologyCount++
                }
                val effStart = if (runningEndMs == Long.MIN_VALUE) ep.startTimestampEpochMs else maxOf(ep.startTimestampEpochMs, runningEndMs)
                val effEnd = maxOf(effStart + 1000L, maxOf(ep.endTimestampEpochMs, effStart))
                runningEndMs = effEnd
                when (ep) {
                    is StoryEpisode.VisitEpisode -> ep.copy(startTimestampEpochMs = effStart, endTimestampEpochMs = effEnd)
                    is StoryEpisode.MovementEpisode -> ep.copy(startTimestampEpochMs = effStart, endTimestampEpochMs = effEnd)
                }
            }

            var unresolvedCrossTypeOverlapCount = 0
            for (s in resolvedSegments) {
                for (v in canonicalVisits) {
                    if (maxOf(s.startTimestampEpochMs, v.startTimestampEpochMs) < minOf(s.endTimestampEpochMs, v.endTimestampEpochMs)) {
                        unresolvedCrossTypeOverlapCount++
                    }
                }
            }

            val storyDiagnostics = TimelineStoryDiagnostics(
                unresolvedCrossTypeOverlapCount = unresolvedCrossTypeOverlapCount,
                backwardEpisodeChronologyCount = backwardEpisodeChronologyCount,
                overlapGeneratedBridgeCount = overlapGeneratedBridgeAttempts
            )

            // Calculate episode start times with title card accounting (P1)
            val titleCardDuration = titleCard?.durationSeconds ?: 0.0f
            val endCardDuration = endCard?.durationSeconds ?: 0.0f
            val totalRecordedDist = canonicalSegments.sumOf { it.distanceMeters }
            val starts = FloatArray(monotonicEpisodes.size)
            val startDistances = DoubleArray(monotonicEpisodes.size)
            var acc = titleCardDuration
            var accDist = 0.0
            for (i in monotonicEpisodes.indices) {
                starts[i] = acc
                startDistances[i] = accDist
                acc += monotonicEpisodes[i].durationStorySeconds
                val ep = monotonicEpisodes[i]
                if (ep is StoryEpisode.MovementEpisode) {
                    val isSyntheticBridge = ep.segment.id.startsWith("bridge_")
                    if (!isSyntheticBridge) {
                        accDist += ep.segment.distanceMeters
                    }
                }
            }
            val totalDuration = if (monotonicEpisodes.isEmpty()) {
                titleCardDuration + endCardDuration
            } else {
                acc + endCardDuration
            }
            val totalTripDist = if (totalRecordedDist > 0.0) totalRecordedDist else accDist

            // Schedule explicit chronological photo windows for each episode (P0-01, P0-06 ~ P0-08)
            val scheduledPhotoMoments = mutableListOf<PhotoStoryMoment>()
            for (i in monotonicEpisodes.indices) {
                val ep = monotonicEpisodes[i]
                val epStart = starts[i]
                val epDur = ep.durationStorySeconds
                val parentId = when (ep) {
                    is StoryEpisode.VisitEpisode -> ep.visit.id
                    is StoryEpisode.MovementEpisode -> ep.segment.id
                }
                val parentType = when (ep) {
                    is StoryEpisode.VisitEpisode -> "VISIT"
                    is StoryEpisode.MovementEpisode -> "MOVEMENT"
                }

                val epMoments = photoMoments.filter { it.parentType == parentType && it.parentId == parentId }
                    .sortedBy { it.effectiveStoryTimestampEpochMs }

                if (epMoments.isNotEmpty()) {
                    val epRealDurationMs = maxOf(1L, ep.endTimestampEpochMs - ep.startTimestampEpochMs)
                    var prevEndStoryTime = epStart

                    for ((momentIndex,moment) in epMoments.withIndex()) {
                        val realFrac = ((moment.effectiveStoryTimestampEpochMs - ep.startTimestampEpochMs).toDouble() / epRealDurationMs.toDouble()).toFloat().coerceIn(0.05f, 0.95f)

                        val baseDisplayDuration = if (moment.photo.isRepresentative) 2.5f else 1.8f
                        val maxAllowedPerMoment = (epDur / epMoments.size) * 0.90f
                        val displayDuration = minOf(baseDisplayDuration, maxOf(1.0f, maxAllowedPerMoment))

                        val targetCenterTime = epStart + epDur * realFrac
                        // P2-04 Arrival moment: settle destination visual before photo appears
                        val visitSettlingOffset = if (ep is StoryEpisode.VisitEpisode) minOf(0.35f, epDur * 0.12f) else 0f
                        val earliestStart = maxOf(epStart + visitSettlingOffset, prevEndStoryTime + 0.05f)
                        val remaining = epMoments.size - momentIndex
                        val latestStart = epStart + epDur - remaining*(displayDuration+.05f)
                        val rawDisplayStart = maxOf(earliestStart, minOf(latestStart,targetCenterTime - displayDuration / 2.0f))
                        val rawDisplayEnd = minOf(epStart + epDur, rawDisplayStart + displayDuration)

                        // P0-01 HARD INVARIANT: photo window must lie strictly within parent episode
                        val clampedStart = maxOf(epStart, rawDisplayStart)
                        val clampedEnd = minOf(epStart + epDur, rawDisplayEnd)
                        if (clampedEnd <= clampedStart + 0.01f) continue  // Invalid window → skip photo

                        val scheduledCenter = (clampedStart + clampedEnd) / 2.0f
                        prevEndStoryTime = clampedEnd

                        // P0 BUILD-TIME VALIDATION: parentStart <= photoStart < photoEnd <= parentEnd
                        val epEnd = epStart + epDur
                        if (clampedStart < epStart || clampedEnd > epEnd || clampedStart >= clampedEnd) continue

                        // P0-04: At (photo.displayStart + photo.displayEnd) / 2, evaluateAtStoryTime must match parent
                        var testEpIdx = starts.binarySearch(scheduledCenter)
                        if (testEpIdx < 0) testEpIdx = -(testEpIdx + 1) - 1
                        testEpIdx = testEpIdx.coerceIn(0, monotonicEpisodes.size - 1)
                        if (testEpIdx != i || monotonicEpisodes[testEpIdx].stableId != ep.stableId) {
                            continue // DROP the photo from playback
                        }

                        scheduledPhotoMoments.add(
                            moment.copy(
                                scheduledStoryTimeSeconds = scheduledCenter,
                                displayStartStorySeconds = clampedStart,
                                displayEndStorySeconds = clampedEnd,
                                durationStorySeconds = clampedEnd - clampedStart,
                                parentEpisodeIndex = i,
                                parentEpisodeStableId = ep.stableId,
                                parentEpisodeStoryStart = epStart,
                                parentEpisodeStoryEnd = epEnd
                            )
                        )
                    }
                }
            }

            return TravelStoryTimeline(
                profile = profile,
                episodes = monotonicEpisodes,
                photoMoments = scheduledPhotoMoments,
                titleCard = titleCard,
                endCard = endCard,
                totalStoryDurationSeconds = totalDuration,
                episodeStartTimes = starts,
                diagnostics = storyDiagnostics,
                totalTripDistanceMeters = totalTripDist,
                episodeStartDistances = startDistances
            )
        }

        private fun buildMovementEpisode(
            item: MovementSegment,
            durationMultiplier: Float
        ): StoryEpisode.MovementEpisode {
            // All consumers share exactly the same horizontal geometry. Never flatten
            // measured mountain profiles into a start/end altitude ramp.
            val pathWithAlt = com.traveler.core.terrain.SharedRouteGeometry.rejectAltitudeImpulses(
                com.traveler.core.terrain.SharedRouteGeometry.path(item)
            )

            val cumDist = ArrayList<Double>(pathWithAlt.size)
            var runningDist = 0.0
            cumDist.add(0.0)
            for (i in 1 until pathWithAlt.size) {
                runningDist += GeodesicUtils.distanceMeters(pathWithAlt[i - 1], pathWithAlt[i])
                cumDist.add(runningDist)
            }

            val headingTrack = VehicleHeadingCalculator.buildPrecomputedHeadingTrack(
                path = pathWithAlt,
                cumulativeDistances = cumDist,
                totalDistanceMeters = runningDist,
                mode = item.effectiveMode
            )

            val baseDuration = when (item.effectiveMode) {
                TransportMode.AIRPLANE -> 5.5f
                TransportMode.TRAIN, TransportMode.SUBWAY -> 4.0f
                TransportMode.CAR, TransportMode.BUS, TransportMode.FERRY -> 3.5f
                TransportMode.BICYCLE -> 2.8f
                else -> 2.2f
            }
            val routeDuration = if(item.geometryProvenance==GeometryProvenance.CONTINUITY_ESTIMATE) baseDuration else
                maxOf(baseDuration,(baseDuration + kotlin.math.ln(1.0+runningDist/5000.0)*1.8).toFloat().coerceAtMost(18f))
            val duration = maxOf(1.2f, routeDuration * durationMultiplier)

            return StoryEpisode.MovementEpisode(
                segment = item,
                pathPoints = pathWithAlt,
                cumulativeDistances = cumDist,
                totalDistanceMeters = runningDist,
                headingTrack = headingTrack,
                durationStorySeconds = duration
            )
        }

        /**
         * Slices a geometric path into pre-split and post-split portions around [splitTarget].
         * Preserves all intermediate curvature and points along the recorded route.
         */
        internal fun slicePathAtPoint(
            path: List<GeoPoint>,
            splitTarget: GeoPoint,
            splitFraction: Double
        ): Pair<List<GeoPoint>, List<GeoPoint>> {
            if (path.isEmpty()) {
                return listOf(splitTarget) to listOf(splitTarget)
            }
            if (path.size <= 2) {
                val pStart = path.first()
                val pEnd = path.last()
                return listOf(pStart, splitTarget) to listOf(splitTarget, pEnd)
            }

            // Calculate cumulative geodesic distances
            val cumulative = DoubleArray(path.size)
            var totalDist = 0.0
            for (i in 1 until path.size) {
                totalDist += GeodesicUtils.distanceMeters(path[i - 1], path[i])
                cumulative[i] = totalDist
            }

            // Check if path passes in close physical proximity to splitTarget (e.g. within 3 km)
            var closestIdx = -1
            var minProxDist = Double.MAX_VALUE
            for (i in path.indices) {
                val d = GeodesicUtils.distanceMeters(path[i], splitTarget)
                if (d < minProxDist) {
                    minProxDist = d
                    closestIdx = i
                }
            }

            val splitIndex = if (minProxDist <= 3000.0 && closestIdx in 0 until path.size) {
                closestIdx
            } else {
                val targetDist = splitFraction.coerceIn(0.0, 1.0) * totalDist
                var bestIdx = 0
                var bestDelta = Double.MAX_VALUE
                for (i in path.indices) {
                    val delta = abs(cumulative[i] - targetDist)
                    if (delta < bestDelta) {
                        bestDelta = delta
                        bestIdx = i
                    }
                }
                bestIdx
            }.coerceIn(0, path.size - 1)

            val firstPart = mutableListOf<GeoPoint>()
            for (i in 0..splitIndex) {
                firstPart.add(path[i])
            }
            if (GeodesicUtils.distanceMeters(firstPart.last(), splitTarget) > 1.0) {
                firstPart.add(splitTarget)
            }

            val secondPart = mutableListOf<GeoPoint>()
            secondPart.add(splitTarget)
            for (i in splitIndex until path.size) {
                if (secondPart.isEmpty() || GeodesicUtils.distanceMeters(secondPart.last(), path[i]) > 1.0) {
                    secondPart.add(path[i])
                }
            }
            if (secondPart.size < 2 && path.isNotEmpty()) {
                secondPart.add(path.last())
            }

            return firstPart to secondPart
        }

        /**
         * Resolves cross-type temporal overlaps between movement segments and intermediate visits.
         * If a Movement encompasses or overlaps with an intermediate Visit, it splits the Movement
         * into pre-visit and post-visit episodes around the visit boundary, preserving route geometry
         * and transport semantics.
         */
        internal fun resolveCrossTypeTemporalOverlaps(
            segments: List<MovementSegment>,
            visits: List<Visit>
        ): List<MovementSegment> {
            if (segments.isEmpty() || visits.isEmpty()) return segments

            val sortedVisits = visits.sortedBy { it.startTimestampEpochMs }
            val resolved = mutableListOf<MovementSegment>()

            for (s in segments) {
                val overlappingVisits = sortedVisits.filter { v ->
                    maxOf(s.startTimestampEpochMs, v.startTimestampEpochMs) < minOf(s.endTimestampEpochMs, v.endTimestampEpochMs)
                }

                if (overlappingVisits.isEmpty()) {
                    resolved.add(s)
                    continue
                }

                var currentStartTs = s.startTimestampEpochMs
                var currentStartPoint = s.startPoint
                var currentRemainingPath = if (s.simplifiedPoints.isNotEmpty()) s.simplifiedPoints else listOf(s.startPoint, s.endPoint)
                val rawPoints = s.rawPoints
                var partIdx = 1

                for (v in overlappingVisits) {
                    // 1. Pre-visit movement: from currentStartTs to v.startTimestampEpochMs
                    if (v.startTimestampEpochMs > currentStartTs) {
                        val subEndTs = v.startTimestampEpochMs
                        val subEndPoint = v.location

                        val totalRemTime = maxOf(1L, s.endTimestampEpochMs - currentStartTs)
                        val frac = ((subEndTs - currentStartTs).toDouble() / totalRemTime.toDouble()).coerceIn(0.0, 1.0)

                        val (subPath, leftoverPath) = slicePathAtPoint(
                            path = currentRemainingPath,
                            splitTarget = subEndPoint,
                            splitFraction = frac
                        )

                        val subDistance = GeodesicUtils.pathDistanceMeters(subPath)
                        val subDuration = maxOf(1000L, subEndTs - currentStartTs)

                        val subSegment = s.copy(
                            id = "${s.id}_part_${partIdx++}",
                            startTimestampEpochMs = currentStartTs,
                            endTimestampEpochMs = subEndTs,
                            startPoint = subPath.first(),
                            endPoint = subPath.last(),
                            simplifiedPoints = subPath,
                            rawPoints = if (rawPoints.isNotEmpty()) {
                                rawPoints.filter { it.timestampEpochMs in currentStartTs..subEndTs }
                            } else emptyList(),
                            distanceMeters = subDistance,
                            durationMillis = subDuration
                        )
                        resolved.add(subSegment)
                        currentRemainingPath = leftoverPath
                    }

                    // 2. Advance currentStartTs past the visit
                    currentStartTs = maxOf(currentStartTs, v.endTimestampEpochMs)
                    currentStartPoint = v.location
                    if (currentRemainingPath.isEmpty() || GeodesicUtils.distanceMeters(currentRemainingPath.first(), v.location) > 1.0) {
                        currentRemainingPath = listOf(v.location) + currentRemainingPath
                    }
                }

                // 3. Post-visit remainder: from currentStartTs to s.endTimestampEpochMs
                if (s.endTimestampEpochMs > currentStartTs) {
                    val subPath = if (currentRemainingPath.size >= 2) {
                        currentRemainingPath
                    } else {
                        listOf(currentStartPoint, s.endPoint)
                    }
                    val subDistance = GeodesicUtils.pathDistanceMeters(subPath)
                    val subDuration = maxOf(1000L, s.endTimestampEpochMs - currentStartTs)

                    val subSegment = s.copy(
                        id = "${s.id}_part_$partIdx",
                        startTimestampEpochMs = currentStartTs,
                        endTimestampEpochMs = s.endTimestampEpochMs,
                        startPoint = subPath.first(),
                        endPoint = subPath.last(),
                        simplifiedPoints = subPath,
                        rawPoints = if (rawPoints.isNotEmpty()) {
                            rawPoints.filter { it.timestampEpochMs in currentStartTs..s.endTimestampEpochMs }
                        } else emptyList(),
                        distanceMeters = subDistance,
                        durationMillis = subDuration
                    )
                    resolved.add(subSegment)
                }
            }

            return resolved.sortedBy { it.startTimestampEpochMs }
        }
    }
}
