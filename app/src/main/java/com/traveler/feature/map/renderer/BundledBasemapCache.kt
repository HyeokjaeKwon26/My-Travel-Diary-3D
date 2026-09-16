package com.traveler.feature.map.renderer

import android.content.Context
import java.io.DataInputStream
import java.io.BufferedInputStream

/** Shared preprojected cartography for both 2D and 3D; never parse the large regional JSON in the app. */
object BundledBasemapCache {
    private var value: Pair<PreparedBasemap,PreparedBasemap>? = null
    @Synchronized fun load(context: Context): Pair<PreparedBasemap,PreparedBasemap> = value ?: context.assets
        // AAPT expands .gz source assets and packages this as basemap_3d.bin.
        .open("basemap_3d.bin").use { stream ->
            DataInputStream(BufferedInputStream(stream,65536)).use { input ->
                check(input.readInt()==0x4d415033 && input.readInt()==1) { "Unsupported bundled map" }
                readMap(input) to readMap(input)
            }
        }.also { value = it }

    // Preprojected binary coordinates avoid constructing millions of JSON objects
    // or reparsing 50+ MB of JSON when a GLES context is recreated.
    private fun readMap(input:DataInputStream):PreparedBasemap {
        fun string():String { val bytes=ByteArray(input.readInt());input.readFully(bytes);return bytes.toString(Charsets.UTF_8) }
        fun ring():PreparedPolygonRing {
            val a=input.readFloat();val b=input.readFloat();val c=input.readFloat();val d=input.readFloat()
            return PreparedPolygonRing(a,b,c,d,FloatArray(input.readInt()) { input.readFloat() })
        }
        var count=0
        val polygons=List(input.readInt()) {
            val name=string();val lake=input.readBoolean()
            val rings=List(input.readInt()) { ring().also { count+=it.pointCount } }
            PreparedBasemapPolygon(name,lake,rings.minOf { it.minWx },rings.maxOf { it.maxWx },
                rings.minOf { it.minWy },rings.maxOf { it.maxWy },rings)
        }
        val lines=List(input.readInt()) {
            val name=string();val type=string();val r=ring();count+=r.pointCount
            PreparedBasemapBoundary(name,type,r.minWx,r.maxWx,r.minWy,r.maxWy,r.worldCoords)
        }
        val places=List(input.readInt()) { PreparedBasemapPlace(string(),input.readFloat(),input.readFloat(),input.readInt()) }
        return PreparedBasemap(polygons,lines,places,count)
    }
}
