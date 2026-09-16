package com.traveler.core.model

import org.junit.Assert.*
import org.junit.Test

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
}
