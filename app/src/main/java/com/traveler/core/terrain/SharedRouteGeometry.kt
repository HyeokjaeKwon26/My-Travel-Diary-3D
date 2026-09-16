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
    fun path(segment: MovementSegment): List<GeoPoint> {
        val observed = segment.simplifiedPoints.ifEmpty {
            segment.rawPoints.sortedBy { it.timestampEpochMs }.map { it.coordinate }
        }
        return if (segment.effectiveMode == TransportMode.AIRPLANE && observed.size <= 2) {
            WebMercator.generateGreatCirclePath(segment.startPoint, segment.endPoint, 64)
        } else observed.ifEmpty { listOf(segment.startPoint, segment.endPoint) }
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
