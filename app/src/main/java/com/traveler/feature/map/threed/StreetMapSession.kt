package com.traveler.feature.map.threed

import android.content.Context
import android.graphics.*
import com.traveler.BuildConfig
import kotlinx.coroutines.*
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Properties
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.*

/** Visible-screen requests only. Exports have no network worker and only read cache.
 * Bounded PNGs, one request at a time, validators, server TTL and backoff protect the
 * public tile service. No offline pack, route prefetch or background download. */
class StreetMapSession(context:Context,private val network:Boolean=false,
                       cacheDirectory:File=File(context.cacheDir,"street_maps_v1"),
                       private val connectionFactory:(StreetTile)->HttpURLConnection={ tile ->
                           URL("https://tile.openstreetmap.org/${tile.z}/${tile.x}/${tile.y}.png").openConnection() as HttpURLConnection
                       },private val changed:()->Unit={}) {
    private val directory=cacheDirectory.apply { mkdirs() }
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val lock=Any()
    private val bitmaps=LinkedHashMap<StreetTile,Bitmap>(32,.75f,true)
    private val checked=object:LinkedHashMap<StreetTile,Long>(64,.75f,true) {
        override fun removeEldestEntry(eldest:MutableMap.MutableEntry<StreetTile,Long>?)=size>4096
    }
    private val revision=AtomicLong(0)
    @Volatile private var wanted:StreetTilePlan?=null
    @Volatile private var active=network
    @Volatile private var closed=false
    @Volatile private var requestedTile:StreetTile?=null
    private val bufferGate=MapBufferGate()
    @Volatile var buffering=false;private set
    @Volatile private var connection:HttpURLConnection?=null
    @Volatile var status="Reference map · loading street detail";private set
    @Volatile var detailCount=0;private set
    val version get()=revision.get()
    private val snapshot=if(network)null else directory.listFiles()?.filter { it.extension=="png" }?.map { it.nameWithoutExtension }?.toSet().orEmpty()

    init { if(network) scope.launch {
        while(isActive) {
            val plan=wanted
            if(plan!=null) {
                for(tile in plan.tiles) {
                    if(closed || tile !in (wanted?.tiles ?: emptyList())) break
                    if((checked[tile] ?: 0)>System.currentTimeMillis() &&
                        (synchronized(lock) { tile in bitmaps } || !File(directory,"${tile.key}.png").isFile)) continue
                    // Parent detail is reused only when already cached; never download a zoom stack.
                    for (depth in 3 downTo 1) if(tile.z >= depth) {
                        val parent=StreetTile(tile.z-depth,tile.x shr depth,tile.y shr depth)
                        if(File(directory,"${parent.key}.png").isFile && synchronized(lock) { parent !in bitmaps }) load(parent,false)
                    }
                    load(tile,active)
                    delay(40)
                }
                updateStatus()
            }
            delay(350)
        }
    } }

    fun setActive(value:Boolean) { active=network && value;if(!active) connection?.disconnect() }
    fun request(plan:StreetTilePlan) {
        wanted=plan
        requestedTile?.let { if(it !in plan.tiles) connection?.disconnect() }
        if(!network) for(tile in plan.tiles) {
            for(depth in 3 downTo 1) if(tile.z>=depth) {
                val parent=StreetTile(tile.z-depth,tile.x shr depth,tile.y shr depth)
                if(snapshot?.contains(parent.key)==true && synchronized(lock) { parent !in bitmaps }) load(parent,false)
            }
            if(snapshot?.contains(tile.key)==true && synchronized(lock) { tile !in bitmaps }) load(tile,false)
        }
        updateStatus()
    }
    private fun updateStatus() {
        if(closed) return
        val keys=wanted?.tiles.orEmpty()
        val count=synchronized(lock) { keys.count { it in bitmaps } }
        detailCount=count
        val waiting=bufferGate.update(android.os.SystemClock.elapsedRealtime(), network && active && System.currentTimeMillis()>=blockedUntil,
            keys.isNotEmpty() && count < keys.size)
        if(buffering!=waiting) { buffering=waiting;changed() }
        val text=when {
            keys.isEmpty() -> "Globe overview · north up"
            waiting -> "Preparing map $count/${keys.size} · playback waits"
            count==keys.size && count>0 -> "Street map · north up"
            count>0 -> "Street detail $count/${keys.size} · north up"
            !network -> "Reference map · street detail not cached"
            else -> "Reference map · street detail needs internet"
        }
        if(status!=text) { status=text;changed() }
    }

    private fun load(tile:StreetTile,fetch:Boolean) {
        val file=File(directory,"${tile.key}.png")
        val meta=File(directory,"${tile.key}.properties")
        val properties=Properties().apply { if(meta.isFile) runCatching { meta.inputStream().use { load(it) } } }
        val now=System.currentTimeMillis()
        val expiry=properties.getProperty("expires")?.toLongOrNull() ?: 0L
        if(file.isFile && synchronized(lock) { tile !in bitmaps }) decode(file)?.let { put(tile,it) }
        val cached=synchronized(lock) { tile in bitmaps }
        if(!fetch) return
        if(cached && expiry>now) { checked[tile]=expiry;return }
        if(!cached) properties.clear()
        if(!active || closed || now<blockedUntil) return
        checked[tile]=now+60_000
        try {
            val request=connectionFactory(tile)
            connection=request
            requestedTile=tile
            if(!active || closed) return
            request.connectTimeout=5000;request.readTimeout=5000
            request.setRequestProperty("User-Agent","MyTravelDiary3D/${BuildConfig.VERSION_NAME} (+https://github.com/HyeokjaeKwon26/My-Travel-Diary-3D)")
            properties.getProperty("etag")?.let { request.setRequestProperty("If-None-Match",it) }
            properties.getProperty("modified")?.let { request.setRequestProperty("If-Modified-Since",it) }
            val code=request.responseCode
            if(code==429 || code==403 || code==503) {
                checked[tile]=now+300_000
                blockedUntil=max(now+300_000,request.getHeaderField("Retry-After")?.toLongOrNull()?.let { now+it.coerceIn(0,86400)*1000 }
                    ?: request.getHeaderFieldDate("Retry-After",now+300_000))
                return
            }
            if(code!=200 && code!=304) return
            val directives=request.getHeaderField("Cache-Control").orEmpty().lowercase()
            val noStore=directives.split(',').any { it.trim()=="no-store" }
            val ttl=Regex("(?:^|,)\\s*max-age=(\\d+)").find(directives)?.groupValues?.get(1)?.toLongOrNull()
            val age=request.getHeaderField("Age")?.toLongOrNull()?.coerceAtLeast(0) ?: 0
            val expires=if(directives.split(',').any { it.trim()=="no-cache" }) now else
                ttl?.let { now+(it.coerceAtMost(31_536_000)-age).coerceAtLeast(0)*1000 } ?: request.getHeaderFieldDate("Expires",now+7*86400_000L)
            if(code==200) {
                if(request.contentLengthLong>1_048_576) return
                val data=request.inputStream.use { input ->
                    val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
                    while(output.size()<=1_048_576) {
                        val count=input.read(buffer)
                        if(count<0) break
                        output.write(buffer,0,count)
                    }
                    output.toByteArray()
                }
                if(data.size>1_048_576 || data.size<8) return
                val options=BitmapFactory.Options().apply { inJustDecodeBounds=true }
                BitmapFactory.decodeByteArray(data,0,data.size,options)
                if(options.outWidth!=256 || options.outHeight!=256) return
                val bitmap=BitmapFactory.decodeByteArray(data,0,data.size) ?: return
                if(closed) { bitmap.recycle();return }
                if(!noStore) {
                    val temp=File.createTempFile(tile.key,".tmp",directory)
                    try {
                        temp.writeBytes(data)
                        java.nio.file.Files.move(temp.toPath(),file.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                    } finally { temp.delete() }
                }
                put(tile,bitmap)
                properties.clear()
            }
            if(noStore) { file.delete();meta.delete();checked[tile]=now+60_000;return }
            properties.setProperty("expires",expires.toString())
            request.getHeaderField("ETag")?.let { properties.setProperty("etag",it) }
            request.getHeaderField("Last-Modified")?.let { properties.setProperty("modified",it) }
            meta.outputStream().use { properties.store(it,null) }
            checked[tile]=max(expires,now+60_000)
            trimDisk()
        } catch(_:Exception) { /* Cached map and bundled geography remain visible. */ }
        finally { connection?.disconnect();connection=null;requestedTile=null;updateStatus() }
    }
    companion object { @Volatile private var blockedUntil=0L }
    private fun decode(file:File):Bitmap? = runCatching {
        if(file.length()>1_048_576) return@runCatching null
        val bounds=BitmapFactory.Options().apply { inJustDecodeBounds=true }
        BitmapFactory.decodeFile(file.path,bounds)
        if(bounds.outWidth!=256 || bounds.outHeight!=256) null else BitmapFactory.decodeFile(file.path)
    }.getOrNull()
    private fun put(tile:StreetTile,bitmap:Bitmap) {
        synchronized(lock) {
            if(closed) { bitmap.recycle();return }
            bitmaps.put(tile,bitmap)?.recycle()
            while(bitmaps.size>96) { val key=bitmaps.keys.first();bitmaps.remove(key)?.recycle() }
        }
        revision.incrementAndGet();changed()
    }
    private fun trimDisk() {
        val files=directory.listFiles()?.filter { it.extension=="png" }?.sortedBy { it.lastModified() }.orEmpty()
        var bytes=files.sumOf { it.length() }
        for(f in files) {
            if(bytes<=96L*1024*1024) break
            bytes-=f.length();f.delete();File(directory,"${f.nameWithoutExtension}.properties").delete()
        }
    }

    fun paint(canvas:Canvas,w:OfflineMapDrape.Window,size:Int,plan:StreetTilePlan) {
        synchronized(lock) {
            val paint=Paint(Paint.FILTER_BITMAP_FLAG)
            for(tile in plan.tiles) {
                var source=tile
                var bitmap=bitmaps[source]
                while(bitmap==null && source.z>0) {
                    source=StreetTile(source.z-1,source.x/2,source.y/2)
                    bitmap=bitmaps[source]
                }
                if(bitmap==null) continue
                val n=(1 shl tile.z).toDouble()
                var x=tile.x/n
                x+=round(w.x+w.span/2-(x+.5/n))
                val left=((x-w.x)/w.span*size).toFloat()
                val top=((tile.y/n-w.y)/w.span*size).toFloat()
                val side=(size/(n*w.span)).toFloat()
                val factor=1 shl (tile.z-source.z)
                val step=256f/factor
                val sx=(tile.x-source.x*factor)*step
                val sy=(tile.y-source.y*factor)*step
                val src=Rect(sx.toInt(),sy.toInt(),ceil(sx+step).toInt(),ceil(sy+step).toInt())
                canvas.drawBitmap(bitmap,src,RectF(left,top,left+side,top+side),paint)
            }
        }
    }
    fun close() { closed=true;active=false;connection?.disconnect();scope.cancel();synchronized(lock) { bitmaps.values.forEach { it.recycle() };bitmaps.clear() } }
}
