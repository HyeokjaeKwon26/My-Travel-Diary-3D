package com.traveler.feature.video

import android.media.MediaCodecList
import android.media.MediaFormat

enum class VideoResolution(val width:Int,val height:Int,val label:String) {
    FULL_HD(1080,1920,"1080p"),HD(720,1280,"720p");
    fun supportedOrFallback():VideoResolution {
        fun supported(option:VideoResolution)=MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { info ->
            info.isEncoder && info.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC,true) } &&
                runCatching { info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
                    ?.areSizeAndRateSupported(option.width,option.height,30.0)==true }.getOrDefault(false)
        }
        if(supported(this)) return this
        if(this==FULL_HD && supported(HD)) return HD
        error("This device cannot encode the selected size. Try 720p or another device.")
    }
}
