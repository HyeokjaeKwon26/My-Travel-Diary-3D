package com.traveler.feature.map.threed

import android.content.Context
import android.graphics.*
import com.traveler.core.common.geo.WebMercator
import com.traveler.feature.map.renderer.*
import kotlin.math.*

/** One bounded, reusable local atlas, shared by the screen and video renderer.
 * Geometry comes from bundled cartography; elevation availability never gates the map. */
class OfflineMapDrape(context: Context) {
    private val maps = BundledBasemapCache.load(context)
    private val base = maps.first
    private val detail = maps.second
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    data class Window(val x: Double, val y: Double, val span: Double) {
        fun containsCenter(wx: Double, wy: Double, wantedSpan: Double): Boolean {
            val dx = wx - (x + span / 2)
            val wrapped = dx - round(dx)
            return abs(wrapped) < span * .18 && abs(wy - (y + span / 2)) < span * .18 &&
                wantedSpan / span in .75..1.3
        }
    }

    fun window(latitude: Double, longitude: Double, distance: Double, aspect: Double): Window {
        val p = WebMercator.project(latitude, longitude)
        // The oblique chase camera sees farther ahead and to the side than a
        // straight rear view. Cover that frustum before falling back to the globe.
        val span = (distance * 3.5 * max(1.0, aspect) /
            (2 * PI * cos(Math.toRadians(latitude)).coerceAtLeast(.08))).coerceIn(.0002, .45)
        return Window(p.x - span / 2, p.y - span / 2, span)
    }

    fun rasterize(w: Window, size: Int = 2048): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(WATER)
        val scale = size / w.span
        fun intersects(minX: Float, maxX: Float, minY: Float, maxY: Float, shift: Int) =
            maxX + shift >= w.x && minX + shift <= w.x + w.span &&
                maxY >= w.y && minY <= w.y + w.span
        fun line(coords: FloatArray, shift: Int, close: Boolean) {
            for (i in coords.indices step 2) {
                val x = ((coords[i].toDouble() + shift - w.x) * scale).toFloat()
                val y = ((coords[i + 1].toDouble() - w.y) * scale).toFloat()
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            if (close) path.close()
        }
        fun polygons(polygons: List<PreparedBasemapPolygon>, urban: Boolean = false) {
            paint.style = Paint.Style.FILL
            for (p in polygons) for (shift in -1..1) {
                if (!intersects(p.minWx,p.maxWx,p.minWy,p.maxWy,shift)) continue
                path.reset(); path.fillType = Path.FillType.EVEN_ODD
                for (ring in p.rings) line(ring.worldCoords,shift,true)
                paint.color = if (urban) URBAN else if (p.isLake) WATER else LAND
                canvas.drawPath(path,paint)
                if (!urban) {
                    paint.style = Paint.Style.STROKE; paint.strokeWidth = 1.5f; paint.color = COAST
                    canvas.drawPath(path,paint); paint.style = Paint.Style.FILL
                }
            }
        }
        polygons(base.polygons)
        polygons(detail.polygons,true)
        // Water is above urban areas, including islands/holes preserved by even-odd paths.
        polygons(base.polygons.filter { it.isLake })
        val named = mutableListOf<Triple<String, Float, Float>>()
        fun lines(lines: List<PreparedBasemapBoundary>) {
            paint.style = Paint.Style.STROKE; paint.strokeJoin = Paint.Join.ROUND
            for (b in lines) for (shift in -1..1) {
                if (!intersects(b.minWx,b.maxWx,b.minWy,b.maxWy,shift)) continue
                path.reset(); line(b.worldCoords,shift,false)
                when(b.boundaryType) {
                    "ROAD" -> {
                        paint.color = ROAD_EDGE; paint.strokeWidth = 6f; canvas.drawPath(path,paint)
                        paint.color = ROAD; paint.strokeWidth = 3.5f
                    }
                    "RIVER" -> { paint.color = WATER_LINE; paint.strokeWidth = 3f }
                    else -> { paint.color = BORDER; paint.strokeWidth = 1.7f }
                }
                canvas.drawPath(path,paint)
                if (b.boundaryType == "RIVER" && b.name.isNotBlank() && b.name != "River") {
                    val i = (b.worldCoords.size / 4) * 2
                    named.add(Triple(b.name,((b.worldCoords[i]+shift-w.x)*scale).toFloat(),
                        ((b.worldCoords[i+1]-w.y)*scale).toFloat()))
                }
            }
        }
        lines(base.boundaries); lines(detail.boundaries)
        val occupied = mutableListOf<RectF>()
        paint.typeface = Typeface.create(Typeface.DEFAULT,Typeface.BOLD)
        paint.textSize = 20f
        fun label(name: String, x: Float, y: Float, river: Boolean = false) {
            if (x !in 12f..size-12f || y !in 32f..size-12f) return
            val box = RectF(x-3,y-22,x+paint.measureText(name)+6,y+8)
            if (occupied.any { RectF.intersects(it,box) }) return
            occupied.add(box)
            paint.style = Paint.Style.STROKE; paint.strokeWidth = 3f; paint.color = Color.WHITE
            canvas.drawText(name,x,y,paint)
            paint.style = Paint.Style.FILL; paint.color = if(river) WATER_TEXT else TEXT
            canvas.drawText(name,x,y,paint)
        }
        for (p in base.places.sortedBy { it.rank }) for (shift in -1..1) {
            val x = ((p.wx + shift - w.x)*scale).toFloat()
            val y = ((p.wy - w.y)*scale).toFloat()
            if (x in 12f..size-12f && y in 32f..size-12f) {
                paint.style=Paint.Style.FILL;paint.color=TEXT;canvas.drawCircle(x-6,y-7,3f,paint)
                label(p.name,x,y)
            }
        }
        for ((name,x,y) in named.distinctBy { it.first }) label(name,x,y,true)
        return bitmap
    }

    companion object {
        val LAND = Color.rgb(221,229,201)
        val WATER = Color.rgb(123,184,207)
        val URBAN = Color.rgb(228,226,213)
        val COAST = Color.rgb(110,158,163)
        val ROAD = Color.rgb(255,248,220)
        val ROAD_EDGE = Color.rgb(198,165,104)
        val WATER_LINE = Color.rgb(81,152,190)
        val BORDER = Color.rgb(149,154,137)
        val TEXT = Color.rgb(44,62,66)
        val WATER_TEXT = Color.rgb(35,102,139)
    }
}
