package com.traveler.feature.map.threed

import com.traveler.core.model.TransportMode
import org.junit.Assert.*
import org.junit.Test

class StreetTilePlanTest {
    @Test fun onlyVisibleTilesAreRequestedWithBoundedCountAndWrappedLongitude() {
        for(f in listOf(MapFootprint(.16,.3,.16008,.30006),MapFootprint(.9999,.4,1.0001,.4002),MapFootprint(-.1,0.0,.9,1.0))) {
            val plan=StreetTilePlan.visible(f,1080,720)
            assertTrue(plan.tiles.size in 1..24)
            val n=1 shl plan.zoom
            assertTrue(plan.tiles.all { it.x in 0 until n && it.y in 0 until n })
            assertEquals(plan.tiles.size,plan.tiles.distinct().size)
            assertTrue(plan.tiles.all { it.z==plan.zoom })
        }
        val wrapped=StreetTilePlan.visible(MapFootprint(.999999,.4,1.000001,.400001),1080,720)
        assertTrue(wrapped.tiles.any { it.x==0 })
        assertTrue(wrapped.tiles.any { it.x==(1 shl wrapped.zoom)-1 })
    }
    @Test fun readableDetailUsesAvailableTileBudget() {
        val f=MapFootprint(.5,.5,.501,.5006)
        val plan=StreetTilePlan.visible(f,720,432)
        val pixelsAcross=(f.right-f.left)*(1 shl plan.zoom)*256
        assertTrue("Road labels should not be magnified from an unnecessarily low zoom", pixelsAcross>=720)
        assertTrue(plan.tiles.size<=24)
    }
    @Test fun groundZoomIsStableRegardlessOfOldArrivalCameraSpan() {
        for(mode in TransportMode.entries.filter { it!=TransportMode.AIRPLANE }) {
            assertEquals(NorthUpCamera.distance(mode,.005),NorthUpCamera.distance(mode,20.0),0.0)
            assertTrue(NorthUpCamera.distance(mode)*EarthGeometry.R<4000)
        }
        assertTrue(NorthUpCamera.distance(TransportMode.AIRPLANE)>NorthUpCamera.distance(TransportMode.CAR))
    }
}
