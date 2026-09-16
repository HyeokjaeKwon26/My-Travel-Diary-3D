package com.traveler.feature.map.renderer

import com.traveler.feature.map.story.StoryEpisode
import com.traveler.feature.map.story.TravelStoryTimeline
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/** Shared by interactive playback and video; never depends on the phone's timezone. */
object PlaybackOverlayContent {
    private val dateFormat = DateTimeFormatter.ofPattern("yyyy.MM.dd", Locale.US)

    fun date(state: TravelPlaybackState, timeline: TravelStoryTimeline): String {
        val episode = timeline.episodes.getOrNull(state.episodeIndex)
            ?: (if (state.isEndCardActive) timeline.episodes.lastOrNull() else timeline.episodes.firstOrNull())
            ?: return "Date unavailable"
        // A movement keeps its departure timezone until arrival. Do not run a geographic
        // timezone lookup on every frame, or invent timezone boundaries over the ocean.
        val zoneName = when (episode) {
            is StoryEpisode.VisitEpisode -> episode.visit.timezoneId
            is StoryEpisode.MovementEpisode -> episode.segment.let {
                if (state.storyTimeMs >= it.endTimestampEpochMs) it.endTimezoneId ?: it.startTimezoneId
                else it.startTimezoneId ?: it.endTimezoneId
            }
        }
        val zone = zoneName?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        val formatted = dateFormat.format(Instant.ofEpochMilli(state.storyTimeMs).atZone(zone ?: ZoneOffset.UTC))
        return if (zone == null) "$formatted UTC" else formatted
    }

    fun day(state: TravelPlaybackState, timeline: TravelStoryTimeline, tripStartDateIso: String?): String {
        val current = runCatching { LocalDate.parse(date(state, timeline).take(10), dateFormat) }.getOrNull()
            ?: return ""
        val start = tripStartDateIso?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            ?: runCatching { LocalDate.parse(date(timeline.evaluate(0f), timeline).take(10), dateFormat) }.getOrNull()
            ?: return ""
        return "Day ${(ChronoUnit.DAYS.between(start, current) + 1).coerceAtLeast(1)}"
    }

    fun mode(state: TravelPlaybackState): String = when {
        state.isTitleCardActive || state.isEndCardActive -> "Journey"
        state.currentSegment?.id?.startsWith("bridge_") == true -> "Estimated"
        state.currentVisit != null -> "Stop"
        else -> state.currentTransportMode.name.lowercase().replaceFirstChar { it.uppercase() }
    }

    fun distance(state: TravelPlaybackState): String {
        fun km(meters: Double): String {
            val km = meters / 1000.0
            return String.format(Locale.US, if (km >= 100) "%,.0f" else "%,.1f", km)
        }
        return "${km(state.currentTraveledDistanceMeters)} / ${km(state.totalTripDistanceMeters)} km"
    }

    data class PhotoSize(val width: Float, val height: Float)

    /** Contain, never crop. Dimensions must describe the upright, EXIF-corrected image. */
    fun fitPhoto(width: Float, height: Float, maxWidth: Float, maxHeight: Float): PhotoSize {
        require(width > 0 && height > 0 && maxWidth >= 0 && maxHeight >= 0)
        val scale = minOf(maxWidth / width, maxHeight / height)
        return PhotoSize(width * scale, height * scale)
    }
}
