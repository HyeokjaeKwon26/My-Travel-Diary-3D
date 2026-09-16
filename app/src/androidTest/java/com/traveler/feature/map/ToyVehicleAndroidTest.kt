package com.traveler.feature.map

import android.graphics.Bitmap
import android.graphics.Color
import android.opengl.EGL14
import android.opengl.GLES20 as GL
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.*
import com.traveler.feature.map.renderer.TravelMapRenderModel
import com.traveler.feature.map.story.TravelStoryTimeline
import com.traveler.feature.map.threed.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.nio.ByteBuffer

/** Actual GLES pixels: every toy is large, visible, and inside the viewport.
 * Continental and dateline flights also cover the original invisible-plane case. */
@RunWith(AndroidJUnit4::class)
class ToyVehicleAndroidTest {
    @Test fun allModesAndLongFlightsAreVisible() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val display=EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        assertTrue(EGL14.eglInitialize(display,IntArray(2),0,IntArray(2),1))
        val configs=arrayOfNulls<android.opengl.EGLConfig>(1)
        assertTrue(EGL14.eglChooseConfig(display,intArrayOf(EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,
            EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,EGL14.EGL_DEPTH_SIZE,16,
            EGL14.EGL_SURFACE_TYPE,EGL14.EGL_PBUFFER_BIT,EGL14.EGL_RENDERABLE_TYPE,EGL14.EGL_OPENGL_ES2_BIT,EGL14.EGL_NONE),
            0,configs,0,1,IntArray(1),0))
        val egl=EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        val width=720;val height=480
        val surface=EGL14.eglCreatePbufferSurface(display,configs[0],
            intArrayOf(EGL14.EGL_WIDTH,width,EGL14.EGL_HEIGHT,height,EGL14.EGL_NONE),0)
        assertTrue(EGL14.eglMakeCurrent(display,surface,surface,egl))
        try {
            val base=CanyonDemo.trip().days.flatMap { it.items }.filterIsInstance<TripDayItem.MovementItem>().first().segment
            for(mode in TransportMode.entries) for(dateline in if(mode==TransportMode.AIRPLANE)listOf(false,true) else listOf(false)) {
                val points=if(mode==TransportMode.AIRPLANE) {
                    if(dateline) listOf(GeoPoint(40.0,170.0),GeoPoint(40.0,-160.0))
                    else listOf(GeoPoint(42.36,-71.06),GeoPoint(36.17,-115.14))
                } else listOf(GeoPoint(36.0,-112.0,1500.0),GeoPoint(36.01,-112.0,1800.0),GeoPoint(36.02,-112.0,1400.0))
                val segment=base.copy(startPoint=points.first(),endPoint=points.last(),simplifiedPoints=points,
                    transport=TransportPrediction(mode,1f,"Fixture"))
                val model=TravelMapRenderModel(emptyList(),listOf(segment))
                val timeline=TravelStoryTimeline.build(model)
                val scene=SceneGeometry(model,timeline,emptyList())
                val renderer=TravelGlRenderer(context,scene)
                try {
                    renderer.initialize()
                    for((index,progress) in listOf(.25f,.50f,.75f).withIndex()) {
                        val state=timeline.evaluate(progress)
                        assertNotNull(state.currentSegment)
                        renderer.render(width,height,state,calm=index==1)
                        assertEquals("$mode GLES error",GL.GL_NO_ERROR,GL.glGetError())
                        val bytes=ByteBuffer.allocateDirect(width*height*4)
                        GL.glReadPixels(0,0,width,height,GL.GL_RGBA,GL.GL_UNSIGNED_BYTE,bytes)
                        val pixels=IntArray(width*height)
                        var count=0;var left=width;var right=0;var top=height;var bottom=0
                        for(y in 0 until height) for(x in 0 until width) {
                            val i=(y*width+x)*4
                            val r=bytes.get(i).toInt() and 255;val g=bytes.get(i+1).toInt() and 255;val b=bytes.get(i+2).toInt() and 255
                            pixels[(height-1-y)*width+x]=Color.rgb(r,g,b)
                            // Saturated toy paint is absent from the muted natural map.
                            if((r>170 && r>g*1.5 && r>b*1.5) || (b>160 && b>r*1.5 && b>g*1.5) ||
                                (r>200 && g in 110..195 && b<50) || (g>150 && g>r*1.7 && g>b*1.3)) {
                                count++;left=minOf(left,x);right=maxOf(right,x);top=minOf(top,y);bottom=maxOf(bottom,y)
                            }
                        }
                        assertTrue("$mode/$dateline/$index invisible: $count",count>200)
                        assertTrue("$mode too small: ${right-left} x ${bottom-top}",maxOf(right-left,bottom-top)>45)
                        assertTrue("$mode clipped",left>0 && top>0 && right<width-1 && bottom<height-1)
                        val bitmap=Bitmap.createBitmap(pixels,width,height,Bitmap.Config.ARGB_8888)
                        val name="toy-${mode.name.lowercase()}-$dateline-$index.png"
                        val output=File(context.getExternalFilesDir(null),name)
                        output.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) };bitmap.recycle()
                        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).executeShellCommand("cp ${output.path} /sdcard/Download/$name")
                    }
                } finally { renderer.release() }
            }
        } finally {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display,surface);EGL14.eglDestroyContext(display,egl);EGL14.eglTerminate(display)
        }
    }
}
