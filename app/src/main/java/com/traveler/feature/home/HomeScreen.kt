package com.traveler.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FlightTakeoff
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.traveler.core.model.Trip
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onNavigateToNewTrip: () -> Unit,
    onNavigateToTripDetail: (String) -> Unit
) {
    val trips by viewModel.trips.collectAsState()
    val context=androidx.compose.ui.platform.LocalContext.current
    val scope=rememberCoroutineScope()
    val restore=androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri ->
        if(uri!=null) scope.launch {
            try {
                val id=com.traveler.core.terrain.TripArchive.restore(context,uri)
                android.widget.Toast.makeText(context,"Journey restored. Allow photo access to reconnect your gallery.",android.widget.Toast.LENGTH_LONG).show()
                onNavigateToTripDetail(id)
            } catch(e:kotlinx.coroutines.CancellationException) { throw e }
            catch(e:Exception) { android.widget.Toast.makeText(context,e.message ?: "Could not restore journey",android.widget.Toast.LENGTH_LONG).show() }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "My Travel Diary 3D",
                            fontWeight = FontWeight.Bold,
                            fontSize = 20.sp,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )

                    }
                },
                actions = { TextButton(onClick={restore.launch(arrayOf("application/json","application/octet-stream"))}) { Text("Restore") } },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onNavigateToNewTrip,
                icon = { Icon(Icons.Default.Add, contentDescription = "New Trip") },
                text = { Text("New Travel Story") }
            )
        }
    ) { paddingValues ->
        var tripPendingDelete by remember { mutableStateOf<Trip?>(null) }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (trips.isEmpty()) {
                EmptyStateView(onNavigateToNewTrip)
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(trips, key = { it.id }) { trip ->
                        TripCard(
                            trip = trip,
                            onClick = { onNavigateToTripDetail(trip.id) },
                            onDelete = { tripPendingDelete = trip }
                        )
                    }
                }
            }

            // P0-01: Delete confirmation dialog for personal travel data privacy
            val tripToDelete = tripPendingDelete
            if (tripToDelete != null) {
                AlertDialog(
                    onDismissRequest = { tripPendingDelete = null },
                    title = { Text("Delete \"${tripToDelete.title}\"?") },
                    text = {
                        Text("This removes the saved travel story and its locally stored timeline/media references from My Travel Diary 3D. Original gallery photos are not deleted.")
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                viewModel.deleteTrip(tripToDelete.id)
                                tripPendingDelete = null
                            }
                        ) {
                            Text("Delete", color = MaterialTheme.colorScheme.error)
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { tripPendingDelete = null }) {
                            Text("Cancel")
                        }
                    }
                )
            }
        }
    }
}

@Composable
private fun TripCard(
    trip: Trip,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = trip.title,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Delete",
                        tint = MaterialTheme.colorScheme.error.copy(alpha = 0.8f)
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "📅 ${trip.startDateIso} ~ ${trip.endDateIso}",
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f)
            )

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                val distanceKm = String.format(java.util.Locale.US, "%.1f", trip.totalDistanceMeters / 1000.0)
                BadgeInfo(icon = "📍", label = "$distanceKm km")
                BadgeInfo(icon = "📷", label = "${trip.totalMediaCount} photos")
                if (trip.cities.isNotEmpty()) {
                    BadgeInfo(icon = "🏙️", label = "${trip.cities.size} places")
                }
            }

            if (trip.cities.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = "Visited: ${trip.cities.take(3).joinToString(" → ")}${if (trip.cities.size > 3) "..." else ""}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

@Composable
private fun BadgeInfo(icon: String, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surface)
            .padding(horizontal = 8.dp, vertical = 4.dp)
    ) {
        Text(text = icon, fontSize = 12.sp)
        Spacer(modifier = Modifier.width(4.dp))
        Text(
            text = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

@Composable
private fun EmptyStateView(onNewTrip: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Map,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = "No Travel Stories Yet",
            fontWeight = FontWeight.Bold,
            fontSize = 20.sp
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Combine your Google Maps Timeline history with gallery photos to generate interactive travel diaries with routes, transport detection, and memories.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onNewTrip) {
            Icon(Icons.Default.Add, contentDescription = null)
            Spacer(modifier = Modifier.width(8.dp))
            Text("Create Your First Story")
        }
    }
}
