package com.traveler.feature.map.threed

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20 as GL
import android.opengl.GLUtils
import android.opengl.Matrix
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.TransportMode
import com.traveler.feature.map.renderer.TravelPlaybackState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*

/** The same renderer runs on GLSurfaceView and the video encoder EGL surface. */
class TravelGlRenderer(private val context: Context, val scene: SceneGeometry) {
    private var program = 0
    private var texture = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val matrix = FloatArray(16)
    private data class Mesh(val data: FloatBuffer, val count: Int)
    private var world: Mesh? = null
    private var terrain = emptyList<Mesh>()
    private var routes = emptyList<Mesh>()
    private val vehicleBuffer = ByteBuffer.allocateDirect(256 * 36).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var positionAttribute=0; private var colorAttribute=0; private var uvAttribute=0
    private var matrixUniform=0; private var textureUniform=0; private var useTextureUniform=0; private var alphaUniform=0

    fun initialize() {
        val vs = shader(GL.GL_VERTEX_SHADER,"""
            uniform mat4 uMatrix; attribute vec3 aPosition; attribute vec4 aColor; attribute vec2 aUv;
            varying vec4 vColor; varying vec2 vUv;
            void main(){ gl_Position=uMatrix*vec4(aPosition,1.0); vColor=aColor; vUv=aUv; }
        """.trimIndent())
        val fs = shader(GL.GL_FRAGMENT_SHADER,"""
            precision mediump float; varying vec4 vColor; varying vec2 vUv;
            uniform sampler2D uTexture; uniform float uUseTexture; uniform float uAlpha;
            void main(){ vec4 c=mix(vColor,texture2D(uTexture,vUv)*vColor,uUseTexture); gl_FragColor=vec4(c.rgb,c.a*uAlpha); }
        """.trimIndent())
        program=GL.glCreateProgram(); GL.glAttachShader(program,vs); GL.glAttachShader(program,fs); GL.glLinkProgram(program)
        val status=IntArray(1); GL.glGetProgramiv(program,GL.GL_LINK_STATUS,status,0)
        check(status[0]!=0) { GL.glGetProgramInfoLog(program) }
        GL.glDeleteShader(vs); GL.glDeleteShader(fs)
        positionAttribute=GL.glGetAttribLocation(program,"aPosition"); colorAttribute=GL.glGetAttribLocation(program,"aColor")
        uvAttribute=GL.glGetAttribLocation(program,"aUv"); matrixUniform=GL.glGetUniformLocation(program,"uMatrix")
        textureUniform=GL.glGetUniformLocation(program,"uTexture"); useTextureUniform=GL.glGetUniformLocation(program,"uUseTexture")
        alphaUniform=GL.glGetUniformLocation(program,"uAlpha")
        val ids=IntArray(1); GL.glGenTextures(1,ids,0); texture=ids[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D,texture)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
        context.assets.open("earth_3d.png").use {
            val bmp=BitmapFactory.decodeStream(it) ?: error("World texture unavailable")
            GLUtils.texImage2D(GL.GL_TEXTURE_2D,0,bmp,0); bmp.recycle()
        }
        world=buildWorld()
        terrain=scene.packs.map { pack ->
            val values=ArrayList<Float>()
            // Bound mesh size even for a maximum-size imported pack.
            val step=max(1,(max(pack.rows,pack.columns)-1)/128)
            for(r in 0 until pack.rows-1 step step) for(c in 0 until pack.columns-1 step step) {
                val r1=min(pack.rows-1,r+step); val c1=min(pack.columns-1,c+step)
                fun p(y:Int,x:Int): Pair<GeoPoint,Double>? {
                    val h=pack.heights[y*pack.columns+x] ?: return null
                    return GeoPoint(pack.north-(pack.north-pack.south)*y/(pack.rows-1),
                        pack.west+(pack.east-pack.west)*x/(pack.columns-1)) to h
                }
                val a=p(r,c) ?: continue; val b=p(r1,c) ?: continue
                val d=p(r,c1) ?: continue; val e=p(r1,c1) ?: continue
                fun triangle(ps:List<Pair<GeoPoint,Double>>) {
                    val xyz=ps.map { EarthGeometry.position(it.first,it.second) }
                    var normal=(xyz[1]-xyz[0]).cross(xyz[2]-xyz[0]).unit()
                    if(normal.x*xyz[0].x+normal.y*xyz[0].y+normal.z*xyz[0].z<0) normal=normal*-1.0
                    val light=Vec3(-.3,.8,-.5).unit()
                    val shade=(.5+.5*abs(normal.x*light.x+normal.y*light.y+normal.z*light.z)).toFloat()
                    ps.forEachIndexed { i,ph ->
                        val t=((ph.second-650)/2200).coerceIn(0.0,1.0).toFloat()
                        vertex(values,xyz[i],floatArrayOf((.65f-.22f*t)*shade,(.34f+.2f*t)*shade,(.22f+.12f*t)*shade,1f))
                    }
                }
                triangle(listOf(a,b,d)); triangle(listOf(d,b,e))
            }
            mesh(values)
        }
        routes=scene.routes.map { route ->
            val values=ArrayList<Float>()
            val color=if(route.mode==TransportMode.AIRPLANE) floatArrayOf(.74f,.64f,1f,1f) else floatArrayOf(.25f,.94f,.89f,1f)
            for(i in 0 until route.xyz.lastIndex) if(!route.uncertainEdges[i]) {
                val a=route.xyz[i]; val b=route.xyz[i+1]
                if(route.mode==TransportMode.AIRPLANE) {
                    vertex(values,a,color);vertex(values,b,color)
                } else {
                    val side=(b-a).cross((a+b).unit()).unit()*(8.0/EarthGeometry.R)
                    for(p in listOf(a-side,a+side,b-side,b-side,a+side,b+side)) vertex(values,p,color)
                }
            }
            mesh(values)
        }
    }

    fun render(width: Int,height: Int,state: TravelPlaybackState?, calm: Boolean=false) {
        check(program!=0)
        GL.glViewport(0,0,width,height); GL.glClearColor(.025f,.055f,.09f,1f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)
        GL.glEnable(GL.GL_DEPTH_TEST); GL.glDepthFunc(GL.GL_LEQUAL)
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA,GL.GL_ONE_MINUS_SRC_ALPHA)
        val point=state?.currentPosition
        val routeIndex=scene.routes.indexOfFirst { it.episodeIndex==state?.episodeIndex && it.id==state.currentSegment?.id }
        val route=scene.routes.getOrNull(routeIndex)
        val routeFraction=if(route!=null && state!=null) ((state.storyTimeMs-route.startMs).toDouble()/max(1,route.endMs-route.startMs)).coerceIn(0.0,1.0) else 0.0
        val surface=point?.let { scene.surface(it) } ?: 0.0
        val flight=state?.currentTransportMode==TransportMode.AIRPLANE
        val lift=if(flight && point?.altitudeMeters==null && route!=null)
            sin(PI*routeFraction)*min(600_000.0,max(12_000.0,(route.xyz.last()-route.xyz.first()).length()*EarthGeometry.R*.05)) else 0.0
        val focus=state?.let { scene.routePosition(it) } ?: if(point!=null) EarthGeometry.position(point,surface+lift+12) else scene.overviewCenter
        val up=focus.unit()
        val forward=if(point!=null) EarthGeometry.forward(point,if(calm) 0.0 else state.currentHeadingDegrees.toDouble()) else Vec3(0.0,1.0,0.0)
        val distance=if(state==null) scene.overviewDistance else if(flight)
            (state.cameraSpanLat*111000/EarthGeometry.R*1.3).coerceIn(.04,2.8) else
            (state.cameraSpanLat*111000/EarthGeometry.R).coerceIn(.0008,.006)
        val eye=focus+up*(distance*.8)-forward*(distance*.7)
        val target=focus+forward*(distance*.10)
        Matrix.setLookAtM(view,0,eye.x.toFloat(),eye.y.toFloat(),eye.z.toFloat(),target.x.toFloat(),target.y.toFloat(),target.z.toFloat(),up.x.toFloat(),up.y.toFloat(),up.z.toFloat())
        Matrix.perspectiveM(projection,0,42f,width.toFloat()/max(1,height),max(.000001,distance/8).toFloat(),(if(distance<.1) max(.03,distance*8) else distance+3).toFloat())
        Matrix.multiplyMM(matrix,0,projection,0,view,0)
        GL.glUseProgram(program); GL.glUniformMatrix4fv(matrixUniform,1,false,matrix,0)
        GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D,texture); GL.glUniform1i(textureUniform,0)
        world?.let { draw(it,GL.GL_TRIANGLES,1f,true) }
        if(distance<.1) terrain.forEach { draw(it,GL.GL_TRIANGLES) }
        // Do not show future / return paths over the current journey during playback.
        routes.forEachIndexed { i,mesh ->
            val r=scene.routes[i]
            val alpha=if(state==null) .85f else if(i==routeIndex) 1f else if(r.endMs<=state.storyTimeMs) .22f else 0f
            if(alpha>0) { GL.glLineWidth(if(i==routeIndex) 4f else 2f); draw(mesh,if(r.mode==TransportMode.AIRPLANE) GL.GL_LINES else GL.GL_TRIANGLES,alpha) }
        }
        if(state!=null && !scene.uncertain(state)) {
            val modelSize=distance*.018
            val front=forward.unit(); val right=front.cross(up).unit()
            val slopePoint=route?.points?.let { ps -> ps.getOrNull(min(ps.lastIndex,(routeFraction*ps.lastIndex).toInt()+1)) }
            val slope=if(point!=null && slopePoint!=null && !flight) {
                val dz=scene.surface(slopePoint)-surface
                val horizontal=com.traveler.core.common.geo.GeodesicUtils.distanceMeters(point,slopePoint)
                atan2(dz,max(20.0,horizontal)).coerceIn(-.35,.35)
            } else 0.0
            val pitched=(front*cos(slope)+up*sin(slope)).unit()
            val values=ArrayList<Float>()
            fun v(x:Double,y:Double,z:Double)=focus+right*(x*modelSize)+up*(y*modelSize)+pitched*(z*modelSize)
            val color=floatArrayOf(1f,.78f,.25f,1f)
            fun tri(a:Vec3,b:Vec3,c:Vec3,col:FloatArray=color) { vertex(values,a,col);vertex(values,b,col);vertex(values,c,col) }
            if(flight) {
                tri(v(0.0,.2,2.8),v(-.35,.2,-1.6),v(.35,.2,-1.6))
                tri(v(-2.3,.2,-.6),v(0.0,.2,1.0),v(2.3,.2,-.6))
                tri(v(0.0,.2,-.6),v(0.0,1.2,-1.6),v(0.0,.2,-1.7))
            } else {
                val pts=listOf(v(-.7,.2,-1.3),v(.7,.2,-1.3),v(.7,.2,1.3),v(-.7,.2,1.3),v(-.6,1.0,-.9),v(.6,1.0,-.9),v(.6,1.0,.6),v(-.6,1.0,.6))
                val faces=listOf(intArrayOf(0,1,5,4),intArrayOf(1,2,6,5),intArrayOf(2,3,7,6),intArrayOf(3,0,4,7),intArrayOf(4,5,6,7))
                faces.forEachIndexed { index,face ->
                    val shade=listOf(.65f,.78f,.88f,.72f,1f)[index]
                    val faceColor=floatArrayOf(color[0]*shade,color[1]*shade,color[2]*shade,1f)
                    tri(pts[face[0]],pts[face[1]],pts[face[2]],faceColor)
                    tri(pts[face[0]],pts[face[2]],pts[face[3]],faceColor)
                }
                val glass=floatArrayOf(.08f,.22f,.3f,1f)
                tri(v(-.5,1.01,.2),v(.5,1.01,.2),v(.5,1.01,.55),glass)
                tri(v(-.5,1.01,.2),v(.5,1.01,.55),v(-.5,1.01,.55),glass)
                // Four dark wheel faces, visible even at the small overview scale.
                for(x in listOf(-.73,.73)) for(z in listOf(-.8,.8)) {
                    tri(v(x,.05,z-.24),v(x,.46,z-.24),v(x,.46,z+.24),glass)
                    tri(v(x,.05,z-.24),v(x,.46,z+.24),v(x,.05,z+.24),glass)
                }
            }
            vehicleBuffer.clear()
            values.forEach { vehicleBuffer.put(it) }
            vehicleBuffer.position(0)
            draw(Mesh(vehicleBuffer, values.size / 9),GL.GL_TRIANGLES)
        }
        GL.glDisable(GL.GL_DEPTH_TEST)
    }

    private fun draw(m:Mesh,mode:Int,alpha:Float=1f,textured:Boolean=false) {
        if(m.count==0) return
        GL.glUniform1f(useTextureUniform,if(textured) 1f else 0f);GL.glUniform1f(alphaUniform,alpha)
        m.data.position(0);GL.glEnableVertexAttribArray(positionAttribute);GL.glVertexAttribPointer(positionAttribute,3,GL.GL_FLOAT,false,36,m.data)
        m.data.position(3);GL.glEnableVertexAttribArray(colorAttribute);GL.glVertexAttribPointer(colorAttribute,4,GL.GL_FLOAT,false,36,m.data)
        m.data.position(7);GL.glEnableVertexAttribArray(uvAttribute);GL.glVertexAttribPointer(uvAttribute,2,GL.GL_FLOAT,false,36,m.data)
        GL.glDrawArrays(mode,0,m.count)
        GL.glDisableVertexAttribArray(positionAttribute);GL.glDisableVertexAttribArray(colorAttribute);GL.glDisableVertexAttribArray(uvAttribute)
    }
    private fun buildWorld():Mesh {
        val values=ArrayList<Float>();val rows=64;val cols=128
        fun add(r:Int,c:Int) {
            val p=GeoPoint(90.0-180.0*r/rows,-180.0+360.0*c/cols)
            vertex(values,EarthGeometry.position(p,-25.0),floatArrayOf(1f,1f,1f,1f),c.toFloat()/cols,r.toFloat()/rows)
        }
        for(r in 0 until rows) for(c in 0 until cols) { add(r,c);add(r+1,c);add(r,c+1);add(r,c+1);add(r+1,c);add(r+1,c+1) }
        return mesh(values)
    }
    private fun vertex(v:MutableList<Float>,p:Vec3,c:FloatArray,u:Float=0f,w:Float=0f) {
        v.add(p.x.toFloat());v.add(p.y.toFloat());v.add(p.z.toFloat());c.forEach { v.add(it) };v.add(u);v.add(w)
    }
    private fun mesh(values:List<Float>):Mesh {
        val buffer=ByteBuffer.allocateDirect(values.size*4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        values.forEach { buffer.put(it) };buffer.position(0);return Mesh(buffer,values.size/9)
    }
    private fun shader(type:Int,source:String):Int {
        val id=GL.glCreateShader(type);GL.glShaderSource(id,source);GL.glCompileShader(id)
        val status=IntArray(1);GL.glGetShaderiv(id,GL.GL_COMPILE_STATUS,status,0)
        check(status[0]!=0) { GL.glGetShaderInfoLog(id) };return id
    }
    fun release() { if(program!=0) GL.glDeleteProgram(program);if(texture!=0) GL.glDeleteTextures(1,intArrayOf(texture),0);program=0;texture=0 }
}
