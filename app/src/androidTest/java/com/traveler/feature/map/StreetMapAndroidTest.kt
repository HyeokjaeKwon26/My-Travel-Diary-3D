package com.traveler.feature.map

import android.graphics.*
import android.opengl.EGL14
import android.opengl.GLES20 as GL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.common.geo.WebMercator
import com.traveler.core.model.TransportMode
import com.traveler.feature.map.renderer.*
import com.traveler.feature.map.story.TravelStoryTimeline
import com.traveler.feature.map.threed.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class StreetMapAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun fixture():Bitmap=Bitmap.createBitmap(256,256,Bitmap.Config.ARGB_8888).also {
        val canvas=Canvas(it);canvas.drawColor(Color.rgb(236,224,245))
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply { color=Color.WHITE;strokeWidth=14f }
        canvas.drawLine(0f,128f,256f,128f,paint);canvas.drawLine(128f,0f,128f,256f,paint)
        paint.color=Color.rgb(70,10,90);paint.textSize=17f
        canvas.drawText("FIXTURE STREET",8f,110f,paint)
        paint.color=Color.rgb(170,40,190);canvas.drawRect(20f,20f,85f,75f,paint)
    }
    private fun store(root:File,tile:StreetTile,expires:Long) {
        fixture().let { bitmap -> File(root,"${tile.key}.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle() }
        Properties().apply { setProperty("expires",expires.toString());setProperty("etag","fixture-etag") }
            .let { p -> File(root,"${tile.key}.properties").outputStream().use { p.store(it,null) } }
    }
    private fun awaitCondition(condition:()->Boolean) {
        val until=System.currentTimeMillis()+5000
        while(!condition() && System.currentTimeMillis()<until) Thread.sleep(25)
        assertTrue(condition())
    }

    @Test fun freshCacheAndExportDoNotFetchAndExpiredCacheUsesConditionalRequest() {
        val root=File(context.cacheDir,"street-http-test-${System.nanoTime()}").apply { mkdirs() }
        val tile=StreetTile(15,5240,12660);val plan=StreetTilePlan(listOf(tile),15)
        val count=AtomicInteger(0)
        var request:HttpURLConnection?=null
        fun session(network:Boolean)=StreetMapSession(context,network,root,connectionFactory={
            count.incrementAndGet()
            object:HttpURLConnection(URL("https://fixture.invalid/tile")) {
                override fun connect() {}
                override fun disconnect() {}
                override fun usingProxy()=false
                override fun getResponseCode()=304
                override fun getHeaderField(name:String?)=when(name) { "Cache-Control"->"public, max-age=3600";"ETag"->"fixture-etag";else->null }
            }.also { request=it }
        })
        try {
            store(root,tile,System.currentTimeMillis()+86400_000)
            session(true).let { s -> try { s.request(plan);awaitCondition { s.detailCount==1 };Thread.sleep(400);assertEquals(0,count.get()) } finally { s.close() } }
            store(root,tile,0)
            session(false).let { s -> try { s.request(plan);assertEquals(1,s.detailCount);assertEquals(0,count.get()) } finally { s.close() } }
            session(true).let { s -> try {
                s.request(plan)
                awaitCondition { count.get()==1 && request?.getRequestProperty("If-None-Match")=="fixture-etag" }
                assertTrue(request!!.getRequestProperty("User-Agent").startsWith("MyTravelDiary3D/"))
                assertNull(request!!.getRequestProperty("Cache-Control"))
                awaitCondition {
                    val p=Properties();File(root,"${tile.key}.properties").inputStream().use { p.load(it) }
                    (p.getProperty("expires")?.toLongOrNull() ?: 0)>System.currentTimeMillis()
                }
            } finally { s.close() } }
        } finally { root.deleteRecursively() }
    }

    @Test fun disabledInternetStillLoadsCacheAndEvictedTilesReloadOnRevisit() {
        val root=File(context.cacheDir,"street-revisit-test-${System.nanoTime()}").apply { mkdirs() }
        val tiles=(0..48).map { StreetTile(15,5240+it,12660) }
        tiles.forEach { store(root,it,System.currentTimeMillis()+86400_000) }
        val session=StreetMapSession(context,true,root,connectionFactory={error("Fresh cache must not fetch")})
        try {
            session.setActive(false)
            for(batch in tiles.chunked(24)) {
                session.request(StreetTilePlan(batch,15))
                awaitCondition { session.detailCount==batch.size }
                // Ensure the next equal-size batch is not mistaken for this batch.
                session.request(StreetTilePlan(emptyList(),15))
                assertEquals(0,session.detailCount)
            }
            session.setActive(true)
            session.request(StreetTilePlan(listOf(tiles.first()),15))
            awaitCondition { session.detailCount==1 }
        } finally { session.close();root.deleteRecursively() }
    }

    @Test fun cachedStreetLabelsAreDrapedAndGroundCameraIgnoresHeadingAndArrivalZoom() {
        val root=File(context.cacheDir,"street-render-test-${System.nanoTime()}").apply { mkdirs() }
        val center=GeoPoint(37.79,-122.43)
        val world=WebMercator.project(center)
        for(z in 13..17) {
            val n=1 shl z;val x=(world.x*n).toInt();val y=(world.y*n).toInt()
            for(dx in -3..3) for(dy in -3..3) store(root,StreetTile(z,x+dx,y+dy),System.currentTimeMillis()+86400_000)
        }
        val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),1))
        val configs=arrayOfNulls<android.opengl.EGLConfig>(1)
        assertTrue(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,
            EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_DEPTH_SIZE,16,
            EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_NONE),0,configs,0,1,IntArray(1),0))
        val egl=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        val width=720;val height=480
        val surface=EGL14.eglCreatePbufferSurface(display,configs[0],intArrayOf(EGL14.EGL_WIDTH,width,EGL14.EGL_HEIGHT,height,EGL14.EGL_NONE),0)
        assertTrue(EGL14.eglMakeCurrent(display,surface,surface,egl))
        val model=TravelMapRenderModel(emptyList(),emptyList(),focusedLocation=center)
        val scene=SceneGeometry(model,TravelStoryTimeline.build(model),emptyList())
        val session=StreetMapSession(context,cacheDirectory=root,connectionFactory={error("Export must never fetch")})
        val renderer=TravelGlRenderer(context,scene,session)
        try {
            renderer.initialize()
            val state=TravelPlaybackState(.2f,0,center,TransportMode.CAR,0f,cameraCenter=center,cameraSpanLat=.22,cameraSpanLng=.22)
            fun pixels(heading:Float,span:Double):IntArray {
                renderer.render(width,height,state.copy(currentHeadingDegrees=heading,cameraSpanLat=span))
                assertEquals(GL.GL_NO_ERROR,GL.glGetError())
                val buffer=ByteBuffer.allocateDirect(width*height*4)
                GL.glReadPixels(0,0,width,height,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,buffer)
                return IntArray(width*height) { i -> val p=i*4;Color.rgb(buffer.get(p).toInt() and 255,buffer.get(p+1).toInt() and 255,buffer.get(p+2).toInt() and 255) }
            }
            val a=pixels(0f,.22);val b=pixels(180f,.005)
            assertTrue("Cached street tiles not loaded",session.detailCount>0)
            assertTrue("Street detail never reached GLES",a.count { Color.red(it)>140 && Color.blue(it)>160 && Color.green(it)<80 }>1500)
            var changed=0
            for(y in 0 until height) for(x in 0 until width) {
                if(x in 220..500 && y in 70..420) continue // The vehicle itself may turn.
                if(a[y*width+x]!=b[y*width+x]) changed++
            }
            assertEquals("North-up ground map rotated or auto-zoomed",0,changed)
            val flipped=IntArray(width*height) { i -> a[(height-1-i/width)*width+i%width] }
            val bitmap=Bitmap.createBitmap(flipped,width,height,Bitmap.Config.ARGB_8888)
            val output=File(context.getExternalFilesDir(null),"rc4-street-fixture.png")
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("cp ${output.path} /sdcard/Download/rc4-street-fixture.png")
        } finally {
            renderer.release();EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,surface);EGL14.eglDestroyContext(display,egl);EGL14.eglTerminate(display);root.deleteRecursively()
        }
    }
}
