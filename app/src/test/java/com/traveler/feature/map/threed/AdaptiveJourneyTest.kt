package com.traveler.feature.map.threed

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.*
import com.traveler.core.media.StoryDurationProfile
import com.traveler.feature.map.renderer.TravelMapRenderModel
import com.traveler.feature.map.renderer.PlaybackTimeTracker
import com.traveler.feature.map.renderer.MonotonicClock
import com.traveler.feature.map.story.TravelStoryTimeline
import com.traveler.feature.video.VideoResolution
import org.junit.Assert.*
import org.junit.Test

class AdaptiveJourneyTest {
    private fun scene(): SceneGeometry {
        val path=listOf(GeoPoint(36.0,-112.0,1800.0),GeoPoint(36.01,-112.02),GeoPoint(36.02,-112.01,2000.0))
        val segment=MovementSegment("road",0,600_000,path.first(),path.last(),simplifiedPoints=path,
            distanceMeters=4000.0,durationMillis=600_000,transport=TransportPrediction(TransportMode.CAR,1f,"test"))
        val model=TravelMapRenderModel(emptyList(),listOf(segment))
        return SceneGeometry(model,TravelStoryTimeline.build(model,StoryDurationProfile.STANDARD),emptyList())
    }
    @Test fun cameraUsesRouteAndAspectAndNeverDependsOnSeekOrder() {
        val s=scene();val state=s.timeline.evaluate(.5f)
        val portrait=s.camera.frame(state,480,800)
        val landscape=s.camera.frame(state,800,480)
        assertTrue(portrait.distance>landscape.distance)
        assertTrue(landscape.distance>NorthUpCamera.distance(TransportMode.CAR)*2)
        s.camera.frame(s.timeline.evaluate(.9f),480,800)
        assertEquals(portrait,s.camera.frame(state,480,800))
        assertEquals(portrait,s.camera.frame(state,960,1600))
    }
    @Test fun missingAltitudeKeepsMarkerAboveEarthAndContinuous() {
        val s=scene()
        val positions=(0..100).map { s.motion(s.timeline.evaluate(it/100f)).position }
        assertTrue(positions.all { it.length()>1 })
        assertTrue(positions.zipWithNext().all { (a,b) -> (a-b).length()*EarthGeometry.R<150 })
    }
    @Test fun userPausePreservesPlaybackTime() {
        var nanos=0L
        val clock=PlaybackTimeTracker(60f,MonotonicClock { nanos })
        clock.start();nanos=1_000_000_000;clock.update();clock.pause()
        val before=clock.progress
        nanos+=8_000_000_000;assertEquals(before,clock.update(),0f)
        clock.resume();nanos+=1_000_000_000;assertEquals(2f/60,clock.update(),.0001f)
    }
    @Test fun outputOrientationDoesNotChangeQualityAndCanRoundTrip() {
        assertEquals(VideoResolution.LANDSCAPE_FULL_HD,VideoResolution.FULL_HD.oriented(true))
        assertEquals(VideoResolution.HD,VideoResolution.LANDSCAPE_HD.oriented(false))
        for(r in VideoResolution.entries) assertEquals(r,r.oriented(!r.landscape).oriented(r.landscape))
    }
}
