package com.traveler.feature.video

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.FrameLayout
import android.widget.MediaController
import android.widget.Toast
import android.widget.VideoView
import androidx.activity.ComponentActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import java.io.File

/** VideoView retains its player when this activity's available window size changes. */
class VideoPlayerActivity : ComponentActivity() {
    private lateinit var video: VideoView
    private var resumePosition = 0
    private var resumePlaying = true

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val file = intent.getStringExtra("videoPath")?.let(::File)
        val exports = TravelVideoExporter.getExportTempDir(this).canonicalFile
        if (file == null || !file.isFile || file.canonicalFile.parentFile != exports) { finish(); return }
        resumePosition = savedInstanceState?.getInt("position") ?: 0
        resumePlaying = savedInstanceState?.getBoolean("playing") ?: true
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val safe = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }
        video = VideoView(this)
        root.addView(video, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT, Gravity.CENTER))
        val controller = MediaController(this)
        controller.setAnchorView(video)
        video.setMediaController(controller)
        val dp = resources.displayMetrics.density
        fun button(text: String, gravity: Int, action: () -> Unit) {
            root.addView(Button(this).apply { this.text = text; setOnClickListener { action() } },
                FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (52 * dp).toInt(), gravity))
        }
        button("Back", Gravity.TOP or Gravity.START) { finish() }
        button("Full screen", Gravity.TOP or Gravity.END) {
            requestedOrientation = if (resources.configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE)
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            WindowInsetsControllerCompat(window, root).apply {
                hide(WindowInsetsCompat.Type.systemBars())
                systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
            }
        }
        setContentView(root)
        video.setOnPreparedListener {
            video.seekTo(resumePosition)
            if (resumePlaying) video.start() else controller.show(0)
        }
        video.setOnErrorListener { _, _, _ ->
            Toast.makeText(this, "This video could not be played", Toast.LENGTH_LONG).show()
            finish(); true
        }
        video.setVideoPath(file.absolutePath)
    }

    override fun onPause() {
        if (::video.isInitialized) { resumePosition = video.currentPosition; resumePlaying = video.isPlaying; video.pause() }
        super.onPause()
    }
    override fun onResume() {
        super.onResume()
        if (::video.isInitialized && resumePlaying) video.start()
    }
    override fun onSaveInstanceState(outState: Bundle) {
        if (::video.isInitialized) {
            outState.putInt("position", video.currentPosition)
            outState.putBoolean("playing", video.isPlaying)
        }
        super.onSaveInstanceState(outState)
    }
    override fun onDestroy() {
        if (::video.isInitialized) video.stopPlayback()
        super.onDestroy()
    }
}
