package com.traveler.feature.video

import android.content.Context
import android.media.*
import android.view.Surface
import com.traveler.feature.map.story.TravelStoryTimeline
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Deterministic Native Android MP4 Video Encoder (P1, P1-01 ~ P1-04).
 *
 * Encodes offscreen canvas frames into a standard H.264 / AAC MP4 file using
 * native [MediaCodec] and [MediaMuxer].
 *
 * Invariants:
 * 1. 100% Offline: Zero network calls, zero external binary dependencies.
 * 2. Real Soundtrack: Muxes bundled traveler_memories.m4a as AAC audio track when enabled.
 * 3. Deterministic Frame Timestamps: Monotonic frame PTS advances at exact frame intervals.
 * 4. Clean Cancellation: Stopping or cancelling immediately cleans up partial files.
 */
class TravelVideoEncoder(
    private val context: Context,
    private val timeline: TravelStoryTimeline,
    private val renderer: TravelVideoRenderer
) {
    private val isCancelled = AtomicBoolean(false)

    fun cancel() {
        isCancelled.set(true)
    }

    private data class QueuedSample(
        val isAudio: Boolean,
        val data: ByteArray,
        val offset: Int,
        val size: Int,
        val presentationTimeUs: Long,
        val flags: Int
    )

    suspend fun encodeToMp4(
        outputFile: File,
        width: Int = 1080,
        height: Int = 1920,
        fps: Int = 30,
        includeMusic: Boolean = true,
        onProgress: ((Float) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val totalDurationSeconds = timeline.totalStoryDurationSeconds
        val totalFrames = maxOf(30, (totalDurationSeconds * fps).toInt())

        var videoEncoder: MediaCodec? = null
        var audioEncoder: MediaCodec? = null
        var inputSurface: Surface? = null
        var codecInputSurface: CodecInputSurface? = null
        var reusableBitmap: android.graphics.Bitmap? = null
        var muxer: MediaMuxer? = null
        var soundtrack: StreamingSoundtrack? = null
        var isMuxerStarted = false
        var videoTrackIndex = -1
        var audioTrackIndex = -1

        val pendingSamples = mutableListOf<QueuedSample>()

        try {
            // 1. Configure Video Encoder (H.264 / AVC)
            val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
                setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
                setInteger(MediaFormat.KEY_BIT_RATE, 6_000_000) // 6 Mbps
                setInteger(MediaFormat.KEY_FRAME_RATE, fps)
                setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1) // 1 second keyframe interval
            }

            videoEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
            videoEncoder.configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            inputSurface = videoEncoder.createInputSurface()
            codecInputSurface = CodecInputSurface(inputSurface, width, height)
            reusableBitmap = android.graphics.Bitmap.createBitmap(width, height, android.graphics.Bitmap.Config.ARGB_8888)
            val reusableCanvas = android.graphics.Canvas(reusableBitmap)
            videoEncoder.start()

            // 2. Configure Audio Encoder (AAC) if includeMusic is true
            if (includeMusic) {
                soundtrack=StreamingSoundtrack(context)
                val audioFormat=MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC,44100,2).apply {
                    setInteger(MediaFormat.KEY_AAC_PROFILE,MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                    setInteger(MediaFormat.KEY_BIT_RATE,128_000)
                    setInteger(MediaFormat.KEY_MAX_INPUT_SIZE,16384)
                }
                audioEncoder=MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
                audioEncoder.configure(audioFormat,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE)
                audioEncoder.start()
            }

            // 3. Initialize MediaMuxer
            muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)

            val vBufferInfo = MediaCodec.BufferInfo()
            val aBufferInfo = MediaCodec.BufferInfo()

            val totalAudioSamples = (totalDurationSeconds * 44100.0).toLong()
            val fadeOutStartSample = maxOf(0L, totalAudioSamples - (2.0 * 44100).toLong())
            var audioSamplesFed = 0L
            var isAudioEosSignaled = (audioEncoder == null)
            var audioOutputEnded = (audioEncoder == null)

            fun flushPendingSamplesIfReady() {
                val needAudio = (audioEncoder != null)
                val isReady = videoTrackIndex >= 0 && (!needAudio || audioTrackIndex >= 0)
                if (isReady && !isMuxerStarted) {
                    muxer.start()
                    isMuxerStarted = true
                    for (s in pendingSamples) {
                        val track = if (s.isAudio) audioTrackIndex else videoTrackIndex
                        val bInfo = MediaCodec.BufferInfo().apply {
                            set(0, s.size, s.presentationTimeUs, s.flags)
                        }
                        val buf = ByteBuffer.wrap(s.data, s.offset, s.size)
                        muxer.writeSampleData(track, buf, bInfo)
                    }
                    pendingSamples.clear()
                }
            }

            fun drainAudioEncoder(endOfStream: Boolean) {
                val enc = audioEncoder ?: return
                var aOutIdx = enc.dequeueOutputBuffer(aBufferInfo, if (endOfStream) 10_000L else 0L)
                while (aOutIdx >= 0 || aOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (aOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (audioTrackIndex < 0) {
                            audioTrackIndex = muxer.addTrack(enc.outputFormat)
                            flushPendingSamplesIfReady()
                        }
                    } else if (aOutIdx >= 0) {
                        val encodedData = enc.getOutputBuffer(aOutIdx)
                        if (encodedData != null && (aBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && aBufferInfo.size != 0) {
                            if (isMuxerStarted) {
                                encodedData.position(aBufferInfo.offset)
                                encodedData.limit(aBufferInfo.offset + aBufferInfo.size)
                                muxer.writeSampleData(audioTrackIndex, encodedData, aBufferInfo)
                            } else {
                                val bytes = ByteArray(aBufferInfo.size)
                                encodedData.position(aBufferInfo.offset)
                                encodedData.get(bytes)
                                pendingSamples.add(
                                    QueuedSample(
                                        isAudio = true,
                                        data = bytes,
                                        offset = 0,
                                        size = bytes.size,
                                        presentationTimeUs = aBufferInfo.presentationTimeUs,
                                        flags = aBufferInfo.flags
                                    )
                                )
                            }
                        }
                        if(aBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) audioOutputEnded=true
                        enc.releaseOutputBuffer(aOutIdx, false)
                    }
                    aOutIdx = enc.dequeueOutputBuffer(aBufferInfo, 0L)
                }
            }

            fun feedAudioInput(targetSamples:Long = totalAudioSamples) {
                val enc = audioEncoder ?: return
                val pcm = soundtrack ?: return
                if (isAudioEosSignaled) return

                while (audioSamplesFed < minOf(totalAudioSamples,targetSamples)) {
                    coroutineContext.ensureActive()
                    val inIdx = enc.dequeueInputBuffer(0L)
                    if (inIdx < 0) break

                    val inputBuf = enc.getInputBuffer(inIdx) ?: break
                    inputBuf.clear()

                    val maxChunkBytes = minOf(inputBuf.capacity(), 4096, ((totalAudioSamples - audioSamplesFed) * 4).coerceAtMost(4096L).toInt())
                    val chunkBytes = ByteArray(maxChunkBytes)
                    pcm.read(chunkBytes)
                    val filled=chunkBytes.size

                    val samplesInChunk = filled / 4 // 16-bit stereo = 4 bytes per sample
                    val chunkShorts = ShortArray(filled / 2)
                    ByteBuffer.wrap(chunkBytes, 0, filled).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(chunkShorts)

                    // Apply smooth fade-out over the final 2 seconds
                    for (i in 0 until samplesInChunk) {
                        val currentSampleIdx = audioSamplesFed + i
                        if (currentSampleIdx >= fadeOutStartSample) {
                            val fadeFactor = ((totalAudioSamples - currentSampleIdx).toFloat() / (totalAudioSamples - fadeOutStartSample).toFloat()).coerceIn(0f, 1f)
                            chunkShorts[i * 2] = (chunkShorts[i * 2] * fadeFactor).toInt().toShort()
                            chunkShorts[i * 2 + 1] = (chunkShorts[i * 2 + 1] * fadeFactor).toInt().toShort()
                        }
                    }

                    ByteBuffer.wrap(chunkBytes, 0, filled).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(chunkShorts)
                    inputBuf.put(chunkBytes, 0, filled)

                    val ptsUs = (audioSamplesFed * 1_000_000L) / 44100L
                    audioSamplesFed += samplesInChunk

                    val isEos = (audioSamplesFed >= totalAudioSamples)
                    val flags = if (isEos) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0
                    enc.queueInputBuffer(inIdx, 0, filled, ptsUs, flags)

                    if (isEos) {
                        isAudioEosSignaled = true
                        break
                    }
                }
            }

            // 4. Main Render & Encode Frame Loop (P0-01, P0-02)
            for (frameIndex in 0 until totalFrames) {
                coroutineContext.ensureActive()
                if (isCancelled.get()) {
                    throw InterruptedException("Video encoding cancelled by user")
                }

                val storyTimeSeconds = frameIndex.toFloat() / fps.toFloat()
                val framePtsNs = frameIndex.toLong() * 1_000_000_000L / fps.toLong()

                // Render into reusable canvas/bitmap and submit via EGL with explicit PTS
                renderer.renderGlFrame(codecInputSurface, reusableBitmap, reusableCanvas, width, height, storyTimeSeconds, framePtsNs)

                // Feed and drain audio
                if (audioEncoder != null) {
                    feedAudioInput(((storyTimeSeconds+1.0)*44100).toLong())
                    drainAudioEncoder(false)
                }

                // Drain video encoder outputs
                var vOutIdx = videoEncoder.dequeueOutputBuffer(vBufferInfo, 10_000L)
                while (vOutIdx >= 0 || vOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    if (vOutIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        if (videoTrackIndex < 0) {
                            videoTrackIndex = muxer.addTrack(videoEncoder.outputFormat)
                            flushPendingSamplesIfReady()
                        }
                    } else if (vOutIdx >= 0) {
                        val encodedData = videoEncoder.getOutputBuffer(vOutIdx)
                        if (encodedData != null && (vBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && vBufferInfo.size != 0) {
                            if (isMuxerStarted) {
                                encodedData.position(vBufferInfo.offset)
                                encodedData.limit(vBufferInfo.offset + vBufferInfo.size)
                                muxer.writeSampleData(videoTrackIndex, encodedData, vBufferInfo)
                            } else {
                                val bytes = ByteArray(vBufferInfo.size)
                                encodedData.position(vBufferInfo.offset)
                                encodedData.get(bytes)
                                pendingSamples.add(
                                    QueuedSample(
                                        isAudio = false,
                                        data = bytes,
                                        offset = 0,
                                        size = bytes.size,
                                        presentationTimeUs = vBufferInfo.presentationTimeUs,
                                        flags = vBufferInfo.flags
                                    )
                                )
                            }
                        }
                        videoEncoder.releaseOutputBuffer(vOutIdx, false)
                    }
                    vOutIdx = videoEncoder.dequeueOutputBuffer(vBufferInfo, 0L)
                }

                onProgress?.invoke((frameIndex + 1).toFloat() / totalFrames.toFloat())
            }

            // 5. Signal End-of-Stream to Video Encoder & Drain EOS
            videoEncoder.signalEndOfInputStream()
            val videoDeadline=System.nanoTime()+15_000_000_000L
            var isVideoEos = false
            while (!isVideoEos) {
                coroutineContext.ensureActive()
                if(isCancelled.get()) throw InterruptedException("Video encoding cancelled")
                check(System.nanoTime()<videoDeadline) { "Video encoder did not finish" }
                val outIndex = videoEncoder.dequeueOutputBuffer(vBufferInfo, 20_000L)
                if (outIndex >= 0) {
                    if ((vBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        isVideoEos = true
                    }
                    val encodedData = videoEncoder.getOutputBuffer(outIndex)
                    if (encodedData != null && (vBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && vBufferInfo.size != 0) {
                        if (isMuxerStarted) {
                            encodedData.position(vBufferInfo.offset)
                            encodedData.limit(vBufferInfo.offset + vBufferInfo.size)
                            muxer.writeSampleData(videoTrackIndex, encodedData, vBufferInfo)
                        }
                    }
                    videoEncoder.releaseOutputBuffer(outIndex, false)
                } else if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && videoTrackIndex<0) {
                    videoTrackIndex=muxer.addTrack(videoEncoder.outputFormat);flushPendingSamplesIfReady()
                }
            }

            // 6. Drain remaining Audio EOS
            if (audioEncoder != null) {
                val audioDeadline=System.nanoTime()+15_000_000_000L
                while (!audioOutputEnded) {
                    coroutineContext.ensureActive()
                    check(System.nanoTime()<audioDeadline) { "Audio encoder did not finish" }
                    if(isCancelled.get()) throw InterruptedException("Video encoding cancelled")
                    if(!isAudioEosSignaled) feedAudioInput()
                    val outIndex = audioEncoder.dequeueOutputBuffer(aBufferInfo, 20_000L)
                    if (outIndex >= 0) {
                        if ((aBufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            audioOutputEnded = true
                        }
                        val encodedData = audioEncoder.getOutputBuffer(outIndex)
                        if (encodedData != null && (aBufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && aBufferInfo.size != 0) {
                            if (isMuxerStarted) {
                                encodedData.position(aBufferInfo.offset)
                                encodedData.limit(aBufferInfo.offset + aBufferInfo.size)
                                muxer.writeSampleData(audioTrackIndex, encodedData, aBufferInfo)
                            }
                        }
                        audioEncoder.releaseOutputBuffer(outIndex, false)
                    } else if(outIndex==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED && audioTrackIndex<0) {
                        audioTrackIndex=muxer.addTrack(audioEncoder.outputFormat);flushPendingSamplesIfReady()
                    }
                }
            }

            check(isMuxerStarted) { "No video frames were encoded" }
            muxer.stop()
            isMuxerStarted=false
            true
        } catch (e: Exception) {
            if (outputFile.exists()) {
                outputFile.delete()
            }
            if (e is InterruptedException) {
                false
            } else {
                throw e
            }
        } finally {
            runCatching { soundtrack?.close() }
            try {
                if (isMuxerStarted) {
                    muxer?.stop()
                }
                muxer?.release()
            } catch (_: Exception) {}

            try {
                videoEncoder?.stop()
                videoEncoder?.release()
            } catch (_: Exception) {}

            try {
                audioEncoder?.stop()
                audioEncoder?.release()
            } catch (_: Exception) {}

            try {
                renderer.release()
                codecInputSurface?.release()
            } catch (_: Exception) {}

            try {
                inputSurface?.release()
            } catch (_: Exception) {}

            try {
                reusableBitmap?.recycle()
            } catch (_: Exception) {}

        }
    }
}
