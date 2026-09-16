package com.traveler.core.common.time

import com.github.luben.zstd.ZstdInputStream
import net.iakovlev.timeshape.TimeZoneEngine
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream
import java.io.BufferedInputStream
import java.io.InputStream
import java.time.ZoneId
import java.util.Optional

fun interface TimezoneLookup {
    fun query(latitude: Double, longitude: Double): Optional<ZoneId>
}

/** Select archive entries before protobuf decoding; never retain the worldwide polygon index. */
internal class RegionalTimezoneLookup : TimezoneLookup {
    private data class Bounds(val entry: String,val zone: String,val west: Double,val south: Double,val east: Double,val north: Double)
    private val bounds = requireNotNull(javaClass.getResourceAsStream("/timezone-regions.tsv"))
        .bufferedReader().useLines { lines -> lines.filter { it.isNotBlank() && !it.startsWith('#') }.map { line ->
            val p=line.split('\t')
            require(p.size==6)
            Bounds(p[0],p[1],p[2].toDouble(),p[3].toDouble(),p[4].toDouble(),p[5].toDouble())
        }.toList() }
    private var loadedEntries: Set<String> = emptySet()
    private var engine: TimeZoneEngine? = null

    @Synchronized override fun query(latitude: Double,longitude: Double): Optional<ZoneId> {
        require(latitude.isFinite() && longitude.isFinite() && latitude in -90.0..90.0 && longitude in -180.0..180.0)
        val candidates=bounds.filter { longitude>=it.west-1e-9 && longitude<=it.east+1e-9 && latitude>=it.south-1e-9 && latitude<=it.north+1e-9 }
        if(candidates.isEmpty()) return Optional.empty()
        val entries=candidates.mapTo(linkedSetOf()) { it.entry }
        if(entries!=loadedEntries) {
            // Drop the previous region before allocating the next one; one query/decoder at a time.
            engine=null
            loadedEntries=emptySet()
            val zones=candidates.mapTo(linkedSetOf()) { ZoneId.of(it.zone) }
            requireNotNull(TimeZoneEngine::class.java.getResourceAsStream("/data.tar.zstd")).use { data ->
                SelectedEntries(BufferedInputStream(ZstdInputStream(data)),entries).use { archive ->
                    engine=TimeZoneEngine.initialize(zones,false,zones.size,archive)
                }
            }
            loadedEntries=entries
        }
        return checkNotNull(engine).query(latitude,longitude)
    }

    private class SelectedEntries(input: InputStream,private val selected: Set<String>): TarArchiveInputStream(input) {
        override fun getNextTarEntry(): TarArchiveEntry? {
            while(true) {
                val entry=super.getNextTarEntry() ?: return null
                if(entry.name in selected) return entry
                // The TAR reader skips the current payload without constructing protobuf objects.
            }
        }
    }
}
