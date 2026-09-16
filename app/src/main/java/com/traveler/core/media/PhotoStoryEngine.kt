package com.traveler.core.media

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.common.geo.GeodesicUtils
import com.traveler.core.model.*
import kotlin.math.abs

/**
 * Pure domain engine that clusters photo candidates, selects deterministic representative memories,
 * and constructs a globally sorted, non-rewinding sequence of [PhotoStoryMoment]s (P0-01 ~ P0-18).
 *
 * Invariants:
 * 1. Journey Timeline is Authoritative: Photos never alter current story time, route marker position,
 *    heading, or active episode.
 * 2. Stale Photos are Skipped: Photos with timestamps earlier than the current playback time are
 *    silently consumed/skipped and NEVER rewind the story timeline.
 * 3. Bounded Story Budget: Large photo libraries (1,000+ images) are clustered and reduced to 20-40
 *    representative cinematic moments in Standard playback (all original photos remain in the Diary).
 */
object PhotoStoryEngine {

    private const val BURST_TIME_WINDOW_MS = 60_000L      // 60s for burst collapse
    private const val SESSION_TIME_WINDOW_MS = 180_000L   // 3 min for same photographic session
    private const val CLUSTER_DISTANCE_METERS = 300.0     // 300m spatial proximity
    private const val PARENT_CLAMP_TOLERANCE_MS = 300_000L // 5 min tolerance for clamping near boundaries

    /**
     * Evaluates photo eligibility and determines effective story timestamp relative to parent interval.
     */
    fun evaluateEligibility(
        photo: MediaItem,
        parentStartEpochMs: Long,
        parentEndEpochMs: Long
    ): Pair<PhotoStoryEligibility, Long> {
        val nameLower = photo.fileName.lowercase()
        if (!photo.isRepresentative && (nameLower.contains("screenshot") || nameLower.contains("screen_shot") ||
            nameLower.contains("screencap") || nameLower.contains("download") ||
            nameLower.contains("received_") || nameLower.contains("thumb") ||
            nameLower.contains("icon"))
        ) {
            return PhotoStoryEligibility.INELIGIBLE_SCREENSHOT_OR_DOWNLOAD to (photo.timestampEpochMs ?: parentStartEpochMs)
        }

        val ts = photo.timestampEpochMs
        if (ts == null || photo.timestampConfidence == TimestampConfidence.UNKNOWN) {
            return PhotoStoryEligibility.INELIGIBLE_TIME_UNCERTAIN to parentStartEpochMs
        }

        // Check against parent boundary
        val parentStart = parentStartEpochMs
        val parentEnd = maxOf(parentStart, parentEndEpochMs)

        return when {
            ts in parentStart..parentEnd -> {
                PhotoStoryEligibility.ELIGIBLE to ts
            }
            ts in (parentStart - PARENT_CLAMP_TOLERANCE_MS)..parentStart -> {
                PhotoStoryEligibility.CLAMPED_TO_PARENT to parentStart
            }
            ts in parentEnd..(parentEnd + PARENT_CLAMP_TOLERANCE_MS) -> {
                PhotoStoryEligibility.CLAMPED_TO_PARENT to parentEnd
            }
            else -> {
                // Substantially outside parent interval (> 5 min delta) -> Ineligible for cinematic playback
                PhotoStoryEligibility.INELIGIBLE_TIMESTAMP_CONFLICT to ts
            }
        }
    }

    /**
     * Scores a photo deterministically for representative ranking within a cluster.
     */
    fun scorePhotoForRepresentative(photo: MediaItem): Int = RepresentativeMediaSelector.scorePhoto(photo)

    /**
     * Clusters eligible media items for a specific visit or movement parent.
     */
    fun clusterParentMedia(
        parentType: String,
        parentId: String,
        parentStartEpochMs: Long,
        parentEndEpochMs: Long,
        photos: List<MediaItem>,
        spatialAnchor: GeoPoint
    ): List<PhotoMomentCluster> {
        val eligiblePairs = photos.mapNotNull { p ->
            val (eligibility, effectiveTs) = evaluateEligibility(p, parentStartEpochMs, parentEndEpochMs)
            if (eligibility == PhotoStoryEligibility.ELIGIBLE || eligibility == PhotoStoryEligibility.CLAMPED_TO_PARENT) {
                Triple(p, eligibility, effectiveTs)
            } else {
                null
            }
        }.sortedBy { it.third }

        if (eligiblePairs.isEmpty()) return emptyList()

        val rawClusters = mutableListOf<MutableList<Triple<MediaItem, PhotoStoryEligibility, Long>>>()
        for (item in eligiblePairs) {
            var matchedCluster: MutableList<Triple<MediaItem, PhotoStoryEligibility, Long>>? = null
            for (cl in rawClusters) {
                val anchor = cl.first()
                val timeDiff = abs(item.third - anchor.third)
                val dist = if (item.first.location != null && anchor.first.location != null) {
                    GeodesicUtils.distanceMeters(item.first.location!!, anchor.first.location!!)
                } else 0.0

                // Burst collapse (< 60s) or session clustering (< 3 min and <= 300m)
                if (!item.first.isRepresentative && !anchor.first.isRepresentative &&
                    timeDiff <= SESSION_TIME_WINDOW_MS && dist <= CLUSTER_DISTANCE_METERS &&
                    RepresentativeMediaSelector.visuallyDuplicate(item.first, anchor.first)) {
                    matchedCluster = cl
                    break
                }
            }

            if (matchedCluster != null) {
                matchedCluster.add(item)
            } else {
                rawClusters.add(mutableListOf(item))
            }
        }

        return rawClusters.mapIndexed { idx, cl ->
            val candidates = cl.map { it.first }
            val best = candidates.maxWithOrNull(
                compareBy<MediaItem> { scorePhotoForRepresentative(it) }
                    .thenBy { it.timestampEpochMs ?: 0L }
                    .thenBy { it.id }
            ) ?: candidates.first()

            PhotoMomentCluster(
                clusterId = "${parentType}_${parentId}_cl_$idx",
                parentType = parentType,
                parentId = parentId,
                startTimestampEpochMs = cl.minOf { it.third },
                endTimestampEpochMs = cl.maxOf { it.third },
                photoCandidates = candidates,
                representativePhoto = best,
                spatialAnchor = spatialAnchor
            )
        }
    }

    /**
     * Builds [StoryAnchor]s for all visits and movements with destination importance scoring (P1-02, P1-03).
     */
    fun buildStoryAnchors(
        visits: List<Visit>,
        segments: List<MovementSegment>,
        allPhotos: List<MediaItem>
    ): List<StoryAnchor> {
        val photosByVisit = allPhotos.filter { it.matchedVisitId != null }.groupBy { it.matchedVisitId!! }
        val photosBySegment = allPhotos.filter { it.matchedSegmentId != null }.groupBy { it.matchedSegmentId!! }

        val anchors = mutableListOf<StoryAnchor>()
        val sortedSegments = segments.sortedBy { it.endTimestampEpochMs }

        for (v in visits) {
            val vPhotos = photosByVisit[v.id] ?: emptyList()
            val clusters = if (vPhotos.isNotEmpty()) {
                clusterParentMedia(
                    parentType = "VISIT",
                    parentId = v.id,
                    parentStartEpochMs = v.startTimestampEpochMs,
                    parentEndEpochMs = v.endTimestampEpochMs,
                    photos = vPhotos,
                    spatialAnchor = v.location
                )
            } else emptyList()

            val eligibleCount = vPhotos.count { p ->
                val (el, _) = evaluateEligibility(p, v.startTimestampEpochMs, v.endTimestampEpochMs)
                el == PhotoStoryEligibility.ELIGIBLE || el == PhotoStoryEligibility.CLAMPED_TO_PARENT
            }

            val durMs = maxOf(0L, v.endTimestampEpochMs - v.startTimestampEpochMs)
            var score = 0

            // Preceding movement distance (P1-05: arrival after long-distance movement)
            val precedingSeg = sortedSegments.filter { it.endTimestampEpochMs <= v.startTimestampEpochMs + 60_000L }.maxByOrNull { it.endTimestampEpochMs }
            val precedingDist = precedingSeg?.distanceMeters ?: 0.0

            // Dwell duration signal (up to 2000 points)
            score += minOf(2000, (durMs / 60000).toInt() * 10)

            // Distinct photo clusters signal (strongest destination evidence - P1-03)
            score += clusters.size * 700
            score += minOf(1000, eligibleCount * 40)

            // Arrival after long-distance Movement signal (P1-05)
            if (precedingDist >= 50_000.0) score += 1500
            if (precedingDist >= 100_000.0) score += 800

            // Locality & place metadata signals
            if (v.isUserOverride) score += 3000
            if (!v.placeName.isNullOrBlank()) score += 500
            if (vPhotos.any { it.isRepresentative }) score += 5000
            if (durMs >= 6 * 3600_000L) score += 1500 // Overnight or major base

            // P1-06 Transient stop penalty: short stop without user override or long preceding travel
            val isTransientStop = durMs < 40 * 60_000L && !v.isUserOverride &&
                    clusters.size <= 1 && !vPhotos.any { it.isRepresentative } &&
                    durMs < 6 * 3600_000L && precedingDist < 50_000.0
            if (isTransientStop) {
                score -= 1800
            }

            val isHighImportance = score >= 2000 || vPhotos.size >= 5 || v.isUserOverride ||
                    durMs >= 3 * 3600_000L || (precedingDist >= 80_000.0 && clusters.isNotEmpty())

            var exclusionReason: AnchorExclusionReason? = null
            if (vPhotos.isNotEmpty() && clusters.isEmpty()) {
                val allScreenshots = vPhotos.all {
                    val (el, _) = evaluateEligibility(it, v.startTimestampEpochMs, v.endTimestampEpochMs)
                    el == PhotoStoryEligibility.INELIGIBLE_SCREENSHOT_OR_DOWNLOAD
                }
                val allConflicts = vPhotos.all {
                    val (el, _) = evaluateEligibility(it, v.startTimestampEpochMs, v.endTimestampEpochMs)
                    el == PhotoStoryEligibility.INELIGIBLE_TIMESTAMP_CONFLICT
                }
                val allUncertain = vPhotos.all {
                    val (el, _) = evaluateEligibility(it, v.startTimestampEpochMs, v.endTimestampEpochMs)
                    el == PhotoStoryEligibility.INELIGIBLE_TIME_UNCERTAIN
                }
                exclusionReason = when {
                    allScreenshots -> AnchorExclusionReason.ONLY_SCREENSHOTS_DOWNLOADS
                    allConflicts -> AnchorExclusionReason.TIMESTAMP_CONFLICT
                    allUncertain -> AnchorExclusionReason.TIME_UNCERTAIN
                    else -> AnchorExclusionReason.NO_VALID_CLUSTER
                }
            }

            anchors.add(
                StoryAnchor(
                    anchorId = "visit_${v.id}",
                    parentType = "VISIT",
                    parentId = v.id,
                    startTimestampEpochMs = v.startTimestampEpochMs,
                    endTimestampEpochMs = v.endTimestampEpochMs,
                    spatialAnchor = v.location,
                    label = v.placeName,
                    importanceScore = score,
                    clusters = clusters,
                    rawPhotoCount = vPhotos.size,
                    eligiblePhotoCount = eligibleCount,
                    isHighImportance = isHighImportance,
                    exclusionReason = exclusionReason
                )
            )
        }

        for (s in segments) {
            val sPhotos = photosBySegment[s.id] ?: emptyList()
            val clusters = if (sPhotos.isNotEmpty()) {
                clusterParentMedia(
                    parentType = "MOVEMENT",
                    parentId = s.id,
                    parentStartEpochMs = s.startTimestampEpochMs,
                    parentEndEpochMs = s.endTimestampEpochMs,
                    photos = sPhotos,
                    spatialAnchor = s.startPoint
                )
            } else emptyList()

            val eligibleCount = sPhotos.count { p ->
                val (el, _) = evaluateEligibility(p, s.startTimestampEpochMs, s.endTimestampEpochMs)
                el == PhotoStoryEligibility.ELIGIBLE || el == PhotoStoryEligibility.CLAMPED_TO_PARENT
            }

            var score = 0
            if (s.effectiveMode == TransportMode.AIRPLANE || s.effectiveMode == TransportMode.TRAIN || s.effectiveMode == TransportMode.FERRY) {
                score += 1000
            }
            score += clusters.size * 400
            score += minOf(800, eligibleCount * 30)
            if (sPhotos.any { it.isRepresentative }) score += 4000

            anchors.add(
                StoryAnchor(
                    anchorId = "movement_${s.id}",
                    parentType = "MOVEMENT",
                    parentId = s.id,
                    startTimestampEpochMs = s.startTimestampEpochMs,
                    endTimestampEpochMs = s.endTimestampEpochMs,
                    spatialAnchor = s.startPoint,
                    label = s.effectiveMode.name,
                    importanceScore = score,
                    clusters = clusters,
                    rawPhotoCount = sPhotos.size,
                    eligiblePhotoCount = eligibleCount,
                    isHighImportance = score >= 2000
                )
            )
        }

        return anchors
    }

    /**
     * Builds the complete, globally sorted, non-rewinding list of [PhotoStoryMoment]s using
     * Day-level weighted round-robin destination-aware coverage allocation (P1-01 ~ P1-15).
     *
     * 4-pass allocation:
     * - Pass A: One representative per major destination across ALL days
     * - Pass B: Other Day anchors (fill remaining 1-per-anchor gaps)
     * - Pass C: 2nd/3rd reps for important destinations (multi-cluster)
     * - Pass D: Scenic movements / bonus
     */
    fun buildGlobalPhotoStoryMoments(
        visits: List<Visit>,
        segments: List<MovementSegment>,
        allPhotos: List<MediaItem>,
        profile: StoryDurationProfile = StoryDurationProfile.STANDARD
    ): List<PhotoStoryMoment> {
        val anchors = buildStoryAnchors(visits, segments, allPhotos)
        val allClusters = anchors.flatMap { it.clusters }

        if (allClusters.isEmpty()) return emptyList()

        // Step 1: Pre-create valid candidate moments per cluster
        val candidateMomentsByCluster = mutableMapOf<String, PhotoStoryMoment>()
        for (cl in allClusters) {
            val rep = cl.representativePhoto
            val (eligibility, effectiveTs) = evaluateEligibility(rep, cl.startTimestampEpochMs, cl.endTimestampEpochMs)
            if (eligibility == PhotoStoryEligibility.ELIGIBLE || eligibility == PhotoStoryEligibility.CLAMPED_TO_PARENT) {
                val moment = PhotoStoryMoment(
                    mediaId = rep.id,
                    photo = rep,
                    captureTimestampEpochMs = rep.timestampEpochMs ?: cl.startTimestampEpochMs,
                    effectiveStoryTimestampEpochMs = effectiveTs,
                    parentType = cl.parentType,
                    parentId = cl.parentId,
                    playbackAnchorLocation = cl.spatialAnchor ?: GeoPoint(0.0, 0.0),
                    photoCoordinate = rep.location,
                    clusterId = cl.clusterId,
                    representativeRank = 1,
                    eligibility = eligibility,
                    durationStorySeconds = if (rep.isRepresentative) 3.0f else 2.0f
                )
                candidateMomentsByCluster[cl.clusterId] = moment
            }
        }

        if (candidateMomentsByCluster.isEmpty()) return emptyList()

        val maxBudget = when (profile) {
            StoryDurationProfile.SHORT -> 15
            StoryDurationProfile.STANDARD -> 35
            StoryDurationProfile.FULL_STORY -> 60
        }

        val selectedMoments = mutableListOf<PhotoStoryMoment>()
        val selectedClusterIds = HashSet<String>()
        // User choices outrank automatic budgets and never collapse into a neighbouring burst.
        candidateMomentsByCluster.values.filter { it.photo.isRepresentative }.forEach {
            selectedMoments.add(it);selectedClusterIds.add(it.clusterId)
        }

        // Group visit anchors by calendar day for Day-level fairness (P1-01)
        val visitAnchors = anchors.filter { it.parentType == "VISIT" && it.clusters.isNotEmpty() }
        val dayGroups = visitAnchors.groupBy { anchor ->
            // Group by calendar date (UTC day boundary)
            anchor.startTimestampEpochMs / (24 * 3600_000L)
        }.toSortedMap()

        // ---------------------------------------------------------------
        // PASS A: One representative per MAJOR destination across ALL days
        // Round-robin through days so later days get fair coverage (P1-04)
        // ---------------------------------------------------------------
        val dayQueues = dayGroups.mapValues { (_, dayAnchors) ->
            dayAnchors.filter { it.isHighImportance }
                .sortedByDescending { it.importanceScore }
                .toMutableList()
        }.toMutableMap()

        var passAComplete = false
        while (!passAComplete && selectedMoments.size < maxBudget) {
            passAComplete = true
            for ((_, queue) in dayQueues) {
                val anchor = queue.firstOrNull { a ->
                    a.clusters.any { it.clusterId in candidateMomentsByCluster && it.clusterId !in selectedClusterIds }
                }
                if (anchor != null) {
                    passAComplete = false
                    queue.remove(anchor)
                    val bestCluster = anchor.clusters
                        .filter { it.clusterId in candidateMomentsByCluster && it.clusterId !in selectedClusterIds }
                        .maxByOrNull { scorePhotoForRepresentative(it.representativePhoto) }
                    if (bestCluster != null) {
                        val moment = candidateMomentsByCluster[bestCluster.clusterId]
                        if (moment != null) {
                            selectedMoments.add(moment)
                            selectedClusterIds.add(bestCluster.clusterId)
                        }
                    }
                }
                if (selectedMoments.size >= maxBudget) break
            }
        }

        // ---------------------------------------------------------------
        // PASS B: Other Day anchors (fill remaining 1-per-anchor gaps) (P1-07)
        // ---------------------------------------------------------------
        if (selectedMoments.size < maxBudget) {
            val remainingAnchors = visitAnchors
                .filter { anchor ->
                    anchor.clusters.any { it.clusterId in candidateMomentsByCluster && it.clusterId !in selectedClusterIds } &&
                    selectedMoments.none { it.parentId == anchor.parentId }
                }
                .sortedByDescending { it.importanceScore }

            for (anchor in remainingAnchors) {
                val bestCluster = anchor.clusters
                    .filter { it.clusterId in candidateMomentsByCluster && it.clusterId !in selectedClusterIds }
                    .maxByOrNull { scorePhotoForRepresentative(it.representativePhoto) }
                if (bestCluster != null) {
                    val moment = candidateMomentsByCluster[bestCluster.clusterId]
                    if (moment != null) {
                        selectedMoments.add(moment)
                        selectedClusterIds.add(bestCluster.clusterId)
                    }
                }
                if (selectedMoments.size >= maxBudget) break
            }
        }

        // ---------------------------------------------------------------
        // PASS C: 2nd/3rd reps for important destinations (P1-04, P1-07)
        // ---------------------------------------------------------------
        if (selectedMoments.size < maxBudget && profile != StoryDurationProfile.SHORT) {
            val depthAnchors = visitAnchors
                .filter { it.isHighImportance && it.clusters.size >= 2 }
                .sortedByDescending { it.importanceScore }

            for (anchor in depthAnchors) {
                val maxMomentsForThisAnchor = when {
                    anchor.isHighImportance && anchor.clusters.size >= 3 -> 3
                    anchor.clusters.size >= 2 -> 2
                    else -> 1
                }

                val remainingClusters = anchor.clusters
                    .filter { it.clusterId in candidateMomentsByCluster && it.clusterId !in selectedClusterIds }
                    .sortedByDescending { cluster ->
                        val category=cluster.representativePhoto.visualFeatures?.category
                        val repeated=category!=null && category!="other" && selectedMoments.any {
                            it.parentId==anchor.parentId && it.photo.visualFeatures?.category==category
                        }
                        scorePhotoForRepresentative(cluster.representativePhoto) - if(repeated) 180 else 0
                    }

                for (cl in remainingClusters) {
                    val currentCountForAnchor = selectedMoments.count { it.parentId == anchor.parentId }
                    if (currentCountForAnchor < maxMomentsForThisAnchor && selectedMoments.size < maxBudget) {
                        val moment = candidateMomentsByCluster[cl.clusterId]
                        if (moment != null) {
                            selectedMoments.add(moment)
                            selectedClusterIds.add(cl.clusterId)
                        }
                    }
                }
                if (selectedMoments.size >= maxBudget) break
            }
        }

        // ---------------------------------------------------------------
        // PASS D: Scenic Movement Photos (P1-07)
        // ---------------------------------------------------------------
        if (selectedMoments.size < maxBudget) {
            val movementAnchors = anchors.filter { it.parentType == "MOVEMENT" && it.clusters.isNotEmpty() }
                .sortedByDescending { it.importanceScore }

            for (anchor in movementAnchors) {
                val cl = anchor.clusters.filter { it.clusterId in candidateMomentsByCluster && it.clusterId !in selectedClusterIds }
                    .maxByOrNull { scorePhotoForRepresentative(it.representativePhoto) }
                if (cl != null) {
                    val moment = candidateMomentsByCluster[cl.clusterId]
                    if (moment != null) {
                        selectedMoments.add(moment)
                        selectedClusterIds.add(cl.clusterId)
                    }
                }
                if (selectedMoments.size >= maxBudget) break
            }
        }

        // Step 5: Globally sort selected moments strictly by authentic effective timestamp (P0-05)
        return selectedMoments.sortedWith(
            compareBy<PhotoStoryMoment> { it.effectiveStoryTimestampEpochMs }
                .thenBy { it.captureTimestampEpochMs }
                .thenBy { it.mediaId }
        )
    }

    /**
     * Builds a per-Day coverage report for diagnostic output (P1-14).
     */
    fun buildCoverageReport(
        visits: List<Visit>,
        segments: List<MovementSegment>,
        allPhotos: List<MediaItem>,
        profile: StoryDurationProfile = StoryDurationProfile.STANDARD
    ): DayCoverageReport {
        val anchors = buildStoryAnchors(visits, segments, allPhotos)
        val moments = buildGlobalPhotoStoryMoments(visits, segments, allPhotos, profile)

        val dayGroups = anchors.filter { it.parentType == "VISIT" }
            .groupBy { it.startTimestampEpochMs / (24 * 3600_000L) }
            .toSortedMap()

        val dayEntries = dayGroups.map { (dayKey, dayAnchors) ->
            val dayMomentParentIds = moments.filter { m ->
                dayAnchors.any { a -> a.parentId == m.parentId }
            }.map { it.parentId }.toSet()

            DayCoverageEntry(
                dayIndex = dayKey,
                anchorCount = dayAnchors.size,
                anchorsWithPhotos = dayAnchors.count { it.clusters.isNotEmpty() },
                totalRawPhotos = dayAnchors.sumOf { it.rawPhotoCount },
                totalEligiblePhotos = dayAnchors.sumOf { it.eligiblePhotoCount },
                totalClusters = dayAnchors.sumOf { it.clusters.size },
                selectedRepresentatives = dayMomentParentIds.size,
                topAnchors = dayAnchors.sortedByDescending { it.importanceScore }.take(5).map { a ->
                    AnchorSummary(
                        anchorId = a.anchorId,
                        label = a.label,
                        importanceScore = a.importanceScore,
                        clusterCount = a.clusters.size,
                        isSelected = dayMomentParentIds.contains(a.parentId),
                        isHighImportance = a.isHighImportance
                    )
                }
            )
        }

        return DayCoverageReport(
            totalDays = dayGroups.size,
            totalMoments = moments.size,
            maxBudget = when (profile) {
                StoryDurationProfile.SHORT -> 15
                StoryDurationProfile.STANDARD -> 35
                StoryDurationProfile.FULL_STORY -> 60
            },
            days = dayEntries
        )
    }

    /**
     * Per-Day coverage report (P1-14).
     */
    data class DayCoverageReport(
        val totalDays: Int,
        val totalMoments: Int,
        val maxBudget: Int,
        val days: List<DayCoverageEntry>
    )

    data class DayCoverageEntry(
        val dayIndex: Long,
        val anchorCount: Int,
        val anchorsWithPhotos: Int,
        val totalRawPhotos: Int,
        val totalEligiblePhotos: Int,
        val totalClusters: Int,
        val selectedRepresentatives: Int,
        val topAnchors: List<AnchorSummary>
    )

    data class AnchorSummary(
        val anchorId: String,
        val label: String?,
        val importanceScore: Int,
        val clusterCount: Int,
        val isSelected: Boolean,
        val isHighImportance: Boolean
    )
}
