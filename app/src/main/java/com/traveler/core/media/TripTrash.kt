package com.traveler.core.media

import android.content.Context

/** Soft deletion preserves the database and owned media until the user explicitly deletes permanently. */
object TripTrash {
    private fun prefs(c: Context) = c.getSharedPreferences("trip_trash", 0)
    fun ids(c: Context): Set<String> = prefs(c).all.filterValues { it is Long }.keys
    fun move(c: Context, id: String) { check(prefs(c).edit().putLong(id, System.currentTimeMillis()).commit()) }
    fun restore(c: Context, id: String) { check(prefs(c).edit().remove(id).commit()) }
}
