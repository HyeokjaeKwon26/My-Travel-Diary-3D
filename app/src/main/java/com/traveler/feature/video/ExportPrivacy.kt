package com.traveler.feature.video

import com.traveler.feature.map.renderer.TravelMapRenderModel

/** Conservative export-only redaction; persisted user labels are never changed. */
object ExportPrivacy {
    fun label(value: String): String = if (
        Regex("(?i)\\b(home|inferred_home|work|inferred_work)\\b").containsMatchIn(value) ||
        value.contains("집") || value.contains("자택") || value.any { it.isDigit() }
    ) "Private place" else value

    fun generalize(model: TravelMapRenderModel) = model.copy(visits = model.visits.map {
        it.copy(placeName = label(it.placeName ?: "Stop"), placeAddress = null)
    })
}
