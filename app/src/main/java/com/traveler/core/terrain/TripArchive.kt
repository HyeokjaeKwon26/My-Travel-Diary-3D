package com.traveler.core.terrain

import android.content.Context
import android.net.Uri
import com.traveler.core.model.*
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.database.TravelerDatabase
import com.traveler.data.repository.TripRepositoryImpl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.util.UUID

@Serializable
private data class JourneyArchive(val format:String="MyTravelDiary3D",val version:Int=1,val trip:Trip)

object TripArchive {
    private const val MAX=32*1024*1024
    suspend fun export(context:Context,trip:Trip,uri:Uri)=withContext(Dispatchers.IO) {
        val bytes=Json.encodeToString(JourneyArchive(trip=trip)).toByteArray()
        require(bytes.size<=MAX) { "Journey is too large for a single backup" }
        (context.contentResolver.openOutputStream(uri,"wt") ?: error("Cannot write backup")).use { it.write(bytes) }
    }
    suspend fun restore(context:Context,uri:Uri):String=withContext(Dispatchers.IO) {
        val bytes=(context.contentResolver.openInputStream(uri) ?: error("Cannot open backup")).use { input ->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) { val n=input.read(buffer);if(n<0) break;require(out.size()+n<=MAX) { "Backup exceeds 32 MB" };out.write(buffer,0,n) }
            out.toByteArray()
        }
        val archive=Json.decodeFromString<JourneyArchive>(bytes.toString(Charsets.UTF_8))
        require(archive.format=="MyTravelDiary3D" && archive.version==1) { "Unsupported backup format" }
        validate(archive.trip)
        // A new id prevents an imported backup from replacing a saved journey.
        val trip=archive.trip.copy(id=UUID.randomUUID().toString())
        TripRepositoryImpl(TravelerDatabase.getDatabase(context)).saveTrip(trip)
        trip.id
    }

    internal fun validate(trip:Trip) {
        val start=java.time.LocalDate.parse(trip.startDateIso)
        val end=java.time.LocalDate.parse(trip.endDateIso)
        require(start.year in 1900..2300 && end>=start && java.time.temporal.ChronoUnit.DAYS.between(start,end)<=4000)
        require(trip.days.size<=4000 && trip.title.length<=500 && trip.days.sumOf { it.items.size }<=20_000)
        fun point(p:GeoPoint) {
            require(p.latitude.isFinite() && p.latitude in -90.0..90.0 && p.longitude.isFinite() && p.longitude in -180.0..180.0)
            require(p.altitudeMeters==null || (p.altitudeMeters.isFinite() && p.altitudeMeters in -12000.0..100000.0))
        }
        fun visit(v:Visit) { point(v.location);require(v.endTimestampEpochMs>=v.startTimestampEpochMs);v.timezoneId?.let { java.time.ZoneId.of(it) } }
        fun segment(s:MovementSegment) {
            point(s.startPoint);point(s.endPoint);s.simplifiedPoints.forEach(::point);s.rawPoints.forEach { point(it.coordinate) }
            require(s.distanceMeters.isFinite() && s.distanceMeters>=0 && s.endTimestampEpochMs>=s.startTimestampEpochMs)
            s.startTimezoneId?.let { java.time.ZoneId.of(it) };s.endTimezoneId?.let { java.time.ZoneId.of(it) }
        }
        fun photos(items:List<MediaItem>) { items.forEach { p ->
            require(p.contentUriString.length<=4096 && (p.contentUriString.startsWith("content://") || p.contentUriString.startsWith("android.resource://"))) { "Backup contains a non-local photo reference" }
            p.location?.let(::point);p.captureTimezoneId?.let { java.time.ZoneId.of(it) }
            p.assignedDayIso?.let { java.time.LocalDate.parse(it) }
        } }
        trip.days.forEach { day ->
            val date=java.time.LocalDate.parse(day.dateIso);require(date>=start && date<=end)
            day.timezoneId?.let { java.time.ZoneId.of(it) };photos(day.unassignedPhotos)
            day.items.forEach { item -> when(item) {
                is TripDayItem.VisitItem -> { visit(item.visit);photos(item.photos) }
                is TripDayItem.MovementItem -> { segment(item.segment);photos(item.photos) }
                is TripDayItem.ContextualPhotosItem -> { item.parentVisit?.let(::visit);item.parentSegment?.let(::segment);photos(item.photos) }
                is TripDayItem.UnassignedPhotosItem -> photos(item.photos)
            } }
        }
        photos(trip.uncertainDateMedia)
    }
}
