package com.traveler.core.common.time

import net.iakovlev.timeshape.TimeZoneEngine
import org.junit.Assert.assertEquals
import org.junit.Test

class RegionalTimezoneLookupTest {
    @Test fun regionalSelectionMatchesFullSourceAtCitiesBordersAndOceans() {
        val full=TimeZoneEngine.initialize()
        val regional=RegionalTimezoneLookup()
        val points=listOf(
            42.3503 to -71.0810,43.0896 to -79.0849,43.0962 to -79.0377,
            37.5665 to 126.9780,55.7558 to 37.6173,62.0355 to 129.6755,
            61.2181 to -149.9003,-31.677 to 128.884,27.7172 to 85.3240,
            -36.8485 to 174.7633,-17.7134 to 178.0650,0.0 to -140.0,
            0.0 to 180.0,0.0 to -180.0,49.0001 to -123.0,48.9999 to -123.0
        )
        for((lat,lon) in points) assertEquals("$lat,$lon",full.query(lat,lon),regional.query(lat,lon))
        assertEquals(full.query(42.3503,-71.0810),regional.query(42.3503,-71.0810))
    }
}
