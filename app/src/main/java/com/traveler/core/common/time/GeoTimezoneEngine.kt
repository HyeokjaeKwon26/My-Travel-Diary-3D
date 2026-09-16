package com.traveler.core.common.time

import com.traveler.core.common.geo.GeoPoint
import kotlinx.coroutines.*
import java.time.ZoneId
import java.util.Collections
import java.util.LinkedHashMap
import kotlin.math.roundToLong

object GeoTimezoneEngine : TimezoneResolver {

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private sealed interface EngineState {
        data class Success(val engine: TimezoneLookup) : EngineState
        data class Failure(val reason: String) : EngineState
    }

    // Factory hook to allow deterministic unit testing of single-flight guarantees
    var engineFactory: () -> TimezoneLookup? = {
        RegionalTimezoneLookup()
    }

    // Single-flight deferred initialization across the entire process lifetime
    @Volatile
    private var engineDeferred: Deferred<EngineState> = createDeferredEngine()

    private fun createDeferredEngine(): Deferred<EngineState> {
        return applicationScope.async(start = CoroutineStart.LAZY) {
            try {
                val engine = engineFactory()
                if (engine != null) {
                    EngineState.Success(engine)
                } else {
                    EngineState.Failure("TimeZoneEngine factory returned null")
                }
            } catch (e: Exception) {
                // P0-03: Catch only recoverable Exception types; never swallow OutOfMemoryError / VirtualMachineError
                EngineState.Failure(e.message ?: "TimeShape initialization failed: ${e.javaClass.simpleName}")
            }
        }
    }

    private const val MAX_CACHE_SIZE = 1024

    // Thread-safe bounded LRU cache for rounded coordinate lookups (resolution ~ 11 meters)
    private val cache: MutableMap<Long, TimezoneResolution> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, TimezoneResolution>(MAX_CACHE_SIZE, 0.75f, true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, TimezoneResolution>?): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }
    )

    private val sessionCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Checks whether the underlying TimeShape engine is already initialized and in memory.
     */
    fun isInitialized(): Boolean {
        return engineDeferred.isCompleted && (engineDeferred.getCompleted() is EngineState.Success)
    }

    /**
     * Resets the engine deferred instance (used primarily in test suites).
     */
    fun resetForTesting() {
        engineFactory = { RegionalTimezoneLookup() }
        sessionCount.set(0)
        cache.clear()
        engineDeferred = createDeferredEngine()
    }

    /**
     * Acquires an import-scoped timezone session to guard against concurrent releases.
     */
    fun acquireSession() {
        sessionCount.incrementAndGet()
    }

    /**
     * Releases an import session. When all sessions are finished, releases the engine.
     */
    fun releaseSession() {
        val remaining = sessionCount.decrementAndGet()
        if (remaining <= 0) {
            sessionCount.set(0)
            releaseEngine()
        }
    }

    /**
     * Releases the TimeShape resident memory structures after batch trip import.
     * Allows Java GC to reclaim the most recently used regional polygon index when idle.
     */
    fun releaseEngine() {
        cache.clear()
        engineDeferred = createDeferredEngine()
    }

    override fun release() {
        releaseSession()
    }

    /**
     * Initializes the small worldwide region catalog on a background thread.
     * Guaranteed to never block the main UI thread, and shares the exact same single-flight initialization.
     */
    fun initializeAsync(onComplete: (() -> Unit)? = null) {
        engineDeferred.start()
        if (onComplete != null) {
            applicationScope.launch {
                engineDeferred.await()
                onComplete.invoke()
            }
        }
    }

    private fun cacheKey(lat: Double, lng: Double): Long {
        val latKey = (lat * 10000.0).roundToLong()
        val lngKey = (lng * 10000.0).roundToLong()
        return (latKey shl 32) or (lngKey and 0xFFFFFFFFL)
    }

    /**
     * Primary production API: asynchronously resolves IANA timezone for a given coordinate.
     */
    override suspend fun resolve(point: GeoPoint?): TimezoneResolution = withContext(Dispatchers.Default) {
        if (point == null) return@withContext TimezoneResolution.Unavailable

        val key = cacheKey(point.latitude, point.longitude)
        cache[key]?.let { return@withContext it }

        val state = engineDeferred.await()
        val engine = when (state) {
            is EngineState.Success -> state.engine
            is EngineState.Failure -> {
                val fail = TimezoneResolution.EngineInitializationFailure(state.reason)
                cache[key] = fail
                return@withContext fail
            }
        }

        val resolution = try {
            val queryResult = engine.query(point.latitude, point.longitude)
            if (queryResult.isPresent) {
                TimezoneResolution.Resolved(queryResult.get())
            } else {
                TimezoneResolution.Offshore
            }
        } catch (e: Exception) {
            TimezoneResolution.Failure(e.message ?: "Unknown TimeShape lookup error")
        }

        cache[key] = resolution
        resolution
    }

    /**
     * Synchronous resolution fallback for test scenarios and legacy sync callers.
     * Awaits the single-flight initialization without spawning duplicate engines.
     */
    override fun resolveSync(point: GeoPoint?): TimezoneResolution {
        if (point == null) return TimezoneResolution.Unavailable

        val key = cacheKey(point.latitude, point.longitude)
        cache[key]?.let { return it }

        val state = runBlocking(Dispatchers.Default) {
            engineDeferred.await()
        }
        val engine = when (state) {
            is EngineState.Success -> state.engine
            is EngineState.Failure -> {
                val fail = TimezoneResolution.EngineInitializationFailure(state.reason)
                cache[key] = fail
                return fail
            }
        }

        val resolution = try {
            val queryResult = engine.query(point.latitude, point.longitude)
            if (queryResult.isPresent) {
                TimezoneResolution.Resolved(queryResult.get())
            } else {
                TimezoneResolution.Offshore
            }
        } catch (e: Exception) {
            TimezoneResolution.Failure(e.message ?: "Unknown TimeShape lookup error")
        }

        cache[key] = resolution
        return resolution
    }

    /**
     * Convenience lookup: returns the resolved ZoneId if found, or null if offshore/unknown.
     */
    fun getTimezoneForLocation(location: GeoPoint?): ZoneId? {
        if (location == null) return null
        return resolveSync(location).zoneIdOrNull
    }
}
