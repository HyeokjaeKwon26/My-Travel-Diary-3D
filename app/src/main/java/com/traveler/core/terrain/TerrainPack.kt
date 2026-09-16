package com.traveler.core.terrain

import com.traveler.core.common.geo.GeoPoint
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.InputStream
import kotlin.math.*

/** WGS84 regular grid, north-to-south rows, west-to-east columns. Null means no data. */
@Serializable
data class TerrainPack(
    val version: Int = 1,
    val name: String,
    val attribution: String,
    val verticalDatum: String,
    val north: Double,
    val south: Double,
    val west: Double,
    val east: Double,
    val rows: Int,
    val columns: Int,
    val heights: List<Double?>
) {
    fun validate(): TerrainPack = apply {
        require(version == 1) { "Unsupported terrain pack version" }
        require(name.isNotBlank() && name.length <= 120 && attribution.isNotBlank() && attribution.length <= 2000)
        require(verticalDatum.isNotBlank() && verticalDatum.length <= 120)
        require(north.isFinite() && south.isFinite() && east.isFinite() && west.isFinite())
        require(south >= -85 && north <= 85 && north > south && west >= -180 && east <= 180 && east > west)
        require(north - south <= 10 && east - west <= 10) { "Use regional packs up to 10 degrees" }
        require(rows in 2..257 && columns in 2..257 && heights.size == rows * columns)
        require(heights.all { it == null || (it.isFinite() && it in -12000.0..10000.0) })
    }

    fun contains(p: GeoPoint) = p.latitude in south..north && p.longitude in west..east

    fun elevation(p: GeoPoint): Double? {
        if (!contains(p)) return null
        val x = (p.longitude - west) / (east - west) * (columns - 1)
        val y = (north - p.latitude) / (north - south) * (rows - 1)
        val ix = floor(x).toInt().coerceIn(0, columns - 2)
        val iy = floor(y).toInt().coerceIn(0, rows - 2)
        val a = heights[iy * columns + ix] ?: return null
        val b = heights[iy * columns + ix + 1] ?: return null
        val c = heights[(iy + 1) * columns + ix] ?: return null
        val d = heights[(iy + 1) * columns + ix + 1] ?: return null
        val fx = (x - ix).coerceIn(0.0, 1.0); val fy = (y - iy).coerceIn(0.0, 1.0)
        return (a * (1 - fx) + b * fx) * (1 - fy) + (c * (1 - fx) + d * fx) * fy
    }

    /** Match the actual simplified triangle mesh, rather than a bilinear surface
     * that may run above or below a steep rendered face. */
    fun meshElevation(p: GeoPoint): Double? {
        if (!contains(p)) return null
        val step=max(1,(max(rows,columns)-1)/128)
        val x=(p.longitude-west)/(east-west)*(columns-1)
        val y=(north-p.latitude)/(north-south)*(rows-1)
        val ix=(floor(x/step).toInt()*step).coerceIn(0,columns-2)
        val iy=(floor(y/step).toInt()*step).coerceIn(0,rows-2)
        val x1=min(columns-1,ix+step);val y1=min(rows-1,iy+step)
        val a=heights[iy*columns+ix] ?: return null
        val b=heights[iy*columns+x1] ?: return null
        val c=heights[y1*columns+ix] ?: return null
        val d=heights[y1*columns+x1] ?: return null
        val fx=((x-ix)/(x1-ix)).coerceIn(0.0,1.0)
        val fy=((y-iy)/(y1-iy)).coerceIn(0.0,1.0)
        return if(fx+fy<=1) a*(1-fx-fy)+b*fx+c*fy
            else d*(fx+fy-1)+b*(1-fy)+c*(1-fx)
    }

    companion object {
        const val MAX_BYTES = 4 * 1024 * 1024
        fun read(stream: InputStream): TerrainPack {
            val bytes = stream.readBytesBounded(MAX_BYTES)
            return Json.decodeFromString<TerrainPack>(bytes.toString(Charsets.UTF_8)).validate()
        }
        private fun InputStream.readBytesBounded(limit: Int): ByteArray {
            val result = java.io.ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (true) {
                val n = read(buffer)
                if (n < 0) break
                require(result.size() + n <= limit) { "Terrain pack exceeds 4 MB" }
                result.write(buffer, 0, n)
            }
            return result.toByteArray()
        }
    }
}
