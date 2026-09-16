package com.traveler.core.terrain

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.Visit
import com.traveler.feature.map.renderer.TravelMapRenderModel
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream

@RunWith(AndroidJUnit4::class)
class TerrainCacheAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun backupRestoreKeepsOriginalTripAndAltitude()=runBlocking {
        val repo=com.traveler.data.repository.TripRepositoryImpl(com.traveler.core.database.TravelerDatabase.getDatabase(context))
        val trip=com.traveler.feature.map.threed.CanyonDemo.trip().copy(id=java.util.UUID.randomUUID().toString())
        val file=java.io.File(context.cacheDir,"archive-test.travel3d.json")
        var restoredId:String?=null
        try {
            repo.saveTrip(trip)
            TripArchive.export(context,trip,android.net.Uri.fromFile(file))
            restoredId=TripArchive.restore(context,android.net.Uri.fromFile(file))
            assertNotEquals(trip.id,restoredId)
            assertNotNull(repo.getTripById(trip.id))
            val restored=repo.getTripById(restoredId)!!
            assertEquals(trip.title,restored.title)
            val a=trip.days.flatMap { it.items }.filterIsInstance<com.traveler.core.model.TripDayItem.MovementItem>().first().segment
            val b=restored.days.flatMap { it.items }.filterIsInstance<com.traveler.core.model.TripDayItem.MovementItem>().first().segment
            assertEquals(a.simplifiedPoints,b.simplifiedPoints)
        } finally { file.delete();repo.deleteTrip(trip.id);restoredId?.let { repo.deleteTrip(it) } }
    }
    @Test fun terrariumDecodePreservesMetresAndRejectsInvalidImages() {
        val bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(0xff85dc00.toInt()) // 1500 m = (133*256+220)-32768
        val out=ByteArrayOutputStream();bitmap.compress(Bitmap.CompressFormat.PNG,100,out);bitmap.recycle()
        val heights=TerrainDownloadWorker.decode(TerrainTileId(12,700,1600),out.toByteArray())
        assertEquals(4225,heights.size);assertTrue(heights.all { it==1500.0 })
        assertThrows(IllegalArgumentException::class.java) { TerrainDownloadWorker.decode(TerrainTileId(12,700,1600),byteArrayOf(1,2,3)) }
    }
    /** Provider smoke test uses a synthetic public canyon coordinate, never a personal journey. */
    @Test fun automaticDownloadSurvivesPauseAndPinnedCacheWorksWithoutNetwork()=runBlocking {
        val model=TravelMapRenderModel(listOf(Visit(id="terrain-fixture",location=GeoPoint(36.07,-112.12),
            startTimestampEpochMs=0,endTimestampEpochMs=1000,confidence=1f)),emptyList())
        val plan=JourneyTerrain.prepare(context,model)
        JourneyTerrain.pause(context,plan.key)
        assertTrue(JourneyTerrain.readPlan(context,plan.key)!!.paused)
        JourneyTerrain.resume(context,plan.key,true)
        withTimeout(180_000) {
            while(true) {
                val status=JourneyTerrain.status(context,plan)
                if(status.ready==plan.tiles.size) break
                check(!status.message.contains("failed",ignoreCase=true)) { status.message }
                delay(1000)
            }
        }
        JourneyTerrain.pin(context,plan.key,true)
        JourneyTerrain.pause(context,plan.key)
        // Stop all network work; disk-only load and eviction must preserve pinned regions.
        JourneyTerrain.clearTemporary(context)
        val grids=JourneyTerrain.load(context,plan.key)
        assertEquals(plan.tiles.size,grids.size)
        assertTrue(grids.flatMap { it.heights }.filterNotNull().let { it.max()-it.min() }>500)
        JourneyTerrain.pin(context,plan.key,false)
        JourneyTerrain.clearTemporary(context)
        assertEquals(0,JourneyTerrain.status(context,plan).ready)
    }
}
