package com.traveler.core.media

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.exifinterface.media.ExifInterface
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.label.ImageLabeling
import com.google.mlkit.vision.label.defaults.ImageLabelerOptions
import com.traveler.core.model.MediaItem
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** One thumbnail at a time. Results survive restarts; originals are never uploaded. */
class PhotoVisualAnalyzer(private val context: Context) {
    private val directory=File(context.cacheDir,"photo_features_v1").apply { mkdirs() }
    private val json=Json { ignoreUnknownKeys=true }
    suspend fun analyze(items: List<MediaItem>, progress: (Int,Int)->Unit = {_,_->}): List<MediaItem> = withContext(Dispatchers.IO) {
        val labeler by lazy { ImageLabeling.getClient(ImageLabelerOptions.DEFAULT_OPTIONS) }
        var labelerUsed=false
        try {
            items.mapIndexed { index, item ->
                currentCoroutineContext().ensureActive()
                progress(index,items.size)
                if(item.isVideo) return@mapIndexed item
                val feature = try {
                    val uri=Uri.parse(item.contentUriString)
                    val key=fingerprint(uri,item)
                    val cached=File(directory,"$key.json")
                    if(cached.isFile) runCatching { json.decodeFromString<PhotoVisualFeatures>(cached.readText()) }.getOrNull()
                        ?.takeIf { it.labelsComputed }?.let { return@mapIndexed item.copy(visualFeatures=it) }
                    val bitmap=thumbnail(uri) ?: return@mapIndexed item
                    try {
                        val small=Bitmap.createScaledBitmap(bitmap,64,64,true)
                        val pixels=IntArray(64*64);small.getPixels(pixels,0,64,0,0,64,64)
                        if(small!==bitmap) small.recycle()
                        var features=PhotoVisualMath.extract(pixels,64,64)
                        var labelsComputed=false
                        val labels=try {
                            labelerUsed=true
                            // Wait for this one inference before recycling its input, also on cancellation.
                            Tasks.await(labeler.process(InputImage.fromBitmap(bitmap,0))).also { labelsComputed=true }
                                .filter { it.confidence>=.65f }.sortedByDescending { it.confidence }.take(8).map { it.text.lowercase() }
                        } catch(_:Exception) { emptyList() }
                        val category=when {
                            labels.any { it in setOf("person","people","selfie","portrait","baby","child") } -> "people"
                            labels.any { it in setOf("food","cuisine","meal","drink","dessert") } -> "food"
                            labels.any { it in setOf("sport","sports","vehicle","boat","bicycle","car","adventure") } -> "activity"
                            labels.any { it in setOf("building","architecture","tower","temple","city") } -> "places"
                            labels.any { it in setOf("landscape","mountain","sky","water","beach","forest","nature","plant") } -> "scenery"
                            else -> "other"
                        }
                        features=features.copy(category=category,labels=labels,labelsComputed=labelsComputed)
                        val temp=File.createTempFile(key,".tmp",directory)
                        try { temp.writeText(json.encodeToString(features)); java.nio.file.Files.move(temp.toPath(),cached.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING) }
                        finally { temp.delete() }
                        features
                    } finally { bitmap.recycle() }
                } catch(e:CancellationException) { throw e }
                catch(_:Exception) { null }
                item.copy(visualFeatures=feature)
            }.also {
                currentCoroutineContext().ensureActive()
                progress(items.size,items.size)
                // Small descriptors only; bound cache independently of media library size.
                val files=directory.listFiles().orEmpty().sortedByDescending { it.lastModified() }
                files.drop(20_000).forEach { it.delete() }
            }
        } finally { if(labelerUsed) labeler.close() }
    }

    private fun fingerprint(uri: Uri, item: MediaItem): String {
        val evidence=runCatching {
            context.contentResolver.query(uri,arrayOf(MediaStore.MediaColumns.SIZE,MediaStore.MediaColumns.DATE_MODIFIED),null,null,null)?.use {
                if(it.moveToFirst()) "${it.getLong(0)}:${it.getLong(1)}" else ""
            }
        }.getOrNull().orEmpty()
        return MessageDigest.getInstance("SHA-256").digest("v1:$uri:$evidence:${item.timestampEpochMs}".toByteArray())
            .joinToString("") { "%02x".format(it) }
    }
    private fun thumbnail(uri: Uri): Bitmap? {
        if(Build.VERSION.SDK_INT>=29 && uri.scheme=="content") runCatching {
            context.contentResolver.loadThumbnail(uri,Size(256,256),null)
        }.getOrNull()?.let { return it }
        val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) }
        if(options.outWidth<=0 || options.outHeight<=0) return null
        options.inSampleSize=1
        while(maxOf(options.outWidth,options.outHeight)/options.inSampleSize>512) options.inSampleSize*=2
        options.inJustDecodeBounds=false
        val bitmap=context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it,null,options) } ?: return null
        val exif=runCatching { context.contentResolver.openInputStream(uri)?.use { ExifInterface(it) } }.getOrNull()
        if(exif!=null && (exif.rotationDegrees!=0 || exif.isFlipped)) {
            val m=Matrix().apply { if(exif.isFlipped) postScale(-1f,1f);postRotate(exif.rotationDegrees.toFloat()) }
            return Bitmap.createBitmap(bitmap,0,0,bitmap.width,bitmap.height,m,true).also { if(it!==bitmap) bitmap.recycle() }
        }
        return bitmap
    }
}
