package com.traveler.feature.map

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.GLES20
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.TransportMode
import com.traveler.core.terrain.TerrainPack
import com.traveler.feature.map.renderer.*
import com.traveler.feature.map.story.TravelStoryTimeline
import com.traveler.feature.map.threed.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/** Moving GLES viewport with a deliberately slow failing tile source; no external network. */
@RunWith(AndroidJUnit4::class)
class MapCadenceAndroidTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Test fun movingMapKeepsRenderingDuringSlowTileRequests() {
        frame(GeoPoint(37.79,-122.43),false,"moving-map").recycle()
    }
    private fun frame(center:GeoPoint,terrain:Boolean,name:String):Bitmap {
        val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),1))
        val configs=arrayOfNulls<android.opengl.EGLConfig>(1)
        val attrs=intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,
            EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_DEPTH_SIZE,16,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_NONE)
        assertTrue(EGL14.eglChooseConfig(display,attrs,0,configs,0,1,IntArray(1),0))
        val egl=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        val width=720;val height=480
        val surface=EGL14.eglCreatePbufferSurface(display,configs[0],
            intArrayOf(EGL14.EGL_WIDTH,width,EGL14.EGL_HEIGHT,height,EGL14.EGL_NONE),0)
        assertTrue(EGL14.eglMakeCurrent(display,surface,surface,egl))
        var renderer:TravelGlRenderer?=null
        try {
            val pack=TerrainPack(1,"Synthetic flat relief","Test fixture","Test",center.latitude+.3,
                center.latitude-.3,center.longitude-.4,center.longitude+.4,65,65,List(65*65){100.0})
            val model=TravelMapRenderModel(emptyList(),emptyList(),focusedLocation=center)
            val scene=SceneGeometry(model,TravelStoryTimeline.build(model),if(terrain) listOf(pack) else emptyList())
            val requests=java.util.concurrent.atomic.AtomicInteger()
            val session=StreetMapSession(context,network=true,cacheDirectory=File(context.cacheDir,"cadence-empty-${System.nanoTime()}"),connectionFactory={
                object:java.net.HttpURLConnection(java.net.URL("https://fixture.invalid/tile")) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy()=false
                    override fun getResponseCode():Int { requests.incrementAndGet();Thread.sleep(1200);return 500 }
                }
            })
            renderer=TravelGlRenderer(context,scene,session,asyncMaps=true);renderer.initialize()
            val state=TravelPlaybackState(0f,0,center,TransportMode.CAR,0f,
                cameraCenter=center,cameraSpanLat=.08,cameraSpanLng=.1)
            val costs=mutableListOf<Double>();val gaps=mutableListOf<Double>()
            var prior=System.nanoTime()
            repeat(120) { i ->
                val start=System.nanoTime();gaps.add((start-prior)/1e6);prior=start
                renderer.render(width,height,state.copy(currentPosition=GeoPoint(center.latitude,center.longitude+i*.002)))
                GLES20.glFinish();costs.add((System.nanoTime()-start)/1e6)
                Thread.sleep(34)
            }
            val sorted=costs.sorted()
            val metrics="frames=${costs.size} slowRequests=${requests.get()} medianMs=${sorted[60]} p95Ms=${sorted[114]} maxMs=${sorted.last()} maxGapMs=${gaps.max()} mapAvailable=${renderer.mapAvailable}"
            File(context.getExternalFilesDir(null),"rc6-frame-cadence.txt").writeText(metrics)
            assertTrue(metrics,renderer.mapAvailable)
            assertTrue(metrics,requests.get()>0)
            // Only reject multi-second hangs here: SwiftShader is not phone hardware.
            assertTrue(metrics,costs.drop(5).max()<2000.0)
            assertEquals("GLES error",GLES20.GL_NO_ERROR,GLES20.glGetError())
            val bytes=ByteBuffer.allocateDirect(width*height*4)
            GLES20.glReadPixels(0,0,width,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,bytes)
            val pixels=IntArray(width*height)
            for(y in 0 until height) for(x in 0 until width) {
                val i=(y*width+x)*4
                pixels[(height-1-y)*width+x]=Color.argb(255,bytes.get(i).toInt() and 255,
                    bytes.get(i+1).toInt() and 255,bytes.get(i+2).toInt() and 255)
            }
            val bitmap=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
            val output=File(context.getExternalFilesDir(null),"rc6-$name.png")
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand(
                "cp ${output.path} /sdcard/Download/rc6-$name.png")
            return bitmap
        } finally {
            renderer?.release()
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,surface);EGL14.eglDestroyContext(display,egl);EGL14.eglTerminate(display)
        }
    }
}
