package com.traveler.feature.map.renderer

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.MediaItem
import com.traveler.core.model.MovementSegment
import com.traveler.core.model.Visit

data class TravelMapRenderModel(
    val visits: List<Visit>,
    val segments: List<MovementSegment>,
    val photos: List<MediaItem> = emptyList(),
    val focusedLocation: GeoPoint? = null,
    val photoSelections: Map<String, List<com.traveler.core.media.PhotoStoryMoment>>? = null
)
