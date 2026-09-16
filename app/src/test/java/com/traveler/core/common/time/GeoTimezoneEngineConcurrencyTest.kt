package com.traveler.core.common.time

import com.traveler.core.common.geo.GeoPoint
import kotlinx.coroutines.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class GeoTimezoneEngineConcurrencyTest {

    @Before
    fun setUp() {
        GeoTimezoneEngine.resetForTesting()
    }

    @Test
    fun singleFlightInitialization_concurrentCallers_onlyInitializesOnce() = runTest {
        val initCount = AtomicInteger(0)

        // Mock engine factory to record initialization invocations
        GeoTimezoneEngine.engineFactory = {
            initCount.incrementAndGet()
            Thread.sleep(50) // Simulate TimeShape polygon parsing latency
            RegionalTimezoneLookup()
        }

        // Launch 20 concurrent coroutines invoking initializeAsync, resolve, and resolveSync
        val jobs = (1..20).map { i ->
            launch(Dispatchers.Default) {
                if (i % 3 == 0) {
                    GeoTimezoneEngine.initializeAsync()
                } else if (i % 3 == 1) {
                    GeoTimezoneEngine.resolve(GeoPoint(42.3503, -71.0810))
                } else {
                    GeoTimezoneEngine.resolveSync(GeoPoint(43.0896, -79.0849))
                }
            }
        }

        jobs.joinAll()

        val finalResolution = GeoTimezoneEngine.resolve(GeoPoint(42.3503, -71.0810))
        assertTrue(finalResolution is TimezoneResolution.Resolved)
        assertEquals("America/New_York", (finalResolution as TimezoneResolution.Resolved).zoneId.id)

        // Strict assertion: TimeShape engine factory must be invoked exactly ONCE
        assertEquals("TimeZoneEngine must be initialized exactly once across concurrent callers", 1, initCount.get())
    }
}
