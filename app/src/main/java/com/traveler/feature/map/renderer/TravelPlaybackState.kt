package com.traveler.feature.map.renderer

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.MediaItem
import com.traveler.core.model.MovementSegment
import com.traveler.core.model.TransportMode
import com.traveler.core.model.Visit

data class TravelPlaybackState(
    val progress: Float,                     // 0.0f to 1.0f
    val storyTimeMs: Long,                   // Timeline story epoch ms
    val currentPosition: GeoPoint,           // Current animated geo coordinate
    val currentTransportMode: TransportMode, // Current vehicle or movement mode
    val currentHeadingDegrees: Float,        // Heading angle (0 = North, 90 = East)
    val currentSegment: MovementSegment? = null,
    val currentVisit: Visit? = null,
    val activePhoto: MediaItem? = null,
    val cameraCenter: GeoPoint,
    val cameraSpanLat: Double,
    val cameraSpanLng: Double,
    val isTitleCardActive: Boolean = false,
    val isEndCardActive: Boolean = false,
    val episodeIndex: Int = -1,
    val currentDayIndex: Int = 1,
    val dayTransitionLabel: String? = null,
    val isDayTransitionActive: Boolean = false,
    val currentAltitudeMeters: Double? = null,
    val currentSpeedKmh: Double = 0.0,
    val currentTraveledDistanceMeters: Double = 0.0,
    val totalTripDistanceMeters: Double = 0.0,
    val animationTimeSeconds: Double? = null
)
