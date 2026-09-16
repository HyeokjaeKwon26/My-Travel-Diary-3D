package com.traveler.feature.home

import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.room.Room
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.traveler.MainActivity
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.database.TravelerDatabase
import com.traveler.core.database.entity.*
import com.traveler.core.model.*
import com.traveler.data.repository.TripRepositoryImpl
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.collect
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.time.Instant
import java.io.File

class TripCardAndroidTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val start = Instant.parse("2026-07-01T20:00:00Z").toEpochMilli()
    private fun trip(id: String) = Trip(id, "Saved journey", "2026-07-01", "2026-07-03", 123_000.0,
        cities = listOf("Home"), totalMediaCount = 30)
    private fun visit(id: String, name: String?) = Visit(id, name, location = GeoPoint(42.0, -71.0),
        startTimestampEpochMs = start, endTimestampEpochMs = start + 86_400_000, timezoneId = "UTC")

    @Test fun legacyTripsUseAllVisitsAndDurableRenamesRefreshEveryCard() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TravelerDatabase::class.java).build()
        try {
            val repository = TripRepositoryImpl(db)
            val stops = (1..8).map { visit("v$it", if (it == 1) "Home" else null) }
            repository.saveTrip(trip("a").copy(days = listOf(TripDay(1, "2026-07-01",
                items = stops.map { TripDayItem.VisitItem(it) }))))
            // The same source may belong to two overlapping trips; overrides apply to both.
            repository.saveTrip(trip("b").copy(days = listOf(TripDay(1, "2026-07-01",
                items = listOf(TripDayItem.VisitItem(stops[1]))))))
            repository.saveTrip(trip("empty"))
            val updates = Channel<List<Trip>>(Channel.UNLIMITED)
            val observer = launch { repository.getAllTrips().collect { updates.send(it) } }
            try {
                val initial = withTimeout(10_000) { updates.receive() }.associateBy { it.id }
                assertEquals(8, initial.getValue("a").visitSummary.visitCount)
                assertTrue(initial.getValue("a").visitSummary.representativeNames.isEmpty())
                assertEquals(0, initial.getValue("empty").visitSummary.visitCount)
                assertTrue(initial.getValue("a").days.isEmpty())
                repository.updateVisitName("a", "v2", "Niagara Falls")
                val renamed = withTimeout(10_000) {
                    var latest: List<Trip>
                    do { latest = updates.receive() } while (latest.any {
                        it.id != "empty" && "Niagara Falls" !in it.visitSummary.representativeNames
                    })
                    latest.associateBy { it.id }
                }
                assertEquals(8, renamed.getValue("a").visitSummary.visitCount)
                assertEquals(6, renamed.getValue("a").visitSummary.unnamedVisitCount)
                assertEquals(listOf("Niagara Falls", "Home"), renamed.getValue("a").visitSummary.representativeNames)
                assertEquals(repository.getTripById("a")!!.visitSummary, renamed.getValue("a").visitSummary)
                assertEquals(repository.getTripById("b")!!.visitSummary, renamed.getValue("b").visitSummary)
                // Legacy cache stays untouched: the new card derives its answer from visits.
                assertEquals("[\"Home\"]", db.tripDao().getTripById("a")!!.citiesJson)
            } finally { observer.cancelAndJoin(); updates.close() }
        } finally { db.close() }
    }

    @Test fun overnightSourceCountsOnceAndReturnVisitCountsAgain() = runBlocking {
        val db = Room.inMemoryDatabaseBuilder(context, TravelerDatabase::class.java).build()
        try {
            val repository = TripRepositoryImpl(db)
            val hotel = visit("overnight", "Hotel")
            val returning = hotel.copy(id = "return", startTimestampEpochMs = start + 100_000_000,
                endTimestampEpochMs = start + 110_000_000)
            repository.saveTrip(trip("stay").copy(days = listOf(
                TripDay(1, "2026-07-01", items = listOf(TripDayItem.VisitItem(hotel))),
                TripDay(2, "2026-07-02", items = listOf(TripDayItem.VisitItem(hotel))),
                TripDay(3, "2026-07-03", items = listOf(TripDayItem.VisitItem(returning)))
            )))
            val summary = repository.getAllTrips().first().single().visitSummary
            assertEquals(2, summary.visitCount)
            assertEquals(listOf("Hotel"), summary.representativeNames)
            assertEquals(2, db.visitDao().getVisitsForTrip("stay").size)
            assertEquals(repository.getTripById("stay")!!.visitSummary, summary)
        } finally { db.close() }
    }

    @Test fun badgesWrapWithinNarrowCardAtLargeFont() {
        val fixture = trip("layout").copy(title = "A very long travel title that must leave room for Delete",
            totalDistanceMeters = 12_584_500.0, totalMediaCount = 3267,
            visitSummary = TripVisitSummary.from((1..12).map { TripVisitLabel("v$it", "Place $it") }))
        compose.activityRule.scenario.onActivity { activity -> activity.setContent {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.5f)) {
                MaterialTheme { Box(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
                    Box(Modifier.width(320.dp)) { TripCard(fixture, {}, {}) }
                } }
            }
        } }
        compose.onNodeWithText("방문 기록 12회").assertIsDisplayed()
        compose.onNodeWithText("대표 장소: Place 1 · Place 2 · Place 3 외 9곳").assertIsDisplayed()
        val card = compose.onNodeWithTag("trip-card-layout").fetchSemanticsNode().boundsInRoot
        val badges = listOf("12584.5 km", "3267 photos", "방문 기록 12회").map {
            compose.onNodeWithText(it, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        }
        badges.forEach { assertTrue(card.contains(it.topLeft)); assertTrue(card.contains(it.bottomRight)) }
        for (i in badges.indices) for (j in i + 1 until badges.size) assertFalse(badges[i].overlaps(badges[j]))
        compose.onNodeWithContentDescription("Delete").assertIsDisplayed()
        compose.waitForIdle()
        assertTrue(UiDevice.getInstance(InstrumentationRegistry.getInstrumentation()).takeScreenshot(
            File(context.getExternalFilesDir(null), "rc8-card-large-font.png")))
    }
}
