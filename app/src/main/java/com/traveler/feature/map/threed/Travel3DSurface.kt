package com.traveler.feature.map.threed

import android.opengl.GLSurfaceView
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.traveler.feature.map.renderer.TravelPlaybackState
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

@Composable
fun Travel3DSurface(scene: SceneGeometry, state: TravelPlaybackState?, calm: Boolean, modifier: Modifier,
                    onError: (String) -> Unit) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val errorCallback by rememberUpdatedState(onError)
    val bridge=remember(scene) { SceneBridge(context,scene) { message -> errorCallback(message) } }
    val surface=remember(scene) {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(2)
            setEGLConfigChooser(8,8,8,8,16,0)
            preserveEGLContextOnPause=true
            setRenderer(bridge)
            renderMode=GLSurfaceView.RENDERMODE_WHEN_DIRTY
        }
    }
    DisposableEffect(surface,lifecycle) {
        val observer=LifecycleEventObserver { _,event ->
            if(event==Lifecycle.Event.ON_RESUME) { surface.onResume();surface.requestRender() }
            if(event==Lifecycle.Event.ON_PAUSE) surface.onPause()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer);surface.queueEvent { bridge.release() };surface.onPause() }
    }
    key(surface) {
        AndroidView(factory={surface},modifier=modifier,update={
            bridge.state=state;bridge.calm=calm;it.requestRender()
        })
    }
}

private class SceneBridge(val context: android.content.Context,val scene: SceneGeometry,val onError:(String)->Unit): GLSurfaceView.Renderer {
    @Volatile var state:TravelPlaybackState?=null
    @Volatile var calm=false
    private var width=1;private var height=1
    private var renderer:TravelGlRenderer?=null
    private val main=android.os.Handler(android.os.Looper.getMainLooper())
    override fun onSurfaceCreated(gl:GL10?,config:EGLConfig?) {
        try { renderer=TravelGlRenderer(context,scene).also { it.initialize() } }
        catch(e:Exception) { main.post { onError(e.message ?: "3D unavailable on this device") } }
    }
    override fun onSurfaceChanged(gl:GL10?,w:Int,h:Int) { width=w;height=h }
    override fun onDrawFrame(gl:GL10?) {
        try { renderer?.render(width,height,state,calm) }
        catch(e:Exception) { renderer=null; main.post { onError(e.message ?: "3D rendering failed") } }
    }
    fun release() { renderer?.release();renderer=null }
}
