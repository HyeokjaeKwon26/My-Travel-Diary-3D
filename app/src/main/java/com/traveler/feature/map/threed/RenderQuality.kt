package com.traveler.feature.map.threed

/** Stable hysteresis: recover slowly instead of changing quality on every frame. */
class RenderQuality(initialLow:Boolean=false) {
    @Volatile var scale=if(initialLow) .65f else 1f
        private set
    private var slow=0;private var fast=0
    fun observe(renderMillis:Double,thermal:Boolean):Boolean {
        val before=scale
        if(thermal) { scale=.5f;slow=0;fast=0 }
        else if(renderMillis>30) {
            slow++;fast=0
            if(slow>=20) { scale=(scale-.15f).coerceAtLeast(.5f);slow=0 }
        } else if(renderMillis<16) {
            fast++;slow=0
            if(fast>=300) { scale=(scale+.1f).coerceAtMost(1f);fast=0 }
        } else { slow=0;fast=0 }
        return before!=scale
    }
}
