package com.traveler.core.terrain

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.common.geo.GeodesicUtils
import com.traveler.core.model.*
import org.junit.Assert.*
import org.junit.Test

class DisplayFlightPathTest {
    private val points=listOf(GeoPoint(45.0,-125.0),GeoPoint(45.0,-70.0),GeoPoint(40.0,-10.0))
    private fun segment(mode:TransportMode)=MovementSegment("sparse-flight",0,3_600_000,points.first(),points.last(),
        simplifiedPoints=points,distanceMeters=GeodesicUtils.pathDistanceMeters(points),durationMillis=3_600_000,
        transport=TransportPrediction(mode,1f,"test"))

    @Test fun sparseRecordedFlightStrokeFollowsMarkerArcBetweenEveryRecordedPoint() {
        val original=segment(TransportMode.AIRPLANE)
        val displayed=SharedRouteGeometry.displayPath(original)
        assertTrue(points.all { it in displayed })
        assertTrue(displayed.zipWithNext().all { (a,b)-> GeodesicUtils.distanceMeters(a,b)<=20_001 })
        val midpoint=GeodesicUtils.interpolate(points[0],points[1],.5)
        assertTrue("Long leg should curve north of the projected chord",midpoint.latitude>48)
        assertTrue(displayed.minOf { GeodesicUtils.distanceMeters(it,midpoint) }<10_001)
        assertEquals(points,original.simplifiedPoints)
    }

    @Test fun groundDisplayDoesNotInventAdditionalRecordedGeometry() {
        assertEquals(points,SharedRouteGeometry.displayPath(segment(TransportMode.CAR)))
    }
}
