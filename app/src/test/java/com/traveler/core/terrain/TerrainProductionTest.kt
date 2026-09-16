package com.traveler.core.terrain

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.*
import com.traveler.feature.map.renderer.TravelMapRenderModel
import com.traveler.feature.map.story.TravelStoryTimeline
import com.traveler.feature.map.threed.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TerrainProductionTest {
    @get:Rule val temporary=TemporaryFolder()
    private fun model(points:List<GeoPoint>,mode:TransportMode=TransportMode.CAR):TravelMapRenderModel {
        val base=CanyonDemo.trip().days.flatMap { it.items }.filterIsInstance<TripDayItem.MovementItem>().first().segment
        return TravelMapRenderModel(emptyList(),listOf(base.copy(startPoint=points.first(),endPoint=points.last(),
            simplifiedPoints=points,transport=TransportPrediction(mode,1f,"Fixture"))))
    }
    @Test fun datelineCorridorWrapsWithoutDownloadingAcrossTheWorld() {
        val p=TerrainTilePlanner.plan(model(listOf(GeoPoint(20.0,179.99),GeoPoint(20.0,-179.99))))
        assertTrue(p.tiles.size in 6..20)
        assertTrue(p.tiles.all { it.x<3 || it.x>4092 })
        assertEquals(p,TerrainTilePlanner.plan(model(listOf(GeoPoint(20.0,179.99),GeoPoint(20.0,-179.99)))))
    }
    @Test fun flightsDoNotDownloadCorridorsAndLongRoadsStayBounded() {
        val path=listOf(GeoPoint(35.0,-120.0),GeoPoint(40.0,-74.0))
        assertTrue(TerrainTilePlanner.plan(model(path,TransportMode.AIRPLANE)).tiles.isEmpty())
        val road=TerrainTilePlanner.plan(model(path))
        assertTrue(road.limited)
        assertTrue(road.tiles.size<=TerrainTilePlanner.MAX_TILES)
    }
    @Test fun tileRoundTripPreservesNoDataAndCorruptionIsDiscarded() {
        val store=TerrainTileStore(temporary.newFolder())
        val id=TerrainTileId(12,500,500)
        val heights=List(65*65) { if(it==0) null else (it%2000-100).toDouble() }
        store.write(id,heights)
        assertEquals(heights,store.read(id)!!.heights)
        val bytes=store.file(id).readBytes();bytes[bytes.size/2]=(bytes[bytes.size/2].toInt() xor 1).toByte()
        store.file(id).writeBytes(bytes)
        assertNull(store.read(id));assertFalse(store.file(id).exists())
    }
    @Test fun cacheEvictsOldestAndNeverEvictsPinnedTiles() {
        val store=TerrainTileStore(temporary.newFolder())
        val a=TerrainTileId(12,500,500);val b=TerrainTileId(12,501,500)
        store.write(a,List(4225) { 100.0 });store.write(b,List(4225) { 200.0 })
        store.file(a).setLastModified(1)
        val keep=store.file(b).length()
        store.trim(keep,setOf(b.key))
        assertFalse(store.file(a).exists());assertTrue(store.file(b).exists())
        assertThrows(IllegalArgumentException::class.java) { store.trim(0,setOf(b.key)) }
        assertTrue(store.file(b).exists())
    }
    @Test fun adjacentEdgesAgreeAndMissingHeightsRemainMissing() {
        val store=TerrainTileStore(temporary.newFolder())
        val a=TerrainTileId(12,500,500);val b=TerrainTileId(12,501,500)
        store.write(a,List(4225) { 100.0 });store.write(b,List(4225) { if(it==0) null else 200.0 })
        val grids=TerrainSeams.stitch(listOf(store.read(a)!!,store.read(b)!!))
        assertEquals(150.0,grids[0].heights[65+64]!!,.001)
        assertEquals(grids[0].heights[65+64],grids[1].heights[65])
        assertNull(grids[1].heights[0])
    }
    @Test fun demBoundaryDoesNotDropToSeaLevelOrMixGpsDatum() {
        val p=TerrainPack(name="Fixture",attribution="Fixture",verticalDatum="Fixture",north=1.0,south=0.0,
            west=0.0,east=1.0,rows=2,columns=2,heights=List(4) { 1500.0 })
        val m=model(listOf(GeoPoint(.5,.999,700.0),GeoPoint(.5,1.002,700.0)))
        val scene=SceneGeometry(m,TravelStoryTimeline.build(m),listOf(p))
        assertTrue(scene.routes.single().uncertainEdges.any { it })
        assertTrue(scene.routes.single().xyz.all { kotlin.math.abs((it.length()-1)*EarthGeometry.R-1512)<.001 })
    }
    @Test fun qualityUsesHysteresisAndThermalLimit() {
        val q=RenderQuality()
        repeat(19) { assertFalse(q.observe(35.0,false)) }
        assertTrue(q.observe(35.0,false));assertEquals(.85f,q.scale,.001f)
        repeat(299) { assertFalse(q.observe(10.0,false)) }
        assertTrue(q.observe(10.0,false));assertEquals(.95f,q.scale,.001f)
        assertTrue(q.observe(10.0,true));assertEquals(.5f,q.scale,.001f)
        repeat(50) { q.observe(40.0,false) };assertEquals(.5f,q.scale,.001f)
    }
    @Test fun finalDecimatedMeshCellMatchesRenderedTriangle() {
        val p=TerrainPack(name="Fixture",attribution="Fixture",verticalDatum="Fixture",north=1.0,south=0.0,
            west=0.0,east=1.0,rows=257,columns=257,heights=List(257*257) { i ->
                if(i%257==255) 9000.0 else (i%257).toDouble()
            })
        // Column 255 is skipped by the renderer; the final cell runs 254 -> 256.
        assertEquals(256.0,p.meshElevation(GeoPoint(0.0,1.0))!!,.001)
        assertEquals(255.5,p.meshElevation(GeoPoint(.5,255.5/256))!!,.001)
        val index=TerrainSpatialIndex(listOf(p))
        assertEquals(p.meshElevation(GeoPoint(.5,.999)),index.elevation(GeoPoint(.5,.999)))
        assertNull(index.elevation(GeoPoint(2.0,2.0)))
    }
    @Test fun archiveRejectsUnboundedDatesAndRemotePhotoReferences() {
        val trip=CanyonDemo.trip()
        TripArchive.validate(trip)
        assertThrows(IllegalArgumentException::class.java) { TripArchive.validate(trip.copy(endDateIso="2299-12-31")) }
        val photo=MediaItem("p","https://example.com/photo.jpg","photo.jpg","image/jpeg",0)
        assertThrows(IllegalArgumentException::class.java) { TripArchive.validate(trip.copy(uncertainDateMedia=listOf(photo))) }
    }
}
