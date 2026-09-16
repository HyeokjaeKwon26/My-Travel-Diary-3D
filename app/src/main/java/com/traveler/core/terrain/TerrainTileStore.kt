package com.traveler.core.terrain

import java.io.*
import java.security.MessageDigest
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/** Compact, checksummed height grids. Atomic replacement prevents partial tiles being used. */
class TerrainTileStore(val directory: File) {
    init { directory.mkdirs() }
    fun file(id: TerrainTileId) = File(directory,"${id.key}.height")
    fun read(id: TerrainTileId): TerrainPack? {
        val file=file(id)
        if(!file.isFile) return null
        return try {
            require(file.length() in 32..100_000)
            val payload = GZIPInputStream(file.inputStream()).use { input ->
                val out=ByteArrayOutputStream();val buffer=ByteArray(4096)
                while(true) { val n=input.read(buffer);if(n<0) break;require(out.size()+n<=20_000);out.write(buffer,0,n) }
                out.toByteArray()
            }
            val body=payload.copyOfRange(32,payload.size)
            require(MessageDigest.getInstance("SHA-256").digest(body).contentEquals(payload.copyOfRange(0,32)))
            val input=DataInputStream(ByteArrayInputStream(body))
            require(input.readInt()==0x54455232 && input.readInt()==id.z && input.readInt()==id.x && input.readInt()==id.y)
            val size=input.readInt();require(size==65)
            val heights=List(size*size) { input.readShort().toInt().let { if(it==Short.MIN_VALUE.toInt()) null else it.toDouble() } }
            require(input.available()==0)
            file.setLastModified(System.currentTimeMillis())
            TerrainPack(name="Terrain ${id.key}",attribution="Mapzen terrain — source credits in Settings",
                verticalDatum="Source-dependent DEM; not mixed with GPS",north=id.north,south=id.south,
                west=id.west,east=id.east,rows=size,columns=size,heights=heights).validate()
        } catch(_:Exception) { file.delete();null }
    }
    fun write(id: TerrainTileId, heights: List<Double?>) {
        require(heights.size==65*65 && heights.all { it==null || (it.isFinite() && it in -12000.0..10000.0) })
        val body=ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(0x54455232);out.writeInt(id.z);out.writeInt(id.x);out.writeInt(id.y);out.writeInt(65)
            heights.forEach { out.writeShort(it?.toInt() ?: Short.MIN_VALUE.toInt()) }
        } }.toByteArray()
        val temp=File(directory,"${id.key}.pending")
        try {
            FileOutputStream(temp).use { file ->
                GZIPOutputStream(file).use { it.write(MessageDigest.getInstance("SHA-256").digest(body));it.write(body) }
            }
            check(temp.renameTo(file(id))) { "Could not save terrain tile" }
        } finally { temp.delete() }
    }
    fun bytes() = directory.listFiles()?.filter { it.extension=="height" }?.sumOf { it.length() } ?: 0L
    fun trim(limit: Long, protected: Set<String>, reserve: Long = 0) {
        var size=bytes()
        directory.listFiles()?.filter { it.extension=="height" && it.nameWithoutExtension !in protected }
            ?.sortedBy { it.lastModified() }?.forEach { val length=it.length();if(size+reserve>limit && it.delete()) size-=length }
        // Recount after deletion: filesystem accounting also handles interrupted older runs.
        require(bytes()+reserve<=limit) { "Terrain storage is full. Increase the limit or unpin a saved journey." }
    }
}
