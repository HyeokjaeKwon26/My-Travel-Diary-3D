package com.traveler.feature.map.threed

/** Stable hysteresis: recover slowly instead of changing quality on every frame. */
enum class QualityMode(val label: String) { AUTO("자동"), BATTERY("발열·배터리 절약"), DETAIL("선명하게") }

class RenderQuality(initialLow:Boolean=false) {
    @Volatile var scale=if(initialLow) .65f else 1f
        private set
    private var slow=0;private var fast=0
    @Volatile var mode = QualityMode.AUTO
    @Volatile var frameIntervalMs = 34L
        private set
    private var cooldown = 0
    fun observe(renderMillis:Double,thermal:Boolean, batterySaver: Boolean = false):Boolean {
        val before=scale
        val conserving = batterySaver || mode == QualityMode.BATTERY
        val ceiling = if (conserving) .65f else 1f
        frameIntervalMs = if (thermal || conserving) 50L else 34L
        if(thermal) { scale=.5f;slow=0;fast=0;cooldown=300 }
        else if(cooldown>0) { cooldown--; fast=0 }
        else if(renderMillis>30) {
            slow++;fast=0
            if(slow>=20) { scale=(scale-.15f).coerceAtLeast(.5f);slow=0 }
        } else if(renderMillis<16) {
            fast++;slow=0
            if(fast >= if(mode == QualityMode.DETAIL) 150 else 300) { scale=(scale+.1f).coerceAtMost(ceiling);fast=0 }
        } else { slow=0;fast=0 }
        scale=scale.coerceAtMost(ceiling)
        return before!=scale
    }
}
