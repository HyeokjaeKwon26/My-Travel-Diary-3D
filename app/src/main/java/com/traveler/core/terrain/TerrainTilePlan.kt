package com.traveler.core.terrain

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.common.geo.GeodesicUtils
import com.traveler.core.model.TransportMode
import com.traveler.feature.map.renderer.TravelMapRenderModel
import kotlinx.serialization.Serializable
import java.security.MessageDigest
import kotlin.math.*

@Serializable
data class TerrainTileId(val z: Int, val x: Int, val y: Int) {
    init { require(z in 7..13 && x in 0 until (1 shl z) && y in 0 until (1 shl z)) }
    val key: String get() = "$z-$x-$y"
    val west get() = x.toDouble() / (1 shl z) * 360 - 180
    val east get() = (x + 1.0) / (1 shl z) * 360 - 180
    val north get() = latitude(y.toDouble(), z)
    val south get() = latitude(y + 1.0, z)
    companion object {
        fun latitude(y: Double, z: Int) = Math.toDegrees(atan(sinh(PI * (1 - 2 * y / (1 shl z)))))
        fun mercatorY(lat: Double, z: Int): Double {
            val r = Math.toRadians(lat.coerceIn(-85.0, 85.0))
            return (1 - ln(tan(r) + 1 / cos(r)) / PI) / 2 * (1 shl z)
        }
        fun at(p: GeoPoint, z: Int): TerrainTileId {
            val n = 1 shl z
            val lng = ((p.longitude + 180) % 360 + 360) % 360
            return TerrainTileId(z, floor(lng / 360 * n).toInt().coerceIn(0, n-1),
                floor(mercatorY(p.latitude, z)).toInt().coerceIn(0, n-1))
        }
    }
}

@Serializable
data class TerrainJourneyPlan(val key: String, val tiles: List<TerrainTileId>, val limited: Boolean = false,
    val pinned: Boolean = false, val paused: Boolean = false, val allowMetered: Boolean = false) {
    val estimatedDownloadBytes get() = tiles.size * 100_000L
}

/** Corridor coverage, never a continent-sized bounding rectangle. Work and memory are bounded. */
object TerrainTilePlanner {
    const val MAX_TILES = 256
    fun plan(model: TravelMapRenderModel): TerrainJourneyPlan {
        val paths = model.segments.filter { it.effectiveMode != TransportMode.AIRPLANE }
            .map { SharedRouteGeometry.path(it) } + model.visits.map { listOf(it.location) }
        val digest = MessageDigest.getInstance("SHA-256")
        for (path in paths) {
            digest.update(0.toByte())
            for(p in path) digest.update("${p.latitude},${p.longitude};".toByteArray())
        }
        val key = digest.digest().take(16).joinToString("") { "%02x".format(it) }
        for(z in 12 downTo 7) {
            val tiles = linkedSetOf<TerrainTileId>()
            fun add(p: GeoPoint) {
                if(abs(p.latitude)>85 || !p.latitude.isFinite() || !p.longitude.isFinite()) return
                val t = TerrainTileId.at(p,z); val n = 1 shl z
                for(dy in -1..1) for(dx in -1..1) if(t.y+dy in 0 until n)
                    tiles.add(TerrainTileId(z,(t.x+dx+n)%n,t.y+dy))
            }
            outer@ for(path in paths) {
                path.firstOrNull()?.let(::add)
                for((a,b) in path.zipWithNext()) {
                    val spacing = max(500.0, 40_000_000.0/(1 shl z)*cos(Math.toRadians(a.latitude))*.4)
                    val count = ceil(GeodesicUtils.distanceMeters(a,b)/spacing).toInt().coerceIn(1,4096)
                    for(i in 1..count) {
                        add(GeodesicUtils.interpolate(a,b,i.toDouble()/count))
                        if(tiles.size>MAX_TILES) break@outer
                    }
                }
                if(tiles.size>MAX_TILES) break
            }
            if(tiles.size<=MAX_TILES) return TerrainJourneyPlan(key,tiles.toList(),z<12 || paths.any { path -> path.any { abs(it.latitude)>85 } })
        }
        // Do not quietly download only the first half of an oversized journey.
        return TerrainJourneyPlan(key,emptyList(),limited=true)
    }
}
