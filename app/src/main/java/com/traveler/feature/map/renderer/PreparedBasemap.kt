package com.traveler.feature.map.renderer

import android.content.Context
import com.traveler.core.common.geo.WebMercator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.InputStream
import kotlin.math.*

/**
 * Pre-projected, immutable polygon ring with precomputed world-space bounding box (P0-02, P0-03).
 *
 * Coordinates are stored as an interleaved FloatArray [wx0, wy0, wx1, wy1, ...] in normalized
 * Web Mercator space [0.0 .. 1.0].
 */
class PreparedPolygonRing(
    val minWx: Float,
    val maxWx: Float,
    val minWy: Float,
    val maxWy: Float,
    val worldCoords: FloatArray
) {
    val pointCount: Int get() = worldCoords.size / 2

    fun intersectsViewport(vpMinX: Double, vpMaxX: Double, vpMinY: Double, vpMaxY: Double): Boolean {
        if (maxWy < vpMinY || minWy > vpMaxY) return false
        if (!(maxWx < vpMinX || minWx > vpMaxX)) return true
        if (vpMinX < 0.0 && !(maxWx < vpMinX + 1.0 || minWx > vpMaxX + 1.0)) return true
        if (vpMaxX > 1.0 && !(maxWx < vpMinX - 1.0 || minWx > vpMaxX - 1.0)) return true
        return false
    }
}

/**
 * Pre-projected immutable polygon feature with bounding box for O(1) viewport culling (P0-02).
 */
class PreparedBasemapPolygon(
    val name: String,
    val isLake: Boolean,
    val minWx: Float,
    val maxWx: Float,
    val minWy: Float,
    val maxWy: Float,
    val rings: List<PreparedPolygonRing>
) {
    fun intersectsViewport(vpMinX: Double, vpMaxX: Double, vpMinY: Double, vpMaxY: Double): Boolean {
        if (maxWy < vpMinY || minWy > vpMaxY) return false
        if (!(maxWx < vpMinX || minWx > vpMaxX)) return true
        if (vpMinX < 0.0 && !(maxWx < vpMinX + 1.0 || minWx > vpMaxX + 1.0)) return true
        if (vpMaxX > 1.0 && !(maxWx < vpMinX - 1.0 || minWx > vpMaxX - 1.0)) return true
        return false
    }
}

/**
 * Pre-projected immutable boundary feature with bounding box (P0-02, P0-03).
 */
class PreparedBasemapBoundary(
    val name: String,
    val boundaryType: String,
    val minWx: Float,
    val maxWx: Float,
    val minWy: Float,
    val maxWy: Float,
    val worldCoords: FloatArray
) {
    val pointCount: Int get() = worldCoords.size / 2

    fun intersectsViewport(vpMinX: Double, vpMaxX: Double, vpMinY: Double, vpMaxY: Double): Boolean {
        if (maxWy < vpMinY || minWy > vpMaxY) return false
        if (!(maxWx < vpMinX || minWx > vpMaxX)) return true
        if (vpMinX < 0.0 && !(maxWx < vpMinX + 1.0 || minWx > vpMaxX + 1.0)) return true
        if (vpMaxX > 1.0 && !(maxWx < vpMinX - 1.0 || minWx > vpMaxX - 1.0)) return true
        return false
    }
}

/**
 * Pre-projected populated place.
 */
class PreparedBasemapPlace(
    val name: String,
    val wx: Float,
    val wy: Float,
    val rank: Int
)

/**
 * Completely pre-projected, memory-compact, immutable basemap geometry.
 */
class PreparedBasemap(
    val polygons: List<PreparedBasemapPolygon>,
    val boundaries: List<PreparedBasemapBoundary>,
    val places: List<PreparedBasemapPlace>,
    val totalPoints: Int
)

/**
 * Parses raw basemap JSON into [PreparedBasemap] with precomputed bounds and projections.
 */
object BasemapParser {

    fun parse(stream: InputStream): PreparedBasemap {
        val jsonStr = stream.bufferedReader().use { it.readText() }
        val root = Json.parseToJsonElement(jsonStr).jsonObject

        val polygons = ArrayList<PreparedBasemapPolygon>()
        val boundaries = ArrayList<PreparedBasemapBoundary>()
        val places = ArrayList<PreparedBasemapPlace>()
        var totalPoints = 0

        // 1. Polygons (Land & Lakes)
        val polyArray = root["polygons"]?.jsonArray
        polyArray?.forEach { polyObj ->
            if (polyObj is JsonObject) {
                val name = polyObj["name"]?.jsonPrimitive?.content ?: ""
                val isLake = polyObj["type"]?.jsonPrimitive?.content == "lake"
                val ringsArray = polyObj["rings"]?.jsonArray ?: return@forEach

                val preparedRings = ArrayList<PreparedPolygonRing>()
                var polyMinWx = Float.MAX_VALUE
                var polyMaxWx = -Float.MAX_VALUE
                var polyMinWy = Float.MAX_VALUE
                var polyMaxWy = -Float.MAX_VALUE

                ringsArray.forEach { rObj ->
                    if (rObj is JsonArray && rObj.size >= 3) {
                        val n = rObj.size
                        val coords = FloatArray(n * 2)
                        var rMinWx = Float.MAX_VALUE
                        var rMaxWx = -Float.MAX_VALUE
                        var rMinWy = Float.MAX_VALUE
                        var rMaxWy = -Float.MAX_VALUE

                        var prevLng = 0.0
                        var hasPrev = false

                        for (i in 0 until n) {
                            val ptObj = rObj[i] as? JsonArray ?: continue
                            if (ptObj.size < 2) continue
                            val rawLng = ptObj[0].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
                            val lat = ptObj[1].jsonPrimitive.content.toDoubleOrNull() ?: 0.0

                            val contLng = if (!hasPrev) {
                                hasPrev = true
                                prevLng = rawLng
                                rawLng
                            } else {
                                val unwrapped = WebMercator.unwrapLongitude(prevLng, rawLng)
                                prevLng = unwrapped
                                unwrapped
                            }

                            val wx = ((contLng + 180.0) / 360.0).toFloat()
                            val wy = WebMercator.project(lat, 0.0).y.toFloat()

                            coords[i * 2] = wx
                            coords[i * 2 + 1] = wy

                            if (wx < rMinWx) rMinWx = wx
                            if (wx > rMaxWx) rMaxWx = wx
                            if (wy < rMinWy) rMinWy = wy
                            if (wy > rMaxWy) rMaxWy = wy
                        }

                        if (hasPrev) {
                            preparedRings.add(
                                PreparedPolygonRing(
                                    minWx = rMinWx,
                                    maxWx = rMaxWx,
                                    minWy = rMinWy,
                                    maxWy = rMaxWy,
                                    worldCoords = coords
                                )
                            )
                            totalPoints += n

                            if (rMinWx < polyMinWx) polyMinWx = rMinWx
                            if (rMaxWx > polyMaxWx) polyMaxWx = rMaxWx
                            if (rMinWy < polyMinWy) polyMinWy = rMinWy
                            if (rMaxWy > polyMaxWy) polyMaxWy = rMaxWy
                        }
                    }
                }

                if (preparedRings.isNotEmpty()) {
                    polygons.add(
                        PreparedBasemapPolygon(
                            name = name,
                            isLake = isLake,
                            minWx = polyMinWx,
                            maxWx = polyMaxWx,
                            minWy = polyMinWy,
                            maxWy = polyMaxWy,
                            rings = preparedRings
                        )
                    )
                }
            }
        }

        // 2. Boundaries (Country Borders & State Lines)
        val bndArray = root["boundaries"]?.jsonArray
        bndArray?.forEach { bObj ->
            if (bObj is JsonObject) {
                val name = bObj["name"]?.jsonPrimitive?.content ?: ""
                val bType = bObj["boundaryType"]?.jsonPrimitive?.content ?: "ADMIN_0"
                val ptsArray = bObj["points"]?.jsonArray ?: return@forEach

                if (ptsArray.size >= 2) {
                    val n = ptsArray.size
                    val coords = FloatArray(n * 2)
                    var bMinWx = Float.MAX_VALUE
                    var bMaxWx = -Float.MAX_VALUE
                    var bMinWy = Float.MAX_VALUE
                    var bMaxWy = -Float.MAX_VALUE

                    var prevLng = 0.0
                    var hasPrev = false

                    for (i in 0 until n) {
                        val ptObj = ptsArray[i] as? JsonArray ?: continue
                        if (ptObj.size < 2) continue
                        val rawLng = ptObj[0].jsonPrimitive.content.toDoubleOrNull() ?: 0.0
                        val lat = ptObj[1].jsonPrimitive.content.toDoubleOrNull() ?: 0.0

                        val contLng = if (!hasPrev) {
                            hasPrev = true
                            prevLng = rawLng
                            rawLng
                        } else {
                            val unwrapped = WebMercator.unwrapLongitude(prevLng, rawLng)
                            prevLng = unwrapped
                            unwrapped
                        }

                        val wx = ((contLng + 180.0) / 360.0).toFloat()
                        val wy = WebMercator.project(lat, 0.0).y.toFloat()

                        coords[i * 2] = wx
                        coords[i * 2 + 1] = wy

                        if (wx < bMinWx) bMinWx = wx
                        if (wx > bMaxWx) bMaxWx = wx
                        if (wy < bMinWy) bMinWy = wy
                        if (wy > bMaxWy) bMaxWy = wy
                    }

                    if (hasPrev) {
                        boundaries.add(
                            PreparedBasemapBoundary(
                                name = name,
                                boundaryType = bType,
                                minWx = bMinWx,
                                maxWx = bMaxWx,
                                minWy = bMinWy,
                                maxWy = bMaxWy,
                                worldCoords = coords
                            )
                        )
                        totalPoints += n
                    }
                }
            }
        }

        // 3. Populated Places
        val placesArray = root["places"]?.jsonArray
        placesArray?.forEach { pObj ->
            if (pObj is JsonObject) {
                val name = pObj["name"]?.jsonPrimitive?.content ?: return@forEach
                val lat = pObj["lat"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@forEach
                val lng = pObj["lng"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: return@forEach
                val rank = pObj["scalerank"]?.jsonPrimitive?.content?.toIntOrNull() ?: 5

                val wx = ((lng + 180.0) / 360.0).toFloat()
                val wy = WebMercator.project(lat, 0.0).y.toFloat()
                places.add(PreparedBasemapPlace(name, wx, wy, rank))
            }
        }

        return PreparedBasemap(polygons, boundaries, places, totalPoints)
    }
}

/**
 * Application/Session-level cache for the 10m regional basemap (P0-06, P0-07).
 *
 * Reuses the same preprojected binary arrays as the 3D renderer,
 * exclusively off the Android main thread, without leaking Context.
 */
object RegionalBasemapCache {

    @Volatile
    var preparedRegionalBasemap: PreparedBasemap? = null
        private set

    val isReady: Boolean
        get() = preparedRegionalBasemap != null

    private val mutex = Mutex()

    /**
     * Ensures the 10m regional basemap is prepared on [Dispatchers.Default].
     */
    suspend fun ensureLoaded(context: Context): PreparedBasemap? {
        if (preparedRegionalBasemap != null) return preparedRegionalBasemap

        return mutex.withLock {
            if (preparedRegionalBasemap != null) return@withLock preparedRegionalBasemap

            withContext(Dispatchers.Default) {
                try {
                    BundledBasemapCache.load(context.applicationContext).first.also {
                        preparedRegionalBasemap = it
                    }
                } catch (_: Exception) {
                    null
                }
            }
        }
    }

    /**
     * Synchronous load helper for unit tests or video export.
     */
    fun loadSynchronously(stream: InputStream): PreparedBasemap {
        val existing = preparedRegionalBasemap
        if (existing != null) return existing

        val prepared = BasemapParser.parse(stream)
        preparedRegionalBasemap = prepared
        return prepared
    }
}
