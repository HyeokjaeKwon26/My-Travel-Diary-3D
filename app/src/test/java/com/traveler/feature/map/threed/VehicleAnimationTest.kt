package com.traveler.feature.map.threed

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.*
import com.traveler.feature.map.renderer.TravelMapRenderModel
import com.traveler.feature.map.story.TravelStoryTimeline
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.*

class VehicleAnimationTest {
    private fun scene(points:List<GeoPoint>,mode:TransportMode):SceneGeometry {
        val base=CanyonDemo.trip().days.flatMap { it.items }.filterIsInstance<TripDayItem.MovementItem>().first().segment
        val movement=base.copy(startPoint=points.first(),endPoint=points.last(),simplifiedPoints=points,
            transport=TransportPrediction(mode,1f,"Fixture"))
        val model=TravelMapRenderModel(emptyList(),listOf(movement))
        return SceneGeometry(model,TravelStoryTimeline.build(model),emptyList())
    }

    @Test fun sparseContinentalAndDatelineFlightsStayAboveEarthAndFaceTheirPath() {
        for(points in listOf(listOf(GeoPoint(42.36,-71.06),GeoPoint(36.17,-115.14)),
            listOf(GeoPoint(40.0,170.0,9500.0),GeoPoint(40.0,-160.0,9500.0)))) {
            val scene=scene(points,TransportMode.AIRPLANE)
            assertTrue(scene.routes.single().xyz.size>30)
            var checked=0
            for(i in 5..95) {
                val state=scene.timeline.evaluate(i/100f)
                if(state.currentSegment==null) continue
                val motion=scene.motion(state.copy(currentHeadingDegrees=0f))
                assertTrue("Flight entered globe",motion.position.length()>1.0)
                assertEquals(0.0,motion.forward.dot(motion.position.unit()),1e-8)
                val future=scene.motion(state.copy(storyTimeMs=state.storyTimeMs+1000)).position
                if((future-motion.position).length()>1e-10)
                    assertTrue("Nose points away from motion",motion.forward.dot(future-motion.position)>0)
                scene.motion(scene.timeline.evaluate(.9f))
                assertEquals("Seeking must not retain a previous heading",motion,scene.motion(state.copy(currentHeadingDegrees=0f)))
                checked++
            }
            assertTrue(checked>30)
        }
    }

    @Test fun uphillAndDownhillPitchFollowRealElevationAndAreExaggerated() {
        for(sign in listOf(-1,1)) {
            val scene=scene(listOf(GeoPoint(36.0,-112.0,1800.0),GeoPoint(36.03,-112.0,1800.0+sign*500)),TransportMode.CAR)
            val state=(20..80).map { scene.timeline.evaluate(it/100f) }.first { it.currentSegment!=null }
            val slope=scene.motion(state).slope
            assertTrue(sign*slope>.05)
            val pose=VehicleAnimation.pose(TransportMode.CAR,0.0,slope,0.0)
            assertTrue(sign*pose.pitch>sign*slope*2)
        }
    }

    @Test fun bankLowersTheInsideWing() {
        // Around the outward Earth normal, a clockwise/right turn is negative.
        val up=EarthGeometry.position(GeoPoint(0.0,0.0)).unit()
        val north=EarthGeometry.forward(GeoPoint(0.0,0.0),0.0)
        val east=EarthGeometry.forward(GeoPoint(0.0,0.0),90.0)
        val turn=atan2(up.dot(north.cross(east)),north.dot(east))
        val pose=VehicleAnimation.pose(TransportMode.AIRPLANE,0.0,0.0,turn)
        val rolledRight=east*cos(pose.roll)+up*sin(pose.roll)
        assertTrue("Right turn must lower the right wing",rolledRight.dot(up)<0)
    }

    @Test fun allModesRemainBoundedVisibleAndDeterministic() {
        for(mode in TransportMode.entries) {
            val mesh=ToyVehicle.mesh(mode,1.2)
            assertEquals(0,mesh.size%21)
            assertTrue(mesh.size/7 in 100..16384)
            assertTrue(mesh.all { it.isFinite() })
            for(i in mesh.indices step 7) assertTrue((0..2).all { abs(mesh[i+it])<4 })
            for(distance in listOf(.0008,.006,.04,2.8)) {
                val scale=VehicleAnimation.scale(distance,720,480,mode)
                val projectedUnit=scale/(distance*sqrt(1.13))*480/(2*tan(Math.toRadians(21.0)))
                assertTrue(projectedUnit in 20.0..60.0)
            }
            val a=VehicleAnimation.pose(mode,1.2,.1,.2)
            VehicleAnimation.pose(mode,10.0,-.1,-.2)
            assertEquals(a,VehicleAnimation.pose(mode,1.2,.1,.2))
            assertNotEquals(a,VehicleAnimation.pose(mode,1.4,.1,.2))
            assertEquals(0.0,VehicleAnimation.pose(mode,20.0,0.0,0.0,false).bounce,0.0)
        }
    }
}
