package com.traveler.feature.map.threed

import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.common.geo.GeodesicUtils
import com.traveler.core.model.*
import java.time.Instant

/** Deliberately labelled illustration, not a user's recorded or road-matched journey. */
object CanyonDemo {
    fun trip(): Trip {
        val start=Instant.parse("2026-07-01T16:00:00Z").toEpochMilli()
        val coordinates=listOf(36.055 to -112.140,36.034 to -112.125,36.015 to -112.100,
            36.012 to -112.070,36.022 to -112.040,36.024 to -112.000,36.021 to -111.972,
            36.016 to -111.940,36.023 to -111.900,36.034 to -111.860,36.044 to -111.826)
        val points=coordinates.map { GeoPoint(it.first,it.second) }
        val distance=GeodesicUtils.pathDistanceMeters(points)
        val segment=MovementSegment("canyon-demo-drive",start,start+3_600_000,points.first(),points.last(),
            simplifiedPoints=points,distanceMeters=distance,durationMillis=3_600_000,
            transport=TransportPrediction(TransportMode.CAR,1f,"Illustrative demo path"),
            startTimezoneId="America/Phoenix",endTimezoneId="America/Phoenix",
            geometryProvenance=GeometryProvenance.CONTINUITY_ESTIMATE)
        val a=Visit("canyon-demo-start","South Rim • demo",location=points.first(),startTimestampEpochMs=start-600_000,endTimestampEpochMs=start,timezoneId="America/Phoenix")
        val b=Visit("canyon-demo-end","Desert View • demo",location=points.last(),startTimestampEpochMs=start+3_600_000,endTimestampEpochMs=start+4_200_000,timezoneId="America/Phoenix")
        return Trip("canyon-3d-demo","Grand Canyon • illustrative 3D demo","2026-07-01","2026-07-01",distance,
            days=listOf(TripDay(1,"2026-07-01","America/Phoenix",listOf(TripDayItem.VisitItem(a),TripDayItem.MovementItem(segment),TripDayItem.VisitItem(b)),totalDistanceMeters=distance)))
    }
}
