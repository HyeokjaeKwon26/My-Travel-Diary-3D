package com.traveler.feature.map.story

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.common.geo.GeodesicUtils
import com.traveler.core.media.StoryDurationProfile
import com.traveler.core.model.*
import com.traveler.feature.map.renderer.TravelMapRenderModel
import org.junit.Assert.*
import org.junit.Test

/**
 * Regression tests for Pass 21.8b:
 * Verifies cross-type temporal overlap resolution and journey chronology invariants:
 * 1. Single intermediate visit inside a movement splits the movement into pre and post episodes
 *    interleaving strictly: Movement A->C -> Visit C -> Movement C->B.
 * 2. Multiple intermediate visits inside a single movement split sequentially:
 *    Move -> Visit 1 -> Move -> Visit 2 -> Move.
 * 3. Legitimate forward temporal gap (Move end 10:00 -> Visit start 10:10) maintains continuity bridging.
 * 4. Diagnostics verify:
 *    - unresolvedCrossTypeOverlapCount == 0
 *    - backwardEpisodeChronologyCount == 0
 *    - overlapGeneratedBridgeCount == 0
 *    - completedEpisodeReactivationCount == 0
 *    - episodeIndexBackwardCount == 0
 *    - playbackProgressBackwardCount == 0
 */
class CrossTypeChronologyAndInterleavingTest {

    // Fixture Coordinates (Synthetic South NV Corridor)
    // Point A: Jean / South NV
    private val pointA = GeoPoint(35.7797, -115.3289, altitudeMeters = 860.0)
    // Point C: Intermediate Stop (Seven Magic Mountains / Henderson South)
    private val pointC = GeoPoint(35.8383, -115.2708, altitudeMeters = 880.0)
    // Point B: Las Vegas Strip
    private val pointB = GeoPoint(36.1147, -115.1728, altitudeMeters = 610.0)
    // Point D: North Las Vegas
    private val pointD = GeoPoint(36.2000, -115.1200, altitudeMeters = 670.0)

    @Test
    fun testSingleIntermediateVisit_splitsMovementAndInterleavesStrictly() {
        // Movement: 10:00 to 11:00 (A -> B, 3600 seconds)
        val driveAtoB = MovementSegment(
            id = "drive_south_to_vegas",
            startTimestampEpochMs = 1720000000000L, // 10:00:00
            endTimestampEpochMs = 1720003600000L,   // 11:00:00
            startPoint = pointA,
            endPoint = pointB,
            simplifiedPoints = listOf(
                pointA,
                GeoPoint(35.8000, -115.3000, altitudeMeters = 870.0),
                GeoPoint(35.8380, -115.2710, altitudeMeters = 880.0), // Highway near C
                GeoPoint(35.9500, -115.2200, altitudeMeters = 750.0),
                pointB
            ),
            distanceMeters = 42_000.0,
            durationMillis = 3600000L,
            transport = TransportPrediction(TransportMode.CAR, 0.95f, "I-15 N")
        )

        // Visit: 10:25 to 10:35 at intermediate position C
        val visitC = Visit(
            id = "visit_intermediate_c",
            placeName = "Seven Magic Mountains",
            location = pointC,
            startTimestampEpochMs = 1720001500000L, // 10:25:00
            endTimestampEpochMs = 1720002100000L,   // 10:35:00
            confidence = 0.95f
        )

        val photoAtC = MediaItem(
            id = "photo_seven_magic",
            contentUriString = "content://media/photos/1",
            fileName = "seven_magic_mountains.jpg",
            mimeType = "image/jpeg",
            timestampEpochMs = 1720001800000L, // 10:30:00
            timestampConfidence = TimestampConfidence.EXIF_EXACT,
            location = pointC,
            locationConfidence = LocationConfidenceLevel.GPS_EXACT,
            confidenceScore = 0.95f,
            matchedVisitId = "visit_intermediate_c"
        )

        val renderModel = TravelMapRenderModel(
            visits = listOf(visitC),
            segments = listOf(driveAtoB),
            photos = listOf(photoAtC)
        )

        val timeline = TravelStoryTimeline.build(renderModel, StoryDurationProfile.STANDARD)

        // 1. Diagnostics validation
        assertEquals("Unresolved cross-type overlap must be 0", 0, timeline.diagnostics.unresolvedCrossTypeOverlapCount)
        assertEquals("Backward episode chronology must be 0", 0, timeline.diagnostics.backwardEpisodeChronologyCount)
        assertEquals("Overlap generated bridge count must be 0", 0, timeline.diagnostics.overlapGeneratedBridgeCount)

        // 2. Expected final Story sequence:
        // Ep 0: Movement A -> C (pre-visit, ends at C)
        // Ep 1: Visit at C
        // Ep 2: Movement C -> B (post-visit, starts at C, ends at B)
        assertEquals("Timeline must contain exactly 3 episodes (Move pre, Visit, Move post)", 3, timeline.episodes.size)

        val ep0 = timeline.episodes[0]
        val ep1 = timeline.episodes[1]
        val ep2 = timeline.episodes[2]

        assertTrue("Ep 0 must be MovementEpisode", ep0 is StoryEpisode.MovementEpisode)
        assertTrue("Ep 1 must be VisitEpisode", ep1 is StoryEpisode.VisitEpisode)
        assertTrue("Ep 2 must be MovementEpisode", ep2 is StoryEpisode.MovementEpisode)

        val movePre = ep0 as StoryEpisode.MovementEpisode
        val visitEp = ep1 as StoryEpisode.VisitEpisode
        val movePost = ep2 as StoryEpisode.MovementEpisode

        // Verify Ep 0 ends at C
        val distEp0EndToC = GeodesicUtils.distanceMeters(movePre.pathPoints.last(), pointC)
        assertTrue("Move pre must end at intermediate stop C (dist: $distEp0EndToC m)", distEp0EndToC < 150.0)

        // Verify Ep 1 is at C
        assertEquals("Visit must be at location C", pointC, visitEp.visit.location)

        // Verify Ep 2 starts at C and ends at B
        val distEp2StartToC = GeodesicUtils.distanceMeters(movePost.pathPoints.first(), pointC)
        val distEp2EndToB = GeodesicUtils.distanceMeters(movePost.pathPoints.last(), pointB)
        assertTrue("Move post must start at C (dist: $distEp2StartToC m)", distEp2StartToC < 150.0)
        assertTrue("Move post must end at B (dist: $distEp2EndToB m)", distEp2EndToB < 150.0)

        // Verify NO backwards bridge exists
        val hasBackwardsBridge = timeline.episodes.any { ep ->
            ep.stableId.contains("bridge") && ep.stableId.contains("vegas")
        }
        assertFalse("Must NOT contain any backwards bridge from Vegas to C", hasBackwardsBridge)

        // Verify playback state progression (no backwards jump)
        val diagnostic = PlaybackContinuityDiagnostic()
        diagnostic.recordTimelineDiagnostics(timeline.diagnostics)

        val steps = 100
        for (i in 0..steps) {
            val progress = i.toFloat() / steps.toFloat()
            val state = timeline.evaluate(progress)
            diagnostic.recordFrame(state)
        }

        assertEquals("Playback progress backward count must be 0", 0, diagnostic.playbackProgressBackwardCount)
        assertEquals("Episode index backward count must be 0", 0, diagnostic.episodeIndexBackwardCount)
        assertEquals("Completed episode reactivation count must be 0", 0, diagnostic.completedEpisodeReactivationCount)
        assertEquals("Spatial jump count must be 0", 0, diagnostic.spatialJumpCount)
    }

    @Test
    fun testMultipleIntermediateVisits_splitsMovementSequentially() {
        // Movement: 10:00 to 12:00 (A -> D, 7200 seconds)
        val driveAtoD = MovementSegment(
            id = "drive_long_corridor",
            startTimestampEpochMs = 1720000000000L, // 10:00:00
            endTimestampEpochMs = 1720007200000L,   // 12:00:00
            startPoint = pointA,
            endPoint = pointD,
            simplifiedPoints = listOf(pointA, pointC, pointB, pointD),
            distanceMeters = 60_000.0,
            durationMillis = 7200000L,
            transport = TransportPrediction(TransportMode.CAR, 0.95f, "CorridorDrive")
        )

        // Visit 1: 10:20 to 10:30 at C
        val visit1 = Visit(
            id = "v1_seven_magic",
            placeName = "Stop 1",
            location = pointC,
            startTimestampEpochMs = 1720001200000L, // 10:20:00
            endTimestampEpochMs = 1720001800000L,   // 10:30:00
            confidence = 0.95f
        )

        // Visit 2: 11:10 to 11:30 at B
        val visit2 = Visit(
            id = "v2_vegas_strip",
            placeName = "Stop 2",
            location = pointB,
            startTimestampEpochMs = 1720004200000L, // 11:10:00
            endTimestampEpochMs = 1720005400000L,   // 11:30:00
            confidence = 0.95f
        )

        val renderModel = TravelMapRenderModel(
            visits = listOf(visit1, visit2),
            segments = listOf(driveAtoD),
            photos = emptyList()
        )

        val timeline = TravelStoryTimeline.build(renderModel, StoryDurationProfile.FULL_STORY)

        assertEquals(0, timeline.diagnostics.unresolvedCrossTypeOverlapCount)
        assertEquals(0, timeline.diagnostics.backwardEpisodeChronologyCount)
        assertEquals(0, timeline.diagnostics.overlapGeneratedBridgeCount)

        // Expected sequence: Move A->C -> Visit 1 -> Move C->B -> Visit 2 -> Move B->D
        assertEquals(5, timeline.episodes.size)
        assertTrue("Ep 0 is Move", timeline.episodes[0] is StoryEpisode.MovementEpisode)
        assertTrue("Ep 1 is Visit 1", timeline.episodes[1] is StoryEpisode.VisitEpisode)
        assertTrue("Ep 2 is Move", timeline.episodes[2] is StoryEpisode.MovementEpisode)
        assertTrue("Ep 3 is Visit 2", timeline.episodes[3] is StoryEpisode.VisitEpisode)
        assertTrue("Ep 4 is Move", timeline.episodes[4] is StoryEpisode.MovementEpisode)
    }

    @Test
    fun testLegitimateGapContinuity_preservedWithoutRegression() {
        // Movement: 09:00 to 10:00 (A -> B)
        val driveAtoB = MovementSegment(
            id = "drive_part1",
            startTimestampEpochMs = 1720000000000L, // 09:00:00
            endTimestampEpochMs = 1720003600000L,   // 10:00:00
            startPoint = pointA,
            endPoint = pointB,
            simplifiedPoints = listOf(pointA, pointB),
            distanceMeters = 40_000.0,
            durationMillis = 3600000L,
            transport = TransportPrediction(TransportMode.CAR, 0.9f, "LegitDrive")
        )

        // Legitimate gap: 10:00 to 10:10 (no movement recorded, 10 min forward gap)
        // Visit: 10:10 to 11:00 at D (~25 km away from B)
        val visitAtD = Visit(
            id = "visit_at_d",
            placeName = "Forward Stop",
            location = pointD,
            startTimestampEpochMs = 1720004200000L, // 10:10:00
            endTimestampEpochMs = 1720007200000L,   // 11:00:00
            confidence = 0.9f
        )

        val renderModel = TravelMapRenderModel(
            visits = listOf(visitAtD),
            segments = listOf(driveAtoB),
            photos = emptyList()
        )

        val timeline = TravelStoryTimeline.build(renderModel, StoryDurationProfile.STANDARD)

        assertEquals(0, timeline.diagnostics.unresolvedCrossTypeOverlapCount)
        assertEquals(0, timeline.diagnostics.backwardEpisodeChronologyCount)
        assertEquals(0, timeline.diagnostics.overlapGeneratedBridgeCount)

        // A forward time gap is not evidence of a drivable straight road.
        assertEquals(3, timeline.episodes.size)
        assertTrue(timeline.episodes[0] is StoryEpisode.MovementEpisode)
        val connection=timeline.episodes[1] as StoryEpisode.MovementEpisode
        assertEquals(GeometryProvenance.CONTINUITY_ESTIMATE, connection.segment.geometryProvenance)
        assertEquals(TransportMode.UNKNOWN, connection.segment.effectiveMode)
        assertTrue(timeline.episodes[2] is StoryEpisode.VisitEpisode)

    }
}
