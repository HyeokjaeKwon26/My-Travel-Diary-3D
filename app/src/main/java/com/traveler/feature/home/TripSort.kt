package com.traveler.feature.home

import com.traveler.core.model.Trip
import java.text.Collator
import java.util.Locale

enum class TripSort(val label: String) {
    NAME("이름순"), CREATED("만든순"), DATE("여행 날짜순");

    fun sorted(trips: List<Trip>, ascending: Boolean): List<Trip> {
        val collator = Collator.getInstance(Locale.getDefault()).apply { strength = Collator.PRIMARY }
        val primary = when (this) {
            NAME -> Comparator<Trip> { a, b -> collator.compare(a.title, b.title) }
            CREATED -> compareBy<Trip> { it.createdAtEpochMs }
            DATE -> compareBy<Trip> { it.startDateIso }.thenBy { it.endDateIso }
        }
        return trips.sortedWith((if (ascending) primary else primary.reversed()).thenBy { it.id })
    }
}
