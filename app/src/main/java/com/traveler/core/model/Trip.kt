package com.traveler.core.model

import com.traveler.core.media.MediaPipelineDiagnostics
import com.traveler.core.timeline.CanonicalTimelineDiagnostics
import kotlinx.serialization.Serializable

@Serializable
data class Trip(
    val id: String,
    val title: String,
    val startDateIso: String,
    val endDateIso: String,
    val totalDistanceMeters: Double,
    val cities: List<String> = emptyList(),
    val countries: List<String> = emptyList(),
    val days: List<TripDay> = emptyList(),
    val uncertainDateMedia: List<MediaItem> = emptyList(),
    val totalMediaCount: Int = 0,
    val mediaDiagnostics: MediaPipelineDiagnostics? = null,
    val movementDiagnostics: CanonicalTimelineDiagnostics? = null,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    // Derived from saved visits, never from the legacy five-name cache or an archived summary.
    @kotlinx.serialization.Transient val visitSummary: TripVisitSummary = TripVisitSummary(),
    @kotlinx.serialization.Transient val memorySnapshot: com.traveler.core.media.TripMemorySnapshot? = null
)

@Serializable
data class ImportMetadata(
    val id: String,
    val sourceName: String,
    val fileHash: String? = null,
    val importedEpochMs: Long = System.currentTimeMillis(),
    val recordCount: Int = 0,
    val dateRangeStartIso: String? = null,
    val dateRangeEndIso: String? = null
)
