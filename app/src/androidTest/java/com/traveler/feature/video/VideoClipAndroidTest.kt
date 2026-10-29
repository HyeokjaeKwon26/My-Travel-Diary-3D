package com.traveler.feature.video

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.traveler.core.media.TripMediaFiles
import com.traveler.core.model.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VideoClipAndroidTest {
    @Test fun selectedFramesMoveAndOriginal48kMonoAudioBecomesBoundedStereo() {
        val c = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(c.filesDir,"clip-decoder-test.mp4")
        InstrumentationRegistry.getInstrumentation().context.assets.open("media/clip-colors-tone.mp4").use { input -> file.outputStream().use { input.copyTo(it) } }
        try {
            val media = MediaItem("test",TripMediaFiles.uri(c,file),file.name,"video/mp4",null,
                videoClip=VideoClip(durationMs=7000))
            ClipFrameReader(c).use { reader ->
                val first = reader.frame(media,1f,80,80)!!
                val red = first.getPixel(first.width/2,first.height/2)
                assertTrue(Color.red(red)>200 && Color.blue(red)<30)
                val second = reader.frame(media,5f,80,80)!!
                val blue = second.getPixel(second.width/2,second.height/2)
                assertTrue(Color.blue(blue)>200 && Color.red(blue)<30)
                assertTrue(second.width<=80 && second.height<=80)
            }
            val pcm = ClipPcm.decode(c,media,VideoClip(startMs=4000,durationMs=3000))
            assertEquals(44100*3*2,pcm.size)
            assertTrue(pcm.any { kotlin.math.abs(it.toInt())>500 })
            assertTrue(pcm.asList().chunked(2).all { it[0]==it[1] })
            var cancelled = false
            try { ClipPcm.decode(c,media,VideoClip()) { throw kotlinx.coroutines.CancellationException() } }
            catch (_: kotlinx.coroutines.CancellationException) { cancelled=true }
            assertTrue(cancelled)
        } finally { file.delete() }
    }
}
