package com.traveler.feature.importtrip

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.traveler.core.media.MediaAccessCapabilities
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportTripScreen(
    viewModel: ImportTripViewModel,
    onNavigateBack: () -> Unit,
    onNavigateToTripDetail: (String) -> Unit
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()

    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var selectedFileName by remember { mutableStateOf<String?>(null) }
    var tripTitle by remember { mutableStateOf("") }
    var startDateText by rememberSaveable { mutableStateOf(LocalDate.now().minusDays(7).toString()) }
    var endDateText by rememberSaveable { mutableStateOf(LocalDate.now().toString()) }
    var showDatePicker by rememberSaveable { mutableStateOf(false) }
    var dateError by remember { mutableStateOf<String?>(null) }

    var mediaCapabilities by remember { mutableStateOf(MediaAccessCapabilities.checkCapabilities(context)) }

    // SAF File Picker
    val filePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedFileUri = uri
            selectedFileName = uri.lastPathSegment ?: "Timeline.json"
        }
    }

    // Permissions launcher (P1-15)
    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        mediaCapabilities = MediaAccessCapabilities.checkCapabilities(context)
    }

    fun requestMediaPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            permissions.add(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            permissions.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    LaunchedEffect(uiState) {
        if (uiState is ImportUiState.Success) {
            val tripId = (uiState as ImportUiState.Success).tripId
            viewModel.resetState()
            onNavigateToTripDetail(tripId)
        }
    }

    if (showDatePicker) {
        // Some edge-to-edge Dialog windows report zero Compose insets on Android 15+.
        // Union with the host window's unconsumed system insets so confirmation stays
        // clear of both gesture navigation and the three-button navigation bar.
        val configuration = androidx.compose.ui.platform.LocalConfiguration.current
        val hostView = androidx.compose.ui.platform.LocalView.current
        val hostInsets = remember(configuration, hostView) {
            androidx.core.view.ViewCompat.getRootWindowInsets(hostView)?.getInsets(
                androidx.core.view.WindowInsetsCompat.Type.systemBars() or
                    androidx.core.view.WindowInsetsCompat.Type.displayCutout())
        }
        val hostSafeArea = WindowInsets(hostInsets?.left ?: 0, hostInsets?.top ?: 0,
            hostInsets?.right ?: 0, hostInsets?.bottom ?: 0)
        // Material pickers represent calendar dates at UTC midnight, not local instants.
        val range = rememberDateRangePickerState(
            initialSelectedStartDateMillis = LocalDate.parse(startDateText).toEpochDay() * 86_400_000L,
            initialSelectedEndDateMillis = LocalDate.parse(endDateText).toEpochDay() * 86_400_000L
        )
        androidx.compose.ui.window.Dialog(onDismissRequest = { showDatePicker = false },
            properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.union(hostSafeArea)).imePadding().padding(8.dp),
                contentAlignment = Alignment.Center) {
                Surface(modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth().fillMaxHeight(), shape = RoundedCornerShape(20.dp)) {
                    Column {
                        DateRangePicker(state = range, showModeToggle = false, modifier = Modifier.weight(1f),
                            title = { Text("Select travel dates", Modifier.padding(16.dp)) })
                        Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(8.dp), horizontalArrangement = Arrangement.End) {
                            TextButton(onClick = { showDatePicker = false }) { Text("Cancel") }
                            TextButton(enabled = range.selectedStartDateMillis != null && range.selectedEndDateMillis != null,
                                onClick = {
                                    startDateText = LocalDate.ofEpochDay(range.selectedStartDateMillis!! / 86_400_000L).toString()
                                    endDateText = LocalDate.ofEpochDay(range.selectedEndDateMillis!! / 86_400_000L).toString()
                                    dateError = null
                                    showDatePicker = false
                                }) { Text("Use dates") }
                        }
                    }
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                title = { Text("New Travel Story", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onNavigateBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // Media Permission Banner (P1-06, P1-12 & P1-15)
                when {
                    mediaCapabilities.isQualifiedFullWithoutLocation -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Location Metadata Unavailable", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("Photos are accessible, but original location metadata is unavailable. GPS-based photo placement may be less accurate.", fontSize = 12.sp)
                                }
                                TextButton(onClick = { requestMediaPermissions() }) {
                                    Text("Enable")
                                }
                            }
                        }
                    }
                    mediaCapabilities.isPhotosOnly -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Photos Linked (Videos Denied)", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("My Travel Diary 3D can access photos, but not videos.", fontSize = 12.sp)
                                }
                                TextButton(onClick = { requestMediaPermissions() }) {
                                    Text("Add Videos")
                                }
                            }
                        }
                    }
                    mediaCapabilities.isPartial -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.Info, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Partial Photo Access", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("My Travel Diary 3D can currently see only selected photos.", fontSize = 12.sp)
                                }
                                TextButton(onClick = { requestMediaPermissions() }) {
                                    Text("Change")
                                }
                            }
                        }
                    }
                    mediaCapabilities.isDenied -> {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(Icons.Default.PhotoLibrary, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                                Spacer(modifier = Modifier.width(10.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text("Photos Not Linked", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                                    Text("Grant photo access to match photos to your travel routes.", fontSize = 12.sp)
                                }
                                Button(
                                    onClick = { requestMediaPermissions() },
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text("Enable", fontSize = 12.sp)
                                }
                            }
                        }
                    }
                    else -> Unit
                }

                // Section 1: Timeline File Picker
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.UploadFile, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "1. Google Maps Timeline JSON",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        Text(
                            text = "Select exported Timeline JSON from Google Maps on-device settings or Google Takeout.",
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        if (selectedFileUri != null) {
                            Text(
                                text = "Selected: ${selectedFileName ?: "JSON File"}",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.primary
                            )
                        }

                        Button(
                            onClick = { filePickerLauncher.launch(arrayOf("application/json", "*/*")) },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Icon(Icons.Default.FolderOpen, contentDescription = null)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(if (selectedFileUri == null) "Choose Timeline JSON File" else "Change File")
                        }
                    }
                }

                // Section 2: Date Range
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.DateRange, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "2. Travel Date Range",
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        OutlinedTextField(
                            value = tripTitle,
                            onValueChange = { tripTitle = it },
                            label = { Text("Trip Title (Optional)") },
                            placeholder = { Text("e.g. 2026 East Coast Road Trip") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )

                        OutlinedButton(onClick = { showDatePicker = true }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Default.DateRange, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Column {
                                Text("$startDateText → $endDateText")
                                Text("${java.time.temporal.ChronoUnit.DAYS.between(LocalDate.parse(startDateText), LocalDate.parse(endDateText)) + 1} days · Select dates")
                            }
                        }

                        if (dateError != null) {
                            Text(
                                text = dateError ?: "",
                                color = MaterialTheme.colorScheme.error,
                                fontSize = 12.sp
                            )
                        }
                    }
                }

                // Section 3: Generate Action
                Button(
                    onClick = {
                        val start = try {
                            LocalDate.parse(startDateText.trim())
                        } catch (_: Exception) {
                            dateError = "Invalid start date format. Use YYYY-MM-DD."
                            return@Button
                        }
                        val end = try {
                            LocalDate.parse(endDateText.trim())
                        } catch (_: Exception) {
                            dateError = "Invalid end date format. Use YYYY-MM-DD."
                            return@Button
                        }

                        if (start.isAfter(end)) {
                            dateError = "Start date cannot be after end date."
                            return@Button
                        }

                        dateError = null
                        val uri = selectedFileUri
                        if (uri != null) {
                            viewModel.createTripFromUri(uri, start, end, tripTitle)
                        }
                    },
                    enabled = selectedFileUri != null && uiState !is ImportUiState.Loading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                ) {
                    Icon(Icons.Default.AutoAwesome, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Reconstruct Travel Story", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                OutlinedButton(onClick = { viewModel.createCanyonDemoTrip() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Try Grand Canyon 3D • illustrative route")
                }
                // Quick Demo Generator
                OutlinedButton(
                    onClick = { viewModel.createSampleDemoTrip() },
                    enabled = uiState !is ImportUiState.Loading,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.FlightTakeoff, contentDescription = null)
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Generate Sample Demo Trip (Boston & Niagara)")
                }
            }

            // Loading / Error Overlay
            when (val state = uiState) {
                is ImportUiState.Loading -> {
                    Surface(
                        modifier = Modifier.fillMaxSize(),
                        color = MaterialTheme.colorScheme.background.copy(alpha = 0.85f)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            val estimator = remember(state.startedMs) { com.traveler.domain.usecase.ImportProgressEstimator(state.startedMs) }
                            var now by remember { mutableStateOf(android.os.SystemClock.elapsedRealtime()) }
                            LaunchedEffect(state.startedMs) { while(true) { kotlinx.coroutines.delay(1000); now = android.os.SystemClock.elapsedRealtime() } }
                            state.progress?.let { p ->
                                LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth(.8f))
                                Text("${(p.fraction*100).toInt()}%", Modifier.padding(12.dp), style = MaterialTheme.typography.headlineSmall)
                                if (p.completed != null && p.total != null) Text("${p.completed} / ${p.total}")
                                Text(estimator.remaining(p, now), Modifier.padding(8.dp))
                            } ?: CircularProgressIndicator()
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(text = state.status, modifier = Modifier.padding(16.dp), fontWeight = FontWeight.Medium)
                            TextButton(onClick = { viewModel.cancelImport() }) { Text("Cancel") }
                        }
                    }
                }
                is ImportUiState.Error -> {
                    AlertDialog(
                        onDismissRequest = { viewModel.resetState() },
                        title = { Text("Failed to Generate Trip") },
                        text = { Text(state.message) },
                        confirmButton = {
                            TextButton(onClick = { viewModel.resetState() }) {
                                Text("OK")
                            }
                        }
                    )
                }
                else -> Unit
            }
        }
    }
}
