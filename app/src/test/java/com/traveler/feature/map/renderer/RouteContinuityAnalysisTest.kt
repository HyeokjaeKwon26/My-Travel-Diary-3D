package com.traveler.feature.map.renderer

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.GeometryProvenance
import com.traveler.core.model.MovementSegment
import com.traveler.core.model.TransportMode
import com.traveler.core.model.TransportPrediction
import com.traveler.core.model.Visit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RouteContinuityAnalysisTest {

    @Test
    fun smallGapBetweenConsecutiveSegments_doesNotInventRoad() {
        val p1 = GeoPoint(40.7128, -74.0060)
        val p2 = GeoPoint(40.7140, -74.0040)
        val p3 = GeoPoint(40.7150, -74.0030) // ~150m away from p2
        val p4 = GeoPoint(40.7200, -74.0000)

        val s1 = MovementSegment(
            id = "s1",
            startTimestampEpochMs = 1000L,
            endTimestampEpochMs = 2000L,
            startPoint = p1,
            endPoint = p2,
            distanceMeters = 200.0,
            durationMillis = 1000L,
            transport = TransportPrediction(TransportMode.WALK, 0.9f, "Walk"),
            geometryProvenance = GeometryProvenance.OBSERVED
        )

        val s2 = MovementSegment(
            id = "s2",
            startTimestampEpochMs = 2100L, // 100ms gap
            endTimestampEpochMs = 3000L,
            startPoint = p3,
            endPoint = p4,
            distanceMeters = 600.0,
            durationMillis = 900L,
            transport = TransportPrediction(TransportMode.CAR, 0.9f, "Drive"),
            geometryProvenance = GeometryProvenance.OBSERVED
        )

        val renderer = TravelMapRenderer(null)
        val prep = renderer.prepareMap(TravelMapRenderModel(emptyList(), listOf(s1, s2), emptyList()))

        // Proximity alone cannot prove a traversable road across a canyon.
        assertEquals(2, prep.preparedSegments.size)
        assertFalse(prep.preparedSegments.any { it.isContinuityConnector })
    }

    @Test
    fun largeGapBetweenSegments_isPreservedWithoutConnector() {
        val p1 = GeoPoint(40.7128, -74.0060)
        val p2 = GeoPoint(40.7140, -74.0040)
        val p3 = GeoPoint(42.3601, -71.0589) // Boston: ~300km away
        val p4 = GeoPoint(42.3700, -71.0500)

        val s1 = MovementSegment(
            id = "s1",
            startTimestampEpochMs = 1000L,
            endTimestampEpochMs = 2000L,
            startPoint = p1,
            endPoint = p2,
            distanceMeters = 200.0,
            durationMillis = 1000L,
            transport = TransportPrediction(TransportMode.WALK, 0.9f, "Walk"),
            geometryProvenance = GeometryProvenance.OBSERVED
        )

        val s2 = MovementSegment(
            id = "s2",
            startTimestampEpochMs = 20000L,
            endTimestampEpochMs = 30000L,
            startPoint = p3,
            endPoint = p4,
            distanceMeters = 1000.0,
            durationMillis = 10000L,
            transport = TransportPrediction(TransportMode.CAR, 0.9f, "Drive"),
            geometryProvenance = GeometryProvenance.OBSERVED
        )

        val renderer = TravelMapRenderer(null)
        val prep = renderer.prepareMap(TravelMapRenderModel(emptyList(), listOf(s1, s2), emptyList()))

        // Should have only 2 segments, no fabricated connector across 300km
        assertEquals(2, prep.preparedSegments.size)
        assertFalse(prep.preparedSegments.any { it.isContinuityConnector })
    }

    @Test
    fun endpointNearVisit_keepsTheSamePathAsPlayback() {
        val vLoc = GeoPoint(40.712800, -74.006000)
        val sStart = GeoPoint(40.712810, -74.006010) // ~1.5m away
        val sEnd = GeoPoint(40.720000, -74.000000)

        val visit = Visit("v1", "Start Spot", location = vLoc, startTimestampEpochMs = 0L, endTimestampEpochMs = 1000L)
        val seg = MovementSegment("s1", 1000L, 2000L, sStart, sEnd, distanceMeters = 1000.0, durationMillis = 1000L, transport = TransportPrediction(TransportMode.WALK, 0.9f, "Walk"))

        val renderer = TravelMapRenderer(null)
        val prep = renderer.prepareMap(TravelMapRenderModel(listOf(visit), listOf(seg), emptyList()))

        val prepSeg = prep.preparedSegments.first()
        assertEquals(sStart.latitude, prepSeg.pathPoints.first().latitude, 0.000001)
        assertEquals(sStart.longitude, prepSeg.pathPoints.first().longitude, 0.000001)

        val timeline = com.traveler.feature.map.story.TravelStoryTimeline.build(
            TravelMapRenderModel(listOf(visit), listOf(seg)), com.traveler.core.media.StoryDurationProfile.STANDARD)
        val episode = timeline.episodes.filterIsInstance<com.traveler.feature.map.story.StoryEpisode.MovementEpisode>().first()
        assertEquals(prepSeg.pathPoints, episode.pathPoints)
        // Original segment coordinates remain immutable
        assertEquals(sStart.latitude, seg.startPoint.latitude, 0.000001)
    }
}
