package com.traveler.core.media

import android.content.ContentUris
import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import com.traveler.core.common.geo.GeoPoint
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class MediaScanResult(
    val candidates: List<RawMediaCandidate>,
    val diagnostics: MediaPipelineDiagnostics = MediaPipelineDiagnostics()
)

interface MediaRepository {
    suspend fun queryMediaCandidatesForDateRange(
        startTimestampEpochMs: Long,
        endTimestampEpochMs: Long
    ): List<RawMediaCandidate>

    suspend fun queryMediaWithDiagnostics(
        startTimestampEpochMs: Long,
        endTimestampEpochMs: Long
    ): MediaScanResult = MediaScanResult(queryMediaCandidatesForDateRange(startTimestampEpochMs, endTimestampEpochMs))
    suspend fun queryMediaWithProgress(start: Long, end: Long, progress: (Int, Int, Float) -> Unit): MediaScanResult {
        val result = queryMediaWithDiagnostics(start,end)
        progress(result.candidates.size,result.candidates.size,1f)
        return result
    }

}

class AndroidMediaStoreScanner(
    private val context: Context
) : MediaRepository {

    override suspend fun queryMediaCandidatesForDateRange(
        startTimestampEpochMs: Long,
        endTimestampEpochMs: Long
    ): List<RawMediaCandidate> = queryMediaWithDiagnostics(startTimestampEpochMs, endTimestampEpochMs).candidates

    override suspend fun queryMediaWithDiagnostics(
        startTimestampEpochMs: Long,
        endTimestampEpochMs: Long
    ): MediaScanResult = queryMediaWithProgress(startTimestampEpochMs,endTimestampEpochMs) { _,_,_ -> }

    override suspend fun queryMediaWithProgress(start: Long, end: Long, progress: (Int, Int, Float) -> Unit): MediaScanResult = withContext(Dispatchers.IO) {
        val startTimestampEpochMs = start
        val endTimestampEpochMs = end
        val candidateMap = mutableMapOf<String, RawMediaCandidate>()

        val capabilities = MediaAccessCapabilities.checkCapabilities(context)
        var visibleImages = 0
        var visibleVideos = 0
        var validDateTakenCount = 0
        var missingOrZeroDateTakenCount = 0

        val projectionList = mutableListOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.MIME_TYPE,
            MediaStore.MediaColumns.DATE_TAKEN,
            MediaStore.MediaColumns.DATE_ADDED,
            MediaStore.MediaColumns.DATE_MODIFIED
        )

        val hasRelativePath = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
        if (hasRelativePath) {
            projectionList.add(MediaStore.MediaColumns.RELATIVE_PATH)
        }
        // BUCKET_DISPLAY_NAME is available across all API versions
        try {
            projectionList.add(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
        } catch (_: Exception) {}
        // DATA for legacy path resolution
        try {
            @Suppress("DEPRECATION")
            projectionList.add(MediaStore.MediaColumns.DATA)
        } catch (_: Exception) {}

        val projection = projectionList.toTypedArray()

        val collectionConfigs = listOf(
            Pair(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "IMG"),
            Pair(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, "VID")
        )

        // Date range normalization: support both ms and sec representations
        val startSec = (startTimestampEpochMs / 1000L) - 86400L
        val endSec = (endTimestampEpochMs / 1000L) + 86400L

        // Primary Fast Path Query: selection covers DATE_TAKEN (ms & sec), DATE_ADDED (sec), DATE_MODIFIED (sec)
        val selectionFast = "(${MediaStore.MediaColumns.DATE_TAKEN} >= ? AND ${MediaStore.MediaColumns.DATE_TAKEN} <= ?) OR " +
                "(${MediaStore.MediaColumns.DATE_TAKEN} >= ? AND ${MediaStore.MediaColumns.DATE_TAKEN} <= ?) OR " +
                "(${MediaStore.MediaColumns.DATE_ADDED} >= ? AND ${MediaStore.MediaColumns.DATE_ADDED} <= ?) OR " +
                "(${MediaStore.MediaColumns.DATE_MODIFIED} >= ? AND ${MediaStore.MediaColumns.DATE_MODIFIED} <= ?)"

        val selectionArgsFast = arrayOf(
            startTimestampEpochMs.toString(),
            endTimestampEpochMs.toString(),
            startSec.toString(),
            endSec.toString(),
            startSec.toString(),
            endSec.toString(),
            startSec.toString(),
            endSec.toString()
        )
        val sortOrder = "${MediaStore.MediaColumns.DATE_TAKEN} ASC"

        for ((collectionUri, prefix) in collectionConfigs) {
            try {
                // Count visible media rows
                context.contentResolver.query(collectionUri, arrayOf(MediaStore.MediaColumns._ID), null, null, null)?.use { c ->
                    if (prefix == "IMG") visibleImages = c.count else visibleVideos = c.count
                }

                // Execute Fast Path
                context.contentResolver.query(
                    collectionUri,
                    projection,
                    selectionFast,
                    selectionArgsFast,
                    sortOrder
                )?.use { cursor ->
                    val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                    val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                    val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                    val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                    val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                    val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                    val relativePathCol = if (hasRelativePath) cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                    val bucketCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                    @Suppress("DEPRECATION")
                    val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                    while (cursor.moveToNext()) {
                        kotlinx.coroutines.currentCoroutineContext().ensureActive()
                        progress(cursor.position, cursor.count, (if(prefix=="IMG") 0f else .5f) + .5f*cursor.position/maxOf(1,cursor.count))
                        val cursorId = cursor.getLong(idCol)
                        val compositeId = "${prefix}_$cursorId"
                        val name = cursor.getString(nameCol) ?: "MEDIA_$cursorId"
                        val mime = cursor.getString(mimeCol) ?: if (prefix == "IMG") "image/jpeg" else "video/mp4"

                        val rawDateTaken = cursor.getLong(dateTakenCol)
                        val rawDateAdded = cursor.getLong(dateAddedCol)
                        val rawDateModified = cursor.getLong(dateModifiedCol)
                        val relativePath = if (relativePathCol >= 0) cursor.getString(relativePathCol) else null
                        val bucketName = if (bucketCol >= 0) cursor.getString(bucketCol) else null
                        val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null

                        val effectivePath = relativePath ?: dataPath

                        // Explicit Unit Normalization (P0-01)
                        val normalizedDateTaken = normalizeDateTakenMs(rawDateTaken)
                        val normalizedDateAdded = if (rawDateAdded > 0) rawDateAdded * 1000L else null
                        val normalizedDateModified = if (rawDateModified > 0) rawDateModified * 1000L else null

                        if (normalizedDateTaken != null) {
                            validDateTakenCount++
                        } else {
                            missingOrZeroDateTakenCount++
                        }

                        val contentUri = ContentUris.withAppendedId(collectionUri, cursorId)

                        val candidate = extractMetadataCandidate(
                            compositeId = compositeId,
                            contentUri = contentUri,
                            fileName = name,
                            mimeType = mime,
                            dateTakenMs = normalizedDateTaken,
                            dateModifiedMs = normalizedDateModified ?: normalizedDateAdded,
                            relativePath = effectivePath,
                            bucketDisplayName = bucketName,
                            isVideo = prefix == "VID"
                        )

                        candidateMap[compositeId] = candidate
                    }
                }
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
            }
        }

        // Bounded Fallback Path: If fast path returned 0 candidates despite visible media and permissions
        if (candidateMap.isEmpty() && (visibleImages > 0 || visibleVideos > 0) && capabilities.images != MediaAccessCapabilities.AccessLevel.DENIED) {
            for ((collectionUri, prefix) in collectionConfigs) {
                try {
                    context.contentResolver.query(
                        collectionUri,
                        projection,
                        null,
                        null,
                        "${MediaStore.MediaColumns._ID} DESC"
                    )?.use { cursor ->
                        val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                        val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                        val mimeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE)
                        val dateTakenCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_TAKEN)
                        val dateAddedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_ADDED)
                        val dateModifiedCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATE_MODIFIED)
                        val relativePathCol = if (hasRelativePath) cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH) else -1
                        val bucketCol = cursor.getColumnIndex(MediaStore.MediaColumns.BUCKET_DISPLAY_NAME)
                        @Suppress("DEPRECATION")
                        val dataCol = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)

                        var count = 0
                        while (cursor.moveToNext() && count < 1000) {
                            kotlinx.coroutines.currentCoroutineContext().ensureActive()
                            progress(count, minOf(cursor.count,1000), .99f)
                            count++
                            val cursorId = cursor.getLong(idCol)
                            val compositeId = "${prefix}_$cursorId"
                            if (candidateMap.containsKey(compositeId)) continue

                            val name = cursor.getString(nameCol) ?: "MEDIA_$cursorId"
                            val mime = cursor.getString(mimeCol) ?: if (prefix == "IMG") "image/jpeg" else "video/mp4"

                            val rawDateTaken = cursor.getLong(dateTakenCol)
                            val rawDateAdded = cursor.getLong(dateAddedCol)
                            val rawDateModified = cursor.getLong(dateModifiedCol)
                            val relativePath = if (relativePathCol >= 0) cursor.getString(relativePathCol) else null
                            val bucketName = if (bucketCol >= 0) cursor.getString(bucketCol) else null
                            val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null

                            val effectivePath = relativePath ?: dataPath

                            val normalizedDateTaken = normalizeDateTakenMs(rawDateTaken)
                            val normalizedDateAdded = if (rawDateAdded > 0) rawDateAdded * 1000L else null
                            val normalizedDateModified = if (rawDateModified > 0) rawDateModified * 1000L else null

                            val contentUri = ContentUris.withAppendedId(collectionUri, cursorId)

                            val candidate = extractMetadataCandidate(
                                compositeId = compositeId,
                                contentUri = contentUri,
                                fileName = name,
                                mimeType = mime,
                                dateTakenMs = normalizedDateTaken,
                                dateModifiedMs = normalizedDateModified ?: normalizedDateAdded,
                                relativePath = effectivePath,
                                bucketDisplayName = bucketName,
                                isVideo = prefix == "VID"
                            )

                            candidateMap[compositeId] = candidate
                        }
                    }
                } catch(e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) {}
            }
        }

        val candidates = candidateMap.values.toList()
        val initialDiagnostics = MediaPipelineDiagnostics(
            visibleImageRows = visibleImages,
            visibleVideoRows = visibleVideos,
            permissionMode = capabilities.images,
            accessMediaLocation = capabilities.locationMetadata == MediaAccessCapabilities.LocationMetadataAccess.AVAILABLE,
            rowsWithValidDateTaken = validDateTakenCount,
            rowsWithMissingOrZeroDateTaken = missingOrZeroDateTakenCount,
            rowsPassingBroadIngestionWindow = candidates.size
        )

        MediaScanResult(candidates, initialDiagnostics)
    }

    private fun extractMetadataCandidate(
        compositeId: String,
        contentUri: Uri,
        fileName: String,
        mimeType: String,
        dateTakenMs: Long?,
        dateModifiedMs: Long?,
        relativePath: String?,
        bucketDisplayName: String?,
        isVideo: Boolean
    ): RawMediaCandidate {
        var exifDateTime: String? = null
        var exifOffset: String? = null
        var geoPoint: GeoPoint? = null

        val uriToOpen = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                MediaStore.setRequireOriginal(contentUri)
            } catch (_: Exception) {
                contentUri
            }
        } else {
            contentUri
        }

        if (isVideo) {
            val retriever = MediaMetadataRetriever()
            try {
                context.contentResolver.openFileDescriptor(uriToOpen, "r")?.use { pfd ->
                    retriever.setDataSource(pfd.fileDescriptor)
                    val locationString = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_LOCATION)
                    if (!locationString.isNullOrBlank()) {
                        geoPoint = parseIso6709Location(locationString)
                    }
                }
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) {
            } finally {
                try {
                    retriever.release()
                } catch(e: kotlinx.coroutines.CancellationException) { throw e }
                catch (_: Exception) {}
            }
        } else {
            try {
                context.contentResolver.openInputStream(uriToOpen)?.use { stream ->
                    val exif = ExifInterface(stream)
                    exifDateTime = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_DATETIME)
                    exifOffset = exif.getAttribute(ExifInterface.TAG_OFFSET_TIME_ORIGINAL)
                        ?: exif.getAttribute(ExifInterface.TAG_OFFSET_TIME)

                    val latLong = exif.latLong
                    if (latLong != null && latLong.size >= 2) {
                        val alt = exif.getAltitude(0.0)
                        geoPoint = GeoPoint(latLong[0], latLong[1], if (alt != 0.0) alt else null)
                    }
                }
            } catch (_: Exception) {}
        }

        val evidence = classifyCaptureEvidence(
            relativePath = relativePath,
            bucketName = bucketDisplayName,
            fileName = fileName,
            dateTakenMs = dateTakenMs,
            dateModifiedMs = dateModifiedMs,
            hasExifOriginal = !exifDateTime.isNullOrBlank(),
            hasExifOffset = !exifOffset.isNullOrBlank(),
            hasGps = geoPoint != null
        )

        return RawMediaCandidate(
            id = compositeId,
            contentUriString = contentUri.toString(),
            fileName = fileName,
            mimeType = mimeType,
            exifDateTimeOriginal = exifDateTime,
            exifOffset = exifOffset,
            mediaStoreDateTaken = dateTakenMs,
            fileDateModifiedMs = dateModifiedMs,
            directGps = geoPoint,
            relativePath = relativePath,
            bucketDisplayName = bucketDisplayName,
            captureEvidence = evidence
        )
    }

    private fun parseIso6709Location(locationStr: String): GeoPoint? {
        val trimmed = locationStr.trim().removeSuffix("/")
        val match = Regex("([+-]\\d+\\.?\\d*)([+-]\\d+\\.?\\d*)").find(trimmed) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lng = match.groupValues[2].toDoubleOrNull() ?: return null
        return GeoPoint(lat, lng)
    }

    companion object {
        /**
         * Classifies media candidate into explicit capture evidence categories (P0-01).
         * Stops treating "saved during trip" (downloads, memes, screenshots) as "captured during trip".
         */
        fun classifyCaptureEvidence(
            relativePath: String?,
            bucketName: String?,
            fileName: String,
            dateTakenMs: Long?,
            dateModifiedMs: Long?,
            hasExifOriginal: Boolean,
            hasExifOffset: Boolean,
            hasGps: Boolean
        ): MediaCaptureEvidence {
            val lowerPath = (relativePath ?: "").lowercase()
            val lowerBucket = (bucketName ?: "").lowercase()
            val lowerName = fileName.lowercase()

            val isScreenshot = lowerPath.contains("screenshot") || lowerBucket.contains("screenshot") ||
                    lowerName.startsWith("screenshot") || lowerName.startsWith("screen_shot") ||
                    lowerName.contains("screencapture")
            if (isScreenshot) return MediaCaptureEvidence.SCREENSHOT

            val isDownloadOrSocial = lowerPath.contains("download") || lowerBucket.contains("download") ||
                    lowerPath.contains("telegram") || lowerBucket.contains("telegram") ||
                    lowerPath.contains("whatsapp") || lowerBucket.contains("whatsapp") ||
                    lowerPath.contains("kakaotalk") || lowerBucket.contains("kakaotalk") ||
                    lowerPath.contains("reddit") || lowerBucket.contains("reddit") ||
                    lowerPath.contains("twitter") || lowerBucket.contains("twitter") ||
                    lowerPath.contains("instagram") || lowerBucket.contains("instagram") ||
                    lowerPath.contains("facebook") || lowerBucket.contains("facebook") ||
                    lowerPath.contains("save") || lowerBucket.contains("save") ||
                    lowerPath.contains("browser") || lowerBucket.contains("chrome")

            if (isDownloadOrSocial) {
                // If direct GPS and EXIF original exist, it might be an original photo sent/received
                return if (hasGps && (hasExifOffset || hasExifOriginal)) {
                    MediaCaptureEvidence.STRONG_CAPTURE
                } else {
                    MediaCaptureEvidence.DOWNLOADED_OR_EXTERNAL
                }
            }

            val isCamera = lowerPath.contains("dcim") || lowerBucket.contains("camera") ||
                    lowerBucket.contains("100andro") || lowerPath.contains("camera") ||
                    lowerName.startsWith("img_") || lowerName.startsWith("dsc_") ||
                    lowerName.startsWith("pxl_") || lowerName.startsWith("mvi_") ||
                    lowerName.startsWith("cimg")

            return when {
                hasExifOffset && hasExifOriginal -> MediaCaptureEvidence.STRONG_CAPTURE
                hasGps && (dateTakenMs != null || hasExifOriginal) -> MediaCaptureEvidence.STRONG_CAPTURE
                isCamera && (hasExifOriginal || dateTakenMs != null) -> MediaCaptureEvidence.STRONG_CAPTURE
                isCamera -> MediaCaptureEvidence.LIKELY_CAPTURE
                hasExifOriginal -> MediaCaptureEvidence.LIKELY_CAPTURE
                dateTakenMs != null -> MediaCaptureEvidence.LIKELY_CAPTURE
                else -> MediaCaptureEvidence.WEAK_DATE_ONLY
            }
        }

        /**
         * Normalizes raw MediaStore DATE_TAKEN value into epoch milliseconds (P0-01).
         * Handles OEM/driver variations where seconds were stored instead of milliseconds.
         */
        fun normalizeDateTakenMs(rawDateTaken: Long): Long? {
            if (rawDateTaken <= 0L) return null
            return when {
                // Already milliseconds (>= year 2000 in ms: 946,684,800,000)
                rawDateTaken >= 946_684_800_000L -> rawDateTaken
                // Stored in seconds (between year 2000 and year 2100 in sec: 946,684,800 .. 4,102,444,800)
                rawDateTaken in 946_684_800L..4_102_444_800L -> rawDateTaken * 1000L
                // Unrecognized / ancient timestamp
                else -> null
            }
        }
    }
}
