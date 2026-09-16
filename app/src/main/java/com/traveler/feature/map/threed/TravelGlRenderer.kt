package com.traveler.feature.map.threed

import android.content.Context
import android.graphics.BitmapFactory
import android.opengl.GLES20 as GL
import android.opengl.GLUtils
import android.opengl.Matrix
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.common.geo.WebMercator
import com.traveler.core.common.geo.WorldPoint
import com.traveler.core.model.TransportMode
import com.traveler.feature.map.renderer.TravelPlaybackState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import kotlin.math.*

/** The same renderer runs on GLSurfaceView and the video encoder EGL surface. */
class TravelGlRenderer(private val context: Context, val scene: SceneGeometry,
                       val streets:StreetMapSession=StreetMapSession(context),
                       private val asyncMaps:Boolean=false,private val onMapReady:()->Unit={}) {
    private var program = 0
    private var texture = 0
    private var mapTexture = 0
    private lateinit var mapDrape: OfflineMapDrape
    private var mapWindow: OfflineMapDrape.Window? = null
    private lateinit var atlasPainter:MapAtlasPainter
    private var atlasWorker:MapAtlasWorker?=null
    private var requestedWindow:OfflineMapDrape.Window?=null
    private var textureAllocated=false
    private var mapRevision=-1L
    private var paintedTiles=emptySet<StreetTile>()
    @Volatile var mapAvailable=false;private set
    @Volatile var mapFrameReady=false;private set
    private var localBase: Mesh? = null
    private var mapBoundsUniform = 0
    private var mapTextureUniform = 0
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val matrix = FloatArray(16)
    private data class Mesh(val data: FloatBuffer, val count: Int)
    private var world: Mesh? = null
    private val terrain = linkedMapOf<Int,Mesh>()
    private var routes = emptyList<Mesh>()
    private val vehicleBuffer = ByteBuffer.allocateDirect(16384 * 36).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var positionAttribute=0; private var colorAttribute=0; private var uvAttribute=0
    private var routeScaleUniform=0
    private var matrixUniform=0; private var textureUniform=0; private var useTextureUniform=0; private var alphaUniform=0

    fun initialize() {
        val vs = shader(GL.GL_VERTEX_SHADER,"""
            uniform mat4 uMatrix; attribute vec3 aPosition; attribute vec4 aColor; attribute vec2 aUv;
            uniform mediump float uUseTexture; uniform vec4 uMapBounds; uniform float uRouteScale;
            varying vec4 vColor; varying vec2 vUv; varying vec2 vGlobeUv;
            void main(){
                vec3 p=aPosition;
                vColor=aColor; vUv=aUv; vGlobeUv=aUv;
                if(uUseTexture < -0.5) { p+=vec3(aUv,aColor.a)*uRouteScale;vColor.a=1.0; }
                gl_Position=uMatrix*vec4(p,1.0);
                if(uUseTexture>1.5){
                    float dx=aUv.x-uMapBounds.x;
                    dx-=floor(dx+0.5);
                    vUv=vec2(dx,aUv.y-uMapBounds.y)*uMapBounds.z;
                    float lat=2.0*atan(exp(3.14159265*(1.0-2.0*aUv.y)))-1.57079633;
                    vGlobeUv=vec2(aUv.x,0.5-lat/3.14159265);
                }
            }
        """.trimIndent())
        val fs = shader(GL.GL_FRAGMENT_SHADER,"""
            precision mediump float; varying vec4 vColor; varying vec2 vUv; varying vec2 vGlobeUv;
            uniform sampler2D uTexture; uniform sampler2D uMapTexture;
            uniform float uUseTexture; uniform float uAlpha;
            void main(){
                vec4 c=vColor;
                if(uUseTexture>1.5){
                    bool inside=vUv.x>=0.0 && vUv.x<=1.0 && vUv.y>=0.0 && vUv.y<=1.0;
                    c*=inside ? texture2D(uMapTexture,vUv) : texture2D(uTexture,vGlobeUv);
                } else if(uUseTexture>0.5) c*=texture2D(uTexture,vUv);
                gl_FragColor=vec4(c.rgb,c.a*uAlpha);
            }
        """.trimIndent())
        program=GL.glCreateProgram(); GL.glAttachShader(program,vs); GL.glAttachShader(program,fs); GL.glLinkProgram(program)
        val status=IntArray(1); GL.glGetProgramiv(program,GL.GL_LINK_STATUS,status,0)
        check(status[0]!=0) { GL.glGetProgramInfoLog(program) }
        GL.glDeleteShader(vs); GL.glDeleteShader(fs)
        positionAttribute=GL.glGetAttribLocation(program,"aPosition"); colorAttribute=GL.glGetAttribLocation(program,"aColor")
        uvAttribute=GL.glGetAttribLocation(program,"aUv"); matrixUniform=GL.glGetUniformLocation(program,"uMatrix")
        textureUniform=GL.glGetUniformLocation(program,"uTexture"); useTextureUniform=GL.glGetUniformLocation(program,"uUseTexture")
        alphaUniform=GL.glGetUniformLocation(program,"uAlpha")
        routeScaleUniform=GL.glGetUniformLocation(program,"uRouteScale")
        mapBoundsUniform=GL.glGetUniformLocation(program,"uMapBounds")
        mapTextureUniform=GL.glGetUniformLocation(program,"uMapTexture")
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
        mapDrape=OfflineMapDrape(context)
        atlasPainter=MapAtlasPainter(context,streets)
        if(asyncMaps) atlasWorker=MapAtlasWorker(atlasPainter::prepare,
            { atlasPainter.recycle(it.bitmap) },atlasPainter::close,onMapReady)
        GL.glGenTextures(1,ids,0);mapTexture=ids[0]
        GL.glBindTexture(GL.GL_TEXTURE_2D,mapTexture)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MIN_FILTER,GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_MAG_FILTER,GL.GL_LINEAR)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_S,GL.GL_CLAMP_TO_EDGE)
        GL.glTexParameteri(GL.GL_TEXTURE_2D,GL.GL_TEXTURE_WRAP_T,GL.GL_CLAMP_TO_EDGE)
        world=buildWorld()
        routes=scene.routes.map { route ->
            val values=ArrayList<Float>()
            val color=if(route.mode==TransportMode.AIRPLANE) floatArrayOf(.74f,.64f,1f,1f) else floatArrayOf(.02f,.48f,.52f,1f)
            for(i in 0 until route.xyz.lastIndex) if(!route.estimated || i % 4 < 2) {
                val a=route.xyz[i]; val b=route.xyz[i+1]
                val side=(b-a).cross((a+b).unit()).unit()
                fun edge(p:Vec3,sign:Double) {
                    val offset=side*sign
                    vertex(values,p,floatArrayOf(color[0],color[1],color[2],offset.z.toFloat()),offset.x.toFloat(),offset.y.toFloat())
                }
                edge(a,-1.0);edge(a,1.0);edge(b,-1.0)
                edge(b,-1.0);edge(a,1.0);edge(b,1.0)
            }
            mesh(values)
        }
    }

    fun render(width: Int,height: Int,state: TravelPlaybackState?, calm: Boolean=true,mapScale:Double=1.0, detailWidth:Int=width, detailHeight:Int=height) {
        check(program!=0)
        GL.glViewport(0,0,width,height); GL.glClearColor(.025f,.055f,.09f,1f)
        GL.glClear(GL.GL_COLOR_BUFFER_BIT or GL.GL_DEPTH_BUFFER_BIT)
        GL.glEnable(GL.GL_DEPTH_TEST); GL.glDepthFunc(GL.GL_LEQUAL)
        GL.glEnable(GL.GL_BLEND); GL.glBlendFunc(GL.GL_SRC_ALPHA,GL.GL_ONE_MINUS_SRC_ALPHA)
        val routeIndex=scene.routes.indexOfFirst { it.episodeIndex==state?.episodeIndex && it.id==state.currentSegment?.id }
        val motion=state?.let { scene.motion(it) }
        val camera = scene.camera.frame(state,width,height)
        val focus=camera.focus
        val up=focus.unit()
        val centerPoint=GeoPoint(Math.toDegrees(asin(up.y)),Math.toDegrees(atan2(-up.z,up.x)))
        // North-up changes only the camera, never the vehicle's route heading.
        val forward=if(calm || motion==null) EarthGeometry.north(centerPoint) else motion.forward
        val distance=camera.distance
        val cameraRight=forward.cross(up).unit()
        var eye=if(calm) focus+up*(distance*.94)-forward*(distance*.34)
            else focus+up*(distance*.65)-forward*(distance*.75)+cameraRight*(if(state==null)0.0 else distance*.5)
        if(distance<.1) {
            val radial=eye.unit()
            val eyePoint=GeoPoint(Math.toDegrees(asin(radial.y)),Math.toDegrees(atan2(-radial.z,radial.x)))
            val floor=scene.ground(eyePoint)
            if(floor!=null && (eye.length()-1)*EarthGeometry.R<floor+200) eye=radial*(1+(floor+200)/EarthGeometry.R)
        }
        val target=focus+forward*(distance*.10)
        Matrix.setLookAtM(view,0,eye.x.toFloat(),eye.y.toFloat(),eye.z.toFloat(),target.x.toFloat(),target.y.toFloat(),target.z.toFloat(),up.x.toFloat(),up.y.toFloat(),up.z.toFloat())
        Matrix.perspectiveM(projection,0,42f,width.toFloat()/max(1,height),max(.000001,distance/8).toFloat(),(if(distance<.1) max(.03,distance*8) else distance+3).toFloat())
        Matrix.multiplyMM(matrix,0,projection,0,view,0)
        GL.glUseProgram(program); GL.glUniformMatrix4fv(matrixUniform,1,false,matrix,0)
        GL.glActiveTexture(GL.GL_TEXTURE0); GL.glBindTexture(GL.GL_TEXTURE_2D,texture); GL.glUniform1i(textureUniform,0)
        world?.let { draw(it,GL.GL_TRIANGLES,1f,1) }
        val center=GeoPoint(Math.toDegrees(asin(up.y)),Math.toDegrees(atan2(-up.z,up.x)))
        if(distance<.1) {
            updateMap(center,distance,detailWidth,detailHeight,calm)
            localBase?.let { draw(it,GL.GL_TRIANGLES,1f,2) }
            val nearby=(if(mapWindow==null) emptyList() else scene.packs.indices.toList()).sortedBy { i ->
                val p=scene.packs[i]
                com.traveler.core.common.geo.GeodesicUtils.distanceMeters(center,
                    GeoPoint(center.latitude.coerceIn(p.south,p.north),center.longitude.coerceIn(p.west,p.east)))
            }.take(12).toSet()
            terrain.keys.toList().filter { it !in nearby }.forEach { terrain.remove(it) }
            for(i in nearby) draw(terrain.getOrPut(i) { buildTerrain(scene.packs[i]) },GL.GL_TRIANGLES,1f,2)
        } else {
            // The local mesh and globe are nearly coplanar at continental scale;
            // a 16-bit depth buffer cannot separate them and produces a checkerboard.
            // At this scale the globe texture supplies geographic context directly.
            streets.request(StreetTilePlan(emptyList(),0))
            mapFrameReady=true
        }
        mapAvailable=distance>=.1 || mapWindow!=null
        GL.glUniform1f(routeScaleUniform,max(8.0/EarthGeometry.R,distance*2*tan(Math.toRadians(21.0))*2.2/max(1,height)).toFloat())
        // Do not show future / return paths over the current journey during playback.
        routes.forEachIndexed { i,mesh ->
            val r=scene.routes[i]
            val alpha=if(state==null || state.isTitleCardActive || state.isEndCardActive) .85f else if(i==routeIndex) 1f else if(r.endMs<=state.storyTimeMs) .22f else 0f
            if(alpha>0) { GL.glLineWidth(if(i==routeIndex) 4f else 2f); draw(mesh,GL.GL_TRIANGLES,alpha,-1) }
        }
        if(state!=null && motion!=null && !state.isTitleCardActive && !state.isEndCardActive) {
            val mode=state.currentTransportMode
            val modelSize=VehicleAnimation.scale(distance,width,height,mode)
            val pose=VehicleAnimation.pose(mode,state.progress.toDouble()*scene.timeline.totalStoryDurationSeconds,
                motion.slope,motion.turn,state.currentSegment!=null)
            val front=motion.forward.unit();val right=front.cross(up).unit()
            val pitchedFront=front*cos(pose.pitch)+up*sin(pose.pitch)
            val pitchedUp=up*cos(pose.pitch)-front*sin(pose.pitch)
            val rolledRight=right*cos(pose.roll)+pitchedUp*sin(pose.roll)
            val rolledUp=pitchedUp*cos(pose.roll)-right*sin(pose.roll)
            val origin=motion.position+motion.position.unit()*(modelSize*(.15+pose.bounce))
            val toy=ToyVehicle.mesh(mode,pose.phase)
            check(toy.size/7<=16384)
            vehicleBuffer.clear()
            for(i in toy.indices step 7) {
                val p=origin+rolledRight*(toy[i]*modelSize)+rolledUp*(toy[i+1]*modelSize*pose.stretch)+
                    pitchedFront*(toy[i+2]*modelSize)
                vehicleBuffer.put(p.x.toFloat()).put(p.y.toFloat()).put(p.z.toFloat())
                for(c in 3..6) vehicleBuffer.put(toy[i+c])
                vehicleBuffer.put(0f).put(0f)
            }
            vehicleBuffer.position(0)
            // Oversized story markers must remain legible even where their wheels
            // overlap relief. Keep depth within the solid toy for correct 3D faces.
            GL.glClear(GL.GL_DEPTH_BUFFER_BIT)
            draw(Mesh(vehicleBuffer,toy.size/7),GL.GL_TRIANGLES)
        }
        GL.glDisable(GL.GL_DEPTH_TEST)
    }

    private fun buildTerrain(pack:com.traveler.core.terrain.TerrainPack):Mesh {
            val values=ArrayList<Float>()
            // Local daylight keeps a flat map equally legible on every continent.
            // A fixed Earth-space light made North American maps uniformly dark.
            val midpoint=GeoPoint((pack.north+pack.south)/2,(pack.west+pack.east)/2)
            val light=(EarthGeometry.position(midpoint).unit()+EarthGeometry.north(midpoint)*.6+
                EarthGeometry.forward(midpoint,90.0)*.8).unit()
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
                    val shade=(.78+.32*max(0.0,normal.x*light.x+normal.y*light.y+normal.z*light.z)).toFloat()
                    ps.forEachIndexed { i,ph ->
                        val uv=WebMercator.project(ph.first)
                        vertex(values,xyz[i],floatArrayOf(shade,shade,shade,1f),uv.x.toFloat(),uv.y.toFloat())
                    }
                }
                triangle(listOf(a,b,d)); triangle(listOf(d,b,e))
            }
            // Blend an uncovered DEM edge back to the reference globe. Without this
            // apron, a 2 km plateau ends abruptly and roads appear to jump at the seam.
            // This is a visual coverage transition, never persisted as measured altitude.
            fun apron(a:GeoPoint,ah:Double,b:GeoPoint,bh:Double,north:Double,east:Double) {
                fun outside(p:GeoPoint,metres:Double)=GeoPoint(
                    (p.latitude+north*metres/111_000).coerceIn(-85.0,85.0),
                    p.longitude+east*metres/(111_000*max(.15,cos(Math.toRadians(p.latitude)))))
                val midpoint=GeoPoint((a.latitude+b.latitude)/2,(a.longitude+b.longitude)/2)
                if(scene.ground(outside(midpoint,30.0))!=null) return
                fun point(p:GeoPoint,h:Double,step:Int):Pair<GeoPoint,Double> {
                    val f=step/8.0
                    return outside(p,f*6000.0) to (h*(1-f*f*(3-2*f))-2*f)
                }
                fun add(ph:Pair<GeoPoint,Double>) {
                    val uv=WebMercator.project(ph.first)
                    vertex(values,EarthGeometry.position(ph.first,ph.second),floatArrayOf(1f,1f,1f,1f),uv.x.toFloat(),uv.y.toFloat())
                }
                for(k in 0..7) {
                    if(scene.ground(outside(midpoint,(k+.5)*750))!=null) continue
                    val p=point(a,ah,k);val q=point(b,bh,k)
                    val r=point(a,ah,k+1);val t=point(b,bh,k+1)
                    for(v in listOf(p,q,r,r,q,t)) add(v)
                }
            }
            for(c in 0 until pack.columns-1 step step) {
                val c1=min(pack.columns-1,c+step)
                val x=pack.west+(pack.east-pack.west)*c/(pack.columns-1)
                val x1=pack.west+(pack.east-pack.west)*c1/(pack.columns-1)
                for((row,n) in listOf(0 to 1.0,pack.rows-1 to -1.0)) {
                    val a=pack.heights[row*pack.columns+c] ?: continue
                    val b=pack.heights[row*pack.columns+c1] ?: continue
                    val y=if(row==0)pack.north else pack.south
                    apron(GeoPoint(y,x),a.toDouble(),GeoPoint(y,x1),b.toDouble(),n,0.0)
                }
            }
            for(r in 0 until pack.rows-1 step step) {
                val r1=min(pack.rows-1,r+step)
                val y=pack.north-(pack.north-pack.south)*r/(pack.rows-1)
                val y1=pack.north-(pack.north-pack.south)*r1/(pack.rows-1)
                for((col,e) in listOf(0 to -1.0,pack.columns-1 to 1.0)) {
                    val a=pack.heights[r*pack.columns+col] ?: continue
                    val b=pack.heights[r1*pack.columns+col] ?: continue
                    val x=if(col==0)pack.west else pack.east
                    apron(GeoPoint(y,x),a.toDouble(),GeoPoint(y1,x),b.toDouble(),0.0,e)
                }
            }
            return mesh(values)
    }

    private fun updateMap(center:GeoPoint,distance:Double,width:Int,height:Int,northUp:Boolean) {
        // North-up's steep camera needs half the old orbit-camera padding. Keep the
        // visible map sharp within the same 2048px atlas, without more texture memory.
        val coverageDistance=distance * if(northUp) .5 else 1.0
        val wanted=mapDrape.window(center.latitude,center.longitude,coverageDistance,width.toDouble()/max(1,height))
        val p=WebMercator.project(center)
        GL.glActiveTexture(GL.GL_TEXTURE1);GL.glBindTexture(GL.GL_TEXTURE_2D,mapTexture)
        val requestWindow=requestedWindow?.takeIf { it.containsCenter(p.x,p.y,wanted.span) }
            ?: wanted.also { requestedWindow=it }
        val footprint=visibleFootprint(requestWindow,1+scene.surface(center)/EarthGeometry.R)
        val plan=StreetTilePlan.visible(footprint,width,height)
        streets.request(plan)
        val request=AtlasRequest(requestWindow,plan.tiles.toSet(),streets.version)
        val changed=mapWindow!=requestWindow || mapRevision!=request.revision || paintedTiles!=request.tiles
        val atlas=if(asyncMaps) {
            atlasWorker!!.request(request)
            // Completion requests a frame even while paused. Playback never waits.
            atlasWorker!!.take(requestWindow)
        } else if(changed) atlasPainter.prepare(request) else null
        if(atlas!=null) {
            try {
                if(!textureAllocated) {
                    GLUtils.texImage2D(GL.GL_TEXTURE_2D,0,atlas.bitmap,0)
                    textureAllocated=true
                } else GLUtils.texSubImage2D(GL.GL_TEXTURE_2D,0,0,0,atlas.bitmap)
                mapWindow=atlas.request.window
                localBase=Mesh(atlas.base,atlas.base.capacity()/9)
                mapRevision=atlas.request.revision;paintedTiles=atlas.request.tiles
            } finally { atlasPainter.recycle(atlas.bitmap) }
        }
        mapFrameReady=mapWindow==requestWindow && mapRevision==streets.version && paintedTiles==request.tiles
        val w=mapWindow
        if(w!=null) GL.glUniform4f(mapBoundsUniform,w.x.toFloat(),w.y.toFloat(),(1/w.span).toFloat(),0f)
        GL.glUniform1i(mapTextureUniform,1)
        GL.glActiveTexture(GL.GL_TEXTURE0)
    }

    /** Ray/sphere intersections bound only the geography currently on screen. */
    private fun visibleFootprint(w:OfflineMapDrape.Window,radius:Double):MapFootprint {
        val inverse=FloatArray(16)
        if(!Matrix.invertM(inverse,0,matrix,0)) return MapFootprint(w.x,w.y,w.x+w.span,w.y+w.span)
        val points=mutableListOf<WorldPoint>()
        for(x in listOf(-1f,0f,1f)) for(y in listOf(-1f,0f,1f)) {
            fun unproject(z:Float):Vec3 {
                val result=FloatArray(4);Matrix.multiplyMV(result,0,inverse,0,floatArrayOf(x,y,z,1f),0)
                return Vec3((result[0]/result[3]).toDouble(),(result[1]/result[3]).toDouble(),(result[2]/result[3]).toDouble())
            }
            val origin=unproject(-1f);val direction=(unproject(1f)-origin).unit()
            val b=origin.dot(direction);val c=origin.dot(origin)-radius*radius
            val discriminant=b*b-c
            if(discriminant<0) continue
            val t=-b-sqrt(discriminant)
            if(t<0) continue
            val hit=(origin+direction*t).unit()
            val geo=WebMercator.project(Math.toDegrees(asin(hit.y.coerceIn(-1.0,1.0))),Math.toDegrees(atan2(-hit.z,hit.x)))
            val wx=geo.x+round(w.x+w.span/2-geo.x)
            points.add(WorldPoint(wx,geo.y))
        }
        if(points.isEmpty()) return MapFootprint(w.x,w.y,w.x+w.span,w.y+w.span)
        val left=max(w.x,points.minOf { it.x });val right=min(w.x+w.span,points.maxOf { it.x })
        val top=max(w.y,points.minOf { it.y });val bottom=min(w.y+w.span,points.maxOf { it.y })
        return if(right>left && bottom>top) MapFootprint(left,top,right,bottom)
            else MapFootprint(w.x,w.y,w.x+w.span,w.y+w.span)
    }

    private fun draw(m:Mesh,mode:Int,alpha:Float=1f,textured:Int=0) {
        if(m.count==0) return
        GL.glUniform1f(useTextureUniform,textured.toFloat());GL.glUniform1f(alphaUniform,alpha)
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
    fun release() {
        if(atlasWorker!=null) { atlasWorker?.close();atlasWorker=null }
        else if(::atlasPainter.isInitialized) atlasPainter.close()
        streets.close();terrain.clear();routes=emptyList();world=null;localBase=null;mapWindow=null;requestedWindow=null
        if(program!=0) GL.glDeleteProgram(program)
        GL.glDeleteTextures(2,intArrayOf(texture,mapTexture),0)
        program=0;texture=0;mapTexture=0;textureAllocated=false;mapAvailable=false
    }
}
