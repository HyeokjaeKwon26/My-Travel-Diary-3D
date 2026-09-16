package com.traveler.feature.map.threed

import com.traveler.core.model.TransportMode
import kotlin.math.*

/** Display-only toy physics. Uses story seconds, never wall clock or accumulated frames. */
data class VehiclePose(val pitch:Double,val roll:Double,val bounce:Double,val stretch:Double,val phase:Double)

object VehicleAnimation {
    fun pose(mode:TransportMode,seconds:Double,slope:Double,turn:Double,moving:Boolean=true):VehiclePose {
        val frequency=when(mode) { TransportMode.RUN->2.8;TransportMode.WALK->1.8;TransportMode.BICYCLE->2.3
            TransportMode.AIRPLANE->.75;TransportMode.FERRY->.65;else->1.65 }
        val phase=seconds*2*PI*frequency
        val active=if(moving) 1.0 else 0.0
        val bounce=when(mode) {
            TransportMode.WALK,TransportMode.RUN->.18+.42*abs(sin(phase))
            TransportMode.AIRPLANE->.32+.17*sin(phase)
            TransportMode.FERRY->.18+.16*sin(phase)
            else->.16+.28*(.5+.5*sin(phase*2))
        }*active
        // A long train must not stand almost end-on on a steep downhill grade.
        val pitch=if(mode==TransportMode.TRAIN) (slope*1.6).coerceIn(-.22,.22)+sin(phase)*.04*active
            else (slope*3.2).coerceIn(-.65,.65)+sin(phase)*.095*active
        val bank=when(mode) { TransportMode.AIRPLANE->.60;TransportMode.BICYCLE->.45;else->.24 }
        val roll=(turn*bank+sin(phase*.7)*.07*active).coerceIn(-.6,.6)
        return VehiclePose(pitch,roll,bounce,1.0+.065*sin(phase*2)*active,if(moving)phase else 0.0)
    }

    fun scale(distance:Double,width:Int,height:Int,mode:TransportMode):Double {
        val pixels=(min(width,height)*.16).coerceIn(36.0,132.0) * if(mode==TransportMode.TRAIN) 1.2 else 1.0
        val extent=when(mode) { TransportMode.WALK,TransportMode.RUN,TransportMode.UNKNOWN->2.0
            TransportMode.AIRPLANE->5.4;TransportMode.FERRY->4.2
            TransportMode.TRAIN->6.6;TransportMode.SUBWAY,TransportMode.BUS->4.0;else->3.2 }
        return distance*sqrt(1.13)*2*tan(Math.toRadians(21.0))*pixels/max(1,height)/extent
    }
}
