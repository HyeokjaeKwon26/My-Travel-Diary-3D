package com.traveler.feature.map.renderer

import android.graphics.*
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.common.geo.GeodesicUtils
import com.traveler.core.common.geo.OfflineCityResolver
import com.traveler.core.common.geo.WebMercator
import com.traveler.core.model.GeometryProvenance
import com.traveler.core.model.MovementSegment
import com.traveler.core.model.TransportMode
import com.traveler.core.model.Visit
import com.traveler.core.timeline.CanonicalTimelineValidator
import com.traveler.core.timeline.MovementTimelineCanonicalizer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.InputStream

data class BasemapPolygon(
    val name: String,
    val isLake: Boolean,
    val rings: List<List<Pair<Double, Double>>> // [lng, lat]
)

data class BasemapBoundary(
    val name: String,
    val boundaryType: String = "ADMIN_0",
    val points: List<Pair<Double, Double>> // [lng, lat]
)

data class BasemapPlace(
    val name: String,
    val lat: Double,
    val lng: Double,
    val scalerank: Int
)

data class PreparedSegment(
    val segment: MovementSegment,
    val pathPoints: List<GeoPoint>,
    val midLocation: GeoPoint,
    val midHeadingDegrees: Float,
    val isFlight: Boolean,
    val isEstimated: Boolean,
    val isContinuityConnector: Boolean = false
)

data class PreparedVisit(
    val visit: Visit,
    val labelText: String,
    val pinColor: Int,
    val priorityScore: Int
)

data class PreparedTravelMap(
    val allPoints: List<GeoPoint>,
    val viewportRef: ViewportReference,
    val preparedVisits: List<PreparedVisit>,
    val preparedSegments: List<PreparedSegment>,
    val focusedLocation: GeoPoint?
)

class TravelMapRenderer(
    basemapInputStream: InputStream? = null
) {
    // Reusable graphics objects for bounded low-allocation rendering
    // P1-13: Geographic Layer Hierarchy (Land warm light neutral, Water cool light blue-gray, Coastline medium-gray)
    private val bgPaint = Paint().apply { color = Color.parseColor("#DCE7F5"); style = Paint.Style.FILL }
    private val basemapPaint = Paint().apply { color = Color.parseColor("#FAF9F6"); style = Paint.Style.FILL; isAntiAlias = true }
    private val coastlinePaint = Paint().apply {
        color = Color.parseColor("#64748B")
        strokeWidth = 1.6f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val lakePaint = Paint().apply { color = Color.parseColor("#DCE7F5"); style = Paint.Style.FILL; isAntiAlias = true }
    private val countryBoundaryPaint = Paint().apply {
        color = Color.parseColor("#94A3B8")
        strokeWidth = 1.2f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val stateBoundaryPaint = Paint().apply {
        color = Color.parseColor("#CBD5E1")
        strokeWidth = 0.8f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }

    private val cityDotPaint = Paint().apply { color = Color.parseColor("#475569"); style = Paint.Style.FILL; isAntiAlias = true }
    private val cityTextStrokePaint = Paint().apply {
        color = Color.parseColor("#F8FAFC")
        strokeWidth = 3.5f
        style = Paint.Style.STROKE
        textSize = 15f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    private val cityTextFillPaint = Paint().apply {
        color = Color.parseColor("#0F172A")
        textSize = 15f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    private val cityTextPaint get() = cityTextFillPaint

    private val landmarkBadgeBgPaint = Paint().apply {
        color = Color.parseColor("#0F172A")
        alpha = 220
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val landmarkBadgeBorderPaint = Paint().apply {
        color = Color.parseColor("#38BDF8")
        strokeWidth = 1.5f
        style = Paint.Style.STROKE
        isAntiAlias = true
    }
    private val landmarkTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 16f
        typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        isAntiAlias = true
    }
    private val vehiclePulsePaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        isAntiAlias = true
    }

    // P1-05: Crisp, followable short dash pattern for estimated routes and continuity connectors
    private val estimatedDashEffect = DashPathEffect(floatArrayOf(6f, 4f), 0f)
    private val flightDashEffect = DashPathEffect(floatArrayOf(14f, 8f), 0f)

    private val routePaint = Paint().apply {
        strokeWidth = 7f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val estimatedRoutePaint = Paint().apply {
        strokeWidth = 5f
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        pathEffect = estimatedDashEffect
        isAntiAlias = true
    }

    private val routeBorderPaint = Paint().apply {
        strokeWidth = 11f
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val activeRouteGlowPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        isAntiAlias = true
    }

    private val flightPaint = Paint().apply {
        strokeWidth = 5f
        color = Color.parseColor("#8B5CF6")
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        pathEffect = flightDashEffect
        isAntiAlias = true
    }

    private val pinFillPaint = Paint().apply { style = Paint.Style.FILL; isAntiAlias = true }
    private val pinBorderPaint = Paint().apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
        isAntiAlias = true
    }

    private val focusRingPaint = Paint().apply { color = Color.parseColor("#60A5FA"); style = Paint.Style.STROKE; strokeWidth = 5f; isAntiAlias = true }
    private val focusGlowPaint = Paint().apply { color = Color.parseColor("#3B82F6"); alpha = 45; style = Paint.Style.FILL; isAntiAlias = true }

    private val labelBgPaint = Paint().apply {
        color = Color.argb(220, 255, 255, 255)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val labelTextPaint = Paint().apply {
        color = Color.parseColor("#1E293B")
        textSize = 22f
        typeface = Typeface.DEFAULT_BOLD
        isAntiAlias = true
    }

    private val contextBannerBgPaint = Paint().apply {
        color = Color.argb(220, 15, 23, 42)
        style = Paint.Style.FILL
        isAntiAlias = true
    }

    private val contextBannerTextPaint = Paint().apply {
        color = Color.WHITE
        textSize = 20f
        typeface = Typeface.DEFAULT_BOLD
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val vehicleBgPaint = Paint().apply {
        color = Color.WHITE
        style = Paint.Style.FILL
        setShadowLayer(4f, 0f, 2f, Color.argb(60, 0, 0, 0))
        isAntiAlias = true
    }

    private val vehicleTextPaint = Paint().apply {
        textSize = 24f
        textAlign = Paint.Align.CENTER
        isAntiAlias = true
    }

    private val vehiclePointerPaint = Paint().apply {
        style = Paint.Style.FILL
        isAntiAlias = true
    }
    private val vehiclePointerPath = Path()
    private val vehicleShadowPaint = Paint().apply {
        style = Paint.Style.FILL
        color = Color.BLACK
        alpha = 45
        isAntiAlias = true
    }

    // Reusable allocation-free scratch objects for drawing
    private val reusablePath = Path()
    private val reusableCoords = FloatArray(2)
    private val reusableRect = RectF()
    private val placedLabelBounds = ArrayList<RectF>()
    private val placedCityLabels = HashSet<String>()

    private var preparedWorldBasemap: PreparedBasemap? = null
    private var preparedRegionalBasemap: PreparedBasemap? = null

    val hasRegionalBasemap: Boolean
        get() = preparedRegionalBasemap != null

    private var cachedRenderModel: TravelMapRenderModel? = null
    private var cachedPreparedMap: PreparedTravelMap? = null

    init {
        if (basemapInputStream != null) {
            loadBasemap(basemapInputStream)
        }
    }

    fun setPreparedRegionalBasemap(prepared: PreparedBasemap) {
        preparedRegionalBasemap = prepared
    }

    fun loadRegionalBasemap(stream: InputStream) {
        preparedRegionalBasemap = RegionalBasemapCache.loadSynchronously(stream)
    }

    fun loadBasemap(stream: InputStream) {
        preparedWorldBasemap = BasemapParser.parse(stream)
    }

    /**
     * Prepares immutable projected geometry for instant low-latency map drawing.
     */
    fun prepareMap(renderModel: TravelMapRenderModel): PreparedTravelMap {
        if (renderModel === cachedRenderModel && cachedPreparedMap != null) {
            return cachedPreparedMap!!
        }

        val allPoints = ArrayList<GeoPoint>()
        renderModel.visits.forEach { allPoints.add(it.location) }

        // P1-11: Consume canonical segments directly from renderModel without recanonicalizing
        val canonicalSegments = CanonicalTimelineValidator.requireNonOverlapping(
            if (CanonicalTimelineValidator.countOverlapViolations(renderModel.segments) == 0) {
                renderModel.segments
            } else {
                MovementTimelineCanonicalizer.canonicalize(renderModel.segments).canonicalSegments
            }
        )
        val sortedSegments = canonicalSegments.sortedBy { it.startTimestampEpochMs }

        val preparedSegments = ArrayList<PreparedSegment>()
        for (i in sortedSegments.indices) {
            val seg = sortedSegments[i]
            val isFlight = seg.effectiveMode == TransportMode.AIRPLANE
            val prov = seg.geometryProvenance
            val hasDetailedGeometry = seg.simplifiedPoints.size > 2 || seg.rawPoints.isNotEmpty()

            val snappedPath = com.traveler.core.terrain.SharedRouteGeometry.path(seg)
            allPoints.addAll(snappedPath)

            var totalDist = 0.0
            val cumDists = ArrayList<Double>(snappedPath.size)
            cumDists.add(0.0)
            for (k in 1 until snappedPath.size) {
                totalDist += GeodesicUtils.distanceMeters(snappedPath[k - 1], snappedPath[k])
                cumDists.add(totalDist)
            }

            val targetDist = totalDist * 0.5
            var midIdx = 0
            while (midIdx < cumDists.size - 1 && cumDists[midIdx + 1] < targetDist) {
                midIdx++
            }

            val p1 = snappedPath[midIdx]
            val p2 = snappedPath[minOf(midIdx + 1, snappedPath.size - 1)]
            val segDist = cumDists[minOf(midIdx + 1, cumDists.size - 1)] - cumDists[midIdx]
            val frac = if (segDist > 0) ((targetDist - cumDists[midIdx]) / segDist).coerceIn(0.0, 1.0) else 0.0

            val midPoint = GeodesicUtils.interpolate(p1, p2, frac)
            val midHeading = GeodesicUtils.initialBearing(p1, p2).toFloat()
            val isEstimated = prov == GeometryProvenance.ESTIMATED_GEODESIC || prov == GeometryProvenance.ENDPOINT_INTERPOLATED || (isFlight && !hasDetailedGeometry)

            preparedSegments.add(
                PreparedSegment(
                    segment = seg,
                    pathPoints = snappedPath,
                    midLocation = midPoint,
                    midHeadingDegrees = midHeading,
                    isFlight = isFlight,
                    isEstimated = isEstimated
                )
            )

        }

        // P1-04A: Deduplicate fallback city labels across nearby visits
        val preparedVisits = renderModel.visits.mapIndexed { idx, v ->
            val isStart = idx == 0
            val isEnd = idx == renderModel.visits.size - 1
            val isFocused = renderModel.focusedLocation != null &&
                    kotlin.math.abs(v.location.latitude - renderModel.focusedLocation.latitude) < 0.0001 &&
                    kotlin.math.abs(v.location.longitude - renderModel.focusedLocation.longitude) < 0.0001
            val isUserRenamed = v.isUserOverride
            val hasPhotos = renderModel.photos.any { it.matchedVisitId == v.id }
            val durationHours = (v.endTimestampEpochMs - v.startTimestampEpochMs).toDouble() / (1000.0 * 3600.0)

            val priorityScore = when {
                isFocused -> 1000
                isStart -> 900
                isEnd -> 800
                isUserRenamed -> 700
                hasPhotos -> 500 + minOf((durationHours * 10).toInt(), 100)
                else -> minOf((durationHours * 10).toInt(), 200)
            }

            val color = when {
                isStart -> Color.parseColor("#10B981") // Emerald Start
                isEnd -> Color.parseColor("#EF4444") // Rose End
                else -> Color.parseColor("#F97316") // Amber Intermediate
            }

            val effectiveLabel = OfflineCityResolver.getEffectivePlaceName(v.placeName, v.isUserOverride, v.location, v.placeAddress)
            val label = if (effectiveLabel.shouldShowOnMap) effectiveLabel.displayName.take(20) else ""

            PreparedVisit(v, label, color, priorityScore)
        }

        val viewportRef = TravelViewportCalculator.computeReference(allPoints)

        val prep = PreparedTravelMap(
            allPoints = allPoints,
            viewportRef = viewportRef,
            preparedVisits = preparedVisits,
            preparedSegments = preparedSegments,
            focusedLocation = renderModel.focusedLocation
        )

        cachedRenderModel = renderModel
        cachedPreparedMap = prep
        return prep
    }

    fun render(
        canvas: Canvas,
        width: Int,
        height: Int,
        renderModel: TravelMapRenderModel,
        playbackState: TravelPlaybackState? = null,
        insets: SafeContentInsets = SafeContentInsets()
    ) {
        if (width <= 0 || height <= 0) return

        val prepared = prepareMap(renderModel)

        // 1. Draw Canvas background (Water / Ocean)
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), bgPaint)

        // 2. Initialize Web Mercator Viewport with precomputed reference in O(1) time
        val viewport = if (playbackState != null) {
            TravelViewportCalculator(
                width = width,
                height = height,
                insets = insets,
                ref = prepared.viewportRef,
                playbackCameraCenter = playbackState.cameraCenter,
                playbackCameraSpanDegrees = playbackState.cameraSpanLat
            )
        } else {
            TravelViewportCalculator(
                width = width,
                height = height,
                insets = insets,
                ref = prepared.viewportRef
            )
        }

        // P1-14: Artificial grid removed for clean geographic map

        // Multi-resolution: select detail level based on camera span (P4-03: Viewport-based LOD, not playback flag)
        val currentSpanDegrees = if (playbackState != null) {
            playbackState.cameraSpanLat
        } else {
            viewport.spanY * 180.0
        }
        val useRegional = (preparedRegionalBasemap != null) && (currentSpanDegrees < 15.0)
        val activeBasemap = if (useRegional) preparedRegionalBasemap else (preparedRegionalBasemap ?: preparedWorldBasemap)

        // 3. Draw Basemap Land, Lakes, Boundaries, and Places with O(1) Bounding-Box Culling (P0-02..P0-05)
        if (activeBasemap != null) {
            val vpMinX = viewport.viewportMinX
            val vpMaxX = viewport.viewportMinX + viewport.spanX
            val vpMinY = viewport.viewportMinY
            val vpMaxY = viewport.viewportMinY + viewport.spanY

            val scaleX = (viewport.contentW / viewport.spanX).toFloat()
            val scaleY = (viewport.contentH / viewport.spanY).toFloat()
            val vMinX = viewport.viewportMinX.toFloat()
            val vMinY = viewport.viewportMinY.toFloat()
            val insetsLeft = viewport.insets.left
            val insetsTop = viewport.insets.top

            // 3A. Land & Lakes Polygons
            for (poly in activeBasemap.polygons) {
                if (!poly.intersectsViewport(vpMinX, vpMaxX, vpMinY, vpMaxY)) continue
                val fillPaint = if (poly.isLake) lakePaint else basemapPaint

                for (ring in poly.rings) {
                    if (!ring.intersectsViewport(vpMinX, vpMaxX, vpMinY, vpMaxY)) continue
                    val coords = ring.worldCoords
                    val len = coords.size
                    if (len < 6) continue

                    val shiftX = when {
                        ring.minWx > vpMaxX && ring.minWx - 1.0f <= vpMaxX -> -1.0f
                        ring.maxWx < vpMinX && ring.maxWx + 1.0f >= vpMinX -> 1.0f
                        else -> 0.0f
                    }

                    reusablePath.reset()
                    reusablePath.moveTo(
                        insetsLeft + (coords[0] + shiftX - vMinX) * scaleX,
                        insetsTop + (coords[1] - vMinY) * scaleY
                    )
                    var i = 2
                    while (i < len) {
                        reusablePath.lineTo(
                            insetsLeft + (coords[i] + shiftX - vMinX) * scaleX,
                            insetsTop + (coords[i + 1] - vMinY) * scaleY
                        )
                        i += 2
                    }
                    reusablePath.close()
                    canvas.drawPath(reusablePath, fillPaint)
                    canvas.drawPath(reusablePath, coastlinePaint)
                }
            }

            // 3B. Regional Boundaries (Country Borders & US State Lines)
            for (boundary in activeBasemap.boundaries) {
                if (!boundary.intersectsViewport(vpMinX, vpMaxX, vpMinY, vpMaxY)) continue
                val coords = boundary.worldCoords
                val len = coords.size
                if (len < 4) continue

                val shiftX = when {
                    boundary.minWx > vpMaxX && boundary.minWx - 1.0f <= vpMaxX -> -1.0f
                    boundary.maxWx < vpMinX && boundary.maxWx + 1.0f >= vpMinX -> 1.0f
                    else -> 0.0f
                }

                reusablePath.reset()
                reusablePath.moveTo(
                    insetsLeft + (coords[0] + shiftX - vMinX) * scaleX,
                    insetsTop + (coords[1] - vMinY) * scaleY
                )
                var i = 2
                while (i < len) {
                    reusablePath.lineTo(
                        insetsLeft + (coords[i] + shiftX - vMinX) * scaleX,
                        insetsTop + (coords[i + 1] - vMinY) * scaleY
                    )
                    i += 2
                }
                val isCountry = boundary.boundaryType == "ADMIN_0"
                canvas.drawPath(reusablePath, if (isCountry) countryBoundaryPaint else stateBoundaryPaint)
            }

            // 3C. Populated Places
            val w = width.toFloat()
            val h = height.toFloat()
            for (place in activeBasemap.places) {
                if (place.rank <= 3) {
                    val sx = insetsLeft + (place.wx - vMinX) * scaleX
                    val sy = insetsTop + (place.wy - vMinY) * scaleY
                    if (sx in 0f..w && sy in 0f..h) {
                        canvas.drawCircle(sx, sy, 2.5f, cityDotPaint)
                        canvas.drawText(place.name, sx + 4f, sy + 3f, cityTextStrokePaint)
                        canvas.drawText(place.name, sx + 4f, sy + 3f, cityTextFillPaint)
                    }
                }
            }
        }

        // 5. Draw Movement Routes with P1-06 Visual Hierarchy (Anti-Spaghetti)
        val currentPlayTs = playbackState?.storyTimeMs ?: Long.MAX_VALUE

        for (prepSeg in prepared.preparedSegments) {
            val path = prepSeg.pathPoints
            if (path.size >= 2) {
                reusablePath.reset()
                viewport.toScreen(path[0].latitude, path[0].longitude, reusableCoords)
                reusablePath.moveTo(reusableCoords[0], reusableCoords[1])
                for (i in 1 until path.size) {
                    viewport.toScreen(path[i].latitude, path[i].longitude, reusableCoords)
                    reusablePath.lineTo(reusableCoords[0], reusableCoords[1])
                }

                val modeColor = getTransportColor(prepSeg.segment.effectiveMode)
                val prov = prepSeg.segment.geometryProvenance
                val hasDetailedGeometry = prepSeg.segment.simplifiedPoints.size > 2 || prepSeg.segment.rawPoints.isNotEmpty()

                val effectiveProv = when {
                    prov == GeometryProvenance.OBSERVED || prov == GeometryProvenance.SIMPLIFIED_OBSERVED -> prov
                    prepSeg.isFlight && !hasDetailedGeometry -> GeometryProvenance.ESTIMATED_GEODESIC
                    prov == GeometryProvenance.ESTIMATED_GEODESIC -> GeometryProvenance.ESTIMATED_GEODESIC
                    prov == GeometryProvenance.ENDPOINT_INTERPOLATED || prov == GeometryProvenance.CONTINUITY_ESTIMATE -> GeometryProvenance.ENDPOINT_INTERPOLATED
                    else -> GeometryProvenance.UNKNOWN
                }

                // P1-06 & P0-04: Playback Route Opacity & Thickness Hierarchy (Using CanonicalTimelineValidator half-open interval)
                val isActiveSeg = playbackState != null && CanonicalTimelineValidator.isSegmentActiveAt(prepSeg.segment, currentPlayTs)
                val (strokeAlpha, strokeWidthMultiplier) = if (playbackState != null) {
                    val sStart = prepSeg.segment.startTimestampEpochMs
                    val sEnd = prepSeg.segment.endTimestampEpochMs
                    when {
                        isActiveSeg -> 255 to 1.15f // Active Movement: 100% solid, prominent
                        currentPlayTs >= sEnd && (currentPlayTs - sEnd < 86_400_000L) -> 160 to 0.90f // Recent completed: 60% alpha
                        currentPlayTs >= sEnd -> 70 to 0.65f // Older past days: 25% faint
                        sStart - currentPlayTs < 43_200_000L -> 45 to 0.60f // Near future: 15% very faint
                        else -> 15 to 0.50f // Far future: 5% hidden
                    }
                } else {
                    255 to 1.0f
                }

                when (effectiveProv) {
                    GeometryProvenance.ESTIMATED_GEODESIC -> {
                        flightPaint.alpha = strokeAlpha
                        flightPaint.strokeWidth = 5f * strokeWidthMultiplier
                        canvas.drawPath(reusablePath, flightPaint)
                    }
                    GeometryProvenance.ENDPOINT_INTERPOLATED, GeometryProvenance.UNKNOWN, GeometryProvenance.CONTINUITY_ESTIMATE -> {
                        estimatedRoutePaint.color = modeColor
                        estimatedRoutePaint.alpha = strokeAlpha
                        estimatedRoutePaint.strokeWidth = 5f * strokeWidthMultiplier
                        canvas.drawPath(reusablePath, estimatedRoutePaint)
                    }
                    GeometryProvenance.OBSERVED, GeometryProvenance.SIMPLIFIED_OBSERVED -> {
                        routePaint.color = modeColor
                        routePaint.alpha = strokeAlpha
                        routePaint.strokeWidth = 7f * strokeWidthMultiplier
                        routeBorderPaint.alpha = minOf(255, strokeAlpha + 30)
                        routeBorderPaint.strokeWidth = 11f * strokeWidthMultiplier
                        if (isActiveSeg) {
                            activeRouteGlowPaint.color = modeColor
                            activeRouteGlowPaint.alpha = 55
                            activeRouteGlowPaint.strokeWidth = 16f * strokeWidthMultiplier
                            canvas.drawPath(reusablePath, activeRouteGlowPaint)
                        }
                        if (strokeAlpha > 60) {
                            canvas.drawPath(reusablePath, routeBorderPaint)
                        }
                        canvas.drawPath(reusablePath, routePaint)
                    }
                }
            }
        }

        // 5B. Draw Active Bridge Segment Route across timeline gaps (Only for Flights, never for ground)
        val activeBridge = playbackState?.currentSegment
        if (activeBridge != null && activeBridge.id.startsWith("bridge_") && activeBridge.effectiveMode == TransportMode.AIRPLANE) {
            val bPath = activeBridge.simplifiedPoints
            if (bPath.size >= 2) {
                reusablePath.reset()
                viewport.toScreen(bPath[0].latitude, bPath[0].longitude, reusableCoords)
                reusablePath.moveTo(reusableCoords[0], reusableCoords[1])
                for (i in 1 until bPath.size) {
                    viewport.toScreen(bPath[i].latitude, bPath[i].longitude, reusableCoords)
                    reusablePath.lineTo(reusableCoords[0], reusableCoords[1])
                }
                flightPaint.alpha = 220
                flightPaint.strokeWidth = 6f
                canvas.drawPath(reusablePath, flightPaint)
            }
        }

        // 6. Draw Visit Markers & Deduplicated Labels (P1-04A, P1-18)
        for (pv in prepared.preparedVisits) {
            viewport.toScreen(pv.visit.location.latitude, pv.visit.location.longitude, reusableCoords)
            val vx = reusableCoords[0]
            val vy = reusableCoords[1]

            pinFillPaint.color = pv.pinColor
            canvas.drawCircle(vx, vy, 11f, pinBorderPaint)
            canvas.drawCircle(vx, vy, 9f, pinFillPaint)
        }

        placedLabelBounds.clear()
        placedCityLabels.clear()
        val prioritizedVisits = prepared.preparedVisits.sortedByDescending { it.priorityScore }

        for (pv in prioritizedVisits) {
            if (pv.labelText.isBlank()) continue

            // Deduplicate fallback city labels (e.g. avoid repeating "Near New York" multiple times)
            if ((pv.labelText.startsWith("Near") || pv.labelText.startsWith("Stop near")) && placedCityLabels.contains(pv.labelText)) {
                continue
            }

            viewport.toScreen(pv.visit.location.latitude, pv.visit.location.longitude, reusableCoords)
            val vx = reusableCoords[0]
            val vy = reusableCoords[1]

            val textW = labelTextPaint.measureText(pv.labelText)
            val textH = 26f
            val padding = 8f

            val isNearRight = vx + textW + padding * 2 > width - 16
            val lx = if (isNearRight) vx - textW - padding * 2 - 12 else vx + 14
            val ly = vy - textH / 2

            reusableRect.set(lx, ly, lx + textW + padding * 2, ly + textH + padding)

            val hasOverlap = placedLabelBounds.any { RectF.intersects(it, reusableRect) }
            if (!hasOverlap && reusableRect.left >= 0 && reusableRect.right <= width && reusableRect.top >= 0 && reusableRect.bottom <= height) {
                placedLabelBounds.add(RectF(reusableRect))
                if (pv.labelText.startsWith("Near") || pv.labelText.startsWith("Stop near")) {
                    placedCityLabels.add(pv.labelText)
                }
                canvas.drawRoundRect(reusableRect, 8f, 8f, labelBgPaint)
                canvas.drawText(pv.labelText, lx + padding, ly + textH - 2f, labelTextPaint)
            }
        }

        // 7A. Draw Iconic Geographic Landmarks & National Parks (Pass 21.8)
        for (landmark in OfflineCityResolver.MAJOR_LANDMARKS) {
            viewport.toScreen(landmark.latitude, landmark.longitude, reusableCoords)
            val lx = reusableCoords[0]
            val ly = reusableCoords[1]

            if (lx in 35f..(width - 35f) && ly in 35f..(height - 35f)) {
                val labelText = "${landmark.icon} ${landmark.name}"
                val textW = landmarkTextPaint.measureText(labelText)
                val padH = 8f
                val padV = 5f
                val badgeRect = RectF(lx - textW / 2f - padH, ly - 12f - padV, lx + textW / 2f + padH, ly + 6f + padV)

                val collides = placedLabelBounds.any { RectF.intersects(it, badgeRect) }
                if (!collides) {
                    placedLabelBounds.add(badgeRect)
                    canvas.drawRoundRect(badgeRect, 10f, 10f, landmarkBadgeBgPaint)
                    canvas.drawRoundRect(badgeRect, 10f, 10f, landmarkBadgeBorderPaint)
                    canvas.drawText(labelText, lx - textW / 2f, ly + 2f, landmarkTextPaint)
                }
            }
        }

        // 7B. Draw Contextual Major Cities with high contrast outline when visible (P1-03, P1-18, Pass 21.8)
        for (city in OfflineCityResolver.MAJOR_CITIES) {
            viewport.toScreen(city.latitude, city.longitude, reusableCoords)
            val cx = reusableCoords[0]
            val cy = reusableCoords[1]

            if (cx in 20f..(width - 20f) && cy in 20f..(height - 20f)) {
                canvas.drawCircle(cx, cy, 3.5f, cityDotPaint)

                val cTextW = cityTextFillPaint.measureText(city.name)
                val cRect = RectF(cx + 6f, cy - 10f, cx + 6f + cTextW, cy + 4f)
                val collides = placedLabelBounds.any { RectF.intersects(it, cRect) }
                if (!collides) {
                    placedLabelBounds.add(cRect)
                    // Halo outline + crisp fill for high contrast on desert & mountain terrain
                    canvas.drawText(city.name, cx + 6f, cy + 2f, cityTextStrokePaint)
                    canvas.drawText(city.name, cx + 6f, cy + 2f, cityTextFillPaint)
                }
            }
        }

        // 8. Visual highlight for focusedLocation
        if (prepared.focusedLocation != null) {
            viewport.toScreen(prepared.focusedLocation.latitude, prepared.focusedLocation.longitude, reusableCoords)
            val fx = reusableCoords[0]
            val fy = reusableCoords[1]

            canvas.drawCircle(fx, fy, 28f, focusGlowPaint)
            canvas.drawCircle(fx, fy, 22f, focusRingPaint)
            canvas.drawCircle(fx, fy, 13f, pinBorderPaint)
            pinFillPaint.color = Color.parseColor("#2563EB")
            canvas.drawCircle(fx, fy, 11f, pinFillPaint)
        }

        // 9. Static Mode Transport Badges on significant segments (P1-09)
        if (playbackState == null) {
            var lastShownLocation: GeoPoint? = null
            var lastShownMode: TransportMode? = null
            for (prepSeg in prepared.preparedSegments) {
                val seg = prepSeg.segment
                val isFlightOrTrain = seg.effectiveMode == TransportMode.AIRPLANE || seg.effectiveMode == TransportMode.TRAIN
                val isSignificantDist = seg.distanceMeters >= 5000.0
                val isModeChange = seg.effectiveMode != lastShownMode
                val isFarFromLastBadge = lastShownLocation == null || GeodesicUtils.distanceMeters(lastShownLocation, prepSeg.midLocation) > 25000.0

                if (isFlightOrTrain || (isSignificantDist && (isModeChange || isFarFromLastBadge))) {
                    viewport.toScreen(prepSeg.midLocation.latitude, prepSeg.midLocation.longitude, reusableCoords)
                    val mx = reusableCoords[0]
                    val my = reusableCoords[1]

                    if (mx in 28f..(width - 28f) && my in 28f..(height - 28f)) {
                        canvas.drawCircle(mx, my, 18f, vehicleBgPaint)
                        canvas.drawText(seg.effectiveMode.emoji, mx, my + 11f, vehicleTextPaint)
                        lastShownMode = seg.effectiveMode
                        lastShownLocation = prepSeg.midLocation
                    }
                }
            }
        }

        // 10. Playback Mode Animated Vehicle Indicator with Directional Pointer (P1-09, Pass 21.8)
        if (playbackState != null) {
            viewport.toScreen(playbackState.currentPosition.latitude, playbackState.currentPosition.longitude, reusableCoords)
            val px = reusableCoords[0]
            val py = reusableCoords[1]

            canvas.save()
            canvas.translate(px, py)

            // P2-07: Subtle life/breathing animation on vehicle indicator
            val timeSec = (playbackState.storyTimeMs % 10000) / 1000f
            val bobScale = 1.0f + 0.035f * kotlin.math.sin(timeSec * 6.0f)
            canvas.scale(bobScale, bobScale)

            val modeColor = getTransportColor(playbackState.currentTransportMode)

            // Dynamic motion ripple pulse when in transit
            if (playbackState.currentSegment != null) {
                val pulsePhase = (playbackState.storyTimeMs % 1200) / 1200f
                val pulseRadius = 24f + pulsePhase * 18f
                val pulseAlpha = ((1.0f - pulsePhase) * 120).toInt()
                vehiclePulsePaint.color = modeColor
                vehiclePulsePaint.alpha = pulseAlpha
                canvas.drawCircle(0f, 0f, pulseRadius, vehiclePulsePaint)
            }

            // Flight elevation shadow (3D altitude effect)
            if (playbackState.currentTransportMode == TransportMode.AIRPLANE) {
                val alt = playbackState.currentAltitudeMeters ?: 1000.0
                val shadowOffset = minOf(20f, (alt / 500.0).toFloat() + 5f)
                canvas.drawCircle(shadowOffset, shadowOffset, 22f, vehicleShadowPaint)
            }

            // Vehicle circular backdrop
            canvas.drawCircle(0f, 0f, 24f, vehicleBgPaint)

            // Directional pointer chevron in transport mode color pointing in smoothed heading direction
            vehiclePointerPaint.color = modeColor

            canvas.save()
            canvas.rotate(playbackState.currentHeadingDegrees)
            vehiclePointerPath.reset()
            vehiclePointerPath.moveTo(0f, -30f)
            vehiclePointerPath.lineTo(8f, -18f)
            vehiclePointerPath.lineTo(-8f, -18f)
            vehiclePointerPath.close()
            canvas.drawPath(vehiclePointerPath, vehiclePointerPaint)
            canvas.restore()

            // Mode emoji in center
            canvas.drawText(playbackState.currentTransportMode.emoji, 0f, 10f, vehicleTextPaint)
            canvas.restore()
        }
    }

    private fun renderPlaybackContextBanner(canvas: Canvas, width: Int, playbackState: TravelPlaybackState) {
        val bannerText = if (playbackState.currentVisit != null) {
            val vName = playbackState.currentVisit.placeName ?: "Stop"
            val nearest = OfflineCityResolver.resolveNearestCity(playbackState.currentVisit.location, 50.0)
            if (nearest != null && !vName.contains(nearest.name)) "$vName · ${nearest.name}" else vName
        } else {
            val nearest = OfflineCityResolver.resolveNearestCity(playbackState.currentPosition, 60.0)
            val locStr = nearest?.name ?: "In transit"
            "$locStr · ${playbackState.currentTransportMode.name.lowercase().replaceFirstChar { it.uppercase() }}"
        }

        val textW = contextBannerTextPaint.measureText(bannerText)
        val pillW = textW + 36f
        val pillH = 34f
        val pillX = (width - pillW) / 2f
        val pillY = 12f

        reusableRect.set(pillX, pillY, pillX + pillW, pillY + pillH)
        canvas.drawRoundRect(reusableRect, 17f, 17f, contextBannerBgPaint)
        canvas.drawText(bannerText, width / 2f, pillY + 24f, contextBannerTextPaint)
    }

    private fun getTransportColor(mode: TransportMode): Int {
        return when (mode) {
            TransportMode.WALK, TransportMode.RUN -> Color.parseColor("#0D9488")
            TransportMode.BICYCLE -> Color.parseColor("#16A34A")
            TransportMode.CAR -> Color.parseColor("#4F46E5")
            TransportMode.BUS -> Color.parseColor("#0891B2")
            TransportMode.TRAIN, TransportMode.SUBWAY -> Color.parseColor("#D97706")
            TransportMode.AIRPLANE -> Color.parseColor("#8B5CF6")
            TransportMode.FERRY -> Color.parseColor("#2563EB")
            else -> Color.parseColor("#64748B")
        }
    }
}
