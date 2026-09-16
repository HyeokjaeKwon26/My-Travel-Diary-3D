package com.traveler.feature.map.renderer

/** Story time, independent of playback speed; matches the seek bar and exported timeline. */
object PlaybackClockLabel {
    fun format(seconds: Float): String {
        val total = if (seconds.isFinite()) seconds.toLong().coerceAtLeast(0) else 0L
        return if (total >= 3600) "%d:%02d:%02d".format(java.util.Locale.US, total / 3600, total / 60 % 60, total % 60)
        else "%d:%02d".format(java.util.Locale.US, total / 60, total % 60)
    }
    fun label(progress: Float, duration: Float) = "${format(progress.coerceIn(0f, 1f) * duration)} / ${format(duration)}"
}
