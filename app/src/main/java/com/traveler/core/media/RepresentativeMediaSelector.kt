package com.traveler.core.media

import com.traveler.core.common.geo.GeodesicUtils
import com.traveler.core.model.LocationConfidenceLevel
import com.traveler.core.model.MediaItem
import com.traveler.core.model.TimestampConfidence
import kotlin.math.abs

data class RepresentativeSelection(
    val hero: MediaItem?,
    val thumbnails: List<MediaItem>,
    val totalCount: Int,
    val allPhotos: List<MediaItem>
) {
    val extraCount: Int get() = maxOf(0, totalCount - (if (hero != null) 1 else 0) - thumbnails.size)
}

object RepresentativeMediaSelector {

    private const val BURST_TIME_WINDOW_MS = 60_000L // 60 seconds
    private const val BURST_DISTANCE_METERS = 50.0 // 50 meters

    /**
     * Scores a photo deterministically for hero selection.
     */
    fun scorePhoto(photo: MediaItem): Int {
        var score = 0

        // 1. User manual override / chosen
        if (photo.isRepresentative) score += 100000

        // 2. Exact GPS location
        if (photo.locationConfidence == LocationConfidenceLevel.GPS_EXACT) {
            score += if (photo.matchedVisitId != null) 500 else 400
        }

        // 3. Exact timestamp confidence
        when (photo.timestampConfidence) {
            TimestampConfidence.EXIF_EXACT -> score += 300
            TimestampConfidence.EXIF_LOCAL -> score += 250
            TimestampConfidence.MEDIASTORE -> score += 200
            TimestampConfidence.FILENAME_INFERRED -> score += 150
            else -> score += 50
        }

        // 4. Photo preferred over video for hero
        val isVideo = photo.mimeType.startsWith("video/")
        if (!isVideo) {
            score += 100
        }

        // 5. Penalties for screenshots, downloads, thumbnails
        val nameLower = photo.fileName.lowercase()
        if (nameLower.contains("screenshot") || nameLower.contains("screen_shot") || nameLower.contains("screencap")) {
            score -= 300
        }
        if (nameLower.contains("download") || nameLower.contains("received_")) {
            score -= 200
        }
        if (nameLower.contains("thumb") || nameLower.contains("preview") || nameLower.contains("icon")) {
            score -= 200
        }

        return (if(photo.isRepresentative) 100000 else 0) +
            (score - if(photo.isRepresentative) 100000 else 0)/8 + (photo.visualFeatures?.qualityScore ?: 180)
    }

    fun visuallyDuplicate(a: MediaItem, b: MediaItem): Boolean {
        val x=a.visualFeatures;val y=b.visualFeatures
        // Unknown image content is never evidence of a duplicate.
        return x != null && y != null && x.similar(y)
    }

    /**
     * Collapses near-duplicate / burst captures within time/distance thresholds.
     */
    fun collapseBursts(photos: List<MediaItem>): List<MediaItem> {
        if (photos.size <= 1) return photos

        val sorted = photos.sortedBy { it.timestampEpochMs ?: Long.MAX_VALUE }
        val clusters = mutableListOf<MutableList<MediaItem>>()

        for (photo in sorted) {
            var matchedCluster: MutableList<MediaItem>? = null
            for (cluster in clusters) {
                val anchor = cluster.first()
                val timeDiff = if (photo.timestampEpochMs != null && anchor.timestampEpochMs != null) {
                    abs(photo.timestampEpochMs - anchor.timestampEpochMs)
                } else {
                    Long.MAX_VALUE
                }

                val dist = if (photo.location != null && anchor.location != null) {
                    GeodesicUtils.distanceMeters(photo.location, anchor.location)
                } else {
                    Double.MAX_VALUE
                }

                val sameVisit = photo.matchedVisitId != null && photo.matchedVisitId == anchor.matchedVisitId

                if (!photo.isRepresentative && !anchor.isRepresentative &&
                    timeDiff <= BURST_TIME_WINDOW_MS && (dist <= BURST_DISTANCE_METERS || sameVisit) &&
                    visuallyDuplicate(photo, anchor)) {
                    matchedCluster = cluster
                    break
                }
            }

            if (matchedCluster != null) {
                matchedCluster.add(photo)
            } else {
                clusters.add(mutableListOf(photo))
            }
        }

        // For each cluster, pick the highest scored item as the cluster representative
        return clusters.map { cluster ->
            cluster.maxByOrNull { scorePhoto(it) } ?: cluster.first()
        }
    }

    /**
     * Selects representative media for a Diary card: 1 hero + up to [maxThumbnails] thumbnails.
     */
    fun select(photos: List<MediaItem>, maxThumbnails: Int = 3): RepresentativeSelection {
        if (photos.isEmpty()) {
            return RepresentativeSelection(hero = null, thumbnails = emptyList(), totalCount = 0, allPhotos = emptyList())
        }

        val totalCount = photos.size
        val collapsed = collapseBursts(photos)
        val ranked = collapsed.sortedByDescending { scorePhoto(it) }

        val hero = ranked.firstOrNull()
        val alternatives = ranked.drop(1).toMutableList()
        val thumbnails = mutableListOf<MediaItem>()
        while(thumbnails.size < maxThumbnails && alternatives.isNotEmpty()) {
            val used=(listOfNotNull(hero)+thumbnails).mapNotNull { it.visualFeatures?.category }.filter { it!="other" }
            val next=alternatives.maxByOrNull { scorePhoto(it) - if(it.visualFeatures?.category in used && !it.isRepresentative) 180 else 0 }!!
            alternatives.remove(next);thumbnails.add(next)
        }

        return RepresentativeSelection(
            hero = hero,
            thumbnails = thumbnails,
            totalCount = totalCount,
            allPhotos = photos
        )
    }
}
