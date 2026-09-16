package com.traveler.feature.video.ui

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.traveler.core.media.StoryDurationProfile
import com.traveler.core.model.Trip
import com.traveler.feature.map.renderer.TravelMapRenderModel
import com.traveler.feature.video.TravelVideoExporter
import kotlinx.coroutines.launch
import java.io.File

sealed interface VideoExportState {
    object Idle : VideoExportState
    data class Encoding(val progress: Float) : VideoExportState
    data class Ready(val videoFile: File, val durationSeconds: Float, val isSavedToGallery: Boolean = false) : VideoExportState
    data class Error(val message: String) : VideoExportState
}

@Composable
fun ExportVideoDialog(
    trip: Trip,
    renderModel: TravelMapRenderModel,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()

    var selectedProfile by remember { mutableStateOf(StoryDurationProfile.STANDARD) }
    var includeMusic by remember { mutableStateOf(true) }
    var generalizeHomeAddress by remember { mutableStateOf(true) }
    var exportState by remember { mutableStateOf<VideoExportState>(VideoExportState.Idle) }
    var currentEncoder by remember { mutableStateOf<com.traveler.feature.video.TravelVideoEncoder?>(null) }
    var exportJob by remember { mutableStateOf<kotlinx.coroutines.Job?>(null) }
    fun saveToGallery(state: VideoExportState.Ready) {
        coroutineScope.launch {
            val uri = TravelVideoExporter.saveVideoToMediaStore(context, state.videoFile, trip.title, trip.startDateIso, trip.endDateIso)
            if (uri != null) {
                exportState = state.copy(isSavedToGallery = true)
                Toast.makeText(context, "Saved to Movies/My Travel Diary 3D", Toast.LENGTH_LONG).show()
            } else Toast.makeText(context, "Failed to save video", Toast.LENGTH_SHORT).show()
        }
    }
    val legacyWritePermission = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) (exportState as? VideoExportState.Ready)?.let { saveToGallery(it) }
        else Toast.makeText(context, "Storage permission is needed to save to the gallery on this Android version", Toast.LENGTH_LONG).show()
    }

    DisposableEffect(Unit) {
        onDispose { currentEncoder?.cancel(); exportJob?.cancel() }
    }

    // Dynamically calculate estimated durations for the 3 profiles (P1)
    val shortTimeline = remember(trip, renderModel) {
        TravelVideoExporter.buildExportTimeline(trip, renderModel, StoryDurationProfile.SHORT)
    }
    val standardTimeline = remember(trip, renderModel) {
        TravelVideoExporter.buildExportTimeline(trip, renderModel, StoryDurationProfile.STANDARD)
    }
    val fullTimeline = remember(trip, renderModel) {
        TravelVideoExporter.buildExportTimeline(trip, renderModel, StoryDurationProfile.FULL_STORY)
    }

    fun formatDuration(seconds: Float): String {
        val totalSec = seconds.toInt()
        val min = totalSec / 60
        val sec = totalSec % 60
        return "%d:%02d".format(min, sec)
    }

    Dialog(
        onDismissRequest = {
            if (exportState !is VideoExportState.Encoding) onDismiss()
        },
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Card(
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Header
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Export Travel Video",
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (exportState !is VideoExportState.Encoding) {
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "Close")
                        }
                    }
                }

                when (val state = exportState) {
                    is VideoExportState.Idle -> {
                        Text(
                            text = "Select a video length profile to create a cinematic 9:16 travel video with map routes, photos, and music.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        // Length Profiles
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            ProfileOptionCard(
                                title = "Short Story",
                                durationStr = formatDuration(shortTimeline.totalStoryDurationSeconds),
                                description = "Fast-paced overview highlighting key places.",
                                isSelected = selectedProfile == StoryDurationProfile.SHORT,
                                onClick = { selectedProfile = StoryDurationProfile.SHORT }
                            )

                            ProfileOptionCard(
                                title = "Standard Story",
                                durationStr = formatDuration(standardTimeline.totalStoryDurationSeconds),
                                description = "Balanced pacing with representative photo moments.",
                                isSelected = selectedProfile == StoryDurationProfile.STANDARD,
                                isRecommended = true,
                                onClick = { selectedProfile = StoryDurationProfile.STANDARD }
                            )

                            ProfileOptionCard(
                                title = "Full Story",
                                durationStr = formatDuration(fullTimeline.totalStoryDurationSeconds),
                                description = "Detailed playback visiting all segments and memories.",
                                isSelected = selectedProfile == StoryDurationProfile.FULL_STORY,
                                onClick = { selectedProfile = StoryDurationProfile.FULL_STORY }
                            )
                        }

                        // Export Options Toggles
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Include Music Soundtrack", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Bundled offline travel melody", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = includeMusic,
                                    onCheckedChange = { includeMusic = it }
                                )
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Generalize Home Location", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                                    Text("Hides exact address numbers for privacy", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Switch(
                                    checked = generalizeHomeAddress,
                                    onCheckedChange = { generalizeHomeAddress = it }
                                )
                            }
                        }

                        Button(
                            onClick = {
                                exportState = VideoExportState.Encoding(0f)
                                val targetTimeline = when (selectedProfile) {
                                    StoryDurationProfile.SHORT -> shortTimeline
                                    StoryDurationProfile.STANDARD -> standardTimeline
                                    StoryDurationProfile.FULL_STORY -> fullTimeline
                                }
                                exportJob = coroutineScope.launch {
                                    try {
                                    val file = TravelVideoExporter.exportVideo(
                                        context = context,
                                        trip = trip,
                                        renderModel = renderModel,
                                        profile = selectedProfile,
                                        includeMusic = includeMusic,
                                        generalizeHomeAddress = generalizeHomeAddress,
                                        encoderRef = { enc -> currentEncoder = enc },
                                        onProgress = { p ->
                                            exportState = VideoExportState.Encoding(p)
                                        }
                                    )
                                    if (file != null) {
                                        exportState = VideoExportState.Ready(file, targetTimeline.totalStoryDurationSeconds)
                                    } else {
                                        exportState = VideoExportState.Error("Failed to encode video.")
                                    }
                                    } catch (e: kotlinx.coroutines.CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        exportState = VideoExportState.Error(e.message ?: "Video export failed")
                                    } finally {
                                        currentEncoder = null
                                    }

                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Create Travel Video", fontWeight = FontWeight.Bold)
                        }
                    }

                    is VideoExportState.Encoding -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 20.dp)
                        ) {
                            Text(
                                text = "Creating your travel video…",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                            LinearProgressIndicator(
                                progress = { state.progress },
                                modifier = Modifier.fillMaxWidth().height(8.dp)
                            )
                            Text(
                                text = "${(state.progress * 100).toInt()}%",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Spacer(modifier = Modifier.height(8.dp))
                            OutlinedButton(
                                onClick = {
                                    currentEncoder?.cancel()
                                    exportJob?.cancel()
                                    exportState = VideoExportState.Idle
                                },
                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)
                            ) {
                                Text("Cancel")
                            }
                        }
                    }

                    is VideoExportState.Ready -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        ) {
                            Text(
                                text = "Video Ready ✓",
                                fontWeight = FontWeight.Bold,
                                fontSize = 18.sp,
                                color = Color(0xFF10B981)
                            )

                            val sizeMbStr = String.format(java.util.Locale.US, "%.1f MB", state.videoFile.length().toFloat() / (1024f * 1024f))
                            val durationStr = formatDuration(state.durationSeconds)

                            Card(
                                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceAround
                                ) {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("Duration", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(durationStr, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    }
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text("File Size", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        Text(sizeMbStr, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }

                            OutlinedButton(
                                onClick = {
                                    val contentUri = androidx.core.content.FileProvider.getUriForFile(
                                        context,
                                        "${context.packageName}.fileprovider",
                                        state.videoFile
                                    )
                                    val viewIntent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
                                        setDataAndType(contentUri, "video/mp4")
                                        addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                    }
                                    try {
                                        context.startActivity(viewIntent)
                                    } catch (_: Exception) {
                                        Toast.makeText(context, "No video player application found", Toast.LENGTH_SHORT).show()
                                    }
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("Play Preview")
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        if (android.os.Build.VERSION.SDK_INT <= 28 &&
                                            androidx.core.content.ContextCompat.checkSelfPermission(context, android.Manifest.permission.WRITE_EXTERNAL_STORAGE) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
                                            legacyWritePermission.launch(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                        } else saveToGallery(state)

                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Check, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(if (state.isSavedToGallery) "Saved ✓" else "Save Video")
                                }

                                Button(
                                    onClick = {
                                        val intent = TravelVideoExporter.createShareIntent(context, state.videoFile)
                                        context.startActivity(intent)
                                    },
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text("Share")
                                }
                            }
                        }
                    }

                    is VideoExportState.Error -> {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)
                        ) {
                            Text("Export Failed", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
                            Text(state.message, fontSize = 13.sp)
                            Button(onClick = { exportState = VideoExportState.Idle }) {
                                Text("Try Again")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileOptionCard(
    title: String,
    durationStr: String,
    description: String,
    isSelected: Boolean,
    isRecommended: Boolean = false,
    onClick: () -> Unit
) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(title, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                    if (isRecommended) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            "Recommended",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
                Text(description, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(durationStr, fontWeight = FontWeight.Bold, fontSize = 14.sp, color = MaterialTheme.colorScheme.primary)
        }
    }
}
