package com.traveler.feature.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.TransportMode
import com.traveler.feature.map.renderer.*
import com.traveler.feature.map.threed.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.FloatBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

@RunWith(AndroidJUnit4::class)
class MapPlaybackRegressionTest {
    @Test fun twoDAndThreeDShareTheSameRegionalCoordinateArrays() = kotlinx.coroutines.runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val threeD=BundledBasemapCache.load(context).first
        val twoD=RegionalBasemapCache.ensureLoaded(context)!!
        assertTrue(twoD.totalPoints>1000)
        assertSame("2D must not decode and retain another regional map",threeD,twoD)
        assertSame(threeD.polygons.first().rings.first().worldCoords,twoD.polygons.first().rings.first().worldCoords)
    }

    @Test fun viewportChangesAndDisablingMapsDoNotWaitForSocketCancellation() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val root=File(context.cacheDir,"slow-cancel-${System.nanoTime()}").apply { mkdirs() }
        val entered=CountDownLatch(1);val unblock=CountDownLatch(1);val stopped=CountDownLatch(1)
        val session=StreetMapSession(context,true,root,connectionFactory={
            object:HttpURLConnection(URL("https://fixture.invalid/tile")) {
                override fun connect() {}
                override fun usingProxy()=false
                override fun getResponseCode():Int { entered.countDown();unblock.await(5,TimeUnit.SECONDS);return 500 }
                override fun disconnect() { unblock.await(5,TimeUnit.SECONDS);stopped.countDown() }
            }
        })
        try {
            session.request(StreetTilePlan(listOf(StreetTile(12,1,1)),12))
            assertTrue(entered.await(5,TimeUnit.SECONDS))
            val start=System.nanoTime()
            session.request(StreetTilePlan(emptyList(),12))
            session.setActive(false)
            assertTrue("UI waited on a blocked socket",(System.nanoTime()-start)/1e6<500)
        } finally {
            unblock.countDown();session.close()
            assertTrue(stopped.await(5,TimeUnit.SECONDS));root.deleteRecursively()
        }
    }

    @Test fun twoDimensionalMapCannotPaintOutsideItsTranslatedViewport() {
        val renderer=TravelMapRenderer()
        val coordinates=floatArrayOf(0f,0f,1f,0f,1f,1f,0f,1f,0f,0f)
        renderer.setPreparedRegionalBasemap(PreparedBasemap(listOf(
            PreparedBasemapPolygon("oversized land",false,0f,1f,0f,1f,
                listOf(PreparedPolygonRing(0f,1f,0f,1f,coordinates)))
        ),emptyList(),emptyList(),5))
        val center=GeoPoint(0.0,0.0)
        val model=TravelMapRenderModel(emptyList(),emptyList(),focusedLocation=center)
        val state=TravelPlaybackState(.3f,0,center,TransportMode.CAR,0f,
            cameraCenter=center,cameraSpanLat=.1,cameraSpanLng=.1)
        for((width,height) in listOf(240 to 160,160 to 240,360 to 120)) {
            val bitmap=Bitmap.createBitmap(480,400,Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.MAGENTA)
            val canvas=Canvas(bitmap)
            canvas.translate(30f,40f)
            val saved=canvas.saveCount
            val originalClip=canvas.clipBounds
            renderer.render(canvas,width,height,model,state)
            assertEquals(saved,canvas.saveCount)
            assertEquals(originalClip,canvas.clipBounds)
            for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
                if(x !in 30 until 30+width || y !in 40 until 40+height)
                    assertEquals("Map escaped at $x,$y ($width x $height)",Color.MAGENTA,bitmap.getPixel(x,y))
            }
            assertNotEquals(Color.MAGENTA,bitmap.getPixel(35,45))
            bitmap.recycle()
        }
    }

    @Test fun slowObsoleteAtlasNeverReplacesTheLatestViewportAndPendingWorkIsCoalesced() {
        val entered=CountDownLatch(1);val unblock=CountDownLatch(1);val done=CountDownLatch(1)
        val built=CopyOnWriteArrayList<AtlasRequest>();val recycled=CopyOnWriteArrayList<AtlasRequest>()
        val cleaned=CountDownLatch(1)
        fun request(x:Double)=AtlasRequest(OfflineMapDrape.Window(x,.3,.01),setOf(StreetTile(8,10,20)),1)
        val a=request(.1);val b=request(.2);val c=request(.3)
        val worker=MapAtlasWorker(build={ req ->
            built.add(req)
            if(req==a) { entered.countDown();assertTrue(unblock.await(5,TimeUnit.SECONDS)) }
            MapAtlas(req,Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888),FloatBuffer.allocate(9))
        },recycle={ recycled.add(it.request);it.bitmap.recycle() },cleanup={cleaned.countDown()},ready={done.countDown()},intervalMs=0)
        try {
            worker.request(a);assertTrue(entered.await(5,TimeUnit.SECONDS))
            worker.request(b);worker.request(c)
            assertNull("Blocked worker must not block render polling",worker.take(c.window))
            unblock.countDown();assertTrue(done.await(5,TimeUnit.SECONDS))
            val result=worker.take(c.window)!!
            assertEquals(c,result.request);result.bitmap.recycle()
            assertEquals(listOf(a,c),built.toList());assertTrue(a in recycled)
        } finally { unblock.countDown();worker.close() }
        assertTrue(cleaned.await(5,TimeUnit.SECONDS))
    }

    @Test fun closeWaitsForActiveRasterBeforeCleanupAndDiscardsItsResult() {
        val entered=CountDownLatch(1);val unblock=CountDownLatch(1);val cleaned=CountDownLatch(1)
        val recycled=CountDownLatch(1)
        val req=AtlasRequest(OfflineMapDrape.Window(.1,.2,.01),emptySet(),0)
        val worker=MapAtlasWorker(build={
            entered.countDown();assertTrue(unblock.await(5,TimeUnit.SECONDS))
            MapAtlas(it,Bitmap.createBitmap(2,2,Bitmap.Config.ARGB_8888),FloatBuffer.allocate(9))
        },recycle={it.bitmap.recycle();recycled.countDown()},cleanup={cleaned.countDown()},ready={fail("Closed worker published a frame")},intervalMs=0)
        worker.request(req);assertTrue(entered.await(5,TimeUnit.SECONDS))
        worker.close();assertEquals(1L,cleaned.count)
        unblock.countDown()
        assertTrue(cleaned.await(5,TimeUnit.SECONDS));assertTrue(recycled.await(5,TimeUnit.SECONDS))
        assertNull(worker.take(req.window))
    }
}
