package com.traveler.feature.map.threed

import com.traveler.core.model.TransportMode
import com.traveler.feature.map.renderer.TravelPlaybackState
import kotlin.math.*

data class JourneyFrame(val focus: Vec3, val distance: Double)

/** Pure random-access camera: seeking, frame rate and video encoding cannot change framing. */
class JourneyCamera(private val scene: SceneGeometry) {
    private val routeRadius = scene.routes.associate { route ->
        val extent = route.xyz.maxOfOrNull { (it-route.xyz.first()).length() } ?: 0.0
        val wide = route.mode == TransportMode.AIRPLANE || route.id.startsWith("bridge_")
        val minimum = if (route.mode == TransportMode.WALK || route.mode == TransportMode.RUN) 650.0 else 2200.0
        route.episodeIndex to if(wide) max(extent, 12_000.0/EarthGeometry.R)
            else {
                // A compressed motorway leg needs regional context: a fixed 9 km window
                // can leave the viewport in a fraction of a story second.
                val horizon = max(9000.0, route.distances.lastOrNull().orEmptyDistance()*.12)
                (extent*1.2).coerceIn(minimum/EarthGeometry.R,horizon/EarthGeometry.R)
            }
    }

    private fun Double?.orEmptyDistance() = this ?: 0.0

    private fun smooth(t:Double)=t.coerceIn(0.0,1.0).let { it*it*(3-2*it) }
    private fun blend(a:JourneyFrame,b:JourneyFrame,f:Double):JourneyFrame {
        val position=(a.focus*(1-f)+b.focus*f).unit()*(a.focus.length()*(1-f)+b.focus.length()*f)
        return JourneyFrame(position,a.distance*(1-f)+b.distance*f)
    }

    fun frame(state: TravelPlaybackState?, width: Int, height: Int): JourneyFrame {
        // Reserve top/bottom space for transport, photos and playback controls.
        val aspect = max(1,width).toDouble()/max(1,height)
        val fit = max(1.0,1.0/aspect) / .68
        val overview=JourneyFrame(scene.overviewCenter, (scene.overviewDistance*fit).coerceIn(.002,4.0))
        if(state == null) return overview
        if(state.isTitleCardActive) {
            val duration=scene.timeline.titleCard?.durationSeconds ?: return overview
            val seconds=state.progress*scene.timeline.totalStoryDurationSeconds
            val next=frame(scene.timeline.evaluateAtStoryTime(duration+.001f),width,height)
            return blend(overview,next,smooth((seconds-duration+1.2)/1.2))
        }
        if(state.isEndCardActive) {
            val start=scene.timeline.totalStoryDurationSeconds-(scene.timeline.endCard?.durationSeconds ?: return overview)
            val seconds=state.progress*scene.timeline.totalStoryDurationSeconds
            val previous=frame(scene.timeline.evaluateAtStoryTime((start-.001f).coerceAtLeast(0f)),width,height)
            return blend(previous,overview,smooth((seconds-start)/1.2))
        }
        val index = state.episodeIndex
        fun radiusAt(i:Int)=routeRadius[i] ?: routeRadius.entries.minByOrNull { abs(it.key-i) }?.value ?: .001
        val radius=radiusAt(index)
        val prior=radiusAt(index-1)
        val next=radiusAt(index+1)
        val ep = scene.timeline.episodes.getOrNull(index)
        val t = if(ep==null) 1.0 else ((state.storyTimeMs-ep.startTimestampEpochMs).toDouble() /
            max(1,ep.endTimestampEpochMs-ep.startTimestampEpochMs)).coerceIn(0.0,1.0)
        // Start at the wider of adjacent legs, settle once, never oscillate at corners.
        val settle=smooth(t/.25)
        val departing=smooth((t-.75)/.25)
        val blended=(max(prior,radius)*(1-settle)+radius*settle)*(1-departing)+max(radius,next)*departing
        return JourneyFrame(scene.motion(state).position, (blended*2.7*fit).coerceIn(.00035,4.0))
    }
}
