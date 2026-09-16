package com.traveler.feature.trip

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.database.TravelerDatabase
import com.traveler.core.model.*
import com.traveler.data.repository.TripRepositoryImpl
import com.traveler.domain.repository.TripRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface TripDetailUiState {
    object Loading : TripDetailUiState
    data class Success(val trip: Trip) : TripDetailUiState
    data class Error(val message: String) : TripDetailUiState
}

class TravelDiaryViewModel(application: Application) : AndroidViewModel(application) {

    private val repository: TripRepository = TripRepositoryImpl(
        TravelerDatabase.getDatabase(application)
    )

    private val _uiState = MutableStateFlow<TripDetailUiState>(TripDetailUiState.Loading)
    val uiState: StateFlow<TripDetailUiState> = _uiState.asStateFlow()

    private val _focusedLocation = MutableStateFlow<GeoPoint?>(null)
    val focusedLocation: StateFlow<GeoPoint?> = _focusedLocation.asStateFlow()

    private val _selectedPhoto = MutableStateFlow<MediaItem?>(null)
    val selectedPhoto: StateFlow<MediaItem?> = _selectedPhoto.asStateFlow()

    private val _editingSegment = MutableStateFlow<MovementSegment?>(null)
    val editingSegment: StateFlow<MovementSegment?> = _editingSegment.asStateFlow()

    private val _editingVisit = MutableStateFlow<Visit?>(null)
    val editingVisit: StateFlow<Visit?> = _editingVisit.asStateFlow()

    private var currentTripId: String? = null

    private var loadJob: kotlinx.coroutines.Job? = null
    private val _photoPreparation = MutableStateFlow("")
    val photoPreparation: StateFlow<String> = _photoPreparation.asStateFlow()
    fun loadTrip(tripId: String) {
        currentTripId = tripId
        loadJob?.cancel()
        loadJob = viewModelScope.launch {
            _uiState.value = TripDetailUiState.Loading
            try {
                val trip = repository.getTripById(tripId)
                if (trip != null) {
                    val originals = trip.days.flatMap { day -> day.items.flatMap { item ->
                        when(item) {
                            is TripDayItem.VisitItem -> item.photos
                            is TripDayItem.MovementItem -> item.photos
                            is TripDayItem.ContextualPhotosItem -> item.photos
                            is TripDayItem.UnassignedPhotosItem -> item.photos
                        }
                    } }.distinctBy { it.id }
                    val analyzed = com.traveler.core.media.PhotoVisualAnalyzer(getApplication()).analyze(originals) { done,total ->
                        _photoPreparation.value = "Choosing memories on this device · $done / $total"
                    }.associateBy { it.id }
                    fun photos(items: List<MediaItem>) = items.map { analyzed[it.id] ?: it }
                    val prepared = trip.copy(days = trip.days.map { day -> day.copy(items=day.items.map { item ->
                        when(item) {
                            is TripDayItem.VisitItem -> item.copy(photos=photos(item.photos))
                            is TripDayItem.MovementItem -> item.copy(photos=photos(item.photos))
                            is TripDayItem.ContextualPhotosItem -> item.copy(photos=photos(item.photos))
                            is TripDayItem.UnassignedPhotosItem -> item.copy(photos=photos(item.photos))
                        }
                    }) })
                    _photoPreparation.value = ""
                    _uiState.value = TripDetailUiState.Success(prepared)
                } else {
                    _uiState.value = TripDetailUiState.Error("Trip not found")
                }
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                _uiState.value = TripDetailUiState.Error(e.message ?: "Failed to load trip")
            }
        }
    }

    fun focusLocation(point: GeoPoint) {
        _focusedLocation.value = point
    }

    fun openPhotoDetail(photo: MediaItem) {
        _selectedPhoto.value = photo
    }

    fun closePhotoDetail() {
        _selectedPhoto.value = null
    }

    fun openEditTransport(segment: MovementSegment) {
        _editingSegment.value = segment
    }

    fun closeEditTransport() {
        _editingSegment.value = null
    }

    fun openEditVisit(visit: Visit) {
        _editingVisit.value = visit
    }

    fun closeEditVisit() {
        _editingVisit.value = null
    }

    fun overrideTransportMode(segmentId: String, newMode: TransportMode) {
        val tripId = currentTripId ?: return
        viewModelScope.launch {
            repository.updateTransportMode(tripId, segmentId, newMode)
            loadTrip(tripId)
            _editingSegment.value = null
        }
    }

    fun overrideVisitName(visitId: String, newName: String) {
        val tripId = currentTripId ?: return
        viewModelScope.launch {
            repository.updateVisitName(tripId, visitId, newName)
            loadTrip(tripId)
            _editingVisit.value = null
        }
    }

    fun overrideMediaVisit(mediaKey: String, visitId: String?) {
        val tripId = currentTripId ?: return
        viewModelScope.launch {
            repository.updateMediaVisit(tripId, mediaKey, visitId)
            loadTrip(tripId)
        }
    }

    fun toggleRepresentativeMedia(photo: MediaItem) {
        val tripId = currentTripId ?: return
        val newRep = !photo.isRepresentative
        viewModelScope.launch {
            repository.setRepresentativeMedia(tripId, photo.id, newRep)
            loadTrip(tripId)
            _selectedPhoto.value = _selectedPhoto.value?.copy(isRepresentative = newRep)
        }
    }
}
