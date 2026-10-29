package com.traveler.core.media

import com.traveler.core.model.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class PortableMediaTest {
    private fun media(id: String) = MediaItem(id, "content://gallery/$id", "$id.jpg", "image/jpeg", null)
    @Test fun collectionIncludesUndatedAndDayOnlyMediaAndDeduplicates() {
        val trip = Trip("t", "Test", "2026-01-01", "2026-01-01", 0.0,
            days = listOf(TripDay(1, "2026-01-01", items = listOf(TripDayItem.UnassignedPhotosItem(listOf(media("a")))),
                unassignedPhotos = listOf(media("a"), media("b")))), uncertainDateMedia = listOf(media("c")))
        assertEquals(listOf("a", "b", "c").toSet(), trip.allMedia().map { it.id }.toSet())
        assertTrue(trip.mapMedia { it.copy(contentUriString = "content://copy/${it.id}") }.allMedia()
            .all { it.contentUriString.startsWith("content://copy/") })
        val restored = PortableTripArchive.remap(trip, "new")
        assertEquals("new", restored.id)
        assertEquals(setOf("new:a", "new:b", "new:c"), restored.allMedia().map { it.id }.toSet())
    }

    @Test fun archiveNamesCannotEscapeOrAliasStagingDirectory() {
        listOf("../a", "media/../a", "media/1.bin/extra", "media\\1.bin", "/media/1.bin", "C:/a", "media/1.jpg")
            .forEach { assertFalse(it, PortableTripArchive.safeEntry(it)) }
        assertTrue(PortableTripArchive.safeEntry("media/19999.bin"))
    }

    @Test fun boundedCopyRejectsOverflowBeforeWritingBeyondLimit() = runBlocking {
        val output = ByteArrayOutputStream()
        try { TripMediaFiles.copy(ByteArrayInputStream(ByteArray(100)), output, 99); fail("Expected bound") }
        catch (_: IllegalArgumentException) { assertEquals(0, output.size()) }
    }

    @Test fun copyHonorsCancellation() = runBlocking {
        val output = ByteArrayOutputStream()
        val task = launch {
            val owner = currentCoroutineContext().job
            val input = object : ByteArrayInputStream(ByteArray(200_000)) {
                override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
                    val count = super.read(buffer, offset, length)
                    owner.cancel()
                    return count
                }
            }
            TripMediaFiles.copy(input, output, 200_000)
        }
        task.join()
        assertTrue(task.isCancelled)
        assertTrue(output.size() < 200_000)
    }
}
