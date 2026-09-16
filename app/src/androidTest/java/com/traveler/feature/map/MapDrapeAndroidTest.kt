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

/** Pixel assertions isolate cartography from routes, vehicles and Compose UI.
 * The old flat brown terrain passes color-count smoke tests but fails these checks. */
@RunWith(AndroidJUnit4::class)
class MapDrapeAndroidTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun landWaterAndRoadsRemainVisibleWithAndWithoutTerrain() {
        for (terrain in listOf(false,true)) {
            val bitmap = frame(GeoPoint(37.79,-122.43),terrain,"san-francisco-$terrain")
            var water=0;var land=0;var road=0
            for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
                val p=bitmap.getPixel(x,y);val r=Color.red(p);val g=Color.green(p);val b=Color.blue(p)
                if(b>r+25 && g>r+20) water++
                if(r>b+12 && g>b+12 && g>140) land++
                if(r>210 && r>g+3 && g>b+12) road++
            }
            bitmap.recycle()
            assertTrue("Bay missing, DEM=$terrain water=$water",water>5000)
            assertTrue("Land missing, DEM=$terrain land=$land",land>5000)
            assertTrue("Roads/labels missing, DEM=$terrain pale pixels=$road",road>250)
        }
    }

    @Test fun inlandRoadsAndLabelsAreVisibleOnFlatTerrain() {
        val bitmap=frame(GeoPoint(33.45,-112.07),true,"phoenix")
        var dark=0;var pale=0
        for(y in 0 until bitmap.height) for(x in 0 until bitmap.width) {
            val p=bitmap.getPixel(x,y)
            if(Color.red(p)<90 && Color.green(p)<110 && Color.blue(p)<120) dark++
            if(Color.red(p)>210 && Color.red(p)>Color.green(p)+3 && Color.green(p)>Color.blue(p)+12) pale++
        }
        bitmap.recycle()
        assertTrue("City label missing: $dark",dark>30)
        assertTrue("Reference roads missing: $pale",pale>250)
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
            renderer=TravelGlRenderer(context,scene,StreetMapSession(context,cacheDirectory=File(context.cacheDir,"map-reference-test")));renderer.initialize()
            val state=TravelPlaybackState(0f,0,center,TransportMode.CAR,0f,
                cameraCenter=center,cameraSpanLat=.08,cameraSpanLng=.1)
            renderer.render(width,height,state,mapScale=6.0)
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
            val output=File(context.getExternalFilesDir(null),"map-fix-$name.png")
            output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand(
                "cp ${output.path} /sdcard/Download/map-fix-$name.png")
            return bitmap
        } finally {
            renderer?.release()
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,surface);EGL14.eglDestroyContext(display,egl);EGL14.eglTerminate(display)
        }
    }
}
