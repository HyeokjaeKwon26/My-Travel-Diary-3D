package com.traveler.feature.video

import android.media.MediaCodecList
import android.media.MediaFormat

enum class VideoResolution(val width:Int,val height:Int,val label:String) {
    FULL_HD(1080,1920,"1080p"),HD(720,1280,"720p"),
    LANDSCAPE_FULL_HD(1920,1080,"1080p"),LANDSCAPE_HD(1280,720,"720p");
    val landscape: Boolean get() = width > height
    fun oriented(landscape: Boolean): VideoResolution = if (width == 1080 || width == 1920) {
        if (landscape) LANDSCAPE_FULL_HD else FULL_HD
    } else if (landscape) LANDSCAPE_HD else HD
    fun supportedOrFallback():VideoResolution {
        fun supported(option:VideoResolution)=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC,true) } &&
                runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                    ?.areSizeAndRateSupported(option.width,option.height,30.0)==true }.getOrDefault(false)
        }
        if(supported(this)) return this
        val fallback = if(landscape) LANDSCAPE_HD else HD
        if(this!=fallback && supported(fallback)) return fallback
        error("This device cannot encode the selected size. Try 720p or another device.")
    }
}
