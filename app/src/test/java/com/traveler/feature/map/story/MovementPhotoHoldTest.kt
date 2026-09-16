package com.traveler.feature.map.story

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.*
import com.traveler.feature.map.renderer.TravelMapRenderModel
import com.traveler.feature.map.threed.SceneGeometry
import org.junit.Assert.*
import org.junit.Test

class MovementPhotoHoldTest {
    @Test fun photosHoldRouteCameraDistanceAndAnimationThenResumeWithoutJumping() {
        val start = 1_780_000_000_000L
        val segment = MovementSegment("car", start, start+3_600_000,
            GeoPoint(42.0,-71.0),GeoPoint(42.2,-71.2),distanceMeters=30_000.0,durationMillis=3_600_000,
            transport=TransportPrediction(TransportMode.CAR,1f,"fixture"))
        val photos = listOf(.25, .75).mapIndexed { i,f -> MediaItem("p$i", "content://fixture/$i", "$i.jpg", "image/jpeg",
            timestampEpochMs=start+(3_600_000*f).toLong(), timestampConfidence=TimestampConfidence.EXIF_EXACT,
            location=GeoPoint(42.1,-71.1), locationConfidence=LocationConfidenceLevel.GPS_EXACT,
            matchedSegmentId=segment.id,isRepresentative=true,confidenceScore=1f) }
        val model = TravelMapRenderModel(emptyList(),listOf(segment),photos)
        val timeline = TravelStoryTimeline.build(model, titleCard=StoryTitleCard("Trip", "2026"),endCard=StoryEndCard("Trip","1 Day","30 km","2 Memories"))
        assertEquals(2,timeline.photoMoments.size)
        val scene = SceneGeometry(model,timeline,emptyList())
        for (moment in timeline.photoMoments) {
            val a=timeline.evaluateAtStoryTime(moment.displayStartStorySeconds+.05f)
            val b=timeline.evaluateAtStoryTime(moment.displayEndStorySeconds-.05f)
            assertEquals(a.currentPosition,b.currentPosition)
            assertEquals(a.cameraCenter,b.cameraCenter)
            assertEquals(a.storyTimeMs,b.storyTimeMs)
            assertEquals(a.animationTimeSeconds,b.animationTimeSeconds)
            assertEquals(a.currentTraveledDistanceMeters,b.currentTraveledDistanceMeters,0.0)
            assertEquals(scene.routePosition(a),scene.routePosition(b))
            assertEquals(0.0,a.currentSpeedKmh,0.0)
            assertEquals(moment.mediaId,b.activePhoto?.id)
            val resume=timeline.evaluateAtStoryTime(moment.displayEndStorySeconds+.001f)
            assertTrue(resume.currentTraveledDistanceMeters>=a.currentTraveledDistanceMeters)
            assertTrue(resume.currentTraveledDistanceMeters-a.currentTraveledDistanceMeters<20)
            assertNull(resume.activePhoto)
            assertEquals(a,timeline.evaluateAtStoryTime(moment.displayStartStorySeconds+.05f)) // random access / backward seek
        }
        var previous=0.0
        for(i in 0..4000) {
            val state=timeline.evaluate(i/4000f)
            assertTrue(state.currentTraveledDistanceMeters+0.01>=previous)
            previous=state.currentTraveledDistanceMeters
        }
        assertEquals(30_000.0,previous,0.01)
        assertEquals(0,timeline.validateInvariants().outOfBoundsPhotoWindowCount)
    }
}
