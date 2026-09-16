package com.traveler.core.common.geo

/** Proximity labels, never proof of a visit to a particular attraction or city boundary. */
object OfflineVisitRegionResolver {
    fun resolve(location: GeoPoint): String? {
        val city = OfflineCityResolver.resolveNearestCity(location, maxDistanceKm = 60.0)
        val landmark = OfflineCityResolver.resolveNearestLandmark(location, maxDistanceKm = 40.0)
        val cityDistance = city?.let {
            GeodesicUtils.distanceMeters(location, GeoPoint(it.latitude, it.longitude))
        } ?: Double.POSITIVE_INFINITY
        val landmarkDistance = landmark?.let {
            GeodesicUtils.distanceMeters(location, GeoPoint(it.latitude, it.longitude))
        } ?: Double.POSITIVE_INFINITY
        // Unlike the diary's legacy landmark preference, use the nearest candidate.
        val name = if (landmarkDistance < cityDistance) landmark?.name else city?.name
        return name?.let { "$it 인근" }
    }
}
