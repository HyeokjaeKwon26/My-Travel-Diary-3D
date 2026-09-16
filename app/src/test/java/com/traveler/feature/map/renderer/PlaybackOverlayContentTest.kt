package com.traveler.feature.map.renderer

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.*
import com.traveler.feature.map.story.TravelStoryTimeline
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.TimeZone

class PlaybackOverlayContentTest {
    private fun ms(s: String) = Instant.parse(s).toEpochMilli()
    private fun visit(zone: String? = "America/New_York") = Visit("v", location = GeoPoint(43.0, -79.0),
        startTimestampEpochMs = ms("2026-08-20T03:00:00Z"), endTimestampEpochMs = ms("2026-08-20T06:00:00Z"), timezoneId = zone)

    @Test fun midnightAndBackwardSeekUseCalendarDayNotElapsed24Hours() {
        val timeline = TravelStoryTimeline.build(TravelMapRenderModel(listOf(visit()), emptyList()))
        val base = timeline.evaluate(.5f)
        val before = base.copy(storyTimeMs = ms("2026-08-20T03:59:00Z"), episodeIndex = 0)
        val after = base.copy(storyTimeMs = ms("2026-08-20T04:01:00Z"), episodeIndex = 0)
        assertEquals("2026.08.19", PlaybackOverlayContent.date(before, timeline))
        assertEquals("Day 4", PlaybackOverlayContent.day(before, timeline, "2026-08-16"))
        assertEquals("2026.08.20", PlaybackOverlayContent.date(after, timeline))
        assertEquals("Day 5", PlaybackOverlayContent.day(after, timeline, "2026-08-16"))
        assertEquals("Day 4", PlaybackOverlayContent.day(before, timeline, "2026-08-16"))
    }

    @Test fun titleEndAndDeviceTimezoneDoNotChangeDates() {
        val timeline = TravelStoryTimeline.build(TravelMapRenderModel(listOf(visit()), emptyList()))
        val previous = TimeZone.getDefault()
        try {
            TimeZone.setDefault(TimeZone.getTimeZone("Asia/Seoul"))
            assertEquals("2026.08.19", PlaybackOverlayContent.date(timeline.evaluate(0f), timeline))
            assertEquals("2026.08.20", PlaybackOverlayContent.date(timeline.evaluate(1f), timeline))
        } finally { TimeZone.setDefault(previous) }
    }

    @Test fun flightUsesDepartureUntilArrivalThenDestinationDate() {
        val start = ms("2026-08-20T05:00:00Z"); val end = start + 12 * 3_600_000
        val segment = MovementSegment("flight", start, end, GeoPoint(35.0, 139.0), GeoPoint(34.0, -118.0),
            distanceMeters = 8_000_000.0, durationMillis = end-start,
            transport = TransportPrediction(TransportMode.AIRPLANE, 1f, "test"),
            startTimezoneId = "Asia/Tokyo", endTimezoneId = "America/Los_Angeles")
        val timeline = TravelStoryTimeline.build(TravelMapRenderModel(emptyList(), listOf(segment)))
        val base = timeline.evaluate(.5f).copy(episodeIndex = 0)
        assertEquals("2026.08.21", PlaybackOverlayContent.date(base.copy(storyTimeMs = end-1), timeline))
        assertEquals("2026.08.20", PlaybackOverlayContent.date(base.copy(storyTimeMs = end), timeline))
    }

    @Test fun unknownTimezoneIsExplicitAndNeverUsesPhoneZone() {
        val timeline = TravelStoryTimeline.build(TravelMapRenderModel(listOf(visit("invalid/zone")), emptyList()))
        assertEquals("2026.08.20 UTC", PlaybackOverlayContent.date(timeline.evaluate(0f), timeline))
    }

    @Test fun portraitLandscapeAndPanoramaKeepFullAspectInsideBounds() {
        for ((w,h) in listOf(3000f to 4000f, 4000f to 3000f, 12000f to 2000f, 1000f to 8000f)) {
            for ((mw,mh) in listOf(140f to 90f, 480f to 320f, 240f to 400f)) {
                val fitted = PlaybackOverlayContent.fitPhoto(w,h,mw,mh)
                assertEquals(w/h, fitted.width/fitted.height, .0001f)
                assertTrue(fitted.width <= mw+.001f && fitted.height <= mh+.001f)
                assertTrue(kotlin.math.abs(fitted.width-mw)<.001f || kotlin.math.abs(fitted.height-mh)<.001f)
            }
        }
    }
}
