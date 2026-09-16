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
}

/** Earth-normalized double precision on CPU, float only at the GPU boundary. */
object EarthGeometry {
    const val R = 6_371_000.0
    fun position(p: GeoPoint, altitude: Double = 0.0): Vec3 {
        val lat = Math.toRadians(p.latitude); val lng = Math.toRadians(p.longitude)
        val r = 1.0 + altitude / R
        return Vec3(cos(lat)*cos(lng)*r, sin(lat)*r, cos(lat)*sin(lng)*r)
    }
    fun north(p: GeoPoint): Vec3 {
        val a = Math.toRadians(p.latitude); val b = Math.toRadians(p.longitude)
        return Vec3(-sin(a)*cos(b),cos(a),-sin(a)*sin(b))
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

class SceneGeometry(val model: TravelMapRenderModel, val timeline: TravelStoryTimeline, val packs: List<TerrainPack>) {
    fun ground(p: GeoPoint): Double? = packs.firstNotNullOfOrNull { it.meshElevation(p) }
    // Use a single DEM datum for terrain attachment. Recorded GPS height is never
    // silently mixed with DEM height or overwritten in the persisted journey.
    fun surface(p: GeoPoint) = ground(p) ?: p.altitudeMeters ?: 0.0
    val routes = timeline.episodes.mapIndexedNotNull { episodeIndex, episode ->
        val ep = episode as? StoryEpisode.MovementEpisode ?: return@mapIndexedNotNull null
        val source = ep.pathPoints
        val points = buildList {
            for (i in 0 until source.lastIndex) {
                val d = GeodesicUtils.distanceMeters(source[i],source[i+1])
                val steps = if (ep.segment.effectiveMode == TransportMode.AIRPLANE) 1 else
                    ceil(d / 50.0).toInt().coerceIn(1, 128)
                for (j in 0 until steps) add(GeodesicUtils.interpolate(source[i],source[i+1],j.toDouble()/steps))
            }
            if (source.isNotEmpty()) add(source.last())
        }
        var distance = 0.0
        val distances = points.mapIndexed { i,p ->
            if (i > 0) distance += GeodesicUtils.distanceMeters(points[i-1], p)
            distance
        }
        val heights = points.map { surface(it) }
        val uncertain = points.zipWithNext().mapIndexed { i,_ ->
            ep.segment.effectiveMode != TransportMode.AIRPLANE &&
                abs(heights[i+1] - heights[i]) > max(75.0, (distances[i+1] - distances[i]) * .55)
        }
        val xyz = points.mapIndexed { i,p ->
            val lift = if (ep.segment.effectiveMode == TransportMode.AIRPLANE && p.altitudeMeters == null)
                sin(PI*distances[i]/max(1.0,distance))*min(600_000.0,max(12_000.0,ep.totalDistanceMeters*.05)) else 0.0
            val height=if(ep.segment.effectiveMode==TransportMode.AIRPLANE) p.altitudeMeters ?: (surface(p)+lift)
                else surface(p)
            EarthGeometry.position(p, height+12)
        }
        SceneRoute(episodeIndex,ep.segment.id,ep.startTimestampEpochMs,ep.endTimestampEpochMs,points,xyz,distances,uncertain,
            ep.segment.effectiveMode,ep.segment.geometryProvenance.name.contains("ESTIMAT") ||
                ep.segment.geometryProvenance.name.contains("INTERPOLAT"))
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
        if (r.uncertainEdges[i]) {
            // Camera-only conservative framing. Vehicle/route edge are suppressed;
            // never present a guessed descent as a measured journey.
            val h=max((r.xyz[i].length()-1)*EarthGeometry.R,(r.xyz[i+1].length()-1)*EarthGeometry.R)
            EarthGeometry.position(state.currentPosition,h)
        } else r.xyz[i]*(1-f)+r.xyz[i+1]*f
    }
    val overviewPoints = routes.flatMap { it.xyz }.ifEmpty { model.visits.map { EarthGeometry.position(it.location,surface(it.location)) } }
    val overviewCenter = model.focusedLocation?.let { EarthGeometry.position(it, surface(it)) } ?: overviewPoints.fold(Vec3(0.0,0.0,0.0)) { a,b -> a+b }.let {
        if (it.length() < 1e-6) Vec3(1.0,0.0,0.0) else it.unit()
    }
    val overviewDistance = if (model.focusedLocation != null) .002 else
        (overviewPoints.maxOfOrNull { (it-overviewCenter).length() } ?: .02).times(3.2).coerceIn(.002,3.2)
}
