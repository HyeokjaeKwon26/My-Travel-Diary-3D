package com.traveler.core.model

import org.junit.Assert.*
import org.junit.Test
import com.traveler.core.common.geo.GeoPoint
import java.time.Instant

class TripVisitSummaryTest {
    @Test fun unnamedStopsAndRevisitsCountButRepeatedSourceDoesNot() {
        val summary = TripVisitSummary.from(listOf(
            TripVisitLabel("overnight", "Hotel"), TripVisitLabel("overnight", "Hotel"),
            TripVisitLabel("return", "Hotel"), TripVisitLabel("unnamed", null)
        ))
        assertEquals(3, summary.visitCount)
        assertEquals(1, summary.unnamedVisitCount)
        assertEquals(listOf("Hotel"), summary.representativeNames)
    }

    @Test fun countsAreNotLimitedByPreviewOrLegacyFiveNames() {
        val summary = TripVisitSummary.from((1..12).map { TripVisitLabel("v$it", "Place $it") })
        assertEquals(12, summary.visitCount)
        assertEquals(listOf("Place 1", "Place 2", "Place 3"), summary.representativeNames)
        assertEquals(9, summary.otherNamedPlaceCount)
    }

    @Test fun homeAndUnknownStopsDoNotImplyAHomeOnlyTrip() {
        val summary = TripVisitSummary.from(listOf(
            TripVisitLabel("1", "Home"), TripVisitLabel("2", null),
            TripVisitLabel("3", " UNKNOWN "), TripVisitLabel("4", "   ")
        ))
        assertEquals(4, summary.visitCount)
        assertEquals(3, summary.unnamedVisitCount)
        assertTrue(summary.representativeNames.isEmpty())
        assertEquals(0, summary.otherNamedPlaceCount)
    }

    @Test fun namedDestinationsComeBeforeHomeAndDuplicateNamesCollapse() {
        val summary = TripVisitSummary.from(listOf(
            TripVisitLabel("1", "Home"), TripVisitLabel("2", " Niagara   Falls "),
            TripVisitLabel("3", "niagara falls"), TripVisitLabel("4", "Museum"),
            TripVisitLabel("5", "Park"), TripVisitLabel("6", null)
        ))
        assertEquals(6, summary.visitCount)
        assertEquals(listOf("Niagara Falls", "Museum", "Park"), summary.representativeNames)
        assertEquals(1, summary.otherNamedPlaceCount)
        assertEquals(1, summary.unnamedVisitCount)
    }

    @Test fun actualHomeOnlyRecordStillHasItsName() {
        assertEquals(listOf("Home"), TripVisitSummary.from(listOf(TripVisitLabel("1", "Home"))).representativeNames)
    }

    @Test fun emptyJourneyHasZeroVisitsAndNoNames() {
        assertEquals(TripVisitSummary(), TripVisitSummary.from(emptyList()))
    }

    @Test fun nearbyCitiesAndLandmarksAreExplicitlyApproximateAndDeduplicated() {
        val summary = TripVisitSummary.from(listOf(
            TripVisitLabel("1", null, GeoPoint(36.1069, -112.1129)),
            TripVisitLabel("2", "UNKNOWN", GeoPoint(36.1069, -112.1129)),
            TripVisitLabel("3", null, GeoPoint(42.3601, -71.0589))
        ))
        assertEquals(3, summary.visitCount)
        assertEquals(0, summary.unresolvedVisitCount)
        assertEquals(listOf("Grand Canyon 인근", "Boston 인근"), summary.representativeNames)
        assertTrue(summary.hasApproximateRegions)
    }

    @Test fun remoteCoordinatesRemainUnresolvedInsteadOfNamingDistantPlaces() {
        val summary = TripVisitSummary.from(listOf(TripVisitLabel("ocean", null, GeoPoint(0.0, -140.0))))
        assertTrue(summary.representativeNames.isEmpty())
        assertEquals(1, summary.unresolvedVisitCount)
        assertFalse(summary.hasApproximateRegions)
    }

    @Test fun userAndRecordedNamesBeatProximityAndHomeDoesNotDominate() {
        val boston = GeoPoint(42.3601, -71.0589)
        val summary = TripVisitSummary.from(listOf(
            TripVisitLabel("home", "Home", boston, durationMs = 86_400_000, photoCount = 1000),
            TripVisitLabel("region", null, GeoPoint(36.1069, -112.1129)),
            TripVisitLabel("named", "My hotel", boston),
            TripVisitLabel("manual", "My favourite picnic spot", boston, isUserOverride = true)
        ))
        assertEquals(listOf("My favourite picnic spot", "My hotel", "Grand Canyon 인근"), summary.representativeNames)
        assertEquals(1, summary.otherNamedPlaceCount)
    }

    @Test fun photosAndDurationPreferMeaningfulStopsOverBriefPasses() {
        val summary = TripVisitSummary.from(listOf(
            TripVisitLabel("short", "Short stop"),
            TripVisitLabel("stay", "Long stay", durationMs = 7_200_000),
            TripVisitLabel("photos", "Photo stop", photoCount = 25)
        ))
        assertEquals(listOf("Photo stop", "Long stay", "Short stop"), summary.representativeNames)
    }

    @Test fun equallyImportantStopsCoverDifferentLocalVisitDates() {
        val first = Instant.parse("2026-07-01T12:00:00Z").toEpochMilli()
        val summary = TripVisitSummary.from(listOf(
            TripVisitLabel("1", "First", startEpochMs = first, timezoneId = "UTC"),
            TripVisitLabel("2", "Same day", startEpochMs = first, timezoneId = "UTC"),
            TripVisitLabel("3", "Next day", startEpochMs = first + 86_400_000, timezoneId = "UTC")
        ))
        assertEquals(listOf("First", "Next day", "Same day"), summary.representativeNames)
    }

    @Test fun manualGenericNamesAreKeptAndFallbackDoesNotMutateRecords() {
        val original = TripVisitLabel("manual", "Home", GeoPoint(42.3601, -71.0589), isUserOverride = true)
        val summary = TripVisitSummary.from(listOf(original))
        assertEquals(listOf("Home"), summary.representativeNames)
        assertFalse(summary.hasApproximateRegions)
        assertEquals("Home", original.name)
    }
}
