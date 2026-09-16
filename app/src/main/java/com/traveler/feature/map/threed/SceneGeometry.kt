package com.traveler.feature.map.threed

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.common.geo.GeodesicUtils
import com.traveler.core.model.TransportMode
import com.traveler.core.terrain.TerrainPack
import com.traveler.feature.map.renderer.TravelMapRenderModel
import com.traveler.feature.map.renderer.TravelPlaybackState
import com.traveler.feature.map.story.StoryEpisode
import com.traveler.feature.map.story.TravelStoryTimeline
import kotlin.math.*

data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(v: Vec3) = Vec3(x+v.x,y+v.y,z+v.z)
    operator fun minus(v: Vec3) = Vec3(x-v.x,y-v.y,z-v.z)
    operator fun times(s: Double) = Vec3(x*s,y*s,z*s)
    fun length() = sqrt(x*x+y*y+z*z)
    fun unit() = this * (1.0 / max(1e-12, length()))
    fun cross(v: Vec3) = Vec3(y*v.z-z*v.y,z*v.x-x*v.z,x*v.y-y*v.x)
    fun dot(v: Vec3) = x*v.x+y*v.y+z*v.z
}

/** Earth-normalized double precision on CPU, float only at the GPU boundary. */
object EarthGeometry {
    const val R = 6_371_000.0
    fun position(p: GeoPoint, altitude: Double = 0.0): Vec3 {
        val lat = Math.toRadians(p.latitude); val lng = Math.toRadians(p.longitude)
        val r = 1.0 + altitude / R
        // East must point right when north is screen-up in a right-handed GLES view.
        return Vec3(cos(lat)*cos(lng)*r, sin(lat)*r, -cos(lat)*sin(lng)*r)
    }
    fun north(p: GeoPoint): Vec3 {
        val a = Math.toRadians(p.latitude); val b = Math.toRadians(p.longitude)
        return Vec3(-sin(a)*cos(b),cos(a),sin(a)*sin(b))
    }
    fun forward(p: GeoPoint, heading: Double): Vec3 {
        val n = north(p); val e = n.cross(position(p).unit())
        val h = Math.toRadians(heading)
        return n*cos(h)+e*sin(h)
    }
}

data class SceneRoute(val episodeIndex: Int, val id: String, val startMs: Long, val endMs: Long, val points: List<GeoPoint>,
                      val xyz: List<Vec3>, val distances: List<Double>, val uncertainEdges: List<Boolean>,
                      val mode: TransportMode, val estimated: Boolean)

data class SceneMotion(val position:Vec3,val forward:Vec3,val slope:Double,val turn:Double)

class SceneGeometry(val model: TravelMapRenderModel, val timeline: TravelStoryTimeline, val packs: List<TerrainPack>) {
    private val terrainIndex=com.traveler.core.terrain.TerrainSpatialIndex(packs)
    fun ground(p: GeoPoint): Double? = terrainIndex.elevation(p)
    // Use a single DEM datum for terrain attachment. Recorded GPS height is never
    // silently mixed with DEM height or overwritten in the persisted journey.
    fun surface(p: GeoPoint) = ground(p) ?: p.altitudeMeters ?: 0.0
    private val sampleSpacing=max(50.0,timeline.episodes.filterIsInstance<StoryEpisode.MovementEpisode>()
        .filter { it.segment.effectiveMode!=TransportMode.AIRPLANE }.sumOf { it.totalDistanceMeters }/50_000.0)
    val routes = timeline.episodes.mapIndexedNotNull { episodeIndex, episode ->
        val ep = episode as? StoryEpisode.MovementEpisode ?: return@mapIndexedNotNull null
        val source = ep.pathPoints
        val points = buildList {
            for (i in 0 until source.lastIndex) {
                val d = GeodesicUtils.distanceMeters(source[i],source[i+1])
                val steps = if (ep.segment.effectiveMode == TransportMode.AIRPLANE) ceil(d/20_000).toInt().coerceIn(1,1024) else
                    ceil(d / sampleSpacing).toInt().coerceIn(1, 128)
                for (j in 0 until steps) add(GeodesicUtils.interpolate(source[i],source[i+1],j.toDouble()/steps))
            }
            if (source.isNotEmpty()) add(source.last())
        }
        var distance = 0.0
        val distances = points.mapIndexed { i,p ->
            if (i > 0) distance += GeodesicUtils.distanceMeters(points[i-1], p)
            distance
        }
        val dem=points.map { ground(it) }
        val known=if(dem.any { it!=null }) dem else points.map { it.altitudeMeters }
        // Fill elevation holes without deleting a valid horizontal track. Interpolation
        // is by travelled distance, not point count; the source journey stays untouched.
        val heights = DoubleArray(known.size)
        val anchors = known.indices.filter { known[it]?.isFinite() == true }
        if (anchors.isNotEmpty()) {
            for (i in 0..anchors.first()) heights[i] = known[anchors.first()]!!
            for ((a,b) in anchors.zipWithNext()) for (i in a..b) {
                val f = ((distances[i]-distances[a]) / max(1.0,distances[b]-distances[a])).coerceIn(0.0,1.0)
                heights[i] = known[a]!! * (1-f) + known[b]!! * f
            }
            for (i in anchors.last()..known.lastIndex) heights[i] = known[anchors.last()]!!
        }
        val hasHeight=known.any { it!=null }
        val uncertain = points.zipWithNext().mapIndexed { i,_ ->
            ep.segment.effectiveMode != TransportMode.AIRPLANE &&
                ((hasHeight && (known[i]==null || known[i+1]==null)) ||
                    abs(heights[i+1] - heights[i]) > max(75.0, (distances[i+1] - distances[i]) * .55))
        }
        val xyz = points.mapIndexed { i,p ->
            val lift = if (ep.segment.effectiveMode == TransportMode.AIRPLANE && p.altitudeMeters == null)
                sin(PI*distances[i]/max(1.0,distance))*min(600_000.0,max(12_000.0,ep.totalDistanceMeters*.05)) else 0.0
            val height=if(ep.segment.effectiveMode==TransportMode.AIRPLANE) p.altitudeMeters ?: (surface(p)+lift)
                else heights[i]
            EarthGeometry.position(p, height+12)
        }
        SceneRoute(episodeIndex,ep.segment.id,ep.startTimestampEpochMs,ep.endTimestampEpochMs,points,xyz,distances,uncertain,
            ep.segment.effectiveMode,ep.segment.geometryProvenance.name.contains("ESTIMAT") ||
                ep.segment.geometryProvenance.name.contains("INTERPOLAT") ||
                (ep.segment.simplifiedPoints.size < 2 && ep.segment.rawPoints.size < 2))
    }
    private fun bracket(state: TravelPlaybackState): Pair<SceneRoute, Pair<Int, Double>>? {
        val route = routes.firstOrNull { it.episodeIndex == state.episodeIndex && it.id == state.currentSegment?.id } ?: return null
        if (route.xyz.size < 2) return null
        val f = ((state.storyTimeMs-route.startMs).toDouble()/max(1,route.endMs-route.startMs)).coerceIn(0.0,1.0)
        val target = f*f*(3-2*f)*route.distances.last()
        var index = route.distances.binarySearch(target)
        if (index < 0) index = -index-2
        index = index.coerceIn(0,route.xyz.size-2)
        val span = route.distances[index+1]-route.distances[index]
        return route to (index to if(span>0) ((target-route.distances[index])/span).coerceIn(0.0,1.0) else 0.0)
    }
    fun uncertain(state: TravelPlaybackState?): Boolean = state?.let { bracket(it)?.let { (r,b) -> r.uncertainEdges[b.first] } } ?: false
    fun routePosition(state: TravelPlaybackState): Vec3? = bracket(state)?.let { (r,b) ->
        val (i,f)=b
        interpolate(r,i,f)
    }

    private fun interpolate(r:SceneRoute,i:Int,f:Double):Vec3 {
        // A chord between flight fixes dives inside the globe. Interpolate geography
        // on the sphere, with altitude separately, including when seeking backwards.
        val geo=GeodesicUtils.interpolate(r.points[i],r.points[i+1],f)
        val radius=r.xyz[i].length()*(1-f)+r.xyz[i+1].length()*f
        return EarthGeometry.position(geo,(radius-1)*EarthGeometry.R)
    }

    private fun atDistance(r:SceneRoute,d:Double):Vec3 {
        val target=d.coerceIn(0.0,r.distances.last())
        var i=r.distances.binarySearch(target)
        if(i<0) i=-i-2
        i=i.coerceIn(0,r.xyz.size-2)
        val span=r.distances[i+1]-r.distances[i]
        return interpolate(r,i,if(span>0) ((target-r.distances[i])/span).coerceIn(0.0,1.0) else 0.0)
    }

    fun motion(state:TravelPlaybackState):SceneMotion {
        val position=routePosition(state) ?: EarthGeometry.position(state.currentPosition,surface(state.currentPosition)+12)
        val up=position.unit()
        val fallback=EarthGeometry.forward(state.currentPosition,state.currentHeadingDegrees.toDouble())
        val bracket=bracket(state) ?: return SceneMotion(position,fallback,0.0,0.0)
        val (r,b)=bracket
        val d=r.distances[b.first]+(r.distances[b.first+1]-r.distances[b.first])*b.second
        val span=if(r.mode==TransportMode.AIRPLANE) 5000.0 else 150.0
        val before=atDistance(r,d-span);val after=atDistance(r,d+span)
        fun tangent(v:Vec3):Vec3 { val flat=v-up*v.dot(up);return if(flat.length()>1e-12) flat.unit() else fallback }
        val forward=tangent(after-before)
        val horizontal=((after-before)-up*(after-before).dot(up)).length()*EarthGeometry.R
        val slope=if(uncertain(state)) 0.0 else atan2((after.length()-before.length())*EarthGeometry.R,max(1.0,horizontal))
        val incoming=tangent(position-before);val outgoing=tangent(after-position)
        val turn=atan2(up.dot(incoming.cross(outgoing)),incoming.dot(outgoing)).coerceIn(-1.0,1.0)
        return SceneMotion(position,forward,slope,turn)
    }
    val camera = JourneyCamera(this)
    val overviewPoints = routes.flatMap { it.xyz }.ifEmpty { model.visits.map { EarthGeometry.position(it.location,surface(it.location)) } }
    val overviewCenter = model.focusedLocation?.let { EarthGeometry.position(it, surface(it)) } ?: overviewPoints.fold(Vec3(0.0,0.0,0.0)) { a,b -> a+b }.let {
        if (it.length() < 1e-6) Vec3(1.0,0.0,0.0) else it.unit()
    }
    val overviewDistance = if (model.focusedLocation != null) .002 else
        (overviewPoints.maxOfOrNull { (it-overviewCenter).length() } ?: .02).times(3.2).coerceIn(.002,3.2)
}
