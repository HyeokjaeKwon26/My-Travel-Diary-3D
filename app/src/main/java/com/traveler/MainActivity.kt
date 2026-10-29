package com.traveler

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.traveler.feature.home.HomeScreen
import com.traveler.feature.home.HomeViewModel
import com.traveler.feature.importtrip.ImportTripScreen
import com.traveler.feature.importtrip.ImportTripViewModel
import com.traveler.feature.trip.TravelDiaryScreen
import com.traveler.feature.trip.TravelDiaryViewModel

class MainActivity : ComponentActivity() {

    private val updateViewModel by viewModels<com.traveler.feature.update.UpdateViewModel>()

    override fun onStart() {
        super.onStart()
        updateViewModel.check(automatic = true)
    }

    private val homeViewModel by viewModels<HomeViewModel>()
    private val importViewModel by viewModels<ImportTripViewModel>()
    private val travelDiaryViewModel by viewModels<TravelDiaryViewModel>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    TravelerAppNavigation(
                        homeViewModel = homeViewModel,
                        importViewModel = importViewModel,
                        travelDiaryViewModel = travelDiaryViewModel,
                        updateViewModel = updateViewModel
                    )
                    com.traveler.feature.update.UpdateNotifications(updateViewModel)
                }
            }
        }
    }
}

@Composable
fun TravelerAppNavigation(
    homeViewModel: HomeViewModel,
    importViewModel: ImportTripViewModel,
    travelDiaryViewModel: TravelDiaryViewModel,
    updateViewModel: com.traveler.feature.update.UpdateViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
) {
    val navController = rememberNavController()

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                viewModel = homeViewModel,
                updateViewModel = updateViewModel,
                onNavigateToNewTrip = { navController.navigate("import") },
                onNavigateToTripDetail = { tripId -> navController.navigate("trip_detail/$tripId") }
            )
        }

        composable("import") {
            ImportTripScreen(
                viewModel = importViewModel,
                onNavigateBack = { navController.popBackStack() },
                onNavigateToTripDetail = { tripId ->
                    navController.navigate("trip_detail/$tripId") {
                        popUpTo("home")
                    }
                }
            )
        }

        composable(
            route = "trip_detail/{tripId}",
            arguments = listOf(navArgument("tripId") { type = NavType.StringType })
        ) { backStackEntry ->
            val tripId = backStackEntry.arguments?.getString("tripId") ?: ""
            TravelDiaryScreen(
                tripId = tripId,
                viewModel = travelDiaryViewModel,
                onNavigateBack = { navController.popBackStack() }
            )
        }
    }
}
