package com.traveler.core.media

import android.content.Context
import java.io.File

fun tripMemoryStore(context: Context) = TripMemoryStore(File(context.filesDir, "trip_memories")) { photos, refresh, progress ->
    PhotoVisualAnalyzer(context.applicationContext, useCache = !refresh).analyze(photos, progress)
}
