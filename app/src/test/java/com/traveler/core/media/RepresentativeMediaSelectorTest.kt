package com.traveler.core.media

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.LocationConfidenceLevel
import com.traveler.core.model.MediaItem
import com.traveler.core.model.TimestampConfidence
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepresentativeMediaSelectorTest {

    @Test
    fun scorePhoto_prioritizesUserOverrideAndGps() {
        val userChosen = MediaItem(
            id = "1",
            contentUriString = "uri://1",
            fileName = "IMG_001.jpg",
            mimeType = "image/jpeg",
            timestampEpochMs = 1000L,
            isRepresentative = true
        )
        val regularGps = MediaItem(
            id = "2",
            contentUriString = "uri://2",
            fileName = "IMG_002.jpg",
            mimeType = "image/jpeg",
            timestampEpochMs = 1000L,
            locationConfidence = LocationConfidenceLevel.GPS_EXACT,
            matchedVisitId = "v1"
        )
        val screenshot = MediaItem(
            id = "3",
            contentUriString = "uri://3",
            fileName = "Screenshot_2026.png",
            mimeType = "image/png",
            timestampEpochMs = 1000L
        )

        val score1 = RepresentativeMediaSelector.scorePhoto(userChosen)
        val score2 = RepresentativeMediaSelector.scorePhoto(regularGps)
        val score3 = RepresentativeMediaSelector.scorePhoto(screenshot)

        assertTrue(score1 > score2)
        assertTrue(score2 > score3)
    }

    @Test
    fun collapseBursts_groupsNearbyPhotosWithinOneMinute() {
        val baseMs = 1783153800000L
        val loc = GeoPoint(42.3601, -71.0589)

        val burstPhotos = (0..5).map { i ->
            MediaItem(
                id = "photo_$i",
                contentUriString = "uri://$i",
                fileName = "IMG_$i.jpg",
                mimeType = "image/jpeg",
                timestampEpochMs = baseMs + i * 5000L, // 5s apart (within 60s)
                location = GeoPoint(loc.latitude + i * 0.00001, loc.longitude), // ~1m apart
                locationConfidence = if (i == 3) LocationConfidenceLevel.GPS_EXACT else LocationConfidenceLevel.UNKNOWN,
                visualFeatures = PhotoVisualFeatures(42L, 500f, .8f, listOf(.2f,.8f)),
                matchedVisitId = "visit_boston"
            )
        }

        val collapsed = RepresentativeMediaSelector.collapseBursts(burstPhotos)
        assertEquals(1, collapsed.size)
        // Highest scored item (the one with GPS_EXACT) should be selected as representative
        assertEquals("photo_3", collapsed.first().id)
    }

    @Test
    fun select_returnsHeroAndThumbnailsWithAccurateExtraCount() {
        val baseMs = 1783153800000L
        // 10 distinct photos spaced by 10 minutes
        val distinctPhotos = (0..9).map { i ->
            MediaItem(
                id = "photo_$i",
                contentUriString = "uri://$i",
                fileName = "IMG_$i.jpg",
                mimeType = "image/jpeg",
                timestampEpochMs = baseMs + i * 600_000L,
                location = GeoPoint(42.0 + i * 0.1, -71.0)
            )
        }

        val selection = RepresentativeMediaSelector.select(distinctPhotos, maxThumbnails = 3)
        assertNotNull(selection.hero)
        assertEquals(3, selection.thumbnails.size)
        assertEquals(10, selection.totalCount)
        assertEquals(6, selection.extraCount) // 10 - 1 hero - 3 thumbnails = 6 extra
    }
}
