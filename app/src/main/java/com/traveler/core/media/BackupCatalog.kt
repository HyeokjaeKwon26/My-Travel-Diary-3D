package com.traveler.core.media

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import com.traveler.core.model.Trip
import com.traveler.core.terrain.TripArchive
import kotlinx.coroutines.*
import kotlinx.serialization.json.*
import java.io.*
import java.security.DigestInputStream
import java.security.MessageDigest
import java.util.zip.ZipInputStream

data class BackupPreview(val uri: String, val title: String, val start: String, val end: String,
    val photos: Int, val containsMedia: Boolean, val digest: String, val similar: Boolean)

/** Preview never writes a journey. Actual restore still validates every media hash before committing. */
object BackupCatalog {
    private val json = Json { ignoreUnknownKeys = true }
    private const val MAX_ARCHIVE = 2L * 1024 * 1024 * 1024 + 64L * 1024 * 1024
    private const val MAX_JSON = 32L * 1024 * 1024
    private fun prefs(c: Context) = c.getSharedPreferences("backup_catalog", 0)
    fun lastBackup(c: Context, id: String) = prefs(c).getLong("last:$id", 0)
    fun recordedImport(c: Context, digest: String): String? = prefs(c).getString("import:$digest", null)
    fun recordImport(c: Context, digest: String, id: String) { check(prefs(c).edit().putString("import:$digest", id).commit()) }
    fun recordBackup(c: Context, id: String) { check(prefs(c).edit().putLong("last:$id", System.currentTimeMillis()).commit()) }
    fun retain(c: Context, uri: Uri, write: Boolean = false) {
        runCatching { c.contentResolver.takePersistableUriPermission(uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or
            if (write) android.content.Intent.FLAG_GRANT_WRITE_URI_PERMISSION else 0) }
    }
    suspend fun preview(c: Context, uri: Uri, existing: List<Trip>): BackupPreview = withContext(Dispatchers.IO) {
        val digest = MessageDigest.getInstance("SHA-256")
        var count = 0L
        val raw = c.contentResolver.openInputStream(uri) ?: error("백업 파일을 열 수 없습니다.")
        val bounded = object : FilterInputStream(raw) {
            override fun read(): Int { val n = super.read(); if (n >= 0) checkLimit(1); return n }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = `in`.read(b, off, len); if (n > 0) checkLimit(n); return n
            }
            fun checkLimit(n: Int) { count += n; require(count <= MAX_ARCHIVE) { "백업 파일이 허용 크기를 초과했습니다." } }
        }
        DigestInputStream(bounded, digest).use { hashed ->
            val input = BufferedInputStream(hashed)
            input.mark(4); val zipped = input.read() == 0x50 && input.read() == 0x4b; input.reset()
            var document: JsonObject? = null
            if (zipped) {
                val zip = ZipInputStream(input)
                var inflated = 0L
                val names = hashSetOf<String>()
                while (true) {
                    currentCoroutineContext().ensureActive()
                    val entry = zip.nextEntry ?: break
                    require(names.add(entry.name) && names.size <= TripMediaFiles.MAX_MEDIA + 1 && !entry.isDirectory)
                    require(entry.name == "manifest.json" || PortableTripArchive.safeEntry(entry.name)) { "지원하지 않는 백업 항목입니다." }
                    val sink = if (entry.name == "manifest.json") ByteArrayOutputStream() else null
                    val bytes = TripMediaFiles.copy(zip, sink ?: object : OutputStream() {
                        override fun write(b: Int) {}
                        override fun write(b: ByteArray, off: Int, len: Int) {}
                    }, if (sink != null) MAX_JSON else minOf(TripMediaFiles.MAX_FILE_BYTES, MAX_ARCHIVE - inflated))
                    inflated += bytes; require(inflated <= MAX_ARCHIVE)
                    if (sink != null) document = json.parseToJsonElement(sink.toString("UTF-8")).jsonObject
                    zip.closeEntry()
                }
                // Include central-directory/trailing bytes in the fingerprint without buffering the archive.
                val buffer = ByteArray(8192)
                while (input.read(buffer) >= 0) currentCoroutineContext().ensureActive()
            } else {
                val bytes = ByteArrayOutputStream(); TripMediaFiles.copy(input, bytes, MAX_JSON)
                document = json.parseToJsonElement(bytes.toString("UTF-8")).jsonObject
            }
            val doc = document ?: error("백업 설명이 없습니다.")
            val format = doc["format"]?.jsonPrimitive?.content
            require(if (zipped) format == "MyTravelDiaryMedia" && doc["version"]?.jsonPrimitive?.int == 2
                else format in setOf("MyTravelDiary", "MyTravelDiary3D") && doc["version"]?.jsonPrimitive?.int == 1)
            val trip = json.decodeFromJsonElement<Trip>(doc.getValue("trip")); TripArchive.validate(trip)
            BackupPreview(uri.toString(), trip.title, trip.startDateIso, trip.endDateIso, trip.allMedia().size, zipped,
                digest.digest().joinToString("") { "%02x".format(it) }, existing.any {
                    it.title == trip.title && it.startDateIso == trip.startDateIso && it.endDateIso == trip.endDateIso
                })
        }
    }
    suspend fun exportToFolder(c: Context, trip: Trip, folder: Uri, progress: (Int, Int) -> Unit): Uri = withContext(Dispatchers.IO) {
        val parent = DocumentsContract.buildDocumentUriUsingTree(folder, DocumentsContract.getTreeDocumentId(folder))
        val title = trip.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(60)
        val target = DocumentsContract.createDocument(c.contentResolver, parent, "application/zip",
            "$title-${trip.startDateIso}-${System.currentTimeMillis()}.travel.zip") ?: error("백업 파일을 만들 수 없습니다.")
        try {
            PortableTripArchive.export(c, trip, target, progress)
            recordBackup(c, trip.id)
            target
        } catch (e: Exception) {
            runCatching { DocumentsContract.deleteDocument(c.contentResolver, target) }; throw e
        }
    }
}
