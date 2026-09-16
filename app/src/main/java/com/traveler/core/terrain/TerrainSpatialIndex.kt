package com.traveler.core.terrain

import com.traveler.core.common.geo.GeoPoint
import kotlin.math.floor

/** Keep route elevation lookups local even when a journey spans hundreds of tiles. */
class TerrainSpatialIndex(packs:List<TerrainPack>) {
    private val cells=HashMap<Pair<Int,Int>,MutableList<TerrainPack>>()
    init {
        packs.forEach { p ->
            for(lat in floor(p.south).toInt()..floor(p.north).toInt())
                for(lng in floor(p.west).toInt()..floor(p.east).toInt())
                    cells.getOrPut(lat to lng) { mutableListOf() }.add(p)
        }
    }
    fun elevation(p:GeoPoint):Double? = cells[floor(p.latitude).toInt() to floor(p.longitude).toInt()]
        ?.firstNotNullOfOrNull { it.meshElevation(p) }
}
