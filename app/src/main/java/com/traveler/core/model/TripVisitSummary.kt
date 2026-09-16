package com.traveler.core.model

import java.util.Locale
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.common.geo.OfflineVisitRegionResolver
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import kotlin.math.ln

/** A recorded stop is not a unique geographical place. Re-visits count separately. */
data class TripVisitLabel(
    val sourceId: String,
    val name: String?,
    val location: GeoPoint? = null,
    val isUserOverride: Boolean = false,
    val startEpochMs: Long? = null,
    val durationMs: Long = 0,
    val timezoneId: String? = null,
    val photoCount: Int = 0
)

data class TripVisitSummary(
    val visitCount: Int = 0,
    val unnamedVisitCount: Int = 0,
    val representativeNames: List<String> = emptyList(),
    val otherNamedPlaceCount: Int = 0,
    val unresolvedVisitCount: Int = 0,
    val hasApproximateRegions: Boolean = false
) {
    companion object {
        private val whitespace = Regex("\\s+")
        private val genericNames = setOf("home", "work", "inferred_home", "inferred_work", "집", "직장")

        private data class Candidate(
            val label: String, val priority: Int, val approximate: Boolean,
            val index: Int, val visit: TripVisitLabel
        )

        private fun day(visit: TripVisitLabel): String? = visit.startEpochMs?.let { timestamp ->
            val zone = runCatching { ZoneId.of(visit.timezoneId) }.getOrDefault(ZoneOffset.UTC)
            runCatching { Instant.ofEpochMilli(timestamp).atZone(zone).toLocalDate().toString() }.getOrNull()
        }

        /** Input order is the first visit time, with source ID as a stable tie-breaker. */
        fun from(visits: List<TripVisitLabel>): TripVisitSummary {
            val uniqueVisits = visits.distinctBy { it.sourceId }
            val names = uniqueVisits.map { visit ->
                visit.name?.trim()?.replace(whitespace, " ")
                    ?.takeUnless { it.isBlank() || it.lowercase(Locale.ROOT) in setOf("unknown", "null") }
            }
            val unnamedCount = names.count { it == null }
            // Cache exact duplicate coordinates only, without merging visits or fetching any data.
            val regionCache = mutableMapOf<Pair<Double, Double>, String?>()
            val candidates = uniqueVisits.mapIndexedNotNull { index, visit ->
                val name = names[index]
                val generic = name?.lowercase(Locale.ROOT) in genericNames
                when {
                    name != null && visit.isUserOverride -> Candidate(name, 0, false, index, visit)
                    name != null && !generic -> Candidate(name, 1, false, index, visit)
                    else -> {
                        val region = visit.location?.let { point ->
                            val key = point.latitude to point.longitude
                            if (key !in regionCache) regionCache[key] = OfflineVisitRegionResolver.resolve(point)
                            regionCache[key]
                        }
                        when {
                            region != null -> Candidate(region, if (generic) 3 else 2, true, index, visit)
                            name != null -> Candidate(name, 4, false, index, visit)
                            else -> null
                        }
                    }
                }
            }
            val eligible = if (candidates.all { it.priority == 4 } && unnamedCount > 0) emptyList() else candidates
            val groups = eligible.groupBy { (if (it.approximate) "near:" else "name:") + it.label.lowercase(Locale.ROOT) }
                .values.toMutableList()
            val totalLabels = groups.size
            val selected = mutableListOf<List<Candidate>>()
            val coveredDays = mutableSetOf<String>()
            repeat(minOf(3, groups.size)) {
                val best = groups.minWithOrNull(compareBy<List<Candidate>> { group -> group.minOf { it.priority } }
                    .thenByDescending { group ->
                        val hours = group.sumOf { it.visit.durationMs.coerceIn(0, 86_400_000).toDouble() / 3_600_000 }
                        val photos = group.sumOf { it.visit.photoCount.coerceAtLeast(0).toDouble() }
                        val newDays = group.mapNotNull { day(it.visit) }.distinct().count { it !in coveredDays }
                        2 * ln(1 + hours) + 2 * ln(1 + photos) + 2 * minOf(3, newDays)
                    }.thenBy { group -> group.minOf { it.index } })!!
                selected += best
                coveredDays += best.mapNotNull { day(it.visit) }
                groups.remove(best)
            }
            return TripVisitSummary(
                visitCount = uniqueVisits.size,
                unnamedVisitCount = unnamedCount,
                representativeNames = selected.map { group -> group.minBy { it.priority }.label },
                otherNamedPlaceCount = (totalLabels - selected.size).coerceAtLeast(0),
                unresolvedVisitCount = uniqueVisits.size - candidates.size,
                hasApproximateRegions = selected.any { group -> group.any { it.approximate } }
            )
        }
    }
}
