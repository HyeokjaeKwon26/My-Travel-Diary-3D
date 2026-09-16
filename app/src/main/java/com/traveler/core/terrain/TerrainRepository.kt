package com.traveler.core.terrain

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** Fixed-size offline collection. No network client and no retained Activity. */
object TerrainRepository {
    @Volatile private var cached: List<TerrainPack>? = null

    suspend fun load(context: Context): List<TerrainPack> = withContext(Dispatchers.IO) {
        synchronized(this@TerrainRepository) {
            cached ?: buildList {
                context.assets.open("terrain/grand_canyon.terrain.json").use { add(TerrainPack.read(it)) }
                File(context.filesDir, "terrain").listFiles()?.filter { it.extension == "json" }
                    ?.sortedBy { it.name }?.take(3)?.forEach { file ->
                        try { file.inputStream().use { add(TerrainPack.read(it)) } }
                        catch (_: IllegalArgumentException) { /* Invalid imported pack is not activated. */ }
                    }
            }.also { cached = it }
        }
    }

    suspend fun importPack(context: Context, uri: Uri): List<TerrainPack> = withContext(Dispatchers.IO) {
        val pack = context.contentResolver.openInputStream(uri)?.use { TerrainPack.read(it) }
            ?: error("Cannot open terrain pack")
        val bytes = Json.encodeToString(pack).toByteArray()
        val key = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        synchronized(this@TerrainRepository) {
            val dir = File(context.filesDir, "terrain").apply { mkdirs() }
            val target = File(dir, "$key.json")
            require(target.exists() || (dir.listFiles()?.count { it.extension == "json" } ?: 0) < 3) {
                "Three regional packs are already stored. Remove a pack before importing another."
            }
            if (!target.exists()) {
                val temp = File(dir, "$key.pending")
                try { temp.writeBytes(bytes); check(temp.renameTo(target)) { "Could not save terrain pack" } }
                finally { temp.delete() }
            }
            cached = null
        }
        load(context)
    }

    suspend fun clearImported(context: Context): List<TerrainPack> = withContext(Dispatchers.IO) {
        synchronized(this@TerrainRepository) {
            File(context.filesDir, "terrain").listFiles()?.filter { it.extension == "json" }?.forEach { it.delete() }
            cached = null
        }
        load(context)
    }
}
