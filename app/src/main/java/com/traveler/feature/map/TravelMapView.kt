package com.traveler.feature.map

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.traveler.core.common.geo.GeoPoint
import com.traveler.core.model.MediaItem
import com.traveler.core.model.MovementSegment
import com.traveler.core.model.Visit
import com.traveler.feature.map.audio.TravelSoundtrackPlayer
import com.traveler.feature.map.renderer.*
import com.traveler.feature.map.story.PlaybackContinuityDiagnostic
import kotlinx.coroutines.isActive

@Composable
fun TravelMapView(
    visits: List<Visit>,
    segments: List<MovementSegment>,
    photos: List<MediaItem> = emptyList(),
    focusedLocation: GeoPoint? = null,
    initialIsPlaying: Boolean = false,
    initialPlaybackProgress: Float = 0.0f,
    modifier: Modifier = Modifier,
    tripStartDateIso: String? = null,
    showMapOptions: Boolean = false,
    onDismissMapOptions: () -> Unit = {},
    photoSelections: Map<String, List<com.traveler.core.media.PhotoStoryMoment>>? = null,
    fullscreen: Boolean = false,
    onToggleFullscreen: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val view = LocalView.current

    // Initialize offline vector map renderer with bundled 110m basemap immediately (< 15ms)
    val renderer = remember {
        val stream = try {
            context.assets.open("basemap_world.json")
        } catch (_: Exception) {
            null
        }
        val r = TravelMapRenderer(stream)
        // If 10m regional basemap was already cached, attach it immediately
        RegionalBasemapCache.preparedRegionalBasemap?.let { r.setPreparedRegionalBasemap(it) }
        r
    }

    // Load/prepare 10m regional basemap off the UI thread (P0-06, P0-07)
    var regionalBasemapVersion by remember { mutableStateOf(if (RegionalBasemapCache.isReady) 1 else 0) }


    val renderModel = remember(visits, segments, photos, focusedLocation, photoSelections) {
        TravelMapRenderModel(
            visits = visits,
            segments = segments,
            photos = photos,
            focusedLocation = focusedLocation,
            photoSelections = photoSelections
        )
    }

    val storyTimeline = remember(renderModel) {
        com.traveler.feature.map.story.TravelStoryTimeline.build(renderModel, com.traveler.core.media.StoryDurationProfile.STANDARD,
            titleCard=com.traveler.feature.map.story.StoryTitleCard("", ""),
            endCard=com.traveler.feature.map.story.StoryEndCard("", "", "", ""))
    }

    val timeTracker = remember(storyTimeline, initialPlaybackProgress) {
        PlaybackTimeTracker(storyTimeline.totalStoryDurationSeconds).apply {
            if (initialPlaybackProgress > 0f) {
                seekTo(initialPlaybackProgress)
            }
        }
    }

    // P1-21: Built-in Offline Travel Soundtrack Player
    val soundtrackPlayer = remember { TravelSoundtrackPlayer(context) }
    var isMusicEnabled by remember { mutableStateOf(true) }

    DisposableEffect(Unit) {
        onDispose {
            soundtrackPlayer.release()
        }
    }

    var isPlaying by remember(initialIsPlaying) { mutableStateOf(initialIsPlaying) }
    var playbackProgress by remember(initialPlaybackProgress) { mutableStateOf(initialPlaybackProgress) }
    var playbackSession by remember(initialIsPlaying, initialPlaybackProgress) { mutableStateOf(initialIsPlaying || initialPlaybackProgress > 0f) }
    var playbackSpeed by remember { mutableStateOf(1.0f) }
    val playbackLifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    DisposableEffect(playbackLifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                isPlaying = false
                soundtrackPlayer.pause()
            }
        }
        playbackLifecycle.addObserver(observer)
        onDispose { playbackLifecycle.removeObserver(observer) }
    }
    val continuityDiagnostic = remember { PlaybackContinuityDiagnostic() }
    LaunchedEffect(storyTimeline) {
        continuityDiagnostic.recordTimelineDiagnostics(storyTimeline.diagnostics)
    }

    // P0-14: Keep screen awake while travel playback is actively running
    DisposableEffect(isPlaying) {
        var current: android.content.Context? = context
        var activity: android.app.Activity? = null
        while (current is android.content.ContextWrapper) {
            if (current is android.app.Activity) {
                activity = current
                break
            }
            current = current.baseContext
        }
        if (activity == null) {
            var vCtx: android.content.Context? = view.context
            while (vCtx is android.content.ContextWrapper) {
                if (vCtx is android.app.Activity) {
                    activity = vCtx
                    break
                }
                vCtx = vCtx.baseContext
            }
        }

        val targetDecor = activity?.window?.decorView
        val prevDecorKeep = targetDecor?.keepScreenOn ?: false
        val prevViewKeep = view.keepScreenOn

        if (isPlaying) {
            targetDecor?.keepScreenOn = true
            view.keepScreenOn = true
        }
        onDispose {
            targetDecor?.keepScreenOn = prevDecorKeep
            view.keepScreenOn = prevViewKeep
        }
    }

    // Playback loop driven by monotonic clock and withFrameNanos (P1-05)
    LaunchedEffect(isPlaying, initialPlaybackProgress, timeTracker) {
        if (isPlaying) {
            if (isMusicEnabled) {
                soundtrackPlayer.start()
            }
            timeTracker.start(playbackProgress)
            while (isActive && isPlaying) {
                withFrameNanos {
                    val p = timeTracker.update()
                    playbackProgress = p
                    if (!timeTracker.isPlaying) {
                        isPlaying = false
                    }
                }
            }
        } else {
            soundtrackPlayer.pause()
            timeTracker.pause()
        }
    }

    val currentActiveState = if (playbackSession) {
        storyTimeline.evaluate(playbackProgress)
    } else {
        null
    }

    val isPlaybackActive = playbackSession

    Box(
        // SurfaceView must punch through the window directly. An offscreen Compose
        // clipping layer covers its separate GPU surface on some Android versions.
        modifier = modifier
    ) {
        com.traveler.feature.map.threed.Map3DLayer(renderModel, storyTimeline, currentActiveState, isPlaying,
            showOptions = showMapOptions, onDismissOptions = onDismissMapOptions) {
    LaunchedEffect(Unit) {
        if (!RegionalBasemapCache.isReady) {
            val prep = RegionalBasemapCache.ensureLoaded(context.applicationContext)
            if (prep != null) {
                renderer.setPreparedRegionalBasemap(prep)
                regionalBasemapVersion++ // Trigger instant redraw with 10m detail
            }
        }
    }
        // Main Vector Canvas Render View with safe insets (explicit state observation in draw scope)
        ComposeCanvas(modifier = Modifier.fillMaxSize()) {
            val width = size.width.toInt()
            val height = size.height.toInt()
            if (regionalBasemapVersion < 0) return@ComposeCanvas // Observes state to trigger redraw when prepared

            val progress = playbackProgress
            val active = playbackSession
            val canvasPlaybackState = if (active) storyTimeline.evaluate(progress) else null
            if (canvasPlaybackState != null && isPlaying) {
                continuityDiagnostic.recordFrame(canvasPlaybackState, isUserScrubbing = false)
            }

            if (width > 0 && height > 0) {
                val insets = SafeContentInsets(
                    left = 20f,
                    top = 28f,
                    right = 20f,
                    bottom = if (active) 110f else 28f
                )

                renderer.render(
                    canvas = drawContext.canvas.nativeCanvas,
                    width = width,
                    height = height,
                    renderModel = renderModel,
                    playbackState = canvasPlaybackState,
                    insets = insets
                )
            }
        }

        }
        if (currentActiveState != null) {
            PlaybackOverlays(currentActiveState, storyTimeline, tripStartDateIso)
        } else {
            Text(
                text = "Total journey: " + String.format(java.util.Locale.US, "%,.1f km", storyTimeline.totalTripDistanceMeters / 1000),
                color = Color.White, fontSize = 11.sp,
                modifier = Modifier.align(Alignment.TopStart).padding(8.dp)
                    .background(Color(0xDD0F172A), RoundedCornerShape(8.dp)).padding(8.dp)
            )
        }

        // Static Mode Clean Floating Replay Button
        if (!isPlaybackActive) {
            FloatingActionButton(
                onClick = {
                    playbackProgress = 0.0f
                    timeTracker.seekTo(0.0f)
                    playbackSession = true
                    isPlaying = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(12.dp)
                    .size(44.dp),
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = Color.White,
                shape = CircleShape
            ) {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "Start Playback",
                    modifier = Modifier.size(24.dp)
                )
            }
        }

        // Bottom Playback Controls Bar (Only shown during playback)
        AnimatedVisibility(
            visible = isPlaybackActive,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.70f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text(PlaybackClockLabel.label(playbackProgress, storyTimeline.totalStoryDurationSeconds),
                    color = Color.White, fontSize = 12.sp,
                    modifier = Modifier.testTag("playback-time"))
                    // Progress Scrubber Slider
                    Slider(
                        value = playbackProgress,
                        onValueChange = {
                            isPlaying = false
                            timeTracker.seekTo(it)
                            playbackProgress = it
                        },
                        modifier = Modifier.fillMaxWidth().height(32.dp).testTag("playback-seek"),
                        colors = SliderDefaults.colors(
                            thumbColor = MaterialTheme.colorScheme.primary,
                            activeTrackColor = MaterialTheme.colorScheme.primary,
                            inactiveTrackColor = Color.White.copy(alpha = 0.3f)
                        )
                    )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // Play / Pause Button
                    IconButton(
                        onClick = {
                            if (playbackProgress >= 1.0f) {
                                playbackProgress = 0f
                                timeTracker.seekTo(0f)
                            }
                            isPlaying = !isPlaying
                        },
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary)
                    ) {
                        Icon(
                            imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (isPlaying) "Pause" else "Play",
                            tint = Color.White,
                            modifier = Modifier.size(20.dp)
                        )
                    }

                    // Reset / Replay Button
                    IconButton(
                        onClick = {
                            playbackProgress = 0.0f
                            timeTracker.seekTo(0.0f)
                            isPlaying = true
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Refresh,
                            contentDescription = "Restart",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }

                    Spacer(Modifier.weight(1f))

                    // Playback Speed Selector (P1-06)
                    val speeds = listOf(0.5f, 1.0f, 1.5f, 2.0f, 3.0f)
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color.White.copy(alpha = 0.20f))
                            .clickable {
                                val nextIdx = (speeds.indexOf(playbackSpeed) + 1) % speeds.size
                                val nextSpeed = speeds[nextIdx]
                                playbackSpeed = nextSpeed
                                timeTracker.playbackSpeed = nextSpeed
                            }
                            .padding(horizontal = 7.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        val speedText = if (playbackSpeed == 1.0f) "1×" else if (playbackSpeed == 0.5f) "0.5×" else if (playbackSpeed == 1.5f) "1.5×" else "${playbackSpeed.toInt()}×"
                        Text(
                            text = speedText,
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // P1-21: Music Soundtrack Toggle Button
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isMusicEnabled) MaterialTheme.colorScheme.primary.copy(alpha = 0.60f) else Color.White.copy(alpha = 0.15f))
                            .clickable {
                                isMusicEnabled = !isMusicEnabled
                                soundtrackPlayer.isEnabled = isMusicEnabled
                                if (isMusicEnabled && isPlaying) {
                                    soundtrackPlayer.resume()
                                } else {
                                    soundtrackPlayer.pause()
                                }
                            }
                            .padding(horizontal = 6.dp, vertical = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isMusicEnabled) "🎵" else "🔇",
                            fontSize = 13.sp
                        )
                    }

                    if (onToggleFullscreen != null) {
                        IconButton(onClick = onToggleFullscreen, modifier = Modifier.size(48.dp)) {
                            Icon(if (fullscreen) Icons.Default.FullscreenExit else Icons.Default.Fullscreen,
                                contentDescription = if (fullscreen) "Exit fullscreen" else "Fullscreen", tint = Color.White)
                        }
                    }

                    // Close Playback Button
                    IconButton(
                        onClick = {
                            isPlaying = false
                            playbackSession = false
                            if (fullscreen) onToggleFullscreen?.invoke()
                            soundtrackPlayer.stop()
                            timeTracker.pause()
                            timeTracker.seekTo(0.0f)
                            playbackProgress = 0.0f
                        },
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Exit Playback",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
