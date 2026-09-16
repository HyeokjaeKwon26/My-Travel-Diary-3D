package com.traveler.domain.usecase

/** Overall percentage is weighted completed work; it never advances on a timer. */
data class ImportProgress(val stage: String, val fraction: Float, val completed: Int? = null, val total: Int? = null)

class ImportProgressEstimator(private val startedMs: Long) {
    private var lastFraction = 0f
    private var lastAdvanceMs = startedMs
    fun remaining(progress: ImportProgress, nowMs: Long): String {
        if(progress.fraction > lastFraction) { lastAdvanceMs = nowMs; lastFraction = progress.fraction }
        if(progress.fraction >= 1f) return "Complete"
        if(nowMs-lastAdvanceMs > 15_000) return "Still processing · estimate updating"
        if(progress.fraction < .12f || nowMs-startedMs < 4000) return "Calculating remaining time…"
        val seconds = ((nowMs-startedMs)/1000.0 * (1-progress.fraction)/progress.fraction).coerceAtLeast(1.0)
        fun duration(s: Double): String = if(s < 60) "${s.toInt().coerceAtLeast(1)} sec" else "${kotlin.math.ceil(s/60).toInt()} min"
        return "About ${duration(seconds*.7)}–${duration(seconds*1.5)} remaining"
    }
}
