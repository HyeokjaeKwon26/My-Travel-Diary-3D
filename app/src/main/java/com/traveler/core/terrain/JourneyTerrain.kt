package com.traveler.core.terrain

import android.content.Context
import android.graphics.BitmapFactory
import androidx.work.*
import com.traveler.feature.map.renderer.TravelMapRenderModel
import kotlinx.coroutines.*
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit
import kotlin.math.*

data class TerrainStatus(val ready: Int=0,val total: Int=0,val message: String="Preparing terrain…",val busy: Boolean=false)

object JourneyTerrain {
    private val json=Json { ignoreUnknownKeys=true }
    private val lock=Any()
    fun store(context:Context)=TerrainTileStore(File(context.filesDir,"terrain_tiles_v2"))
    private fun plans(context:Context)=File(context.filesDir,"terrain_journeys").apply { mkdirs() }
    private fun planFile(context:Context,key:String):File {
        require(key.matches(Regex("[a-f0-9]{32}")))
        return File(plans(context),"$key.json")
    }
    fun readPlan(context:Context,key:String):TerrainJourneyPlan? = synchronized(lock) {
        runCatching { val f=planFile(context,key);require(f.length()<100_000);json.decodeFromString<TerrainJourneyPlan>(f.readText()) }.getOrNull()
    }
    private fun save(context:Context,plan:TerrainJourneyPlan) = synchronized(lock) {
        val file=planFile(context,plan.key);val temp=File(file.parentFile,file.name+".pending")
        temp.writeText(json.encodeToString(plan));check(temp.renameTo(file)) { "Could not save terrain preparation" }
    }
    fun budget(context:Context)=context.getSharedPreferences("terrain_settings",0).getInt("limit_mb",200)*1_000_000L
    suspend fun setBudget(context:Context,mb:Int) = withContext(Dispatchers.IO) {
        require(mb in listOf(100,200,500))
        synchronized(lock) {
            store(context).trim(mb*1_000_000L,protected(context))
            context.getSharedPreferences("terrain_settings",0).edit().putInt("limit_mb",mb).apply()
        }
    }
    private fun protected(context:Context):Set<String> = plans(context).listFiles().orEmpty()
        .filter { it.extension=="json" }.flatMap { f ->
            runCatching { json.decodeFromString<TerrainJourneyPlan>(f.readText()) }.getOrNull()
                ?.takeIf { it.pinned }?.tiles?.map { it.key } ?: emptyList()
        }.toSet()
    suspend fun prepare(context:Context,model:TravelMapRenderModel):TerrainJourneyPlan = withContext(Dispatchers.IO) {
        val plan=TerrainTilePlanner.plan(model)
        val saved=readPlan(context,plan.key) ?: plan.also { save(context,it) }
        if(!saved.paused && synchronized(lock) { saved.tiles.any { store(context).read(it)==null } }) enqueue(context,saved,false)
        saved
    }
    fun enqueue(context:Context,plan:TerrainJourneyPlan,replace:Boolean=true) {
        if(plan.tiles.isEmpty()) return
        val request=OneTimeWorkRequestBuilder<TerrainDownloadWorker>()
            .setInputData(workDataOf("key" to plan.key))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(if(plan.allowMetered) NetworkType.CONNECTED else NetworkType.UNMETERED).setRequiresStorageNotLow(true).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()
        WorkManager.getInstance(context).enqueueUniqueWork("terrain-${plan.key}",if(replace) ExistingWorkPolicy.REPLACE else ExistingWorkPolicy.KEEP,request)
    }
    suspend fun resume(context:Context,key:String,metered:Boolean) = withContext(Dispatchers.IO) {
        val plan=readPlan(context,key)?.copy(paused=false,allowMetered=metered) ?: return@withContext
        save(context,plan);enqueue(context,plan)
    }
    suspend fun pause(context:Context,key:String) = withContext(Dispatchers.IO) {
        readPlan(context,key)?.let { save(context,it.copy(paused=true)) }
        WorkManager.getInstance(context).cancelUniqueWork("terrain-$key").result.get()
    }
    suspend fun pin(context:Context,key:String,pinned:Boolean) = withContext(Dispatchers.IO) {
        readPlan(context,key)?.let { save(context,it.copy(pinned=pinned)) }
    }
    suspend fun clearTemporary(context:Context) = withContext(Dispatchers.IO) {
        synchronized(lock) {
            val keep=protected(context)
            store(context).directory.listFiles().orEmpty().filter { it.extension=="height" && it.nameWithoutExtension !in keep }.forEach { it.delete() }
        }
    }
    suspend fun status(context:Context,plan:TerrainJourneyPlan):TerrainStatus = withContext(Dispatchers.IO) {
        val saved=readPlan(context,plan.key) ?: plan
        val ready=saved.tiles.count { store(context).file(it).exists() }
        val work=WorkManager.getInstance(context).getWorkInfosForUniqueWork("terrain-${plan.key}").get()
        val info=work.firstOrNull { !it.state.isFinished } ?: work.firstOrNull()
        val message=when {
            saved.tiles.isEmpty() && saved.limited -> "Journey too large for detailed terrain. Use a shorter date range."
            ready==saved.tiles.size -> if(saved.limited) "Offline terrain ready • reduced detail for this long journey" else "Offline terrain ready"
            saved.paused || info?.state==WorkInfo.State.CANCELLED -> "Terrain paused • $ready/${saved.tiles.size} regions saved"
            info?.state==WorkInfo.State.FAILED -> info.outputData.getString("error") ?: "Terrain unavailable. Retry when connected."
            info==null || info.state==WorkInfo.State.SUCCEEDED -> "Some terrain was cleared. Tap Prepare to download again."
            info.state==WorkInfo.State.RUNNING -> "Preparing terrain • $ready/${saved.tiles.size}"
            else -> if(saved.allowMetered) "Waiting for connection • $ready/${saved.tiles.size}" else "Waiting for Wi-Fi • $ready/${saved.tiles.size}"
        }
        TerrainStatus(ready,saved.tiles.size,message,info?.state==WorkInfo.State.RUNNING || info?.state==WorkInfo.State.ENQUEUED)
    }
    suspend fun load(context:Context,key:String):List<TerrainPack> = withContext(Dispatchers.IO) {
        val tiles=readPlan(context,key)?.tiles.orEmpty()
        val downloaded=synchronized(lock) { tiles.mapNotNull { store(context).read(it) } }
        // Imported and bundled grids are small and remain available offline.
        if(downloaded.isEmpty()) TerrainRepository.load(context) else TerrainSeams.stitch(downloaded)
    }
    suspend fun load(context:Context,model:TravelMapRenderModel)=load(context,TerrainTilePlanner.plan(model).key)
    internal fun cached(context:Context,id:TerrainTileId)=synchronized(lock) { store(context).read(id)!=null }
    internal fun commit(context:Context,id:TerrainTileId,heights:List<Double?>,active:TerrainJourneyPlan) = synchronized(lock) {
        val store=store(context)
        store.trim(budget(context),protected(context)+active.tiles.map { it.key },20_000)
        require(context.filesDir.usableSpace>4_000_000) { "Not enough free space to prepare terrain" }
        store.write(id,heights)
    }
}

class TerrainDownloadWorker(context:Context,params:WorkerParameters):CoroutineWorker(context,params) {
    override suspend fun doWork():Result = withContext(Dispatchers.IO) {
        val plan=JourneyTerrain.readPlan(applicationContext,inputData.getString("key") ?: return@withContext Result.failure())
            ?: return@withContext Result.failure()
        if(plan.paused) return@withContext Result.success()
        try {
            for((index,id) in plan.tiles.withIndex()) {
                ensureActive()
                if(!JourneyTerrain.cached(applicationContext,id)) {
                    val heights=download(id)
                    ensureActive()
                    JourneyTerrain.commit(applicationContext,id,heights,plan)
                }
                setProgress(workDataOf("done" to index+1,"total" to plan.tiles.size))
            }
            Result.success()
        } catch(e:CancellationException) { throw e }
        catch(e:java.io.IOException) {
            if(runAttemptCount<3) Result.retry() else Result.failure(workDataOf("error" to "Terrain download failed. Saved regions are kept; tap Retry."))
        } catch(e:Exception) { Result.failure(workDataOf("error" to (e.message ?: "Terrain preparation failed"))) }
    }
    private suspend fun download(id:TerrainTileId):List<Double?> {
        val connection=URL("https://s3.amazonaws.com/elevation-tiles-prod/terrarium/${id.z}/${id.x}/${id.y}.png").openConnection() as HttpURLConnection
        connection.connectTimeout=15_000;connection.readTimeout=15_000;connection.instanceFollowRedirects=false
        connection.setRequestProperty("User-Agent","MyTravelDiary3D/1.0")
        try {
            if(connection.responseCode!=200) throw java.io.IOException("Terrain provider unavailable")
            val data=connection.inputStream.use { input ->
                val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                while(true) { currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0) break
                    require(out.size()+n<=1_000_000) { "Terrain tile exceeds size limit" };out.write(buffer,0,n) }
                out.toByteArray()
            }
            return decode(id,data)
        } finally { connection.disconnect() }
    }
    companion object {
        internal fun decode(id:TerrainTileId,data:ByteArray):List<Double?> {
            val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
            BitmapFactory.decodeByteArray(data,0,data.size,bounds)
            require(bounds.outWidth==256 && bounds.outHeight==256) { "Unexpected terrain tile format" }
            val bitmap=BitmapFactory.decodeByteArray(data,0,data.size) ?: error("Cannot decode terrain")
            try {
                fun height(x:Int,y:Int):Double? {
                    val c=bitmap.getPixel(x.coerceIn(0,255),y.coerceIn(0,255))
                    val h=((c ushr 16) and 255)*256.0+((c ushr 8) and 255)+(c and 255)/256.0-32768
                    return h.takeIf { (c ushr 24)!=0 && it in -12000.0..10000.0 }
                }
                return List(65*65) { i ->
                    val row=i/65;val col=i%65
                    val lat=id.north+(id.south-id.north)*row/64
                    val y=((TerrainTileId.mercatorY(lat,id.z)-id.y)*256-.5).coerceIn(0.0,255.0)
                    val x=(col/64.0*256-.5).coerceIn(0.0,255.0)
                    val ix=floor(x).toInt();val iy=floor(y).toInt();val fx=x-ix;val fy=y-iy
                    val a=height(ix,iy);val b=height(ix+1,iy);val c=height(ix,iy+1);val d=height(ix+1,iy+1)
                    if(a==null||b==null||c==null||d==null) null else (a*(1-fx)+b*fx)*(1-fy)+(c*(1-fx)+d*fx)*fy
                }
            } finally { bitmap.recycle() }
        }
    }
}
