package com.traveler.feature.map.threed

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import com.traveler.core.common.geo.WebMercator
import com.traveler.core.common.geo.WorldPoint
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executors
import kotlin.math.floor

internal data class AtlasRequest(val window: OfflineMapDrape.Window, val tiles: Set<StreetTile>, val revision: Long)
internal data class MapAtlas(val request: AtlasRequest, val bitmap: Bitmap, val base: FloatBuffer)

/** One owner for raster/mesh preparation. Export calls it synchronously; live playback uses a worker. */
internal class MapAtlasPainter(context: Context, private val streets: StreetMapSession) : AutoCloseable {
    private val drape by lazy { OfflineMapDrape(context) }
    private var window: OfflineMapDrape.Window? = null
    private var reference: Bitmap? = null
    private var base: FloatBuffer? = null
    private val pool = ArrayDeque<Bitmap>()
    private var closed = false

    fun prepare(request: AtlasRequest): MapAtlas {
        if (window != request.window) {
            reference?.recycle()
            reference = drape.rasterize(request.window)
            base = buildBase(request.window)
            window = request.window
        }
        val bitmap = synchronized(pool) {
            check(!closed)
            if (pool.isEmpty()) Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888) else pool.removeFirst()
        }
        try {
            val canvas = Canvas(bitmap)
            canvas.drawBitmap(reference!!, 0f, 0f, null)
            streets.paint(canvas, request.window, bitmap.width, StreetTilePlan(request.tiles.toList(), 0))
            return MapAtlas(request, bitmap, base!!)
        } catch (e: Throwable) { recycle(bitmap); throw e }
    }

    fun recycle(bitmap: Bitmap) = synchronized(pool) {
        if (closed || pool.size >= 2) bitmap.recycle() else pool.addLast(bitmap)
    }

    override fun close() {
        reference?.recycle(); reference = null; base = null
        synchronized(pool) { closed = true; pool.forEach { it.recycle() }; pool.clear() }
    }

    private fun buildBase(w: OfflineMapDrape.Window): FloatBuffer {
        val buffer = ByteBuffer.allocateDirect(64 * 64 * 6 * 9 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        fun add(r: Int, c: Int) {
            val x = w.x + w.span * c / 64
            val y = (w.y + w.span * r / 64).coerceIn(0.0, 1.0)
            val p = EarthGeometry.position(WebMercator.unproject(WorldPoint(x-floor(x), y)), -2.0)
            buffer.put(p.x.toFloat()).put(p.y.toFloat()).put(p.z.toFloat())
            buffer.put(1f).put(1f).put(1f).put(1f).put((x-floor(x)).toFloat()).put(y.toFloat())
        }
        for (r in 0 until 64) for (c in 0 until 64) {
            add(r,c); add(r+1,c); add(r,c+1); add(r,c+1); add(r+1,c); add(r+1,c+1)
        }
        buffer.position(0)
        return buffer
    }
}

/** Latest viewport wins. Only one build and one completed atlas can be retained. */
internal class MapAtlasWorker(
    private val build: (AtlasRequest) -> MapAtlas,
    private val recycle: (MapAtlas) -> Unit,
    private val cleanup: () -> Unit,
    private val ready: () -> Unit,
    private val intervalMs: Long = 200
) : AutoCloseable {
    private val lock = Any()
    private val executor = Executors.newSingleThreadExecutor { Thread(it, "journey-map-atlas").apply { isDaemon = true } }
    private var wanted: AtlasRequest? = null
    private var pending: AtlasRequest? = null
    private var completed: MapAtlas? = null
    private var running = false
    private var closed = false
    private var failure: Throwable? = null

    fun request(request: AtlasRequest) = synchronized(lock) {
        if (closed || wanted == request) return@synchronized
        wanted = request; pending = request
        if (!running) { running = true; executor.execute { drain() } }
    }

    fun take(window: OfflineMapDrape.Window): MapAtlas? = synchronized(lock) {
        failure?.let { failure = null; throw IllegalStateException("Map preparation failed", it) }
        val result = completed ?: return@synchronized null
        completed = null
        if (result.request.window == window) result else { recycle(result); null }
    }

    private fun drain() {
        var lastBuild = 0L
        while (true) {
            val wait = intervalMs - (System.nanoTime()/1_000_000 - lastBuild)
            if (wait > 0) Thread.sleep(wait)
            val request = synchronized(lock) {
                if (closed || pending == null) { running = false; return }
                pending!!.also { pending = null }
            }
            lastBuild = System.nanoTime()/1_000_000
            val result = try { build(request) } catch (e: Throwable) {
                val notify = synchronized(lock) { if (!closed) { failure = e; true } else false }
                if (notify) ready()
                continue
            }
            val publish = synchronized(lock) {
                if (closed || wanted?.window != request.window) { recycle(result); false }
                else { completed?.let(recycle); completed = result; true }
            }
            if (publish) ready()
        }
    }

    override fun close() = synchronized(lock) {
        if (closed) return@synchronized
        closed = true; pending = null; completed?.let(recycle); completed = null
        // Cleanup follows any in-flight build; never recycle a bitmap while Canvas is using it.
        executor.execute(cleanup)
        executor.shutdown()
    }
}
