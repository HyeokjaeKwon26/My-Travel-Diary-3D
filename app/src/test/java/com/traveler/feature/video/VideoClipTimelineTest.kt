package com.traveler.feature.video

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.media.StoryDurationProfile
import com.traveler.core.model.*
import com.traveler.feature.map.renderer.TravelMapRenderModel
import com.traveler.feature.map.story.TravelStoryTimeline
import org.junit.Assert.*
import org.junit.Test

class VideoClipTimelineTest {
    @Test fun selectedClipsKeepTheirFullDurationWithoutOverlapping() {
        val time = 1767225600000L
        val visit = Visit("visit", location = GeoPoint(42.0, -71.0), startTimestampEpochMs = time,
            endTimestampEpochMs = time + 3600000, confidence = 1f)
        val media = listOf(3000L, 7000L, 10000L).mapIndexed { i, duration ->
            MediaItem("clip-$i", "content://media/$i", "$i.mp4", "video/mp4", time + 1000L * (i + 1),
                timestampConfidence = TimestampConfidence.EXIF_EXACT, matchedVisitId = visit.id, isRepresentative = true, videoClip = VideoClip(durationMs = duration))
        }
        val timeline = TravelStoryTimeline.build(TravelMapRenderModel(listOf(visit), emptyList(), media), StoryDurationProfile.FULL_STORY)
        val moments = timeline.photoMoments.sortedBy { it.displayStartStorySeconds }
        assertEquals(3, moments.size)
        moments.forEach { moment ->
            assertEquals(moment.photo.videoClip!!.durationMs / 1000f, moment.durationStorySeconds, .02f)
            assertTrue(moment.displayStartStorySeconds >= moment.parentEpisodeStoryStart)
            assertTrue(moment.displayEndStorySeconds <= moment.parentEpisodeStoryEnd)
            assertEquals(moment.mediaId, timeline.evaluateAtStoryTime(moment.scheduledStoryTimeSeconds).activePhoto?.id)
        }
        moments.zipWithNext().forEach { (a,b) -> assertTrue(a.displayEndStorySeconds <= b.displayStartStorySeconds) }
    }
    @Test fun explicitlyPlacedUnknownTimeMediaCanAppearWithoutInventingCaptureTime() {
        val visit = Visit("v", location = GeoPoint(42.0,-71.0), startTimestampEpochMs = 10000, endTimestampEpochMs = 20000, confidence = 1f)
        val media = MediaItem("placed", "content://media/placed", "placed.mp4", "video/mp4", null,
            matchedVisitId = "v", assignedDayIso = "2026-01-01", isUserLocationOverride = true,
            dayAssignmentProvenance = "USER_SELECTED", isRepresentative = true, videoClip = VideoClip(durationMs=3000))
        val moments = com.traveler.core.media.PhotoStoryEngine.buildGlobalPhotoStoryMoments(listOf(visit), emptyList(), listOf(media))
        assertEquals(1,moments.size); assertNull(moments.single().captureTimestampEpochMs)
        assertNull(moments.single().photo.timestampEpochMs)
        assertEquals(com.traveler.core.media.PhotoStoryEligibility.USER_PLACED,moments.single().eligibility)
        assertEquals(15000L,moments.single().effectiveStoryTimestampEpochMs)
    }
    @Test fun mixingClampsInsteadOfWrappingAndHonorsMutedTracks() {
        assertEquals(32767.toShort(), ClipAudioMath.mix(30000, 30000, 1f, 1f))
        assertEquals((-32768).toShort(), ClipAudioMath.mix(-30000, -30000, 1f, 1f))
        assertEquals(2500.toShort(), ClipAudioMath.mix(10000, 30000, .25f, 0f))
        assertEquals(0.toShort(), ClipAudioMath.mix(10000, -10000, 1f, 1f))
    }
    @Test fun invalidClipSettingsAreRejected() {
        listOf(VideoClip(startMs = -1), VideoClip(durationMs = 10001), VideoClip(durationMs = 0),
            VideoClip(originalVolume = Float.NaN), VideoClip(musicVolume = 1.1f)).forEach {
            assertTrue(runCatching { it.validate() }.isFailure)
        }
    }
}
