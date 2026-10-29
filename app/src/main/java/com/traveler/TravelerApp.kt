package com.traveler

import android.app.Application

class TravelerApp : Application(), coil.ImageLoaderFactory {
    override fun newImageLoader(): coil.ImageLoader = coil.ImageLoader.Builder(this)
        .components { add(coil.decode.VideoFrameDecoder.Factory()) }.build()

    override fun onCreate() {
        super.onCreate()
        // P1-03: Do not initialize TimeShape on app start.
        // Saved trips already have persisted timezones, so TimeShape is lazily initialized
        // only when CreateTripUseCase actually requires geographic polygon resolution.
    }
}

