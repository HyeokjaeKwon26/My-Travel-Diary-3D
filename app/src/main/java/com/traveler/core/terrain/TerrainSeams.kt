package com.traveler.core.terrain

import kotlin.math.roundToLong

/** Adjacent tile edges share exactly the same height, including four-tile corners. */
object TerrainSeams {
    fun stitch(packs:List<TerrainPack>):List<TerrainPack> {
        data class Sum(var value:Double=0.0,var count:Int=0)
        val edge=HashMap<Pair<Long,Long>,Sum>()
        fun key(p:TerrainPack,r:Int,c:Int)=
            ((p.north-(p.north-p.south)*r/(p.rows-1))*1e8).roundToLong() to
                ((((p.west+(p.east-p.west)*c/(p.columns-1)+180)%360)-180)*1e8).roundToLong()
        fun each(p:TerrainPack,action:(Int,Int)->Unit) {
            for(c in 0 until p.columns) { action(0,c);action(p.rows-1,c) }
            for(r in 1 until p.rows-1) { action(r,0);action(r,p.columns-1) }
        }
        packs.forEach { p -> each(p) { r,c -> p.heights[r*p.columns+c]?.let { h ->
            edge.getOrPut(key(p,r,c)) { Sum() }.apply { value+=h;count++ }
        } } }
        return packs.map { p ->
            val heights=p.heights.toMutableList()
            each(p) { r,c -> val sum=edge[key(p,r,c)]
                if(heights[r*p.columns+c]!=null && sum!=null && sum.count>1) heights[r*p.columns+c]=sum.value/sum.count
            }
            p.copy(heights=heights)
        }
    }
}
