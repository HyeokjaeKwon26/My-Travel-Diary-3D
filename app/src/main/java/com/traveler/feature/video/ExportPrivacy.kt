package com.traveler.feature.video

import com.traveler.feature.map.renderer.TravelMapRenderModel

/** Conservative export-only redaction; persisted user labels are never changed. */
object ExportPrivacy {
    fun label(value: String): String = if (
        Regex("(?i)\\b(home|inferred_home|work|inferred_work)\\b").containsMatchIn(value) ||
        value.contains("집") || value.contains("자택") || value.any { it.isDigit() }
    ) "Private place" else value

    // A custom story title is not a street label: e.g. "Newport 2" and "Summer 2026".
    fun title(value: String): String = if (
        Regex("(?i)\\b(home|inferred_home|work|inferred_work)\\b|자택|^집$").containsMatchIn(value) ||
        Regex("(?i)\\b\\d+\\s+.+\\b(street|st|road|rd|avenue|ave|drive|dr|lane|ln|court|ct|boulevard|blvd)\\b").containsMatchIn(value) ||
        Regex("[가-힣]+(?:로|길)\\s*\\d+").containsMatchIn(value)
    ) "My Travel Story" else value

    fun generalize(model: TravelMapRenderModel) = model.copy(visits = model.visits.map {
        it.copy(placeName = label(it.placeName ?: "Stop"), placeAddress = null)
    })
}
