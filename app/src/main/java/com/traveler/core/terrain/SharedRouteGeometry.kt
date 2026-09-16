package com.traveler.core.terrain

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.common.geo.GeodesicUtils
import com.traveler.core.common.geo.WebMercator
import com.traveler.core.model.MovementSegment
import com.traveler.core.model.TransportMode
import kotlin.math.abs

/** One horizontal path for map strokes, story motion, terrain and export.
 * Does not manufacture connectors or delete genuine return journeys. */
object SharedRouteGeometry {
    /** Display-only samples follow the same spherical interpolation as the moving marker. */
    fun displayPath(segment: MovementSegment): List<GeoPoint> {
        val points=path(segment)
        if(segment.effectiveMode!=TransportMode.AIRPLANE || points.size<2) return points
        return buildList {
            add(points.first())
            for(i in 1 until points.size) {
                val a=points[i-1];val b=points[i]
                val steps=kotlin.math.ceil(GeodesicUtils.distanceMeters(a,b)/20_000).toInt().coerceIn(1,1024)
                for(step in 1..steps) add(GeodesicUtils.interpolate(a,b,step.toDouble()/steps))
            }
        }
    }

    fun path(segment: MovementSegment): List<GeoPoint> {
        val raw=segment.rawPoints.sortedBy { it.timestampEpochMs }.distinctBy { it.timestampEpochMs }
        val rejected=raw.indices.filter { i ->
            if(i==0 || i==raw.lastIndex) false else {
                val a=raw[i-1];val p=raw[i];val b=raw[i+1]
                val before=(p.timestampEpochMs-a.timestampEpochMs)/1000.0
                val after=(b.timestampEpochMs-p.timestampEpochMs)/1000.0
                val speedLimit=if(segment.effectiveMode==TransportMode.AIRPLANE) 400.0 else 100.0
                before in .001..60.0 && after in .001..60.0 &&
                    GeodesicUtils.distanceMeters(a.coordinate,p.coordinate)>maxOf(1000.0,before*speedLimit) &&
                    GeodesicUtils.distanceMeters(p.coordinate,b.coordinate)>maxOf(1000.0,after*speedLimit) &&
                    GeodesicUtils.distanceMeters(a.coordinate,b.coordinate)<500.0
            }
        }.map { raw[it].coordinate }.toSet()
        val observed = cleanHorizontal(segment.simplifiedPoints.ifEmpty { raw.map { it.coordinate } }.filter { it !in rejected })
        return if (segment.effectiveMode == TransportMode.AIRPLANE && observed.size <= 2) {
            WebMercator.generateGreatCirclePath(segment.startPoint, segment.endPoint, 64)
        } else observed.ifEmpty { listOf(segment.startPoint, segment.endPoint) }
    }

    /** No geometric-only spike deletion: a legitimate out-and-back can have the same shape. */
    internal fun cleanHorizontal(points: List<GeoPoint>): List<GeoPoint> {
        val finite = points.filter { it.latitude.isFinite() && it.longitude.isFinite() &&
            it.latitude in -90.0..90.0 && it.longitude in -180.0..180.0 }
        return finite.filterIndexed { i,p -> i==0 || GeodesicUtils.distanceMeters(finite[i-1],p)>.5 }
    }

    /** Reject isolated altitude impulses only. Sustained slopes and switchbacks survive.
     * This is not road matching; ambiguous gaps are left unknown. */
    fun rejectAltitudeImpulses(points: List<GeoPoint>): List<GeoPoint> = points.mapIndexed { i, p ->
        if (i == 0 || i == points.lastIndex) return@mapIndexed p
        val a = points[i - 1]; val b = points[i + 1]
        val z = p.altitudeMeters ?: return@mapIndexed p
        val az = a.altitudeMeters ?: return@mapIndexed p
        val bz = b.altitudeMeters ?: return@mapIndexed p
        val d1 = GeodesicUtils.distanceMeters(a, p)
        val d2 = GeodesicUtils.distanceMeters(p, b)
        val isolated = (z - az) * (bz - z) < 0 && abs(az - bz) < 60 &&
            abs(z - az) > maxOf(100.0, d1 * 0.7) && abs(z - bz) > maxOf(100.0, d2 * 0.7)
        if (isolated) p.copy(altitudeMeters = null) else p
    }
}
