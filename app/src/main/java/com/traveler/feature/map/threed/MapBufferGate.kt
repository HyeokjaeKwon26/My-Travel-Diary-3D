package com.traveler.feature.map.threed

/** No timer advances story time. Network failures have a bounded wait and retry cooldown. */
class MapBufferGate {
    private var waitingSince: Long? = null
    private var retryAfter = 0L
    fun update(nowMs: Long, online: Boolean, missingVisibleTiles: Boolean): Boolean {
        if(!online || !missingVisibleTiles) { waitingSince=null;return false }
        if(nowMs < retryAfter) return false
        val start = waitingSince ?: nowMs.also { waitingSince=it }
        if(nowMs-start >= 8000) { waitingSince=null;retryAfter=nowMs+20_000;return false }
        return true
    }
}
