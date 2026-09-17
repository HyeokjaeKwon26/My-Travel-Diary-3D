package com.traveler.feature.map

import androidx.compose.animation.*
import androidx.compose.foundation.Canvas as ComposeCanvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.onClick
import kotlinx.coroutines.delay
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.ui.platform.testTag
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
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
    var controlsVisible by remember { mutableStateOf(true) }
    var controlsInteraction by remember { mutableStateOf(0) }
    var controlsPressed by remember { mutableStateOf(false) }
    var isScrubbing by remember { mutableStateOf(false) }
    var resumeAfterScrub by remember { mutableStateOf(false) }
    var controlsHeight by remember { mutableStateOf(96.dp) }
    val density = LocalDensity.current
    val accessibility = LocalAccessibilityManager.current
    fun revealControls() { controlsVisible = true; controlsInteraction++ }
    fun toggleControls() {
        if (controlsVisible && isPlaying && !isScrubbing) controlsVisible = false
        else revealControls()
    }
    LaunchedEffect(isPlaying, playbackSession, fullscreen, showMapOptions) { revealControls() }
    LaunchedEffect(controlsVisible, controlsInteraction, controlsPressed, isScrubbing, isPlaying, showMapOptions) {
        if (controlsVisible && isPlaying && !controlsPressed && !isScrubbing && !showMapOptions) {
            val timeout = accessibility?.calculateRecommendedTimeoutMillis(
                3_000L, containsIcons = true, containsText = true, containsControls = true) ?: 3_000L
            delay(timeout)
            controlsVisible = false
        }
    }
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
        modifier = modifier.testTag("playback-surface")
            .pointerInput(playbackSession, isPlaying, controlsVisible) {
                if (playbackSession) detectTapGestures(onTap = { toggleControls() })
            }
            .semantics {
                if (playbackSession) onClick(label = "Show playback controls") { revealControls(); true }
            }
    ) {
        com.traveler.feature.map.threed.Map3DLayer(renderModel, storyTimeline, currentActiveState, isPlaying,
            showOptions = showMapOptions, onDismissOptions = onDismissMapOptions,
            controlsBottomInset = if (playbackSession && controlsVisible) controlsHeight + 4.dp else 8.dp) {
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

        // Live UI only: hiding controls must never change timeline, camera or export frames.
        AnimatedVisibility(
            visible = isPlaybackActive && controlsVisible,
            enter = fadeIn(), exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter)
        ) {
            PlaybackControls(
                progress = playbackProgress,
                timeLabel = PlaybackClockLabel.label(playbackProgress, storyTimeline.totalStoryDurationSeconds),
                isPlaying = isPlaying, speed = playbackSpeed, musicEnabled = isMusicEnabled,
                fullscreen = fullscreen, canToggleFullscreen = onToggleFullscreen != null,
                modifier = Modifier.onSizeChanged { controlsHeight = with(density) { it.height.toDp() } },
                onPressChanged = { controlsPressed = it; if (it) revealControls() },
                onSeek = {
                    revealControls()
                    if (!isScrubbing) { resumeAfterScrub = isPlaying; isScrubbing = true }
                    isPlaying = false
                    timeTracker.seekTo(it)
                    playbackProgress = it
                },
                onSeekFinished = {
                    if (isScrubbing) {
                        isScrubbing = false
                        isPlaying = resumeAfterScrub && playbackProgress < 1f
                    }
                    revealControls()
                },
                onPlayPause = {
                    revealControls()
                    if (playbackProgress >= 1f) { playbackProgress = 0f; timeTracker.seekTo(0f) }
                    isPlaying = !isPlaying
                },
                onRestart = {
                    revealControls(); playbackProgress = 0f; timeTracker.seekTo(0f); isPlaying = true
                },
                onSpeed = {
                    revealControls()
                    val speeds = listOf(.5f, 1f, 1.5f, 2f, 3f)
                    playbackSpeed = speeds[(speeds.indexOf(playbackSpeed) + 1) % speeds.size]
                    timeTracker.playbackSpeed = playbackSpeed
                },
                onMusic = {
                    revealControls(); isMusicEnabled = !isMusicEnabled
                    soundtrackPlayer.isEnabled = isMusicEnabled
                    if (isMusicEnabled && isPlaying) soundtrackPlayer.resume() else soundtrackPlayer.pause()
                },
                onFullscreen = { revealControls(); onToggleFullscreen?.invoke() },
                onClose = {
                    isPlaying = false; playbackSession = false
                    if (fullscreen) onToggleFullscreen?.invoke()
                    soundtrackPlayer.stop(); timeTracker.pause(); timeTracker.seekTo(0f); playbackProgress = 0f
                }
            )
        }
    }
}
