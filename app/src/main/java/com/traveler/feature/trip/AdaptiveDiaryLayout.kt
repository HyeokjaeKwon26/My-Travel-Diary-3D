package com.traveler.feature.trip

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp

/** Both children keep their composition identity across rotation and split-screen resize. */
@Composable
internal fun AdaptiveDiaryLayout(map: @Composable () -> Unit, diary: @Composable () -> Unit, fullscreen: Boolean = false) {
    Layout(content = { Box { map() }; Box { diary() } }, modifier = Modifier.fillMaxSize()) { children, c ->
        val width = c.maxWidth
        val height = c.maxHeight
        val sideBySide = !fullscreen && (width >= 840.dp.roundToPx() || (width >= 600.dp.roundToPx() && width > height))
        val mapWidth = if(sideBySide) (width*.55f).toInt() else width
        val mapHeight = if(fullscreen) height else if(sideBySide) height else (height*.44f).toInt().coerceAtMost(420.dp.roundToPx())
        val mapPlaceable = children[0].measure(Constraints.fixed(mapWidth,mapHeight))
        val listPlaceable = children[1].measure(Constraints.fixed(if(sideBySide) width-mapWidth else width,
            if(fullscreen || sideBySide) height else height-mapHeight))
        layout(width,height) {
            mapPlaceable.placeRelative(0,0)
            if (!fullscreen) listPlaceable.placeRelative(if(sideBySide) mapWidth else 0,if(sideBySide) 0 else mapHeight)
        }
    }
}
