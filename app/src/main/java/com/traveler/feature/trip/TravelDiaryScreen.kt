package com.traveler.feature.trip

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.traveler.core.common.geo.OfflineCityResolver
import com.traveler.core.common.time.TimeUtils
import com.traveler.core.media.RepresentativeMediaSelector
import com.traveler.core.model.*
import com.traveler.feature.map.TravelMapView
import com.traveler.feature.map.renderer.TravelMapRenderModel
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TravelDiaryScreen(
    tripId: String,
    viewModel: TravelDiaryViewModel = viewModel(),
    showPhotoMetadata: Boolean = false,
    onNavigateBack: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    val photoPreparation by viewModel.photoPreparation.collectAsState()
    val focusedLocation by viewModel.focusedLocation.collectAsState()
    val selectedPhoto by viewModel.selectedPhoto.collectAsState()
    val editingSegment by viewModel.editingSegment.collectAsState()
    val editingVisit by viewModel.editingVisit.collectAsState()

    var viewingGalleryPhotos by remember { mutableStateOf<List<MediaItem>?>(null) }
    var isExportVideoOpen by remember { mutableStateOf(false) }
    var showMapOptions by remember { mutableStateOf(false) }

    LaunchedEffect(tripId) {
        viewModel.loadTrip(tripId)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    val title = (uiState as? TripDetailUiState.Success)?.trip?.title ?: "Travel Diary"
                    Text(title, fontWeight = FontWeight.Bold, maxLines = 1)
                },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    if (uiState is TripDetailUiState.Success) {
                        IconButton(onClick = { showMapOptions = true }) {
                            Icon(Icons.Default.Settings, contentDescription = "Map settings")
                        }
                        IconButton(onClick = { isExportVideoOpen = true }) {
                            Icon(Icons.Default.PlayArrow, contentDescription = "Export Video", tint = MaterialTheme.colorScheme.primary)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (val state = uiState) {
                is TripDetailUiState.Loading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            CircularProgressIndicator()
                            Text(photoPreparation, Modifier.padding(16.dp))
                        }
                    }
                }
                is TripDetailUiState.Error -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.message, color = MaterialTheme.colorScheme.error)
                    }
                }
                is TripDetailUiState.Success -> {
                    val trip = state.trip
                    val allVisits = remember(trip) {
                        trip.days.flatMap { it.items }.mapNotNull {
                            when (it) {
                                is TripDayItem.VisitItem -> it.visit
                                is TripDayItem.ContextualPhotosItem -> it.parentVisit
                                else -> null
                            }
                        }.distinctBy { it.id }
                    }
                    val allSegments = remember(trip) {
                        trip.days.flatMap { it.items }.mapNotNull {
                            when (it) {
                                is TripDayItem.MovementItem -> it.segment
                                is TripDayItem.ContextualPhotosItem -> it.parentSegment
                                else -> null
                            }
                        }.distinctBy { it.id }
                    }

                    val allPhotos = remember(trip) {
                        trip.days.flatMap { it.items }.flatMap {
                            when (it) {
                                is TripDayItem.VisitItem -> it.photos
                                is TripDayItem.MovementItem -> it.photos
                                is TripDayItem.ContextualPhotosItem -> it.photos
                                is TripDayItem.UnassignedPhotosItem -> it.photos
                            }
                        }
                    }

                    AdaptiveDiaryLayout(map = {
                        // 1. Offline Vector Map & Cinematic Playback Header
                        TravelMapView(
                            visits = allVisits,
                            segments = allSegments,
                            photos = allPhotos,
                            focusedLocation = focusedLocation,
                            tripStartDateIso = trip.startDateIso,
                            showMapOptions = showMapOptions,
                            onDismissMapOptions = { showMapOptions = false },
                            modifier = Modifier.fillMaxSize()
                        )
                    }, diary = {
                        // 2. Chronological Diary Timeline List
                        LazyColumn(
                            modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            // Trip Summary Header Card
                            item {
                                TripSummaryHeader(trip)
                            }

                            // Day by Day Items
                            for (day in trip.days) {
                                item {
                                    DayHeader(day)
                                }

                                items(day.items) { item ->
                                    when (item) {
                                        is TripDayItem.VisitItem -> {
                                            VisitCard(
                                                visit = item.visit,
                                                photos = item.photos,
                                                onCardClick = { viewModel.focusLocation(item.visit.location) },
                                                onEditNameClick = { viewModel.openEditVisit(item.visit) },
                                                onPhotoClick = { viewModel.openPhotoDetail(it) },
                                                onViewAllClick = { viewingGalleryPhotos = it }
                                            )
                                        }
                                        is TripDayItem.MovementItem -> {
                                            MovementCard(
                                                segment = item.segment,
                                                photos = item.photos,
                                                onCardClick = { viewModel.focusLocation(item.segment.startPoint) },
                                                onEditTransportClick = { viewModel.openEditTransport(item.segment) },
                                                onPhotoClick = { viewModel.openPhotoDetail(it) },
                                                onViewAllClick = { viewingGalleryPhotos = it }
                                            )
                                        }
                                        is TripDayItem.ContextualPhotosItem -> {
                                            ContextualPhotosCard(
                                                contextLabel = item.contextLabel,
                                                photos = item.photos,
                                                onCardClick = {
                                                    val loc = item.parentVisit?.location ?: item.parentSegment?.startPoint
                                                    if (loc != null) viewModel.focusLocation(loc)
                                                },
                                                onPhotoClick = { viewModel.openPhotoDetail(it) },
                                                onViewAllClick = { viewingGalleryPhotos = it }
                                            )
                                        }
                                        is TripDayItem.UnassignedPhotosItem -> {
                                            UnassignedPhotosCard(
                                                photos = item.photos,
                                                onPhotoClick = { viewModel.openPhotoDetail(it) },
                                                onViewAllClick = { viewingGalleryPhotos = it }
                                            )
                                        }
                                    }
                                }
                            }

                            // P0-01: Other photos — date uncertain
                            if (trip.uncertainDateMedia.isNotEmpty()) {
                                item {
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                Text(
                                                    text = "🗓️ Other photos — date uncertain",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Spacer(modifier = Modifier.weight(1f))
                                                Text(
                                                    text = "${trip.uncertainDateMedia.size} photos",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(12.dp))
                                            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                                items(trip.uncertainDateMedia) { photo ->
                                                    Box(
                                                        modifier = Modifier
                                                            .size(100.dp)
                                                            .clip(RoundedCornerShape(8.dp))
                                                            .clickable { viewModel.openPhotoDetail(photo) }
                                                    ) {
                                                        AsyncImage(
                                                            model = photo.contentUriString,
                                                            contentDescription = photo.fileName,
                                                            contentScale = ContentScale.Crop,
                                                            modifier = Modifier.fillMaxSize()
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    })
                }
            }

            // Fullscreen Immersive Photo Detail Dialog
            selectedPhoto?.let { photo ->
                FullscreenPhotoDialog(
                    photo = photo,
                    initialShowTechDetails = showPhotoMetadata,
                    onToggleRepresentative = { viewModel.toggleRepresentativeMedia(photo) },
                    onDismiss = { viewModel.closePhotoDetail() }
                )
            }

            // Export Travel Video Dialog (P1)
            if (isExportVideoOpen && uiState is TripDetailUiState.Success) {
                val successTrip = (uiState as TripDetailUiState.Success).trip
                val visits = successTrip.days.flatMap { it.items }.mapNotNull {
                    when (it) {
                        is TripDayItem.VisitItem -> it.visit
                        is TripDayItem.ContextualPhotosItem -> it.parentVisit
                        else -> null
                    }
                }.distinctBy { it.id }
                val segments = successTrip.days.flatMap { it.items }.mapNotNull {
                    when (it) {
                        is TripDayItem.MovementItem -> it.segment
                        is TripDayItem.ContextualPhotosItem -> it.parentSegment
                        else -> null
                    }
                }.distinctBy { it.id }
                val photos = successTrip.days.flatMap { it.items }.flatMap {
                    when (it) {
                        is TripDayItem.VisitItem -> it.photos
                        is TripDayItem.MovementItem -> it.photos
                        is TripDayItem.ContextualPhotosItem -> it.photos
                        is TripDayItem.UnassignedPhotosItem -> it.photos
                    }
                }
                val renderModel = TravelMapRenderModel(
                    visits = visits,
                    segments = segments,
                    photos = photos
                )
                com.traveler.feature.video.ui.ExportVideoDialog(
                    trip = successTrip,
                    renderModel = renderModel,
                    onDismiss = { isExportVideoOpen = false }
                )
            }

            // Edit Transport Mode Dialog (User Override)
            editingSegment?.let { segment ->
                EditTransportDialog(
                    currentMode = segment.effectiveMode,
                    onSelectMode = { newMode ->
                        viewModel.overrideTransportMode(segment.id, newMode)
                    },
                    onDismiss = { viewModel.closeEditTransport() }
                )
            }

            // All Photos Gallery Dialog / Viewer (P0-02)
            viewingGalleryPhotos?.let { photos ->
                AllPhotosGalleryDialog(
                    photos = photos,
                    onPhotoClick = {
                        viewingGalleryPhotos = null
                        viewModel.openPhotoDetail(it)
                    },
                    onDismiss = { viewingGalleryPhotos = null }
                )
            }

            // Edit Visit Name Dialog
            editingVisit?.let { visit ->
                EditVisitDialog(
                    currentName = visit.placeName ?: "",
                    onSave = { newName ->
                        viewModel.overrideVisitName(visit.id, newName)
                    },
                    onDismiss = { viewModel.closeEditVisit() }
                )
            }
        }
    }
}

@Composable
private fun TripSummaryHeader(trip: Trip) {
    val distKm = String.format(java.util.Locale.US, "%.1f", trip.totalDistanceMeters / 1000.0)
    val metrics = listOf(Triple("🛣️", "$distKm km", "Total Distance"),
        Triple("📷", "${trip.totalMediaCount}", "Captured Media"),
        Triple("🗓️", "${trip.days.size} Days", "Duration"))
    val fontScale = androidx.compose.ui.platform.LocalDensity.current.fontScale
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(16.dp)) {
            if (maxWidth.value / fontScale < 290) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    metrics.forEach { (icon, value, label) ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Text(icon, fontSize = 20.sp)
                            Column {
                                Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp)
                                Text(label, fontSize = 11.sp, color = MaterialTheme.colorScheme.onPrimaryContainer)
                            }
                        }
                    }
                }
            } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                metrics.forEach { (icon, value, label) ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(icon, fontSize = 20.sp)
                        Text(value, fontWeight = FontWeight.Bold, fontSize = 16.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        Text(label, fontSize = 11.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f))
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeader(day: TripDay) {
    val distKm = String.format(java.util.Locale.US, "%.1f", day.totalDistanceMeters / 1000.0)
    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("Day ${day.dayIndex} · ${day.dateIso}", fontWeight = FontWeight.ExtraBold,
            fontSize = 18.sp, color = MaterialTheme.colorScheme.primary)
        Text("$distKm km · ${day.photoCount} photos", fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun VisitCard(
    visit: Visit,
    photos: List<MediaItem>,
    onCardClick: () -> Unit,
    onEditNameClick: () -> Unit,
    onPhotoClick: (MediaItem) -> Unit,
    onViewAllClick: ((List<MediaItem>) -> Unit)? = null
) {
    val effectiveLabel = OfflineCityResolver.getEffectivePlaceName(visit.placeName, visit.isUserOverride, visit.location, visit.placeAddress)
    val displayName = effectiveLabel.displayName

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(32.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f))
                        .clickable(onClick = onCardClick),
                    contentAlignment = Alignment.Center
                ) {
                    Text("📍", fontSize = 16.sp)
                }

                Spacer(modifier = Modifier.width(10.dp))

                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .clickable(onClick = onEditNameClick)
                        .padding(vertical = 2.dp, horizontal = 4.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = displayName,
                            modifier = Modifier.weight(1f, fill = false),
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Icon(
                            Icons.Default.Edit,
                            contentDescription = "Edit place name",
                            modifier = Modifier.size(16.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    visit.placeAddress?.let {
                        Text(text = it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

            }
                val visitZone = visit.timezoneId?.let { try { ZoneId.of(it) } catch (_: Exception) { null } }
                val timeSpanText = if (visitZone != null) {
                    val startTime = TimeUtils.formatTime(Instant.ofEpochMilli(visit.startTimestampEpochMs), visitZone)
                    val endTime = TimeUtils.formatTime(Instant.ofEpochMilli(visit.endTimestampEpochMs), visitZone)
                    "$startTime ~ $endTime"
                } else {
                    val durationMs = maxOf(0L, visit.endTimestampEpochMs - visit.startTimestampEpochMs)
                    val durationText = TimeUtils.formatDuration(durationMs)
                    "Local timezone unavailable ($durationText)"
                }
                Text(
                    text = timeSpanText,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

            if (photos.isNotEmpty()) {
                Spacer(modifier = Modifier.height(12.dp))
                PhotoVisualHierarchy(photos = photos, onPhotoClick = onPhotoClick, onViewAllClick = onViewAllClick)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MovementCard(
    segment: MovementSegment,
    photos: List<MediaItem>,
    onCardClick: () -> Unit,
    onEditTransportClick: () -> Unit,
    onPhotoClick: (MediaItem) -> Unit,
    onViewAllClick: ((List<MediaItem>) -> Unit)? = null
) {
    val distanceKm = String.format(java.util.Locale.US, "%.1f", segment.distanceMeters / 1000.0)
    val durationText = TimeUtils.formatDuration(segment.durationMillis)
    val speedText = String.format(java.util.Locale.US, "%.1f km/h", segment.averageSpeedKmh)
    val displayMode = segment.effectiveMode

    val startZone = segment.startTimezoneId?.let {
        try { ZoneId.of(it) } catch (_: Exception) { null }
    }
    val endZone = segment.endTimezoneId?.let {
        try { ZoneId.of(it) } catch (_: Exception) { null }
    }
    val startInstant = Instant.ofEpochMilli(segment.startTimestampEpochMs)
    val endInstant = Instant.ofEpochMilli(segment.endTimestampEpochMs)

    val timeSpanText = when {
        startZone != null && endZone != null -> {
            val formattedSpan = TimeUtils.formatMovementTimeSpan(startInstant, startZone, endInstant, endZone)
            "$formattedSpan (avg $speedText)"
        }
        startZone != null && endZone == null -> {
            val startStr = TimeUtils.formatTime(startInstant, startZone)
            "$startStr departure (arrival timezone unavailable, avg $speedText)"
        }
        startZone == null && endZone != null -> {
            val endStr = TimeUtils.formatTime(endInstant, endZone)
            "Departure timezone unavailable → $endStr (avg $speedText)"
        }
        else -> {
            "Duration: $durationText (timezones unavailable, avg $speedText)"
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Transport Mode Badge (Clickable for override)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.secondaryContainer)
                        .clickable(onClick = onEditTransportClick)
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(displayMode.emoji, fontSize = 16.sp)
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = displayMode.displayName + if (segment.isUserOverride) " (User)" else "",
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Icon(
                        Icons.Default.Edit,
                        contentDescription = "Edit transport",
                        modifier = Modifier.size(12.dp),
                        tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.6f)
                    )
                }

                Text(
                    text = "$distanceKm km · $durationText",
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 13.sp,
                    modifier = Modifier.clickable(onClick = onCardClick)
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = timeSpanText,
                fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.clickable(onClick = onCardClick)
            )

            if (photos.isNotEmpty()) {
                Spacer(modifier = Modifier.height(10.dp))
                PhotoVisualHierarchy(photos = photos, onPhotoClick = onPhotoClick, onViewAllClick = onViewAllClick)
            }
        }
    }
}

@Composable
private fun ContextualPhotosCard(
    contextLabel: String,
    photos: List<MediaItem>,
    onCardClick: () -> Unit,
    onPhotoClick: (MediaItem) -> Unit,
    onViewAllClick: ((List<MediaItem>) -> Unit)? = null
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onCardClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📌", fontSize = 16.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = contextLabel,
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "(${photos.size})",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            PhotoVisualHierarchy(photos = photos, onPhotoClick = onPhotoClick, onViewAllClick = onViewAllClick)
        }
    }
}

@Composable
private fun UnassignedPhotosCard(
    photos: List<MediaItem>,
    onPhotoClick: (MediaItem) -> Unit,
    onViewAllClick: ((List<MediaItem>) -> Unit)? = null
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("📷", fontSize = 16.sp)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = "Other photos from this day",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "(${photos.size})",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            PhotoVisualHierarchy(photos = photos, onPhotoClick = onPhotoClick, onViewAllClick = onViewAllClick)
        }
    }
}

@Composable
private fun PhotoVisualHierarchy(
    photos: List<MediaItem>,
    onPhotoClick: (MediaItem) -> Unit,
    onViewAllClick: ((List<MediaItem>) -> Unit)? = null
) {
    val selection = remember(photos) {
        RepresentativeMediaSelector.select(photos, maxThumbnails = 3)
    }

    if (selection.hero == null) return

    if (selection.thumbnails.isEmpty() && selection.extraCount == 0) {
        val photo = selection.hero
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(150.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(Color(0xFFE2E8F0))
                .clickable { onPhotoClick(photo) }
        ) {
            AsyncImage(
                model = photo.contentUriString,
                contentDescription = photo.fileName,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            ConfidencePill(photo, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
        }
    } else {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 1. Hero Image
            val heroPhoto = selection.hero
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(150.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Color(0xFFE2E8F0))
                    .clickable { onPhotoClick(heroPhoto) }
            ) {
                AsyncImage(
                    model = heroPhoto.contentUriString,
                    contentDescription = heroPhoto.fileName,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                ConfidencePill(heroPhoto, modifier = Modifier.align(Alignment.BottomEnd).padding(6.dp))
            }

            // 2. Representative Thumbnails + Extra Count Pill
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(selection.thumbnails) { photo ->
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(0xFFCBD5E1))
                            .clickable { onPhotoClick(photo) }
                    ) {
                        AsyncImage(
                            model = photo.contentUriString,
                            contentDescription = photo.fileName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                        ConfidencePill(photo, modifier = Modifier.align(Alignment.BottomEnd).padding(2.dp))
                    }
                }

                if (selection.extraCount > 0) {
                    item {
                        Box(
                            modifier = Modifier
                                .size(72.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.primaryContainer)
                                .clickable {
                                    if (onViewAllClick != null) {
                                        onViewAllClick(photos)
                                    } else {
                                        onPhotoClick(selection.thumbnails.firstOrNull() ?: selection.hero)
                                    }
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = "+${selection.extraCount}",
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                                Text(
                                    text = "more",
                                    fontSize = 11.sp,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ConfidencePill(photo: MediaItem, modifier: Modifier = Modifier) {
    val badgeEmoji = when (photo.locationConfidence) {
        LocationConfidenceLevel.GPS_EXACT -> "🎯 EXIF GPS"
        LocationConfidenceLevel.VISIT_INFERRED -> "📍 Matched"
        LocationConfidenceLevel.TIMELINE_INTERPOLATED -> "〰️ Route"
        else -> "⏱️ Time"
    }
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.65f))
            .padding(horizontal = 4.dp, vertical = 2.dp)
    ) {
        Text(badgeEmoji, fontSize = 9.sp, color = Color.White, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun FullscreenPhotoDialog(
    photo: MediaItem,
    initialShowTechDetails: Boolean = false,
    onToggleRepresentative: () -> Unit = {},
    onDismiss: () -> Unit
) {
    val photoZone = photo.captureTimezoneId?.let {
        try { ZoneId.of(it) } catch (_: Exception) { null }
    }
    var showTechDetails by remember(initialShowTechDetails) { mutableStateOf(initialShowTechDetails) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color(0xFF090D16))
        ) {
            // Main Image
            AsyncImage(
                model = photo.contentUriString,
                contentDescription = photo.fileName,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(onClick = onDismiss)
            )

            // Top App Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color.Black.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }

                Text(
                    text = photo.fileName,
                    color = Color.White,
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 14.sp,
                    maxLines = 1,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )

                IconButton(onClick = { showTechDetails = !showTechDetails }) {
                    Icon(Icons.Default.Info, contentDescription = "Toggle info", tint = if (showTechDetails) Color(0xFF60A5FA) else Color.White)
                }
            }

            // Bottom Info Overlay / Expandable Panel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(Color.Black.copy(alpha = 0.75f))
                    .padding(16.dp)
            ) {
                val captureTimeStr = when {
                    photo.timestampEpochMs != null && photoZone != null -> {
                        val inst = Instant.ofEpochMilli(photo.timestampEpochMs)
                        "${TimeUtils.formatDate(inst, photoZone)} · ${TimeUtils.formatTime(inst, photoZone)}"
                    }
                    photo.timestampEpochMs != null && photo.assignedDayIso != null -> {
                        "${photo.assignedDayIso} · Capture timezone unknown"
                    }
                    photo.timestampEpochMs != null -> {
                        "Capture date/time uncertain"
                    }
                    else -> {
                        "Capture date/time uncertain"
                    }
                }

                Text(
                    text = captureTimeStr,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )

                AnimatedVisibility(visible = showTechDetails) {
                    Column(
                        modifier = Modifier.padding(top = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "Time Source: ${photo.timestampConfidence.name}",
                            color = Color(0xFF93C5FD),
                            fontSize = 12.sp
                        )
                        if (photo.dayAssignmentConfidence != DayAssignmentConfidence.UNKNOWN) {
                            Text(
                                text = "Day Assignment: ${photo.dayAssignmentConfidence.name}${photo.dayAssignmentProvenance?.let { " ($it)" } ?: ""}",
                                color = Color(0xFFFDE047),
                                fontSize = 12.sp
                            )
                        }
                        photo.location?.let { loc ->
                            Text(
                                text = "Coordinates: ${String.format(java.util.Locale.US, "%.5f", loc.latitude)}, ${String.format(java.util.Locale.US, "%.5f", loc.longitude)}",
                                color = Color.White.copy(alpha = 0.85f),
                                fontSize = 12.sp
                            )
                        }
                        Text(
                            text = "Location Match: ${photo.locationConfidence.name} (${(photo.confidenceScore * 100).toInt()}% confidence)",
                            color = Color(0xFF86EFAC),
                            fontSize = 12.sp
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Button(
                    onClick = onToggleRepresentative,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (photo.isRepresentative) Color(0xFFF59E0B) else Color(0xFF334155)
                    ),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = if (photo.isRepresentative) "★ Representative Memory" else "☆ Use as Representative Photo",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun EditTransportDialog(
    currentMode: TransportMode,
    onSelectMode: (TransportMode) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Change Transport Mode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (mode in TransportMode.entries) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(8.dp))
                            .clickable { onSelectMode(mode) }
                            .padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(mode.emoji, fontSize = 18.sp)
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = mode.displayName,
                            fontWeight = if (mode == currentMode) FontWeight.Bold else FontWeight.Normal,
                            color = if (mode == currentMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun EditVisitDialog(
    currentName: String,
    onSave: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(currentName) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Edit Place Name") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Place Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            Button(
                onClick = {
                    if (text.isNotBlank()) {
                        onSave(text.trim())
                    }
                }
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun AllPhotosGalleryDialog(
    photos: List<MediaItem>,
    onPhotoClick: (MediaItem) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.92f))
                .padding(16.dp)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "All Photos (${photos.size})",
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                    }
                }
                Spacer(modifier = Modifier.height(12.dp))
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(photos) { photo ->
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(220.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Color(0xFF1E293B))
                                .clickable { onPhotoClick(photo) }
                        ) {
                            AsyncImage(
                                model = photo.contentUriString,
                                contentDescription = photo.fileName,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                            ConfidencePill(photo, modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp))
                        }
                    }
                }
            }
        }
    }
}
