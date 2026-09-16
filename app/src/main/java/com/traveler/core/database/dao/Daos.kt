package com.traveler.core.database.dao

import androidx.room.*
import com.traveler.core.database.entity.*
import kotlinx.coroutines.flow.Flow

@Dao
interface TripDao {
    @Query("""
        SELECT t.*, v.sourceId AS summaryVisitId,
               COALESCE(o.overrideValue, v.placeName) AS summaryPlaceName,
               v.latitude AS summaryLatitude, v.longitude AS summaryLongitude,
               v.startTimestampEpochMs AS summaryStart, v.endTimestampEpochMs AS summaryEnd,
               v.timezoneId AS summaryTimezone,
               (o.id IS NOT NULL OR COALESCE(v.isUserOverride, 0)) AS summaryUserOverride,
               COALESCE(p.photoCount, 0) AS summaryPhotoCount
        FROM trips t
        LEFT JOIN visits v ON v.tripId = t.id
        LEFT JOIN user_overrides o ON o.targetType = 'VISIT_NAME' AND o.targetSourceId = v.sourceId
        LEFT JOIN (
            SELECT tripId, matchedVisitId, COUNT(*) AS photoCount FROM trip_media
            WHERE matchedVisitId IS NOT NULL GROUP BY tripId, matchedVisitId
        ) p ON p.tripId = v.tripId AND p.matchedVisitId = v.sourceId
        ORDER BY t.createdAtEpochMs DESC, t.id, v.startTimestampEpochMs, v.sourceId
    """)
    fun getTripCardRowsFlow(): Flow<List<TripCardRow>>

    @Query("SELECT * FROM trips ORDER BY createdAtEpochMs DESC")
    fun getAllTripsFlow(): Flow<List<TripEntity>>

    @Query("SELECT * FROM trips WHERE id = :tripId")
    suspend fun getTripById(tripId: String): TripEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrip(trip: TripEntity)

    @Query("DELETE FROM trips WHERE id = :tripId")
    suspend fun deleteTrip(tripId: String)
}

@Dao
interface VisitDao {
    @Query("SELECT * FROM visits WHERE tripId = :tripId ORDER BY startTimestampEpochMs ASC")
    suspend fun getVisitsForTrip(tripId: String): List<VisitEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVisits(visits: List<VisitEntity>)

    @Query("DELETE FROM visits WHERE tripId = :tripId")
    suspend fun deleteVisitsForTrip(tripId: String)

    @Query("DELETE FROM visits WHERE tripId NOT IN (SELECT id FROM trips)")
    suspend fun deleteOrphanVisits(): Int

    @Query("UPDATE visits SET placeName = :name, isUserOverride = 1 WHERE tripId = :tripId AND sourceId = :sourceId")
    suspend fun updateVisitName(tripId: String, sourceId: String, name: String)
}

@Dao
interface MovementSegmentDao {
    @Query("SELECT * FROM movement_segments WHERE tripId = :tripId ORDER BY startTimestampEpochMs ASC")
    suspend fun getSegmentsForTrip(tripId: String): List<MovementSegmentEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSegments(segments: List<MovementSegmentEntity>)

    @Query("DELETE FROM movement_segments WHERE tripId = :tripId")
    suspend fun deleteSegmentsForTrip(tripId: String)

    @Query("DELETE FROM movement_segments WHERE tripId NOT IN (SELECT id FROM trips)")
    suspend fun deleteOrphanSegments(): Int

    @Query("UPDATE movement_segments SET userOverrideTransportMode = :mode, isUserOverride = 1 WHERE tripId = :tripId AND sourceId = :sourceId")
    suspend fun updateTransportMode(tripId: String, sourceId: String, mode: String)
}

@Dao
interface TripMediaDao {
    @Query("SELECT * FROM trip_media WHERE tripId = :tripId ORDER BY timestampEpochMs ASC")
    suspend fun getMediaForTrip(tripId: String): List<TripMediaEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMediaItems(items: List<TripMediaEntity>)

    @Query("DELETE FROM trip_media WHERE tripId = :tripId")
    suspend fun deleteMediaForTrip(tripId: String)

    @Query("DELETE FROM trip_media WHERE tripId NOT IN (SELECT id FROM trips)")
    suspend fun deleteOrphanMedia(): Int

    @Query("UPDATE trip_media SET matchedVisitId = :visitId, matchedSegmentId = NULL, isUserLocationOverride = 1 WHERE tripId = :tripId AND mediaKey = :mediaKey")
    suspend fun updateMediaVisit(tripId: String, mediaKey: String, visitId: String?)

    @Query("UPDATE trip_media SET isRepresentative = :isRepresentative WHERE tripId = :tripId AND mediaKey = :mediaKey")
    suspend fun updateRepresentative(tripId: String, mediaKey: String, isRepresentative: Boolean)
}

@Dao
interface UserOverrideDao {
    @Query("SELECT * FROM user_overrides WHERE targetSourceId = :sourceId")
    suspend fun getOverridesForSource(sourceId: String): List<UserOverrideEntity>

    @Query("SELECT * FROM user_overrides")
    suspend fun getAllOverrides(): List<UserOverrideEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertOverride(override: UserOverrideEntity)

    @Query("DELETE FROM user_overrides WHERE targetType = :targetType AND targetSourceId = :sourceId")
    suspend fun deleteOverride(targetType: String, sourceId: String)
}
