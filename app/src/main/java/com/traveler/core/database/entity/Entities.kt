package com.traveler.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "trips",
    indices = [
        Index(value = ["startDateIso"]),
        Index(value = ["endDateIso"])
    ]
)
data class TripEntity(
    @PrimaryKey val id: String,
    val title: String,
    val startDateIso: String,
    val endDateIso: String,
    val totalDistanceMeters: Double,
    val citiesJson: String,
    val countriesJson: String,
    val totalMediaCount: Int,
    val createdAtEpochMs: Long
)

@Entity(
    tableName = "visits",
    primaryKeys = ["tripId", "sourceId"],
    indices = [
        Index(value = ["tripId"]),
        Index(value = ["sourceId"]),
        Index(value = ["startTimestampEpochMs"]),
        Index(value = ["endTimestampEpochMs"])
    ]
)
data class VisitEntity(
    val tripId: String,
    val sourceId: String,
    val placeName: String?,
    val placeAddress: String?,
    val placeId: String?,
    val latitude: Double,
    val longitude: Double,
    val startTimestampEpochMs: Long,
    val endTimestampEpochMs: Long,
    val confidence: Float,
    val timezoneId: String? = null,
    val isUserOverride: Boolean,
    val altitudeMeters: Double? = null
)

@Entity(
    tableName = "movement_segments",
    primaryKeys = ["tripId", "sourceId"],
    indices = [
        Index(value = ["tripId"]),
        Index(value = ["sourceId"]),
        Index(value = ["startTimestampEpochMs"]),
        Index(value = ["endTimestampEpochMs"])
    ]
)
data class MovementSegmentEntity(
    val tripId: String,
    val sourceId: String,
    val startTimestampEpochMs: Long,
    val endTimestampEpochMs: Long,
    val startLat: Double,
    val startLng: Double,
    val endLat: Double,
    val endLng: Double,
    val distanceMeters: Double,
    val durationMillis: Long,
    val predictedTransportMode: String,
    val predictedConfidence: Float,
    val predictedReason: String,
    val startTimezoneId: String? = null,
    val endTimezoneId: String? = null,
    val userOverrideTransportMode: String? = null,
    val polylineJson: String,
    val isUserOverride: Boolean,
    val geometryProvenance: String = "UNKNOWN",
    val startAltitudeMeters: Double? = null,
    val endAltitudeMeters: Double? = null,
    @androidx.room.ColumnInfo(defaultValue = "''")
    val rawPointsJson: String = ""
)

@Entity(
    tableName = "trip_media",
    primaryKeys = ["tripId", "mediaKey"],
    indices = [
        Index(value = ["tripId"]),
        Index(value = ["mediaKey"]),
        Index(value = ["timestampEpochMs"]),
        Index(value = ["matchedVisitId"]),
        Index(value = ["matchedSegmentId"]),
        Index(value = ["assignedDayIso"])
    ]
)
data class TripMediaEntity(
    val tripId: String,
    val mediaKey: String,
    val contentUriString: String,
    val fileName: String,
    val mimeType: String,
    val timestampEpochMs: Long?,
    val timestampConfidence: String,
    val captureTimezoneId: String? = null,
    val latitude: Double?,
    val longitude: Double?,
    val locationConfidence: String,
    val confidenceScore: Float,
    val matchedVisitId: String?,
    val matchedSegmentId: String?,
    val isRepresentative: Boolean,
    val isUserLocationOverride: Boolean,
    val assignedDayIso: String? = null,
    val dayAssignmentConfidence: String = "UNKNOWN",
    val dayAssignmentProvenance: String? = null
)

@Entity(
    tableName = "user_overrides",
    indices = [
        Index(value = ["targetSourceId"]),
        Index(value = ["targetType", "targetSourceId"], unique = true)
    ]
)
data class UserOverrideEntity(
    @PrimaryKey val id: String, // e.g. "SEGMENT_TRANSPORT:seg-123"
    val targetType: String,     // "SEGMENT_TRANSPORT" or "MEDIA_LOCATION" or "VISIT_NAME"
    val targetSourceId: String,
    val overrideValue: String,
    val overriddenAtEpochMs: Long = System.currentTimeMillis()
)
