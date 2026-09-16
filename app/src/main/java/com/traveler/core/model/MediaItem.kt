package com.traveler.core.model

import com.traveler.core.common.geo.GeoPoint
import kotlinx.serialization.Serializable

@Serializable
enum class TimestampConfidence {
    EXIF_EXACT,             // EXIF DateTimeOriginal + timezone offset
    EXIF_LOCAL,             // EXIF DateTimeOriginal without offset (resolved with contextual timezone)
    MEDIASTORE,             // MediaStore DATE_TAKEN
    FILENAME_INFERRED,      // Parsed from filename (e.g. IMG_20260704_153412.jpg)
    FILETIME_INFERRED,      // File modification time fallback
    UNKNOWN                 // Unknown capture time (timestampEpochMs is null)
}

@Serializable
enum class LocationConfidenceLevel {
    GPS_EXACT,              // Direct EXIF GPS coordinates from photo
    TIMELINE_INTERPOLATED,  // Estimated from trajectory interpolation during movement
    VISIT_INFERRED,         // Assigned to visit place during stay
    TIME_ONLY,              // No spatial anchor found, chronological only
    UNKNOWN
}

@Serializable
enum class DayAssignmentConfidence {
    EXACT,                  // Known explicit timezone (EXIF offset or GPS)
    CONTEXTUAL,             // Inferred from matched Visit / Movement context
    CONSENSUS,              // All candidate trip zones map to identical LocalDate
    AMBIGUOUS,              // Candidate trip zones produce conflicting calendar dates
    UNKNOWN                 // Unknown timestamp or no context
}

@Serializable
data class MediaItem(
    val id: String,                         // Unique composite key, e.g. "IMG_123", "VID_456"
    val contentUriString: String,
    val fileName: String,
    val mimeType: String,
    val timestampEpochMs: Long?,            // Nullable: unknown time is null, never fabricated
    val timestampConfidence: TimestampConfidence = TimestampConfidence.UNKNOWN,
    val captureTimezoneId: String? = null,  // Resolved timezone provenance (e.g. "Asia/Seoul"), truthful & nullable
    val location: GeoPoint? = null,
    val locationConfidence: LocationConfidenceLevel = LocationConfidenceLevel.UNKNOWN,
    val confidenceScore: Float = 0.0f,      // 0.0 to 1.0
    val matchedVisitId: String? = null,
    val matchedSegmentId: String? = null,
    val isRepresentative: Boolean = false,
    val isUserLocationOverride: Boolean = false,
    val assignedDayIso: String? = null,     // Distinct persisted diary day (e.g. "2026-07-02")
    val dayAssignmentConfidence: DayAssignmentConfidence = DayAssignmentConfidence.UNKNOWN,
    val dayAssignmentProvenance: String? = null,
    val visualFeatures: com.traveler.core.media.PhotoVisualFeatures? = null,
    val captureEvidence: MediaCaptureEvidence = MediaCaptureEvidence.UNKNOWN
) {
    val isVideo: Boolean
        get() = mimeType.startsWith("video/")
}
