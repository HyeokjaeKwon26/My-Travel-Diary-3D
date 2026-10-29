package com.traveler.feature.video

import android.content.Context
import android.graphics.Bitmap
import android.media.*
import android.net.Uri
import android.os.Build
import com.traveler.core.model.MediaItem
import com.traveler.core.model.VideoClip
import com.traveler.feature.map.story.TravelStoryTimeline
import java.io.Closeable
import java.nio.ByteOrder
import kotlin.math.*

/** One bounded video frame and one retriever; adjacent output frames reuse a 15 fps source frame. */
class ClipFrameReader(private val context: Context) : Closeable {
    private var uri: String? = null
    private var retriever: MediaMetadataRetriever? = null
    private var frame: Bitmap? = null
    private var key = -1L
    fun frame(media: MediaItem, elapsedSeconds: Float, width: Int, height: Int): Bitmap? {
        val clip = media.videoClip?.takeIf { it.enabled } ?: return null
        clip.validate()
        if (uri != media.contentUriString) {
            close(); uri = media.contentUriString
            retriever = MediaMetadataRetriever().also { it.setDataSource(context, Uri.parse(uri)) }
        }
        val at = clip.startMs + (elapsedSeconds.coerceAtLeast(0f) * 1000).toLong().coerceAtMost((clip.durationMs - 1).coerceAtLeast(0))
        val bucket = at / 66
        if (key == bucket && frame != null) return frame
        val next = if (Build.VERSION.SDK_INT >= 27) retriever!!.getScaledFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST, width.coerceAtLeast(1), height.coerceAtLeast(1))
            else retriever!!.getFrameAtTime(at * 1000, MediaMetadataRetriever.OPTION_CLOSEST)
        check(next != null) { "동영상 구간의 프레임을 읽지 못했습니다: ${media.fileName}" }
        val scale = minOf(1f, width.toFloat() / next.width, height.toFloat() / next.height)
        val fitted = if (scale < 1f) Bitmap.createScaledBitmap(next, (next.width * scale).toInt().coerceAtLeast(1), (next.height * scale).toInt().coerceAtLeast(1), true).also { next.recycle() } else next
        frame?.recycle(); frame = fitted; key = bucket
        return frame
    }
    override fun close() { frame?.recycle(); frame = null; retriever?.release(); retriever = null; uri = null; key = -1 }
}

object ClipAudioMath {
    fun mix(background: Int, original: Int, backgroundGain: Float, originalGain: Float): Short =
        (background * backgroundGain + original * originalGain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
}

/** Decode at most the selected ten seconds into 44.1 kHz stereo; never load a full source video. */
internal object ClipPcm {
    fun decode(context: Context, media: MediaItem, clip: VideoClip, checkActive: () -> Unit = {}): ShortArray {
        clip.validate()
        val result = ShortArray(ceil(clip.durationMs * 44.1).toInt() * 2)
        val extractor = MediaExtractor()
        var decoder: MediaCodec? = null
        try {
            extractor.setDataSource(context, Uri.parse(media.contentUriString), null)
            val track = (0 until extractor.trackCount).firstOrNull { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true } ?: return result
            extractor.selectTrack(track)
            val format = extractor.getTrackFormat(track)
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val codec = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!); decoder = codec
            codec.configure(format, null, null, 0); codec.start()
            val startUs = clip.startMs * 1000; val endUs = (clip.startMs + clip.durationMs) * 1000
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            var inputEnded = false; var finished = false
            val deadline = System.nanoTime() + 30_000_000_000L
            val info = MediaCodec.BufferInfo()
            while (!finished) {
                checkActive()
                check(System.nanoTime() < deadline) { "동영상 소리 해독 시간이 초과됐습니다: ${media.fileName}" }
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(0)
                    if (index >= 0) {
                        val data = codec.getInputBuffer(index)!!; data.clear()
                        val n = if (extractor.sampleTime > endUs + 1_000_000) -1 else extractor.readSampleData(data, 0)
                        if (n < 0) { codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputEnded = true }
                        else { codec.queueInputBuffer(index, 0, n, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val index = codec.dequeueOutputBuffer(info, 10_000)
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val actual = codec.outputFormat
                    rate = actual.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = actual.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (actual.containsKey(MediaFormat.KEY_PCM_ENCODING)) actual.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                    require(rate in 8000..192000 && channels in 1..8 && encoding in setOf(AudioFormat.ENCODING_PCM_16BIT, AudioFormat.ENCODING_PCM_FLOAT)) { "지원하지 않는 원본 소리 형식입니다." }
                } else if (index >= 0) {
                    val buffer = codec.getOutputBuffer(index)!!
                    buffer.position(info.offset); buffer.limit(info.offset + info.size)
                    val data = buffer.slice().order(ByteOrder.LITTLE_ENDIAN)
                    val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                    val frames = info.size / (bytesPerSample * channels)
                    val start = (info.presentationTimeUs - startUs) * 44100.0 / 1_000_000
                    val first = ceil(start).toInt().coerceAtLeast(0)
                    val last = ceil(start + frames * 44100.0 / rate).toInt().coerceAtMost(result.size / 2)
                    fun sample(frame: Int, channel: Int): Float {
                        val at = (frame.coerceIn(0, frames - 1) * channels + channel.coerceAtMost(channels - 1)) * bytesPerSample
                        return if (encoding == AudioFormat.ENCODING_PCM_FLOAT) data.getFloat(at).coerceIn(-1f, 1f) * 32767f else data.getShort(at).toFloat()
                    }
                    if (frames > 0) for (output in first until last) {
                        val source = ((output - start) * rate / 44100.0).coerceAtLeast(0.0)
                        val left = floor(source).toInt(); val f = (source - left).toFloat()
                        for (channel in 0..1) result[output * 2 + channel] = (sample(left, channel) * (1 - f) + sample(left + 1, channel) * f).roundToInt().coerceIn(-32768, 32767).toShort()
                    }
                    finished = info.presentationTimeUs >= endUs || info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    codec.releaseOutputBuffer(index, false)
                }
            }
            return result
        } finally { runCatching { decoder?.stop() }; decoder?.release(); extractor.release() }
    }
}

class ClipAudioMixer(private val context: Context, timeline: TravelStoryTimeline, includeMusic: Boolean, private val checkActive: () -> Unit = {}) : Closeable {
    private val music = if (includeMusic) StreamingSoundtrack(context) else null
    private val moments = timeline.photoMoments.filter { it.photo.isVideo && it.photo.videoClip?.enabled == true }.sortedBy { it.displayStartStorySeconds }
    private var cursor = 0
    private var cachedId: String? = null
    private var cached = ShortArray(0)
    fun read(bytes: ByteArray, firstSample: Long) {
        if (music != null) music.read(bytes) else bytes.fill(0)
        val samples = java.nio.ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        for (i in 0 until bytes.size / 4) {
            val seconds = (firstSample + i) / 44100.0
            while (cursor < moments.size && seconds >= moments[cursor].displayEndStorySeconds) { cursor++; cachedId = null; cached = ShortArray(0) }
            val moment = moments.getOrNull(cursor)?.takeIf { seconds >= it.displayStartStorySeconds } ?: continue
            val clip = moment.photo.videoClip!!
            if (clip.originalVolume > 0 && cachedId != moment.mediaId) { cached = ClipPcm.decode(context, moment.photo, clip, checkActive); cachedId = moment.mediaId }
            val source = ((seconds - moment.displayStartStorySeconds) * 44100).toInt() * 2
            for (channel in 0..1) {
                val index = i * 2 + channel
                val original = if (clip.originalVolume > 0) cached.getOrElse(source + channel) { 0 }.toInt() else 0
                samples.put(index, ClipAudioMath.mix(samples.get(index).toInt(), original, clip.musicVolume, clip.originalVolume))
            }
        }
    }
    override fun close() { music?.close(); cached = ShortArray(0) }
}
