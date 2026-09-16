package com.traveler.feature.video

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import com.traveler.R
import java.io.Closeable

/** Decode only the next PCM chunk; no full-length WAV or PCM copy in memory. */
class StreamingSoundtrack(context:Context):Closeable {
    private val extractor=MediaExtractor()
    private val codec:MediaCodec
    private var inputEnded=false
    private var outputEnded=false
    private var pending=ByteArray(0)
    private var offset=0
    init {
        var created:MediaCodec?=null
        try {
        context.resources.openRawResourceFd(R.raw.traveler_memories).use { fd ->
            extractor.setDataSource(fd.fileDescriptor,fd.startOffset,fd.length)
        }
        val index=(0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/")==true }
        extractor.selectTrack(index)
        val format=extractor.getTrackFormat(index)
        require(format.getInteger(MediaFormat.KEY_SAMPLE_RATE)==44100 && format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)==2)
        codec=MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
        created=codec
        codec.configure(format,null,null,0);codec.start()
        } catch(e:Exception) { runCatching { created?.release() };extractor.release();throw e }
    }
    fun read(bytes:ByteArray) {
        var filled=0
        val deadline=System.nanoTime()+5_000_000_000L
        val info=MediaCodec.BufferInfo()
        while(filled<bytes.size) {
            check(System.nanoTime()<deadline) { "Soundtrack decoder timed out" }
            if(offset<pending.size) {
                val n=minOf(bytes.size-filled,pending.size-offset)
                pending.copyInto(bytes,filled,offset,offset+n);offset+=n;filled+=n
                continue
            }
            if(outputEnded) {
                extractor.seekTo(0,MediaExtractor.SEEK_TO_CLOSEST_SYNC);codec.flush()
                inputEnded=false;outputEnded=false
            }
            if(!inputEnded) {
                val i=codec.dequeueInputBuffer(0)
                if(i>=0) {
                    val buffer=codec.getInputBuffer(i)!!;buffer.clear()
                    val size=extractor.readSampleData(buffer,0)
                    if(size<0) { codec.queueInputBuffer(i,0,0,0,MediaCodec.BUFFER_FLAG_END_OF_STREAM);inputEnded=true }
                    else { codec.queueInputBuffer(i,0,size,extractor.sampleTime,0);extractor.advance() }
                }
            }
            val i=codec.dequeueOutputBuffer(info,10_000)
            if(i==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                val f=codec.outputFormat
                check(f.getInteger(MediaFormat.KEY_SAMPLE_RATE)==44100 && f.getInteger(MediaFormat.KEY_CHANNEL_COUNT)==2)
                check(!f.containsKey(MediaFormat.KEY_PCM_ENCODING) || f.getInteger(MediaFormat.KEY_PCM_ENCODING)==android.media.AudioFormat.ENCODING_PCM_16BIT)
            } else if(i>=0) {
                val buffer=codec.getOutputBuffer(i)!!
                pending=ByteArray(info.size);offset=0
                buffer.position(info.offset);buffer.limit(info.offset+info.size);buffer.get(pending)
                outputEnded=info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                codec.releaseOutputBuffer(i,false)
            }
        }
    }
    override fun close() { try { codec.stop() } finally { codec.release();extractor.release() } }
}
