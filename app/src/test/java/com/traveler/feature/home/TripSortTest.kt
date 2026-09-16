package com.traveler.feature.home

import com.traveler.core.model.Trip
import org.junit.Assert.*
import org.junit.Test

class TripSortTest {
    @Test fun allSixSortOrdersUseTheirOwnField() {
        val trips=listOf(Trip("a","Zulu","2026-01-01","2026-01-03",0.0,createdAtEpochMs=20),
            Trip("b","alpha","2026-02-01","2026-02-01",0.0,createdAtEpochMs=30),
            Trip("c","Beta","2026-03-01","2026-03-01",0.0,createdAtEpochMs=10))
        for((sort,ids) in listOf(TripSort.NAME to listOf("b","c","a"),TripSort.CREATED to listOf("c","a","b"),TripSort.DATE to listOf("a","b","c"))) {
            assertEquals(ids,sort.sorted(trips,true).map { it.id })
            assertEquals(ids.reversed(),sort.sorted(trips,false).map { it.id })
        }
        assertEquals(listOf("a","b"),TripSort.NAME.sorted(listOf(trips[0].copy(id="b"),trips[0]),false).map { it.id })
    }
}
