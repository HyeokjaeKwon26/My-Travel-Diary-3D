package com.traveler.feature.video

import android.content.Context
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File

/** Freeze the allowed local cache before encoding, so live eviction/downloads cannot
 * change the map halfway through a video. No route-wide requests to public tile servers. */
object ExportMapSnapshot {
    suspend fun create(context: Context, onProgress: (Float)->Unit): File {
        val dir=File(TravelVideoExporter.getExportTempDir(context),"maps_${java.util.UUID.randomUUID()}").apply { mkdirs() }
        try {
            val source=File(context.cacheDir,"street_maps_v1")
            val files=source.listFiles().orEmpty().filter { it.extension=="png" && it.length()<=1_048_576 }
                .sortedByDescending { it.lastModified() }
            var bytes=0L
            for((index,file) in files.withIndex()) {
                currentCoroutineContext().ensureActive()
                if(bytes+file.length()>96L*1024*1024) break
                // A concurrent eviction may remove a source file. A partial copy is never retained.
                val target=File(dir,file.name)
                try { file.copyTo(target);bytes+=target.length() } catch(e:java.io.IOException) { target.delete();if(file.exists()) throw e }
                onProgress((index+1f)/maxOf(1,files.size))
            }
            return dir
        } catch(e:Exception) { clear(dir);throw e }
    }
    fun clear(directory: File) { directory.listFiles().orEmpty().filter { it.isFile }.forEach { it.delete() };directory.delete() }
}
