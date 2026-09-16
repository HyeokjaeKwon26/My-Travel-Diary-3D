package com.traveler.feature.map

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.traveler.feature.map.renderer.PlaybackOverlayContent
import com.traveler.feature.map.renderer.TravelPlaybackState
import com.traveler.feature.map.story.TravelStoryTimeline

/** A separate overlay, never an offscreen/clipping parent of the map's SurfaceView. */
@Composable
internal fun PlaybackOverlays(state: TravelPlaybackState, timeline: TravelStoryTimeline, tripStartDateIso: String?) {
    val density = LocalDensity.current
    BoxWithConstraints(Modifier.fillMaxSize()) {
        var headerHeight by remember { mutableStateOf(48.dp) }
        val cardColor = Color(0xDD0B1329)
        Row(Modifier.align(Alignment.TopStart).padding(8.dp).fillMaxWidth()
            .onSizeChanged { headerHeight = with(density) { it.height.toDp() } },
            horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
            Column(Modifier.widthIn(max = 200.dp).weight(1f, fill = false).testTag("playback-info")
                .background(cardColor, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp),
                verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text(state.currentTransportMode.emoji, fontSize = 12.sp, lineHeight = 14.sp)
                    Text(PlaybackOverlayContent.mode(state), Modifier.weight(1f), color = Color.White,
                        fontSize = 11.sp, lineHeight = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Text(PlaybackOverlayContent.distance(state), color = Color(0xFF72D6F6), fontSize = 10.sp, lineHeight = 13.sp,
                        fontWeight = FontWeight.SemiBold, maxLines = 1)
                val fraction = (state.currentTraveledDistanceMeters / maxOf(1.0, state.totalTripDistanceMeters)).toFloat().coerceIn(0f, 1f)
                Box(Modifier.fillMaxWidth().height(2.dp).background(Color.White.copy(alpha = .2f))) {
                    Box(Modifier.fillMaxWidth(fraction).fillMaxHeight().background(Color(0xFF45CDB5)))
                }
            }
            Spacer(Modifier.width(8.dp))
            Column(Modifier.testTag("playback-date").background(cardColor, RoundedCornerShape(8.dp))
                .padding(horizontal = 8.dp, vertical = 5.dp), horizontalAlignment = Alignment.End) {
                Text(PlaybackOverlayContent.date(state, timeline), color = Color.White, fontSize = 11.sp, lineHeight = 14.sp,
                    fontWeight = FontWeight.SemiBold, maxLines = 1)
                Text(PlaybackOverlayContent.day(state, timeline, tripStartDateIso), color = Color(0xFFCBD5E1),
                    fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1)
            }
        }
        state.activePhoto?.let { photo ->
            key(photo.contentUriString) {
                var aspect by remember { mutableStateOf(1.5f) }
                val top = headerHeight + 24.dp
                val maxPhotoWidth = minOf(180.dp, maxWidth * .38f)
                // Reserve controls, credits and caption, including when font size is enlarged.
                val captionSpace = with(density) { 16.sp.toDp() } + 10.dp
                val available = (maxHeight - top - 90.dp - captionSpace).coerceAtLeast(0.dp)
                val maxPhotoHeight = minOf(180.dp, maxHeight * .38f, available)
                if (maxPhotoHeight >= 24.dp) {
                    val fitted = PlaybackOverlayContent.fitPhoto(aspect, 1f, maxPhotoWidth.value, maxPhotoHeight.value)
                    Column(Modifier.align(Alignment.TopEnd).padding(top = top, end = 8.dp)
                        .width(fitted.width.dp + 8.dp).testTag("playback-photo-card")
                        .background(cardColor, RoundedCornerShape(8.dp)).padding(4.dp)) {
                        AsyncImage(model = photo.contentUriString, contentDescription = photo.fileName,
                            contentScale = ContentScale.Fit,
                            onSuccess = { result ->
                                val drawable = result.result.drawable
                                if (drawable.intrinsicWidth > 0 && drawable.intrinsicHeight > 0)
                                    aspect = drawable.intrinsicWidth.toFloat() / drawable.intrinsicHeight
                            },
                            modifier = Modifier.size(fitted.width.dp, fitted.height.dp).testTag("playback-photo"))
                        Text(state.currentVisit?.placeName ?: photo.assignedDayIso ?: "Photo", color = Color.White,
                            fontSize = 10.sp, lineHeight = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(top = 3.dp))
                    }
                }
            }
        }
    }
}
