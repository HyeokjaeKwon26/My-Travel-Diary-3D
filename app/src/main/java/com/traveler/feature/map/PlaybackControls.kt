package com.traveler.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Compact live-player chrome. Every button retains a 48dp touch target. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PlaybackControls(
    progress: Float, timeLabel: String, isPlaying: Boolean, speed: Float,
    musicEnabled: Boolean, fullscreen: Boolean, canToggleFullscreen: Boolean,
    onPressChanged: (Boolean) -> Unit, onSeek: (Float) -> Unit, onSeekFinished: () -> Unit,
    onPlayPause: () -> Unit, onRestart: () -> Unit, onSpeed: () -> Unit,
    onMusic: () -> Unit, onFullscreen: () -> Unit, onClose: () -> Unit,
    modifier: Modifier = Modifier
) {
    val pressChanged by rememberUpdatedState(onPressChanged)
    BoxWithConstraints(modifier.fillMaxWidth().testTag("playback-controls")
        .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xC9101726))))
        .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom))
        .padding(horizontal = 8.dp)
        .pointerInput(Unit) {
            // Observe without consuming: sliders/buttons still own their gestures.
            awaitEachGesture {
                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                pressChanged(true)
                try {
                    do { val event = awaitPointerEvent(PointerEventPass.Initial) }
                    while (event.changes.any { it.pressed })
                } finally { pressChanged(false) }
            }
        }) {
        val oneRow = maxWidth >= 600.dp && LocalDensity.current.fontScale <= 1.3f
        val clock: @Composable () -> Unit = {
            Text(timeLabel, color = Color.White, fontSize = 11.sp, maxLines = 1,
                modifier = Modifier.testTag("playback-time"))
        }
        val seek: @Composable (Modifier) -> Unit = { seekModifier ->
            Slider(value = progress, onValueChange = onSeek, onValueChangeFinished = onSeekFinished,
                modifier = seekModifier.testTag("playback-seek")
                    .semantics { contentDescription = "Playback position" },
                thumb = { Box(Modifier.size(12.dp).background(Color(0xFFC4B5FD), androidx.compose.foundation.shape.CircleShape)) },
                track = { state -> SliderDefaults.Track(state, Modifier.height(3.dp), colors = SliderDefaults.colors(
                    activeTrackColor = Color(0xFFC4B5FD), inactiveTrackColor = Color.White.copy(alpha = .35f))) })
        }
        val actions: @Composable RowScope.() -> Unit = {
            ControlIcon(if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                if (isPlaying) "Pause" else "Play", onPlayPause)
            ControlIcon(Icons.Default.Refresh, "Restart", onRestart)
            if (!oneRow) Spacer(Modifier.weight(1f))
            TextButton(onClick = onSpeed, modifier = Modifier.size(48.dp)
                .semantics { contentDescription = "Playback speed" }, contentPadding = PaddingValues(0.dp)) {
                Text("${if (speed % 1f == 0f) speed.toInt().toString() else speed.toString()}×", color = Color.White, fontSize = 12.sp)
            }
            ControlIcon(if (musicEnabled) Icons.Default.MusicNote else Icons.Default.MusicOff,
                if (musicEnabled) "Mute music" else "Enable music", onMusic)
            if (canToggleFullscreen) ControlIcon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                if (fullscreen) "Exit fullscreen" else "Fullscreen", onFullscreen)
            ControlIcon(Icons.Default.Close, "Exit Playback", onClose)
        }
        if (oneRow) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            clock()
            seek(Modifier.weight(1f).height(48.dp).padding(horizontal = 8.dp))
            actions()
        } else Column {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                clock()
                seek(Modifier.weight(1f).height(48.dp).padding(start = 8.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, content = actions)
        }
    }
}

@Composable
private fun ControlIcon(icon: ImageVector, label: String, onClick: () -> Unit) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp)) {
        Icon(icon, contentDescription = label, tint = Color.White, modifier = Modifier.size(22.dp))
    }
}
