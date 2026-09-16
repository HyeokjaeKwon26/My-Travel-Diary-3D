package com.traveler.core.media

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.*
import com.traveler.feature.map.story.TravelStoryTimeline
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class TripMemoryStoreTest {
    @get:Rule val temp = TemporaryFolder()
    private val start = 1_780_000_000_000L
    private val segment = MovementSegment("car",start,start+3_600_000,GeoPoint(42.0,-71.0),GeoPoint(42.2,-71.2),
        distanceMeters=30_000.0,durationMillis=3_600_000,transport=TransportPrediction(TransportMode.CAR,1f,"fixture"))
    private fun photo(id: String, minute: Int) = MediaItem(id,"content://fixture/$id","$id.jpg","image/jpeg",start+minute*60_000,
        timestampConfidence=TimestampConfidence.EXIF_EXACT,matchedSegmentId="car",confidenceScore=1f)
    private fun trip(photos: List<MediaItem>) = Trip("trip","Test","2026-05-28","2026-05-28",30_000.0,
        days=listOf(TripDay(1,"2026-05-28",items=listOf(TripDayItem.MovementItem(segment,photos)))),createdAtEpochMs=123)

    @Test fun reopeningAfterProcessRestartUsesDurableSelectionEvenWhenAnalysisFailed() = runBlocking {
        val input = trip(listOf(photo("a",15),photo("b",45)))
        var analyzed=0
        val first=TripMemoryStore(temp.root) { items, _, _ -> analyzed+=items.size; items }.prepare(input)
        assertEquals(2,analyzed)
        val second=TripMemoryStore(temp.root) { _,_,_ -> error("Reopen must not analyze or query images") }.prepare(input)
        assertEquals(first.memorySnapshot,second.memorySnapshot)
        for (profile in StoryDurationProfile.entries) {
            val a=TravelStoryTimeline.build(first.memoryRenderModel(),profile)
            val b=TravelStoryTimeline.build(second.memoryRenderModel(),profile)
            assertTrue(a.photoMoments.isNotEmpty())
            assertEquals(a.photoMoments,b.photoMoments)
            assertEquals(a.totalStoryDurationSeconds,b.totalStoryDurationSeconds,0f)
        }
    }

    @Test fun onlyAddedPhotosAreAnalyzedAndManualSelectionIsPreservedOnRefresh() = runBlocking {
        val calls=mutableListOf<List<String>>()
        val forced=mutableListOf<Boolean>()
        val store=TripMemoryStore(temp.root) { items,force,_ -> calls+=items.map { it.id };forced+=force;items }
        store.prepare(trip(listOf(photo("a",15))))
        val two=trip(listOf(photo("a",15).copy(isRepresentative=true),photo("b",45)))
        val changed=store.prepare(two)
        assertEquals(listOf(listOf("a"),listOf("b")),calls)
        assertTrue(changed.memorySnapshot!!.selections.values.all { moments -> moments.any { it.mediaId=="a" && it.photo.isRepresentative } })
        store.prepare(two,refresh=true)
        assertEquals(listOf("a","b"),calls.last())
        assertTrue(forced.last())
        val removed=store.prepare(trip(listOf(photo("b",45))))
        assertEquals(3,calls.size)
        assertTrue(removed.memorySnapshot!!.selections.values.flatten().none { it.mediaId=="a" })
    }

    @Test fun failedRefreshDoesNotReplaceSavedSelectionAndDeletionRemovesFile() = runBlocking {
        val input=trip(listOf(photo("a",15)))
        val store=TripMemoryStore(temp.root) { items,_,_ -> items }
        val first=store.prepare(input)
        try { TripMemoryStore(temp.root) { _,_,_ -> throw kotlinx.coroutines.CancellationException() }.prepare(input,true);fail() }
        catch (_: kotlinx.coroutines.CancellationException) { }
        assertEquals(first.memorySnapshot,store.prepare(input).memorySnapshot)
        store.delete(input.id)
        assertEquals(0,temp.root.listFiles()!!.size)
    }
}
