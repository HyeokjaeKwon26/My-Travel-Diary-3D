package com.traveler.feature.importtrip

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.traveler.core.classifier.RuleBasedTransportClassifier
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.common.time.GeoTimezoneEngine
import com.traveler.core.database.TravelerDatabase
import com.traveler.core.media.AndroidMediaStoreScanner
import com.traveler.core.media.MediaRepository
import com.traveler.core.media.RawMediaCandidate
import com.traveler.core.timeline.GoogleTimelineJsonParser
import com.traveler.data.repository.TripRepositoryImpl
import com.traveler.domain.usecase.CreateTripUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.time.LocalDate

sealed interface ImportUiState {
    object Idle : ImportUiState
    data class Loading(val status: String, val progress: com.traveler.domain.usecase.ImportProgress? = null, val startedMs: Long = android.os.SystemClock.elapsedRealtime()) : ImportUiState
    data class Success(val tripId: String) : ImportUiState
    data class Error(val message: String) : ImportUiState
}

class ImportTripViewModel(application: Application) : AndroidViewModel(application) {

    private val tripRepository = TripRepositoryImpl(TravelerDatabase.getDatabase(application))

    private val createTripUseCase = CreateTripUseCase(
        locationHistorySource = GoogleTimelineJsonParser(),
        mediaRepository = AndroidMediaStoreScanner(application),
        transportClassifier = RuleBasedTransportClassifier(),
        tripRepository = tripRepository,
        analyzePhotos = com.traveler.core.media.PhotoVisualAnalyzer(application)::analyze,
        prepareMemories = { com.traveler.core.media.tripMemoryStore(application).prepare(it) }
    )

    private val _uiState = MutableStateFlow<ImportUiState>(ImportUiState.Idle)
    val uiState: StateFlow<ImportUiState> = _uiState.asStateFlow()

    private var importJob: kotlinx.coroutines.Job? = null
    private var startedMs = 0L
    private fun updateProgress(progress: com.traveler.domain.usecase.ImportProgress) {
        if (importJob?.isActive == true) _uiState.value = ImportUiState.Loading(progress.stage, progress, startedMs)
    }
    fun cancelImport() {
        importJob?.cancel()
        _uiState.value = ImportUiState.Idle
    }

    fun createTripFromUri(
        uri: Uri,
        startDate: LocalDate,
        endDate: LocalDate,
        customTitle: String?
    ) {
        if (importJob?.isCompleted == false) return
        startedMs = android.os.SystemClock.elapsedRealtime()
        importJob = viewModelScope.launch {
            _uiState.value = ImportUiState.Loading("Reading and parsing Timeline JSON...")
            try {
                val context = getApplication<Application>()
                val stream = context.contentResolver.openInputStream(uri)
                    ?: throw IllegalArgumentException("Cannot open selected file stream")

                val trip = stream.use { s ->
                    createTripUseCase.execute(
                        timelineStream = s,
                        startDate = startDate,
                        endDate = endDate,
                        customTitle = customTitle,
                        onWorkProgress = ::updateProgress
                    )
                }

                _uiState.value = ImportUiState.Success(trip.id)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                _uiState.value = ImportUiState.Error(e.message ?: "Failed to generate travel story")
            }
        }
    }

    fun createSampleDemoTrip() {
        if (importJob?.isCompleted == false) return
        startedMs = android.os.SystemClock.elapsedRealtime()
        importJob = viewModelScope.launch {
            _uiState.value = ImportUiState.Loading("Generating Sample Boston & Niagara Trip...")
            try {
                val demoJson = getSampleTimelineJson()
                val stream = ByteArrayInputStream(demoJson.toByteArray())

                // Synthetic Media Repository with local fixture candidates
                val sampleMediaRepo = object : MediaRepository {
                    override suspend fun queryMediaCandidatesForDateRange(startTimestampEpochMs: Long, endTimestampEpochMs: Long): List<RawMediaCandidate> {
                        return getSampleMediaCandidates()
                    }
                }

                val demoUseCase = CreateTripUseCase(
                    locationHistorySource = GoogleTimelineJsonParser(),
                    mediaRepository = sampleMediaRepo,
                    transportClassifier = RuleBasedTransportClassifier(),
                    tripRepository = tripRepository
                )

                val trip = demoUseCase.execute(
                    timelineStream = stream,
                    startDate = LocalDate.of(2026, 7, 1),
                    endDate = LocalDate.of(2026, 7, 2),
                    customTitle = "🇨🇦 Boston to Niagara Road & Flight",
                    onWorkProgress = ::updateProgress
                )
                _uiState.value = ImportUiState.Success(trip.id)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) {
                _uiState.value = ImportUiState.Error(e.message ?: "Failed to generate demo trip")
            }
        }
    }

    fun createCanyonDemoTrip() {
        if (importJob?.isCompleted == false) return
        startedMs = android.os.SystemClock.elapsedRealtime()
        importJob = viewModelScope.launch {
            _uiState.value = ImportUiState.Loading("Preparing offline Grand Canyon demo…")
            try {
                val trip = com.traveler.feature.map.threed.CanyonDemo.trip()
                tripRepository.saveTrip(trip)
                _uiState.value = ImportUiState.Success(trip.id)
            } catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (e: Exception) { _uiState.value = ImportUiState.Error(e.message ?: "Could not create demo") }
        }
    }

    fun resetState() {
        _uiState.value = ImportUiState.Idle
    }

    private fun computeEpochMs(isoLocal: String, offset: String): Long {
        val ldt = java.time.LocalDateTime.parse(isoLocal)
        val zoneOffset = java.time.ZoneOffset.of(offset)
        return ldt.atOffset(zoneOffset).toInstant().toEpochMilli()
    }

    private fun getSampleMediaCandidates(): List<RawMediaCandidate> {
        val ts1 = computeEpochMs("2026-07-01T08:20:15", "-04:00")
        val ts2 = computeEpochMs("2026-07-01T11:45:00", "-04:00")
        val ts3 = computeEpochMs("2026-07-02T11:30:00", "-04:00")
        val ts4 = computeEpochMs("2026-07-02T07:30:00", "-04:00")

        return listOf(
            RawMediaCandidate(
                id = "DEMO_IMG_01",
                contentUriString = "android.resource://com.traveler.threed/drawable/demo_boston",
                fileName = "IMG_20260701_082015_BostonBackBay.jpg",
                mimeType = "image/jpeg",
                exifDateTimeOriginal = "2026:07:01 08:20:15",
                exifOffset = "-04:00",
                mediaStoreDateTaken = ts1,
                fileDateModifiedMs = ts1,
                directGps = GeoPoint(42.3503, -71.0810)
            ),
            RawMediaCandidate(
                id = "DEMO_IMG_02",
                contentUriString = "android.resource://com.traveler.threed/drawable/demo_flight",
                fileName = "IMG_20260701_114500_FlightWing.jpg",
                mimeType = "image/jpeg",
                exifDateTimeOriginal = "2026:07:01 11:45:00",
                exifOffset = "-04:00",
                mediaStoreDateTaken = ts2,
                fileDateModifiedMs = ts2,
                directGps = null
            ),
            RawMediaCandidate(
                id = "DEMO_IMG_03",
                contentUriString = "android.resource://com.traveler.threed/drawable/demo_niagara",
                fileName = "IMG_20260702_113000_NiagaraHorseshoe.jpg",
                mimeType = "image/jpeg",
                exifDateTimeOriginal = "2026:07:02 11:30:00",
                exifOffset = "-04:00",
                mediaStoreDateTaken = ts3,
                fileDateModifiedMs = ts3,
                directGps = GeoPoint(43.0896, -79.0849)
            ),
            RawMediaCandidate(
                id = "DEMO_IMG_04",
                contentUriString = "android.resource://com.traveler.threed/drawable/demo_unassigned",
                fileName = "IMG_20260702_073000_MorningCoffee.jpg",
                mimeType = "image/jpeg",
                exifDateTimeOriginal = "2026:07:02 07:30:00",
                exifOffset = "-04:00",
                mediaStoreDateTaken = ts4,
                fileDateModifiedMs = ts4,
                directGps = null // Unassigned photo during Niagara morning
            )
        )
    }

    private fun getSampleTimelineJson(): String = """
    {
      "semanticSegments": [
        {
          "startTime": "2026-07-01T08:00:00-04:00",
          "endTime": "2026-07-01T08:45:00-04:00",
          "visit": {
            "topCandidate": {
              "placeId": "ChIJ_BostonHome",
              "placeName": "Boston Back Bay",
              "probability": 0.95,
              "placeLocation": { "latLng": "42.3503°, -71.0810°" }
            }
          }
        },
        {
          "startTime": "2026-07-01T08:45:00-04:00",
          "endTime": "2026-07-01T09:30:00-04:00",
          "activity": {
            "topCandidate": { "type": "IN_PASSENGER_VEHICLE", "probability": 0.92 },
            "distanceMeters": 12000.0,
            "start": { "latLng": "42.3503°, -71.0810°" },
            "end": { "latLng": "42.3656°, -71.0096°" },
            "simplifiedRawPath": {
              "points": [
                { "latLng": "42.3503°, -71.0810°", "timestamp": "2026-07-01T08:45:00-04:00" },
                { "latLng": "42.3656°, -71.0096°", "timestamp": "2026-07-01T09:30:00-04:00" }
              ]
            }
          }
        },
        {
          "startTime": "2026-07-01T09:30:00-04:00",
          "endTime": "2026-07-01T11:00:00-04:00",
          "visit": {
            "topCandidate": {
              "placeId": "ChIJ_BOS",
              "placeName": "Boston Logan Airport (BOS)",
              "probability": 0.98,
              "placeLocation": { "latLng": "42.3656°, -71.0096°" }
            }
          }
        },
        {
          "startTime": "2026-07-01T11:00:00-04:00",
          "endTime": "2026-07-01T12:30:00-04:00",
          "activity": {
            "topCandidate": { "type": "FLYING", "probability": 0.96 },
            "distanceMeters": 690000.0,
            "start": { "latLng": "42.3656°, -71.0096°" },
            "end": { "latLng": "43.6777°, -79.6248°" },
            "simplifiedRawPath": {
              "points": [
                { "latLng": "42.3656°, -71.0096°", "timestamp": "2026-07-01T11:00:00-04:00" },
                { "latLng": "43.6777°, -79.6248°", "timestamp": "2026-07-01T12:30:00-04:00" }
              ]
            }
          }
        },
        {
          "startTime": "2026-07-01T12:30:00-04:00",
          "endTime": "2026-07-01T14:00:00-04:00",
          "visit": {
            "topCandidate": {
              "placeId": "ChIJ_YYZ",
              "placeName": "Toronto Pearson Airport (YYZ)",
              "probability": 0.95,
              "placeLocation": { "latLng": "43.6777°, -79.6248°" }
            }
          }
        },
        {
          "startTime": "2026-07-02T09:00:00-04:00",
          "endTime": "2026-07-02T10:30:00-04:00",
          "activity": {
            "topCandidate": { "type": "IN_PASSENGER_VEHICLE", "probability": 0.88 },
            "distanceMeters": 130000.0,
            "start": { "latLng": "43.6532°, -79.3832°" },
            "end": { "latLng": "43.0896°, -79.0849°" },
            "simplifiedRawPath": {
              "points": [
                { "latLng": "43.6532°, -79.3832°", "timestamp": "2026-07-02T09:00:00-04:00" },
                { "latLng": "43.0896°, -79.0849°", "timestamp": "2026-07-02T10:30:00-04:00" }
              ]
            }
          }
        },
        {
          "startTime": "2026-07-02T10:30:00-04:00",
          "endTime": "2026-07-02T16:00:00-04:00",
          "visit": {
            "topCandidate": {
              "placeId": "ChIJ_Niagara",
              "placeName": "Niagara Falls",
              "probability": 0.99,
              "placeLocation": { "latLng": "43.0896°, -79.0849°" }
            }
          }
        },
        {
          "startTime": "2026-07-02T16:00:00-04:00",
          "endTime": "2026-07-02T17:30:00-04:00",
          "activity": {
            "topCandidate": { "type": "WALKING", "probability": 0.94 },
            "distanceMeters": 3500.0,
            "start": { "latLng": "43.0896°, -79.0849°" },
            "end": { "latLng": "43.0850°, -79.0780°" }
          }
        },
        {
          "startTime": "2026-07-02T17:30:00-04:00",
          "endTime": "2026-07-02T20:00:00-04:00",
          "visit": {
            "topCandidate": {
              "placeId": "ChIJ_Skylon",
              "placeName": "Skylon Tower Observation",
              "probability": 0.96,
              "placeLocation": { "latLng": "43.0850°, -79.0780°" }
            }
          }
        }
      ]
    }
    """.trimIndent()
}
