package com.traveler.core.media

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.MediaItem

/**
 * Eligibility status of a media item for cinematic playback inclusion (P0-03 ~ P0-06).
 */
enum class PhotoStoryEligibility {
    /** Capture timestamp is reliably inside the matched parent interval */
    ELIGIBLE,

    /** Capture timestamp is slightly outside parent interval (within tolerance) and clamped */
    CLAMPED_TO_PARENT,

    /** Capture timestamp substantially contradicts the matched parent (omitted from playback) */
    INELIGIBLE_TIMESTAMP_CONFLICT,

    /** Media is a screenshot, screen recording, download, or icon */
    INELIGIBLE_SCREENSHOT_OR_DOWNLOAD,

    /** Capture timestamp is uncertain, missing, or unresolvable */
    INELIGIBLE_TIME_UNCERTAIN
}

/**
 * A group of photo candidates taken around the same time and place during a visit or movement episode (P0-10 ~ P0-12).
 */
data class PhotoMomentCluster(
    val clusterId: String,
    val parentType: String, // "VISIT" or "MOVEMENT"
    val parentId: String,
    val startTimestampEpochMs: Long,
    val endTimestampEpochMs: Long,
    val photoCandidates: List<MediaItem>,
    val representativePhoto: MediaItem,
    val spatialAnchor: GeoPoint?
)

/**
 * Precomputed, immutable playback photo moment built BEFORE interactive playback or video export (P0-03).
 *
 * Invariants:
 * 1. The journey timeline controls photos; photos never control the journey timeline.
 * 2. An active photo moment is a pure UI overlay and does NOT mutate currentStoryTimestamp,
 *    StoryNode index, route progress, or route marker position.
 */
@kotlinx.serialization.Serializable
data class PhotoStoryMoment(
    val mediaId: String,
    val photo: MediaItem,
    val captureTimestampEpochMs: Long,
    val effectiveStoryTimestampEpochMs: Long,
    val parentType: String, // "VISIT" or "MOVEMENT"
    val parentId: String,
    val playbackAnchorLocation: GeoPoint,
    val photoCoordinate: GeoPoint?,
    val clusterId: String,
    val representativeRank: Int,
    val eligibility: PhotoStoryEligibility,
    val durationStorySeconds: Float = 2.0f,
    val scheduledStoryTimeSeconds: Float = 0.0f,
    val displayStartStorySeconds: Float = 0.0f,
    val displayEndStorySeconds: Float = 0.0f,
    /** Index of the parent episode in [TravelStoryTimeline.episodes] (-1 if unscheduled) */
    val parentEpisodeIndex: Int = -1,
    /** Stable identifier of the parent episode (e.g. "ep_visit_v1") */
    val parentEpisodeStableId: String = "",
    /** Story-domain start of the parent episode (seconds) */
    val parentEpisodeStoryStart: Float = 0.0f,
    /** Story-domain end of the parent episode (seconds) */
    val parentEpisodeStoryEnd: Float = 0.0f
)

/**
 * Cinematic story length profiles for playback and video export (P1).
 */
enum class StoryDurationProfile {
    /** Content-compressed story (removes lower-priority moments, compresses cruising movements) */
    SHORT,

    /** Normal 1x readable baseline story (recommended default) */
    STANDARD,

    /** Extended story with more representative moments */
    FULL_STORY
}

/**
 * Exclusion reason explaining why an anchor has 0 selected representative photos (P1-08).
 */
enum class AnchorExclusionReason {
    NONE_SELECTED_BUDGET_PRUNED,
    NO_ELIGIBLE_MEDIA,
    TIMESTAMP_CONFLICT,
    ONLY_SCREENSHOTS_DOWNLOADS,
    NO_VALID_CLUSTER,
    TIME_UNCERTAIN
}

/**
 * Structural anchor for story coverage allocation across trips (P1-02).
 */
data class StoryAnchor(
    val anchorId: String,
    val parentType: String, // "VISIT" or "MOVEMENT"
    val parentId: String,
    val startTimestampEpochMs: Long,
    val endTimestampEpochMs: Long,
    val spatialAnchor: GeoPoint,
    val label: String?,
    val importanceScore: Int,
    val clusters: List<PhotoMomentCluster>,
    val rawPhotoCount: Int,
    val eligiblePhotoCount: Int,
    val isHighImportance: Boolean = false,
    val exclusionReason: AnchorExclusionReason? = null
)

/**
 * Concise photo candidate exclusion reasons per P1-07.
 */
enum class PhotoExclusionReason {
    WRONG_PARENT,
    TIMESTAMP_CONFLICT,
    TIME_UNCERTAIN,
    CLUSTER_NOT_SELECTED,
    LOW_SCORE_IN_CLUSTER,
    DAY_BUDGET,
    TRIP_BUDGET,
    NO_PARENT_EPISODE,
    NO_WINDOW_SPACE
}
