package com.traveler.feature.home

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.traveler.core.database.TravelerDatabase
import com.traveler.core.model.Trip
import com.traveler.data.repository.TripRepositoryImpl
import com.traveler.domain.repository.TripRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class HomeViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: TripRepository = TripRepositoryImpl(
        TravelerDatabase.getDatabase(application)
    )

    private val preferences = application.getSharedPreferences("home_sort", 0)
    val sort = MutableStateFlow(TripSort.entries.firstOrNull { it.name == preferences.getString("sort", null) } ?: TripSort.CREATED)
    val ascending = MutableStateFlow(preferences.getBoolean("ascending", false))
    val trips: StateFlow<List<Trip>> = combine(repository.getAllTrips(), sort, ascending) { trips, sort, ascending ->
        sort.sorted(trips, ascending)
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun setSort(value: TripSort, increasing: Boolean = ascending.value) {
        sort.value = value
        ascending.value = increasing
        preferences.edit().putString("sort", value.name).putBoolean("ascending", increasing).apply()
    }

    init {
        viewModelScope.launch {
            repository.cleanOrphanTripData()
        }
    }

    fun deleteTrip(tripId: String) {
        viewModelScope.launch {
            repository.deleteTrip(tripId)
            com.traveler.core.media.tripMemoryStore(getApplication()).delete(tripId)
        }
    }
}
