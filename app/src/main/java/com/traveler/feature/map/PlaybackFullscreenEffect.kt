package com.traveler.feature.map

import android.app.Activity
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/** The same map stays composed; only available space and system chrome change. */
@Composable
internal fun PlaybackFullscreenEffect(fullscreen: Boolean) {
    val context = LocalContext.current
    DisposableEffect(fullscreen, context) {
        var owner = context
        while (owner is ContextWrapper && owner !is Activity) owner = owner.baseContext
        val activity = owner as? Activity
        if (!fullscreen || activity == null) return@DisposableEffect onDispose { }
        val window = activity.window
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        val oldBehavior = controller.systemBarsBehavior
        val oldOrientation = activity.requestedOrientation
        val insets = androidx.core.view.ViewCompat.getRootWindowInsets(window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
        onDispose {
            controller.systemBarsBehavior = oldBehavior
            if (insets?.isVisible(WindowInsetsCompat.Type.statusBars()) != false) controller.show(WindowInsetsCompat.Type.statusBars())
            if (insets?.isVisible(WindowInsetsCompat.Type.navigationBars()) != false) controller.show(WindowInsetsCompat.Type.navigationBars())
            activity.requestedOrientation = oldOrientation
        }
    }
}
