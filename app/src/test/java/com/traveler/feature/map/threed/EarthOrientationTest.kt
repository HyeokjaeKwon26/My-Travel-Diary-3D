package com.traveler.feature.map.threed

import com.traveler.core.common.geo.GeoPoint
import org.junit.Assert.assertTrue
import org.junit.Test

class EarthOrientationTest {
    @Test fun increasingLongitudeIsScreenRightWhenLookingNorth() {
        for(p in listOf(GeoPoint(0.0,0.0),GeoPoint(37.79,-122.43),GeoPoint(-33.8,151.2),GeoPoint(50.0,179.99))) {
            val up=EarthGeometry.position(p).unit()
            val screenRight=EarthGeometry.north(p).cross(up).unit()
            val geographicEast=(EarthGeometry.position(p.copy(longitude=p.longitude+.0001))-
                EarthGeometry.position(p)).unit()
            assertTrue("East/west mirror at $p",(screenRight-geographicEast).length()<.00001)
            assertTrue("Heading 90 must travel east",(EarthGeometry.forward(p,90.0)-geographicEast).length()<.00001)
        }
    }
}
