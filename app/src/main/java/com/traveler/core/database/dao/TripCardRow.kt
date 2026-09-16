package com.traveler.core.database.dao

import androidx.room.Embedded
import com.traveler.core.database.entity.TripEntity

/** Small home projection: no route geometry, photo records or timezone reconstruction. */
data class TripCardRow(
    @Embedded val trip: TripEntity,
    val summaryVisitId: String?,
    val summaryPlaceName: String?
)
