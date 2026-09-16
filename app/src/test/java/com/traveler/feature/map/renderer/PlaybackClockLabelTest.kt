package com.traveler.feature.map.renderer

import org.junit.Assert.assertEquals
import org.junit.Test

class PlaybackClockLabelTest {
    @Test fun clockShowsStoryPositionAndHandlesHourLongTripsAndEndpoints() {
        assertEquals("0:00 / 8:40",PlaybackClockLabel.label(0f,520f))
        assertEquals("4:20 / 8:40",PlaybackClockLabel.label(.5f,520f))
        assertEquals("8:40 / 8:40",PlaybackClockLabel.label(1f,520f))
        assertEquals("1:01:01",PlaybackClockLabel.format(3661f))
        assertEquals("0:00",PlaybackClockLabel.format(Float.NaN))
    }
}
