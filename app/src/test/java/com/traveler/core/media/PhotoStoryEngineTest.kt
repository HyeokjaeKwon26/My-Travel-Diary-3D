package com.traveler.core.media

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.*
import org.junit.Assert.*
import org.junit.Test

class PhotoStoryEngineTest {

    private fun createPhoto(
        id: String,
        timestampEpochMs: Long?,
        fileName: String = "photo_$id.jpg",
        matchedVisitId: String? = null,
        matchedSegmentId: String? = null,
        location: GeoPoint? = GeoPoint(42.1292, -80.0851),
        locationConfidence: LocationConfidenceLevel = LocationConfidenceLevel.GPS_EXACT,
        timestampConfidence: TimestampConfidence = TimestampConfidence.EXIF_EXACT,
        isRepresentative: Boolean = false
    ): MediaItem {
        return MediaItem(
            id = id,
            contentUriString = "content://media/$id",
            fileName = fileName,
            mimeType = "image/jpeg",
            timestampEpochMs = timestampEpochMs,
            timestampConfidence = timestampConfidence,
            location = location,
            locationConfidence = locationConfidence,
            confidenceScore = 0.95f,
            matchedVisitId = matchedVisitId,
            matchedSegmentId = matchedSegmentId,
            visualFeatures = PhotoVisualFeatures(42L, 500f, .8f, listOf(.2f,.8f)),
            isRepresentative = isRepresentative
        )
    }

    // 1. Erie photo is chronologically earlier than Niagara -> Never emitted after Niagara (P0-08)
    @Test
    fun testEriePhotoNeverEmittedAfterNiagara() {
        val erieVisit = Visit(
            id = "v_erie",
            location = GeoPoint(42.1292, -80.0851),
            startTimestampEpochMs = 1723810000000L, // T0
            endTimestampEpochMs = 1723813600000L,   // T0 + 1h
            confidence = 0.9f
        )
        val niagaraVisit = Visit(
            id = "v_niagara",
            location = GeoPoint(43.0962, -79.0377),
            startTimestampEpochMs = 1723820000000L, // T0 + 2.7h
            endTimestampEpochMs = 1723830000000L,   // T0 + 5.5h
            confidence = 0.9f
        )

        val eriePhoto = createPhoto(id = "p_erie", timestampEpochMs = 1723812000000L, matchedVisitId = "v_erie")
        val niagaraPhoto = createPhoto(id = "p_niagara", timestampEpochMs = 1723825000000L, matchedVisitId = "v_niagara")

        val moments = PhotoStoryEngine.buildGlobalPhotoStoryMoments(
            visits = listOf(erieVisit, niagaraVisit),
            segments = emptyList(),
            allPhotos = listOf(eriePhoto, niagaraPhoto)
        )

        assertEquals(2, moments.size)
        assertEquals("p_erie", moments[0].mediaId)
        assertEquals("p_niagara", moments[1].mediaId)
        assertTrue(moments[0].effectiveStoryTimestampEpochMs < moments[1].effectiveStoryTimestampEpochMs)
    }

    // 2. Syracuse context -> Emitted only in Syracuse context (P0-09)
    @Test
    fun testSyracusePhotoEmittedOnlyInContext() {
        val syracuseVisit = Visit(
            id = "v_syracuse",
            location = GeoPoint(43.0481, -76.1474),
            startTimestampEpochMs = 1723900000000L,
            endTimestampEpochMs = 1723907200000L,
            confidence = 0.9f
        )
        val syracusePhoto = createPhoto(id = "p_syracuse", timestampEpochMs = 1723903600000L, matchedVisitId = "v_syracuse")

        val moments = PhotoStoryEngine.buildGlobalPhotoStoryMoments(
            visits = listOf(syracuseVisit),
            segments = emptyList(),
            allPhotos = listOf(syracusePhoto)
        )

        assertEquals(1, moments.size)
        assertEquals("v_syracuse", moments[0].parentId)
        assertEquals(1723903600000L, moments[0].effectiveStoryTimestampEpochMs)
    }

    // 3. 50 photos captured within two minutes -> Collapses into 1 representative moment (P0-11)
    @Test
    fun testBurstCollapseFiftyPhotosInTwoMinutes() {
        val visit = Visit(
            id = "v_burst",
            location = GeoPoint(43.0962, -79.0377),
            startTimestampEpochMs = 1723820000000L,
            endTimestampEpochMs = 1723825000000L,
            confidence = 0.9f
        )
        val burstPhotos = (1..50).map { i ->
            createPhoto(
                id = "p_burst_$i",
                timestampEpochMs = 1723820000000L + (i * 2000L), // Every 2s for 100s
                matchedVisitId = "v_burst"
            )
        }

        val clusters = PhotoStoryEngine.clusterParentMedia(
            parentType = "VISIT",
            parentId = visit.id,
            parentStartEpochMs = visit.startTimestampEpochMs,
            parentEndEpochMs = visit.endTimestampEpochMs,
            photos = burstPhotos,
            spatialAnchor = visit.location
        )

        assertEquals(1, clusters.size)
        assertEquals(50, clusters[0].photoCandidates.size)
    }

    // 4. 190 photos in one Visit -> Bounded cinematic representative count (P0-15)
    @Test
    fun testBoundedPhotoBudgetForLargeVisit() {
        val visit = Visit(
            id = "v_large",
            location = GeoPoint(40.7580, -73.9855),
            startTimestampEpochMs = 1723800000000L,
            endTimestampEpochMs = 1723836000000L, // 10h dwell
            confidence = 0.9f
        )
        val photos = (1..190).map { i ->
            createPhoto(
                id = "p_$i",
                timestampEpochMs = 1723800000000L + (i * 180_000L),
                matchedVisitId = "v_large"
            )
        }

        val moments = PhotoStoryEngine.buildGlobalPhotoStoryMoments(
            visits = listOf(visit),
            segments = emptyList(),
            allPhotos = photos,
            profile = StoryDurationProfile.STANDARD
        )

        assertTrue("Cinematic moments must be bounded (<= 3 for one visit)", moments.size <= 3)
    }

    // 5. Long Visit with morning/afternoon/evening sessions -> Multiple distinct clusters allowed (P0-16)
    @Test
    fun testLongVisitDistinctSessionClustering() {
        val visit = Visit(
            id = "v_all_day",
            location = GeoPoint(40.7580, -73.9855),
            startTimestampEpochMs = 1723800000000L,        // 08:00
            endTimestampEpochMs = 1723843200000L,          // 20:00 (12h)
            confidence = 0.9f
        )
        val morningPhotos = (1..5).map { i ->
            createPhoto(id = "p_m_$i", timestampEpochMs = 1723800000000L + (i * 10000L), matchedVisitId = "v_all_day")
        }
        val afternoonPhotos = (1..5).map { i ->
            createPhoto(id = "p_a_$i", timestampEpochMs = 1723818000000L + (i * 10000L), matchedVisitId = "v_all_day")
        }
        val eveningPhotos = (1..5).map { i ->
            createPhoto(id = "p_e_$i", timestampEpochMs = 1723839600000L + (i * 10000L), matchedVisitId = "v_all_day")
        }

        val clusters = PhotoStoryEngine.clusterParentMedia(
            parentType = "VISIT",
            parentId = visit.id,
            parentStartEpochMs = visit.startTimestampEpochMs,
            parentEndEpochMs = visit.endTimestampEpochMs,
            photos = morningPhotos + afternoonPhotos + eveningPhotos,
            spatialAnchor = visit.location
        )

        assertEquals(3, clusters.size)
    }

    // 6. Screenshot/download media -> Ineligible for cinematic playback (P0-13)
    @Test
    fun testScreenshotAndDownloadIneligible() {
        val screenshot = createPhoto(id = "p_screen", timestampEpochMs = 1723810000000L, fileName = "Screenshot_20260817.png")
        val download = createPhoto(id = "p_dl", timestampEpochMs = 1723810000000L, fileName = "downloaded_map.jpg")

        val (sEligibility, _) = PhotoStoryEngine.evaluateEligibility(screenshot, 1723800000000L, 1723820000000L)
        val (dEligibility, _) = PhotoStoryEngine.evaluateEligibility(download, 1723800000000L, 1723820000000L)

        assertEquals(PhotoStoryEligibility.INELIGIBLE_SCREENSHOT_OR_DOWNLOAD, sEligibility)
        assertEquals(PhotoStoryEligibility.INELIGIBLE_SCREENSHOT_OR_DOWNLOAD, dEligibility)
    }

    // 7. Unknown capture timestamp -> Ineligible for cinematic playback (P0-04)
    @Test
    fun testUncertainTimestampIneligible() {
        val uncertainPhoto = createPhoto(
            id = "p_uncertain",
            timestampEpochMs = null,
            timestampConfidence = TimestampConfidence.UNKNOWN
        )
        val (eligibility, _) = PhotoStoryEngine.evaluateEligibility(uncertainPhoto, 1723800000000L, 1723820000000L)
        assertEquals(PhotoStoryEligibility.INELIGIBLE_TIME_UNCERTAIN, eligibility)
    }

    // 8. Capture timestamp contradicts matched parent -> Ineligible timestamp conflict (P0-04)
    @Test
    fun testTimestampContradictionIneligible() {
        val photo = createPhoto(id = "p_conflict", timestampEpochMs = 1723800000000L) // 3 hours before parent
        val (eligibility, _) = PhotoStoryEngine.evaluateEligibility(photo, 1723810800000L, 1723820000000L)
        assertEquals(PhotoStoryEligibility.INELIGIBLE_TIMESTAMP_CONFLICT, eligibility)
    }

    // 9. Photo GPS far from route but valid timestamp -> Displays as overlay, route marker stays canonical (P0-07)
    @Test
    fun testPhotoGpsFarFromRouteAnchorsToParent() {
        val visit = Visit(
            id = "v_1",
            location = GeoPoint(40.7128, -74.0060),
            startTimestampEpochMs = 1723800000000L,
            endTimestampEpochMs = 1723810000000L,
            confidence = 0.9f
        )
        val remotePhoto = createPhoto(
            id = "p_remote",
            timestampEpochMs = 1723805000000L,
            location = GeoPoint(40.7580, -73.9855), // 5km away
            matchedVisitId = "v_1"
        )

        val moments = PhotoStoryEngine.buildGlobalPhotoStoryMoments(
            visits = listOf(visit),
            segments = emptyList(),
            allPhotos = listOf(remotePhoto)
        )

        assertEquals(1, moments.size)
        assertEquals(visit.location, moments[0].playbackAnchorLocation)
    }

    // 10. User explicitly selects representative -> Wins automatic selection (P0-13 & P1)
    @Test
    fun testUserOverrideRepresentativeWinsSelection() {
        val photoAuto = createPhoto(id = "p_auto", timestampEpochMs = 1723801000000L, isRepresentative = false)
        val photoUser = createPhoto(id = "p_user", timestampEpochMs = 1723801050000L, isRepresentative = true)

        val scoreAuto = PhotoStoryEngine.scorePhotoForRepresentative(photoAuto)
        val scoreUser = PhotoStoryEngine.scorePhotoForRepresentative(photoUser)

        assertTrue(scoreUser > scoreAuto + 9000)
    }
}
