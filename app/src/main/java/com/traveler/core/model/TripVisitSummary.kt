package com.traveler.core.model

import java.util.Locale

/** A recorded stop is not a unique geographical place. Re-visits count separately. */
data class TripVisitLabel(val sourceId: String, val name: String?)

data class TripVisitSummary(
    val visitCount: Int = 0,
    val unnamedVisitCount: Int = 0,
    val representativeNames: List<String> = emptyList(),
    val otherNamedPlaceCount: Int = 0
) {
    companion object {
        private val whitespace = Regex("\\s+")
        private val genericNames = setOf("home", "work", "집", "직장")

        /** Input order is the first visit time, with source ID as a stable tie-breaker. */
        fun from(visits: List<TripVisitLabel>): TripVisitSummary {
            val uniqueVisits = visits.distinctBy { it.sourceId }
            val names = uniqueVisits.map { visit ->
                visit.name?.trim()?.replace(whitespace, " ")
                    ?.takeUnless { it.isBlank() || it.equals("unknown", ignoreCase = true) }
            }
            val unnamedCount = names.count { it == null }
            val uniqueNames = names.filterNotNull().distinctBy { it.lowercase(Locale.ROOT) }
            val (generic, specific) = uniqueNames.partition { it.lowercase(Locale.ROOT) in genericNames }
            // "Home" alone must not suggest that an otherwise unnamed journey only visited home.
            val displayNames = if (specific.isEmpty() && unnamedCount > 0) emptyList() else specific + generic
            return TripVisitSummary(
                visitCount = uniqueVisits.size,
                unnamedVisitCount = unnamedCount,
                representativeNames = displayNames.take(3),
                otherNamedPlaceCount = (displayNames.size - 3).coerceAtLeast(0)
            )
        }
    }
}
