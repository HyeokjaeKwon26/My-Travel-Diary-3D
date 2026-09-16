package com.traveler.feature.map.story

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.common.geo.GeodesicUtils
import com.traveler.core.media.StoryDurationProfile
import com.traveler.core.model.*
import com.traveler.feature.map.renderer.TravelMapRenderModel
import org.junit.Assert.*
import org.junit.Test

/** Missing observations remain missing: no inferred roads, transport or measured altitude. */
class GapBridgingAndAltitudeTest {

    private fun createTripWithGaps(): TravelMapRenderModel {
        // Visit 1: New York
        val visitNY = Visit(
            id = "v_ny",
            location = GeoPoint(40.7128, -74.0060, altitudeMeters = 10.0),
            startTimestampEpochMs = 1787100000000L,
            endTimestampEpochMs = 1787103600000L,
            confidence = 0.9f
        )
        // GAP: No movement between NY and Philadelphia (~130 km gap)

        // Visit 2: Philadelphia
        val visitPhilly = Visit(
            id = "v_philly",
            location = GeoPoint(39.9526, -75.1652, altitudeMeters = 12.0),
            startTimestampEpochMs = 1787110000000L,
            endTimestampEpochMs = 1787115000000L,
            confidence = 0.9f
        )

        // Movement: Philly to DC airport
        val driveToDC = MovementSegment(
            id = "s_dc",
            startTimestampEpochMs = 1787115000000L,
            endTimestampEpochMs = 1787125000000L,
            startPoint = GeoPoint(39.9526, -75.1652, altitudeMeters = 12.0),
            endPoint = GeoPoint(38.8512, -77.0402, altitudeMeters = 5.0), // DCA airport
            distanceMeters = 200_000.0,
            durationMillis = 10000000L,
            transport = TransportPrediction(TransportMode.CAR, 0.9f, "TestDrive")
        )

        // GAP: Flight from DCA to Chicago O'Hare (~950 km flight gap without segment)

        // Visit 3: Chicago
        val visitChicago = Visit(
            id = "v_chicago",
            location = GeoPoint(41.8781, -87.6298, altitudeMeters = 180.0),
            startTimestampEpochMs = 1787140000000L,
            endTimestampEpochMs = 1787150000000L,
            confidence = 0.9f
        )

        return TravelMapRenderModel(
            visits = listOf(visitNY, visitPhilly, visitChicago),
            segments = listOf(driveToDC),
            photos = emptyList()
        )
    }

    @Test
    fun missingRecordingDoesNotInventDrivingOrFlight() {
        val timeline = TravelStoryTimeline.build(createTripWithGaps(), StoryDurationProfile.STANDARD)
        val movements = timeline.episodes.filterIsInstance<StoryEpisode.MovementEpisode>()
        assertEquals(listOf("s_dc"), movements.filterNot { it.segment.id.startsWith("bridge_") }.map { it.segment.id })
        assertEquals(3, timeline.episodes.filterIsInstance<StoryEpisode.VisitEpisode>().size)
        val bridges = movements.filter { it.segment.id.startsWith("bridge_") }
        assertEquals(2, bridges.size)
        assertTrue(bridges.all { it.segment.geometryProvenance == GeometryProvenance.CONTINUITY_ESTIMATE && it.segment.effectiveMode == TransportMode.UNKNOWN })
        assertEquals(200_000.0, timeline.totalTripDistanceMeters, 0.1)
        for (i in 0..1000) {
            val state=timeline.evaluate(i/1000f)
            assertTrue(state.currentTraveledDistanceMeters <= timeline.totalTripDistanceMeters)
        }
        val diagnostic = PlaybackContinuityDiagnostic()
        for (step in 0..500) diagnostic.recordFrame(timeline.evaluate(step / 500f), false)
        assertEquals(0, diagnostic.playbackProgressBackwardCount)
        assertEquals(0, diagnostic.episodeIndexBackwardCount)
    }

    @Test
    fun absentFlightDoesNotManufactureCruisingAltitude() {
        val timeline = TravelStoryTimeline.build(createTripWithGaps(), StoryDurationProfile.STANDARD)
        val states = (0..300).map { timeline.evaluate(it / 300f) }
        assertTrue(states.all { it.currentAltitudeMeters == null || it.currentAltitudeMeters!! <= 180.0 })
        assertFalse(states.any { it.currentTransportMode == TransportMode.AIRPLANE })
    }

    @Test
    fun testRealRoadConnectingSegmentsPreferredOverSyntheticBridges() {
        val visitA = Visit(
            id = "v_vegas",
            location = GeoPoint(36.1699, -115.1398),
            startTimestampEpochMs = 1787100000000L,
            endTimestampEpochMs = 1787103600000L,
            confidence = 0.9f
        )
        val visitB = Visit(
            id = "v_north",
            location = GeoPoint(36.5000, -115.2000),
            startTimestampEpochMs = 1787110000000L,
            endTimestampEpochMs = 1787120000000L,
            confidence = 0.9f
        )

        // Real curved road segment between visitA and visitB
        val realRoad = MovementSegment(
            id = "s_curved_road",
            startTimestampEpochMs = 1787103600000L,
            endTimestampEpochMs = 1787110000000L,
            startPoint = GeoPoint(36.1699, -115.1398),
            endPoint = GeoPoint(36.5000, -115.2000),
            simplifiedPoints = listOf(
                GeoPoint(36.1699, -115.1398),
                GeoPoint(36.2500, -115.1500),
                GeoPoint(36.3500, -115.1800),
                GeoPoint(36.5000, -115.2000)
            ),
            distanceMeters = 40_000.0,
            durationMillis = 6400000L,
            transport = TransportPrediction(TransportMode.CAR, 0.9f, "Highway")
        )

        // Add 12 dummy segments elsewhere so canonicalSegments.size > 10
        val dummySegments = (1..12).map { idx ->
            MovementSegment(
                id = "s_dummy_$idx",
                startTimestampEpochMs = 1787200000000L + idx * 100000L,
                endTimestampEpochMs = 1787200000000L + idx * 100000L + 50000L,
                startPoint = GeoPoint(40.0 + idx * 0.01, -100.0),
                endPoint = GeoPoint(40.0 + idx * 0.02, -100.0),
                distanceMeters = 100_000.0, // higher distance to crowd out keySegmentIds
                durationMillis = 50000L,
                transport = TransportPrediction(TransportMode.CAR, 0.9f, "Dummy")
            )
        }

        val allSegments = listOf(realRoad) + dummySegments
        val trip = TravelMapRenderModel(
            visits = listOf(visitA, visitB),
            segments = allSegments,
            photos = emptyList()
        )

        val timeline = TravelStoryTimeline.build(trip, StoryDurationProfile.STANDARD)

        // Find movement episode between visitA and visitB
        val movementEp = timeline.episodes.filterIsInstance<StoryEpisode.MovementEpisode>()
            .find { it.segment.id == "s_curved_road" }

        assertNotNull("Real road segment s_curved_road MUST be preserved in the timeline!", movementEp)
        assertTrue(
            "Episode must use real curved road points instead of a synthetic 2-point chord",
            (movementEp?.pathPoints?.size ?: 0) >= 4
        )

        // Verify NO synthetic straight bridge was created between visitA and visitB
        val bridgeEp = timeline.episodes.filterIsInstance<StoryEpisode.MovementEpisode>()
            .find { it.segment.id.startsWith("bridge_") && it.segment.id.contains("v_vegas") }
        assertNull("No synthetic straight bridge should be created when real road segment exists", bridgeEp)
    }
}

