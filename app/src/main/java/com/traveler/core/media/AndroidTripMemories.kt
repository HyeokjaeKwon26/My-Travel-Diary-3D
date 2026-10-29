package com.traveler.core.media

import android.content.Context
import java.io.File

fun tripMemoryStore(context: Context, tripId: String? = null) = TripMemoryStore(File(context.filesDir, "trip_memories")) { photos, refresh, progress ->
    PhotoVisualAnalyzer(context.applicationContext, useCache = !refresh).analyze(if (tripId == null) photos else photos.map { TripMediaFiles.resolveMedia(context, tripId, it) }, progress)
}
