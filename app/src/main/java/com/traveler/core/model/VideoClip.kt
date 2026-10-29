package com.traveler.core.model

import kotlinx.serialization.Serializable

@Serializable
data class VideoClip(val enabled: Boolean = true, val startMs: Long = 0, val durationMs: Long = 5000,
    val originalVolume: Float = 1f, val musicVolume: Float = .25f) {
    fun validate() {
        require(startMs in 0..86_400_000 && durationMs in 1..10_000)
        require(originalVolume.isFinite() && originalVolume in 0f..1f && musicVolume.isFinite() && musicVolume in 0f..1f)
    }
}
