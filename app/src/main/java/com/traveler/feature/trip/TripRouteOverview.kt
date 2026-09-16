package com.traveler.feature.trip

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.traveler.core.model.Visit
import com.traveler.feature.map.renderer.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal fun isHomeVisit(visit: Visit): Boolean =
    Regex("(?i)\\b(home|inferred_home)\\b|자택|^집$").containsMatchIn(visit.placeName.orEmpty())

/** One bounded, offline snapshot per model. No tile requests or live map in the diary list. */
@Composable
internal fun TripRouteOverview(model: TravelMapRenderModel) {
    val context = LocalContext.current
    val bitmap by produceState<Bitmap?>(null, model) {
        value = withContext(Dispatchers.Default) {
            context.assets.open("basemap_world.json").use { stream ->
                val renderer = TravelMapRenderer(stream)
                RegionalBasemapCache.preparedRegionalBasemap?.let(renderer::setPreparedRegionalBasemap)
                Bitmap.createBitmap(960, 540, Bitmap.Config.ARGB_8888).also {
                    renderer.render(Canvas(it), 960, 540, model, null, SafeContentInsets(48f, 40f, 48f, 40f))
                }
            }
        }
    }
    Column {
        Text("여행 전체 경로", style = MaterialTheme.typography.titleSmall)
        Spacer(Modifier.height(8.dp))
        Surface(shape = MaterialTheme.shapes.medium) {
            val image = bitmap
            if (image != null) Image(image.asImageBitmap(), "여행 전체 경로 지도", Modifier.fillMaxWidth().aspectRatio(16f / 9f))
            else Box(Modifier.fillMaxWidth().aspectRatio(16f / 9f), contentAlignment = androidx.compose.ui.Alignment.Center) {
                CircularProgressIndicator()
            }
        }
        Text("© Natural Earth · 경로 기록 기준", style = MaterialTheme.typography.labelSmall)
    }
}
