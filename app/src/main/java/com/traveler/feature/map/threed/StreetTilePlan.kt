package com.traveler.feature.map.threed

import kotlin.math.*

data class StreetTile(val z:Int,val x:Int,val y:Int) {
    val key get()="$z-$x-$y"
}
data class MapFootprint(val left:Double,val top:Double,val right:Double,val bottom:Double)
data class StreetTilePlan(val tiles:List<StreetTile>,val zoom:Int) {
    companion object {
        /** Current visible footprint only; never a journey corridor or zoom stack. */
        fun visible(f:MapFootprint,width:Int,height:Int):StreetTilePlan {
            val span=max(f.right-f.left,f.bottom-f.top).coerceAtLeast(1e-8)
            var z=ceil(log2(max(width,height)/(256.0*span))).toInt().coerceIn(2,17)
            fun tiles(zoom:Int):List<StreetTile> {
                val n=1 shl zoom
                val x0=floor(f.left*n).toInt();val x1=floor(f.right*n-1e-9).toInt().coerceAtLeast(x0)
                val y0=floor(f.top*n).toInt().coerceIn(0,n-1)
                val y1=floor(f.bottom*n-1e-9).toInt().coerceIn(y0,n-1)
                if((x1-x0+1).toLong()*(y1-y0+1)>24) return emptyList()
                return (x0..x1).flatMap { x -> (y0..y1).map { y -> StreetTile(zoom,((x%n)+n)%n,y) } }.distinct()
            }
            var result=tiles(z)
            while(result.isEmpty() && z>0) { z--;result=tiles(z) }
            val n = (1 shl z).toDouble()
            val cx = (f.left+f.right)*.5*n
            val cy = (f.top+f.bottom)*.5*n
            return StreetTilePlan(result.sortedBy { t ->
                val dx = t.x+.5-cx
                val wrapped = dx-round(dx/n)*n
                wrapped*wrapped+(t.y+.5-cy).pow(2)
            },z)
        }
    }
}

object NorthUpCamera {
    fun distance(mode:com.traveler.core.model.TransportMode?,flightSpan:Double=6.0):Double = when(mode) {
        com.traveler.core.model.TransportMode.AIRPLANE -> (flightSpan*111000/EarthGeometry.R*1.3).coerceIn(.04,2.8)
        com.traveler.core.model.TransportMode.TRAIN,com.traveler.core.model.TransportMode.SUBWAY,
        com.traveler.core.model.TransportMode.FERRY -> .00055
        com.traveler.core.model.TransportMode.WALK,com.traveler.core.model.TransportMode.RUN,
        com.traveler.core.model.TransportMode.BICYCLE -> .00018
        else -> .0003
    }
}
