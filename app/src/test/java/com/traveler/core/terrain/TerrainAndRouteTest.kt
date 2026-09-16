package com.traveler.core.terrain

import com.traveler.core.common.geo.GeoPoint
import com.traveler.feature.map.threed.EarthGeometry
import com.traveler.feature.video.ExportPrivacy
import org.junit.Assert.*
import org.junit.Test

class TerrainAndRouteTest {
    private fun grid() = TerrainPack(name="Test",attribution="Fixture",verticalDatum="Fixture",
        north=1.0,south=0.0,west=0.0,east=1.0,rows=2,columns=2,heights=listOf(100.0,200.0,300.0,400.0)).validate()

    @Test fun interpolationAndCoverageDoNotInventOutsideHeights() {
        val p=grid()
        assertEquals(250.0,p.elevation(GeoPoint(.5,.5))!!,.001)
        assertEquals(400.0,p.elevation(GeoPoint(0.0,1.0))!!,.001)
        assertNull(p.elevation(GeoPoint(2.0,.5)))
        assertNull(p.copy(heights=listOf(null,200.0,300.0,400.0)).elevation(GeoPoint(.5,.5)))
    }
    @Test fun isolatedAltitudeErrorIsRemovedButRealClimbSurvives() {
        val spike=listOf(GeoPoint(36.0,-112.0,2000.0),GeoPoint(36.0001,-112.0,700.0),GeoPoint(36.0002,-112.0,2010.0))
        assertNull(SharedRouteGeometry.rejectAltitudeImpulses(spike)[1].altitudeMeters)
        val climb=spike.mapIndexed { i,p -> p.copy(altitudeMeters=1000.0+i*100) }
        assertEquals(climb,SharedRouteGeometry.rejectAltitudeImpulses(climb))
    }
    @Test fun drapedElevationMatchesTriangleRatherThanBilinearSurface() {
        val p=grid().copy(heights=listOf(100.0,200.0,300.0,1000.0))
        assertEquals(250.0,p.meshElevation(GeoPoint(.5,.5))!!,.001)
        assertEquals(625.0,p.meshElevation(GeoPoint(.25,.75))!!,.001)
        assertNull(p.meshElevation(GeoPoint(2.0,.5)))
    }
    @Test fun malformedOrOversizedGridIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { grid().copy(rows=100000).validate() }
        assertThrows(IllegalArgumentException::class.java) { grid().copy(heights=listOf(Double.NaN,1.0,2.0,3.0)).validate() }
        assertThrows(IllegalArgumentException::class.java) { grid().copy(west=Double.NEGATIVE_INFINITY).validate() }
    }
    @Test fun antimeridianRemainsContinuousIn3D() {
        val a=EarthGeometry.position(GeoPoint(40.0,179.999))
        val b=EarthGeometry.position(GeoPoint(40.0,-179.999))
        assertTrue((a-b).length()*EarthGeometry.R < 200)
        assertEquals(1000.0,(EarthGeometry.position(GeoPoint(40.0,20.0),1000.0).length()-1)*EarthGeometry.R,.001)
    }
    @Test fun exportRedactsAddressesWithoutMutatingNames() {
        assertEquals("Private place",ExportPrivacy.label("123 Main Street"))
        assertEquals("Private place",ExportPrivacy.label("Home"))
        assertEquals("Grand Canyon",ExportPrivacy.label("Grand Canyon"))
    }
    @Test fun sceneVehicleStaysOnTheRenderedRouteAndPreservesMountainProfile() {
        val trip=com.traveler.feature.map.threed.CanyonDemo.trip()
        val segment=trip.days.flatMap { it.items }.filterIsInstance<com.traveler.core.model.TripDayItem.MovementItem>().first().segment
        val points=listOf(GeoPoint(36.0,-112.0,1500.0),GeoPoint(36.01,-112.0,1800.0),GeoPoint(36.02,-112.0,2100.0),GeoPoint(36.03,-112.0,1700.0))
        val movement=segment.copy(startPoint=points.first(),endPoint=points.last(),simplifiedPoints=points)
        val model=com.traveler.feature.map.renderer.TravelMapRenderModel(emptyList(),listOf(movement))
        val timeline=com.traveler.feature.map.story.TravelStoryTimeline.build(model)
        val scene=com.traveler.feature.map.threed.SceneGeometry(model,timeline,emptyList())
        val episode=timeline.episodes.filterIsInstance<com.traveler.feature.map.story.StoryEpisode.MovementEpisode>().single()
        assertEquals(points.map { it.altitudeMeters },episode.pathPoints.map { it.altitudeMeters })
        for (i in 10..90) {
            val state=timeline.evaluate(i/100f)
            if(state.currentSegment==null) continue
            val position=scene.routePosition(state)!!
            val nearest=scene.routes.single().xyz.zipWithNext().minOf { (a,b) ->
                val d=b-a;val q=position-a
                val f=((q.x*d.x+q.y*d.y+q.z*d.z)/(d.length()*d.length())).coerceIn(0.0,1.0)
                (position-(a+d*f)).length()*EarthGeometry.R
            }
            assertTrue("Vehicle left rendered path by $nearest m",nearest<.01)
            assertFalse(scene.uncertain(state))
        }
    }

    @Test fun observedFlightAltitudeIsNotReplacedByGroundElevation() {
        val trip=com.traveler.feature.map.threed.CanyonDemo.trip()
        val base=trip.days.flatMap { it.items }.filterIsInstance<com.traveler.core.model.TripDayItem.MovementItem>().first().segment
        val points=listOf(GeoPoint(.5,.2,9000.0),GeoPoint(.5,.5,9500.0),GeoPoint(.5,.8,10000.0))
        val flight=base.copy(startPoint=points.first(),endPoint=points.last(),simplifiedPoints=points,
            transport=com.traveler.core.model.TransportPrediction(com.traveler.core.model.TransportMode.AIRPLANE,1f,"Test"))
        val model=com.traveler.feature.map.renderer.TravelMapRenderModel(emptyList(),listOf(flight))
        val timeline=com.traveler.feature.map.story.TravelStoryTimeline.build(model)
        val scene=com.traveler.feature.map.threed.SceneGeometry(model,timeline,listOf(grid()))
        val route=scene.routes.single()
        val midpoint=route.points.indexOfFirst { kotlin.math.abs(it.longitude-.5)<1e-8 }
        assertTrue(midpoint>=0)
        assertEquals(9512.0,(route.xyz[midpoint].length()-1)*EarthGeometry.R,.01)
    }

    @Test fun cliffDiscontinuityIsFlaggedInsteadOfAnimatingVehicleFall() {
        val trip=com.traveler.feature.map.threed.CanyonDemo.trip()
        val base=trip.days.flatMap { it.items }.filterIsInstance<com.traveler.core.model.TripDayItem.MovementItem>().first().segment
        val p=listOf(GeoPoint(36.0,-112.0,2000.0),GeoPoint(36.0001,-112.0,700.0))
        val m=base.copy(startPoint=p.first(),endPoint=p.last(),simplifiedPoints=p)
        val model=com.traveler.feature.map.renderer.TravelMapRenderModel(emptyList(),listOf(m))
        val timeline=com.traveler.feature.map.story.TravelStoryTimeline.build(model)
        val scene=com.traveler.feature.map.threed.SceneGeometry(model,timeline,emptyList())
        assertTrue(scene.routes.single().uncertainEdges.single())
        assertTrue((1..99).map { timeline.evaluate(it/100f) }.any { scene.uncertain(it) })
    }

}
